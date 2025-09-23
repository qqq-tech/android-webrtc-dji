package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v3"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	maxMessageSize = 64 * 1024
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

type signalMessage struct {
	Type          string  `json:"type"`
	SDP           string  `json:"sdp,omitempty"`
	SDPType       string  `json:"sdpType,omitempty"`
	Candidate     string  `json:"candidate,omitempty"`
	SDPMid        string  `json:"sdpMid,omitempty"`
	SDPMLineIndex *uint16 `json:"sdpMLineIndex,omitempty"`
}

type client struct {
	conn      *websocket.Conn
	send      chan []byte
	role      string
	stream    *stream
	peer      *webrtc.PeerConnection
	closeOnce sync.Once
}

type stream struct {
	id          string
	mu          sync.Mutex
	publisher   *client
	subscribers map[*client]struct{}
	pending     []*client
	videoTrack  *webrtc.TrackLocalStaticRTP
	remoteTrack *webrtc.TrackRemote
}

type streamManager struct {
	mu      sync.Mutex
	streams map[string]*stream
}

func newStreamManager() *streamManager {
	return &streamManager{streams: make(map[string]*stream)}
}

func (m *streamManager) get(streamID string) *stream {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s, ok := m.streams[streamID]; ok {
		return s
	}
	s := &stream{
		id:          streamID,
		subscribers: make(map[*client]struct{}),
	}
	m.streams[streamID] = s
	return s
}

func (m *streamManager) handleWebsocket(w http.ResponseWriter, r *http.Request) {
	role := r.URL.Query().Get("role")
	streamID := r.URL.Query().Get("streamId")
	if role == "" || streamID == "" {
		http.Error(w, "role and streamId are required", http.StatusBadRequest)
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("failed to upgrade websocket: %v", err)
		return
	}

	s := m.get(streamID)
	c := &client{
		conn:   conn,
		send:   make(chan []byte, 32),
		role:   role,
		stream: s,
	}

	go c.writePump()

	var registerErr error
	switch role {
	case "publisher":
		registerErr = s.registerPublisher(c)
	case "subscriber":
		registerErr = s.registerSubscriber(c)
	default:
		registerErr = fmt.Errorf("unknown role: %s", role)
	}

	if registerErr != nil {
		c.sendError(registerErr.Error(), "REGISTRATION_FAILED")
		c.close()
		return
	}

	c.readPump()
}

func (c *client) readPump() {
	defer c.close()
	c.conn.SetReadLimit(maxMessageSize)
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		_, message, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("unexpected websocket close: %v", err)
			}
			break
		}

		var signal signalMessage
		if err := json.Unmarshal(message, &signal); err != nil {
			c.sendError("invalid signaling message", "INVALID_JSON")
			continue
		}
		if err := c.handleSignal(signal); err != nil {
			c.sendError(err.Error(), "SIGNALING_ERROR")
		}
	}
}

func (c *client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()

	for {
		select {
		case message, ok := <-c.send:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := c.conn.WriteMessage(websocket.TextMessage, message); err != nil {
				log.Printf("failed to write websocket message: %v", err)
				return
			}
		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func (c *client) handleSignal(msg signalMessage) error {
	if c.peer == nil {
		return fmt.Errorf("peer connection not ready")
	}

	switch msg.Type {
	case "sdp":
		if msg.SDP == "" {
			return fmt.Errorf("missing SDP")
		}
		sdpType := webrtc.SDPTypeAnswer
		if msg.SDPType != "" {
			switch msg.SDPType {
			case webrtc.SDPTypeOffer.String():
				sdpType = webrtc.SDPTypeOffer
			case webrtc.SDPTypeAnswer.String():
				sdpType = webrtc.SDPTypeAnswer
			case webrtc.SDPTypePranswer.String():
				sdpType = webrtc.SDPTypePranswer
			case webrtc.SDPTypeRollback.String():
				sdpType = webrtc.SDPTypeRollback
			default:
				return fmt.Errorf("unsupported SDP type: %s", msg.SDPType)
			}
		}
		desc := webrtc.SessionDescription{Type: sdpType, SDP: msg.SDP}
		if err := c.peer.SetRemoteDescription(desc); err != nil {
			return err
		}
		if sdpType == webrtc.SDPTypeOffer {
			answer, err := c.peer.CreateAnswer(nil)
			if err != nil {
				return err
			}
			if err := c.peer.SetLocalDescription(answer); err != nil {
				return err
			}
			return c.sendSignal(signalMessage{Type: "sdp", SDP: answer.SDP, SDPType: answer.Type.String()})
		}
	case "ice":
		candidate := webrtc.ICECandidateInit{}
		if msg.Candidate != "" {
			candidate.Candidate = msg.Candidate
			if msg.SDPMid != "" {
				candidate.SDPMid = &msg.SDPMid
			}
			candidate.SDPMLineIndex = msg.SDPMLineIndex
		}
		if err := c.peer.AddICECandidate(candidate); err != nil {
			return err
		}
	default:
		return fmt.Errorf("unsupported signal type: %s", msg.Type)
	}

	return nil
}

func (c *client) sendSignal(msg signalMessage) error {
	payload, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	select {
	case c.send <- payload:
		return nil
	default:
		return fmt.Errorf("client send queue full")
	}
}

func (c *client) sendError(description, code string) {
	payload, err := json.Marshal(map[string]string{
		"error": description,
		"code":  code,
	})
	if err != nil {
		log.Printf("failed to marshal error payload: %v", err)
		return
	}
	select {
	case c.send <- payload:
	default:
		log.Printf("failed to deliver error to client: queue full")
	}
}

func (c *client) close() {
	c.closeOnce.Do(func() {
		if c.peer != nil {
			_ = c.peer.Close()
		}
		close(c.send)
		if c.stream != nil {
			c.stream.removeClient(c)
		}
	})
}

func (s *stream) registerPublisher(c *client) error {
	s.mu.Lock()
	if s.publisher != nil {
		s.mu.Unlock()
		return fmt.Errorf("publisher already connected")
	}
	s.publisher = c
	s.mu.Unlock()

	pc, err := s.createPeerConnection(c)
	if err != nil {
		return err
	}
	c.peer = pc

	pc.OnTrack(func(remoteTrack *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		log.Printf("received remote track kind=%s from publisher", remoteTrack.Kind())
		s.setRemoteTrack(remoteTrack)
	})

	return nil
}

func (s *stream) registerSubscriber(c *client) error {
	pc, err := s.createPeerConnection(c)
	if err != nil {
		return err
	}
	c.peer = pc

	s.mu.Lock()
	s.subscribers[c] = struct{}{}
	if s.videoTrack != nil {
		if _, err := pc.AddTrack(s.videoTrack); err != nil {
			s.mu.Unlock()
			return err
		}
		s.mu.Unlock()
		return c.sendOffer()
	}
	s.pending = append(s.pending, c)
	s.mu.Unlock()
	return nil
}

func (s *stream) removeClient(c *client) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.publisher == c {
		s.publisher = nil
		s.videoTrack = nil
		s.remoteTrack = nil
	}

	delete(s.subscribers, c)
	for i, pending := range s.pending {
		if pending == c {
			s.pending = append(s.pending[:i], s.pending[i+1:]...)
			break
		}
	}
}

func (s *stream) createPeerConnection(c *client) (*webrtc.PeerConnection, error) {
	config := webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{{URLs: []string{"stun:stun.l.google.com:19302"}}},
	}
	pc, err := webrtc.NewPeerConnection(config)
	if err != nil {
		return nil, err
	}

	pc.OnICECandidate(func(candidate *webrtc.ICECandidate) {
		if candidate == nil {
			return
		}
		init := candidate.ToJSON()
		var lineIndex *uint16
		if init.SDPMLineIndex != nil {
			v := uint16(*init.SDPMLineIndex)
			lineIndex = &v
		}
		var sdpMid string
		if init.SDPMid != nil {
			sdpMid = *init.SDPMid
		}
		msg := signalMessage{
			Type:          "ice",
			Candidate:     init.Candidate,
			SDPMid:        sdpMid,
			SDPMLineIndex: lineIndex,
		}
		if err := c.sendSignal(msg); err != nil {
			log.Printf("failed to send ICE candidate: %v", err)
		}
	})

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("peer connection (%s) state: %s", c.role, state)
		if state == webrtc.PeerConnectionStateFailed || state == webrtc.PeerConnectionStateClosed {
			c.close()
		}
	})

	return pc, nil
}

func (s *stream) setRemoteTrack(remoteTrack *webrtc.TrackRemote) {
	s.mu.Lock()
	s.remoteTrack = remoteTrack
	localTrack, err := webrtc.NewTrackLocalStaticRTP(remoteTrack.Codec().RTPCodecCapability, remoteTrack.ID(), fmt.Sprintf("%s-video", s.id))
	if err != nil {
		s.mu.Unlock()
		log.Printf("failed to create local track: %v", err)
		return
	}
	s.videoTrack = localTrack
	pending := append([]*client(nil), s.pending...)
	s.pending = nil
	s.mu.Unlock()

	for _, subscriber := range pending {
		if subscriber.peer == nil {
			continue
		}
		if _, err := subscriber.peer.AddTrack(localTrack); err != nil {
			log.Printf("failed to add track to subscriber: %v", err)
			continue
		}
		if err := subscriber.sendOffer(); err != nil {
			log.Printf("failed to send offer to subscriber: %v", err)
		}
	}

	go s.forwardRTP(remoteTrack)
	go s.requestKeyFrames(remoteTrack)
}

func (s *stream) forwardRTP(remoteTrack *webrtc.TrackRemote) {
	for {
		packet, _, err := remoteTrack.ReadRTP()
		if err != nil {
			log.Printf("publisher track closed: %v", err)
			return
		}
		s.mu.Lock()
		track := s.videoTrack
		s.mu.Unlock()
		if track != nil {
			if err := track.WriteRTP(packet); err != nil {
				log.Printf("failed to forward RTP packet: %v", err)
				return
			}
		}
	}
}

func (s *stream) requestKeyFrames(remoteTrack *webrtc.TrackRemote) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		s.mu.Lock()
		publisher := s.publisher
		s.mu.Unlock()
		if publisher == nil || publisher.peer == nil {
			return
		}
		pli := &rtcp.PictureLossIndication{MediaSSRC: uint32(remoteTrack.SSRC())}
		if err := publisher.peer.WriteRTCP([]rtcp.Packet{pli}); err != nil {
			log.Printf("failed to send PLI: %v", err)
		}
	}
}

func (c *client) sendOffer() error {
	if c.peer == nil {
		return fmt.Errorf("peer connection not initialised")
	}
	offer, err := c.peer.CreateOffer(nil)
	if err != nil {
		return err
	}
	if err := c.peer.SetLocalDescription(offer); err != nil {
		return err
	}
	return c.sendSignal(signalMessage{Type: "sdp", SDP: offer.SDP, SDPType: offer.Type.String()})
}

func main() {
	addr := flag.String("addr", ":8080", "http service address")
	flag.Parse()

	manager := newStreamManager()
	http.HandleFunc("/ws", manager.handleWebsocket)

	log.Printf("Pion WebRTC relay listening on %s", *addr)
	if err := http.ListenAndServe(*addr, nil); err != nil {
		log.Fatalf("server failed: %v", err)
	}
}
