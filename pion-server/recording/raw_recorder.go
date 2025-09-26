package recording

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v3"
)

type RawRecorder struct {
	streamID string
	mimeType string
	fmtp     string
	file     *os.File
	writer   *bufio.Writer
	mu       sync.Mutex
	closed   bool
}

var _ Recorder = (*RawRecorder)(nil)

func NewRecorder(streamID string, track *webrtc.TrackRemote) Recorder {
	if track == nil {
		return nil
	}
	if track.Codec().MimeType == webrtc.MimeTypeH264 {
		return NewStreamRecorder(streamID, track)
	}
	recorder, err := newRawRecorder(streamID, track)
	if err != nil {
		log.Printf("failed to create raw recorder for stream %s: %v", streamID, err)
		return nil
	}
	return recorder
}

func newRawRecorder(streamID string, track *webrtc.TrackRemote) (*RawRecorder, error) {
	codec := track.Codec()
	log.Printf("stream %s received codec %s (fmtp: %s); storing raw RTP stream", streamID, codec.MimeType, codec.SDPFmtpLine)
	base := filepath.Join(DirName, streamID)
	if err := os.MkdirAll(base, 0o755); err != nil {
		return nil, err
	}
	name := fmt.Sprintf("%s-%s.rtp", time.Now().UTC().Format("20060102-150405"), sanitizeMimeType(codec.MimeType))
	path := filepath.Join(base, name)
	file, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	writer := bufio.NewWriterSize(file, 1<<20)
	metadata := fmt.Sprintf("# codec=%s\n# clockRate=%d\n# fmtp=%s\n", codec.MimeType, codec.ClockRate, codec.SDPFmtpLine)
	if _, err := writer.WriteString(metadata); err != nil {
		file.Close()
		return nil, err
	}
	if err := writer.Flush(); err != nil {
		file.Close()
		return nil, err
	}
	return &RawRecorder{
		streamID: streamID,
		mimeType: codec.MimeType,
		fmtp:     codec.SDPFmtpLine,
		file:     file,
		writer:   writer,
	}, nil
}

func sanitizeMimeType(mime string) string {
	if mime == "" {
		return "unknown"
	}
	lower := strings.ToLower(mime)
	lower = strings.ReplaceAll(lower, "/", "-")
	lower = strings.ReplaceAll(lower, "+", "-")
	lower = strings.ReplaceAll(lower, ".", "-")
	lower = strings.ReplaceAll(lower, ";", "-")
	sanitized := strings.Builder{}
	for _, r := range lower {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' {
			sanitized.WriteRune(r)
		} else {
			sanitized.WriteRune('-')
		}
	}
	result := strings.Trim(sanitized.String(), "-")
	if result == "" {
		return "unknown"
	}
	return result
}

func (r *RawRecorder) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return
	}
	r.closed = true
	if r.writer != nil {
		if err := r.writer.Flush(); err != nil {
			log.Printf("failed to flush raw recorder for stream %s: %v", r.streamID, err)
		}
	}
	if r.file != nil {
		if err := r.file.Close(); err != nil {
			log.Printf("failed to close raw recorder file for stream %s: %v", r.streamID, err)
		}
	}
	r.writer = nil
	r.file = nil
}

func (r *RawRecorder) Push(packet *rtp.Packet) {
	if packet == nil {
		return
	}
	raw, err := packet.Marshal()
	if err != nil {
		log.Printf("failed to marshal RTP packet for stream %s: %v", r.streamID, err)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed || r.writer == nil {
		return
	}
	if err := binary.Write(r.writer, binary.BigEndian, uint32(len(raw))); err != nil {
		log.Printf("failed to write raw packet length for stream %s: %v", r.streamID, err)
		return
	}
	if _, err := r.writer.Write(raw); err != nil {
		log.Printf("failed to write raw RTP packet for stream %s: %v", r.streamID, err)
		return
	}
}
