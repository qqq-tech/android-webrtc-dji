package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/example/android-webrtc-dji/pion-server/recording"
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
	Type          string          `json:"type"`
	SDP           string          `json:"sdp,omitempty"`
	SDPType       string          `json:"sdpType,omitempty"`
	Candidate     string          `json:"candidate,omitempty"`
	SDPMid        string          `json:"sdpMid,omitempty"`
	SDPMLineIndex *uint16         `json:"sdpMLineIndex,omitempty"`
	Latitude      *float64        `json:"latitude,omitempty"`
	Longitude     *float64        `json:"longitude,omitempty"`
	Altitude      *float64        `json:"altitude,omitempty"`
	Accuracy      *float64        `json:"accuracy,omitempty"`
	Timestamp     *int64          `json:"timestamp,omitempty"`
	Source        string          `json:"source,omitempty"`
	Payload       json.RawMessage `json:"payload,omitempty"`
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
	telemetry   *telemetryData
	recorder    recording.Recorder
}

type telemetryData struct {
	Latitude  float64
	Longitude float64
	Altitude  *float64
	Accuracy  *float64
	Timestamp int64
	Source    string
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
	case "telemetry":
		if c.role != "publisher" {
			return fmt.Errorf("telemetry messages only accepted from publishers")
		}
		if msg.Latitude == nil || msg.Longitude == nil {
			return fmt.Errorf("telemetry message missing coordinates")
		}
		lat := *msg.Latitude
		lng := *msg.Longitude
		if !isValidLatitude(lat) || !isValidLongitude(lng) {
			return fmt.Errorf("invalid telemetry coordinates")
		}
		data := telemetryData{
			Latitude:  lat,
			Longitude: lng,
			Source:    msg.Source,
		}
		if msg.Altitude != nil && !math.IsNaN(*msg.Altitude) && !math.IsInf(*msg.Altitude, 0) {
			data.Altitude = float64Ptr(*msg.Altitude)
		}
		if msg.Accuracy != nil && !math.IsNaN(*msg.Accuracy) && !math.IsInf(*msg.Accuracy, 0) && *msg.Accuracy >= 0 {
			data.Accuracy = float64Ptr(*msg.Accuracy)
		}
		if msg.Timestamp != nil && *msg.Timestamp > 0 {
			data.Timestamp = *msg.Timestamp
		} else {
			data.Timestamp = time.Now().UnixMilli()
		}
		c.stream.updateTelemetry(data)
	case "gcs_command":
		if c.role != "subscriber" {
			return fmt.Errorf("gcs_command messages only accepted from subscribers")
		}
		if len(msg.Payload) == 0 {
			return fmt.Errorf("gcs_command message missing payload")
		}
		return c.stream.forwardToPublisher(msg, c)
	case "raw_stream":
		if c.role != "subscriber" {
			return fmt.Errorf("raw_stream messages only accepted from subscribers")
		}
		if len(msg.Payload) == 0 {
			return fmt.Errorf("raw_stream message missing payload")
		}
		return c.stream.forwardToPublisher(msg, c)
	case "gcs_command_ack":
		if c.role != "publisher" {
			return fmt.Errorf("gcs_command_ack messages only accepted from publishers")
		}
		return c.stream.broadcastToSubscribers(msg, c)
	case "raw_stream_ack":
		if c.role != "publisher" {
			return fmt.Errorf("raw_stream_ack messages only accepted from publishers")
		}
		return c.stream.broadcastToSubscribers(msg, c)
	case "register":
		// Legacy clients may send a register signal even though the server already
		// associates the role/stream via query parameters. Treat it as a no-op so
		// they do not receive an unsupported signal error.
		log.Printf("received legacy register message for stream %s from %s", c.stream.id, c.role)
		return nil
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
	track := s.videoTrack
	if track != nil {
		sender, err := pc.AddTrack(track)
		if err != nil {
			s.mu.Unlock()
			return err
		}
		if err := preferH264VideoCodec(pc, sender); err != nil {
			log.Printf("failed to set codec preference: %v", err)
		}
	}
	if track == nil {
		s.pending = append(s.pending, c)
	}
	telemetry := s.telemetry
	s.mu.Unlock()
	if track != nil {
		if err := c.sendOffer(); err != nil {
			return err
		}
	}
	if telemetry != nil {
		if err := c.sendSignal(telemetry.toSignalMessage()); err != nil {
			log.Printf("failed to send telemetry to subscriber: %v", err)
		}
	}
	return nil
}

func (s *stream) removeClient(c *client) {
	s.mu.Lock()
	var recorder recording.Recorder
	defer func() {
		s.mu.Unlock()
		if recorder != nil {
			recorder.Close()
		}
	}()

	if s.publisher == c {
		s.publisher = nil
		s.videoTrack = nil
		s.remoteTrack = nil
		s.telemetry = nil
		recorder = s.recorder
		s.recorder = nil
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

func preferH264VideoCodec(pc *webrtc.PeerConnection, sender *webrtc.RTPSender) error {
	if pc == nil || sender == nil {
		return nil
	}

	var transceiver *webrtc.RTPTransceiver
	for _, t := range pc.GetTransceivers() {
		if t.Sender() == sender {
			transceiver = t
			break
		}
	}
	if transceiver == nil || transceiver.Kind() != webrtc.RTPCodecTypeVideo {
		return nil
	}

	params := sender.GetParameters()
	if len(params.Codecs) == 0 {
		return nil
	}

	var preferred []webrtc.RTPCodecParameters
	var fallback []webrtc.RTPCodecParameters
	for _, codec := range params.Codecs {
		if strings.EqualFold(codec.MimeType, webrtc.MimeTypeH264) {
			preferred = append(preferred, codec)
		} else {
			fallback = append(fallback, codec)
		}
	}
	if len(preferred) == 0 {
		return nil
	}

	preferred = append(preferred, fallback...)
	return transceiver.SetCodecPreferences(preferred)
}

func (s *stream) setRemoteTrack(remoteTrack *webrtc.TrackRemote) {
	s.mu.Lock()
	oldRecorder := s.recorder
	s.remoteTrack = remoteTrack
	localTrack, err := webrtc.NewTrackLocalStaticRTP(remoteTrack.Codec().RTPCodecCapability, remoteTrack.ID(), fmt.Sprintf("%s-video", s.id))
	if err != nil {
		s.mu.Unlock()
		if oldRecorder != nil {
			oldRecorder.Close()
		}
		log.Printf("failed to create local track: %v", err)
		return
	}
	s.videoTrack = localTrack
	s.recorder = recording.NewRecorder(s.id, remoteTrack)
	pending := append([]*client(nil), s.pending...)
	s.pending = nil
	s.mu.Unlock()
	if oldRecorder != nil {
		oldRecorder.Close()
	}

	for _, subscriber := range pending {
		if subscriber.peer == nil {
			continue
		}
		sender, err := subscriber.peer.AddTrack(localTrack)
		if err != nil {
			log.Printf("failed to add track to subscriber: %v", err)
			continue
		}
		if err := preferH264VideoCodec(subscriber.peer, sender); err != nil {
			log.Printf("failed to set codec preference: %v", err)
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
		recorder := s.recorder
		s.mu.Unlock()
		if track != nil {
			if err := track.WriteRTP(packet); err != nil {
				log.Printf("failed to forward RTP packet: %v", err)
				return
			}
		}
		if recorder != nil {
			recorder.Push(packet.Clone())
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

func (s *stream) updateTelemetry(data telemetryData) {
	signal := data.toSignalMessage()
	s.mu.Lock()
	s.telemetry = &data
	recipients := make([]*client, 0, len(s.subscribers)+len(s.pending))
	for subscriber := range s.subscribers {
		recipients = append(recipients, subscriber)
	}
	recipients = append(recipients, s.pending...)
	s.mu.Unlock()

	for _, subscriber := range recipients {
		if err := subscriber.sendSignal(signal); err != nil {
			log.Printf("failed to deliver telemetry to subscriber: %v", err)
		}
	}
}

func (s *stream) forwardToPublisher(msg signalMessage, sender *client) error {
	if msg.Source == "" && sender != nil {
		msg.Source = sender.role
	}
	s.mu.Lock()
	publisher := s.publisher
	s.mu.Unlock()
	if publisher == nil {
		return fmt.Errorf("publisher not connected")
	}
	return publisher.sendSignal(msg)
}

func (s *stream) broadcastToSubscribers(msg signalMessage, sender *client) error {
	if msg.Source == "" && sender != nil {
		msg.Source = sender.role
	}
	s.mu.Lock()
	recipients := make([]*client, 0, len(s.subscribers)+len(s.pending))
	for subscriber := range s.subscribers {
		if subscriber != sender {
			recipients = append(recipients, subscriber)
		}
	}
	for _, pending := range s.pending {
		if pending != sender {
			recipients = append(recipients, pending)
		}
	}
	s.mu.Unlock()

	if len(recipients) == 0 {
		return nil
	}

	var firstErr error
	for _, subscriber := range recipients {
		if err := subscriber.sendSignal(msg); err != nil {
			if firstErr == nil {
				firstErr = err
			}
			log.Printf("failed to deliver control message to subscriber: %v", err)
		}
	}
	return firstErr
}

func (data telemetryData) toSignalMessage() signalMessage {
	msg := signalMessage{Type: "telemetry", Source: data.Source}
	msg.Latitude = float64Ptr(data.Latitude)
	msg.Longitude = float64Ptr(data.Longitude)
	if data.Altitude != nil {
		msg.Altitude = data.Altitude
	}
	if data.Accuracy != nil {
		msg.Accuracy = data.Accuracy
	}
	if data.Timestamp > 0 {
		msg.Timestamp = int64Ptr(data.Timestamp)
	}
	return msg
}

func isValidLatitude(lat float64) bool {
	if math.IsNaN(lat) || math.IsInf(lat, 0) {
		return false
	}
	return lat >= -90 && lat <= 90
}

func isValidLongitude(lng float64) bool {
	if math.IsNaN(lng) || math.IsInf(lng, 0) {
		return false
	}
	return lng >= -180 && lng <= 180
}

func float64Ptr(v float64) *float64 {
	return &v
}

func int64Ptr(v int64) *int64 {
	return &v
}

func main() {
	addr := flag.String("addr", ":8080", "HTTP service address (empty to disable HTTP)")
	httpsAddr := flag.String("https-addr", "", "HTTPS service address (requires TLS flags; empty disables HTTPS)")
	certFile := flag.String("tls-cert", "", "Path to a TLS certificate in PEM format")
	keyFile := flag.String("tls-key", "", "Path to the TLS private key in PEM format")
	flag.Parse()

	if (*certFile == "") != (*keyFile == "") {
		log.Fatal("both --tls-cert and --tls-key must be provided to enable TLS")
	}
	if *httpsAddr != "" && *certFile == "" {
		log.Fatal("--https-addr requires both --tls-cert and --tls-key")
	}

	manager := newStreamManager()
	http.HandleFunc("/ws", manager.handleWebsocket)
	if err := os.MkdirAll(recording.DirName, 0o755); err != nil {
		log.Fatalf("failed to create recording directory: %v", err)
	}

	type listener struct {
		addr     string
		protocol string
		serve    func() error
	}

	var listeners []listener
	if *addr != "" {
		listeners = append(listeners, listener{
			addr:     *addr,
			protocol: "HTTP",
			serve: func() error {
				log.Printf("Pion WebRTC relay listening on %s (HTTP)", *addr)
				return http.ListenAndServe(*addr, nil)
			},
		})
	}

	if *httpsAddr != "" {
		addrCopy := *httpsAddr
		listeners = append(listeners, listener{
			addr:     addrCopy,
			protocol: "HTTPS",
			serve: func() error {
				log.Printf("Pion WebRTC relay listening on %s (HTTPS)", addrCopy)
				return http.ListenAndServeTLS(addrCopy, *certFile, *keyFile, nil)
			},
		})
	} else if *certFile != "" {
		log.Printf("Pion WebRTC relay listening on %s (HTTPS)", *addr)
		if err := http.ListenAndServeTLS(*addr, *certFile, *keyFile, nil); err != nil {
			log.Fatalf("server failed: %v", err)
		}
		return
	}

	if len(listeners) == 0 {
		log.Fatal("no listeners configured: provide --addr, --https-addr, or TLS flags")
	}

	if len(listeners) == 1 {
		if err := listeners[0].serve(); err != nil {
			log.Fatalf("%s server failed: %v", listeners[0].protocol, err)
		}
		return
	}

	errCh := make(chan error, len(listeners))
	for i := range listeners {
		go func(l listener) {
			if err := l.serve(); err != nil {
				errCh <- fmt.Errorf("%s server failed: %w", l.protocol, err)
			}
		}(listeners[i])
	}

	err := <-errCh
	log.Fatal(err)
}
