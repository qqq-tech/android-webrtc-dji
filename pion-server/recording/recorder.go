package recording

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"math"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v3"
	"github.com/pion/webrtc/v3/pkg/media"
	"github.com/pion/webrtc/v3/pkg/media/samplebuilder"
)

const (
	DirName          = "recordings"
	segmentLength    = 5 * time.Minute
	defaultTimescale = 90000
)

type StreamRecorder struct {
	streamID   string
	clockRate  uint32
	builder    *samplebuilder.SampleBuilder
	sps        []byte
	pps        []byte
	width      uint16
	height     uint16
	profileIDC uint8
	constraint uint8
	levelIDC   uint8
	segment    *mp4SegmentWriter
	segmentDur time.Duration
	mu         sync.Mutex
	closed     bool
}

func NewStreamRecorder(streamID string, track *webrtc.TrackRemote) *StreamRecorder {
	clockRate := track.Codec().ClockRate
	if clockRate == 0 {
		clockRate = defaultTimescale
	}
	recorder := &StreamRecorder{
		streamID:  streamID,
		clockRate: clockRate,
	}
	recorder.builder = samplebuilder.New(512, &codecs.H264Packet{}, clockRate)
	recorder.initFromSDP(track.Codec().SDPFmtpLine)
	return recorder
}

func (r *StreamRecorder) Close() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.closed = true
	if r.segment != nil {
		if err := r.segment.close(); err != nil {
			log.Printf("failed to close recording segment: %v", err)
		}
		r.segment = nil
	}
}

func (r *StreamRecorder) Push(packet *rtp.Packet) {
	if packet == nil {
		return
	}
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return
	}
	r.builder.Push(packet)
	samples := make([]*media.Sample, 0)
	for {
		sample := r.builder.Pop()
		if sample == nil {
			break
		}
		samples = append(samples, sample)
	}
	r.mu.Unlock()

	for _, sample := range samples {
		r.handleSample(sample)
	}
}

func (r *StreamRecorder) handleSample(sample *media.Sample) {
	if sample == nil || len(sample.Data) == 0 {
		return
	}

	nalus := splitAnnexB(sample.Data)
	if len(nalus) == 0 {
		return
	}

	keyframe := false
	filtered := make([][]byte, 0, len(nalus))
	for _, nalu := range nalus {
		if len(nalu) == 0 {
			continue
		}
		nalType := nalu[0] & 0x1F
		switch nalType {
		case 7:
			r.handleSPS(nalu)
			continue
		case 8:
			r.handlePPS(nalu)
			continue
		case 5:
			keyframe = true
		}
		filtered = append(filtered, append([]byte(nil), nalu...))
	}

	if len(filtered) == 0 {
		return
	}

	avcc := buildAVCCSample(filtered)
	if len(avcc) == 0 {
		return
	}

	duration := r.convertDuration(sample.Duration)

	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return
	}
	if r.segment == nil || r.segmentDur >= segmentLength {
		if r.segment != nil {
			if err := r.segment.close(); err != nil {
				log.Printf("failed to finalize recording segment: %v", err)
			}
			r.segment = nil
			r.segmentDur = 0
		}
		if len(r.sps) == 0 || len(r.pps) == 0 {
			return
		}
		writer, err := newMP4SegmentWriter(r.streamID, r.sps, r.pps, r.profileIDC, r.constraint, r.levelIDC, r.width, r.height)
		if err != nil {
			log.Printf("failed to create MP4 segment: %v", err)
			return
		}
		r.segment = writer
	}
	if r.segment == nil {
		return
	}
	if err := r.segment.writeSample(avcc, duration, keyframe); err != nil {
		log.Printf("failed to write recording sample: %v", err)
		return
	}
	increment := sample.Duration
	if increment <= 0 {
		increment = time.Duration(duration) * time.Second / time.Duration(r.clockRate)
	}
	if increment <= 0 {
		increment = time.Second / 30
	}
	r.segmentDur += increment
}

func (r *StreamRecorder) handleSPS(nalu []byte) {
	if len(nalu) < 1 {
		return
	}
	parsed := removeEmulationPrevention(nalu[1:])
	width, height, profile, constraint, level, err := parseSPS(parsed)
	if err != nil {
		log.Printf("failed to parse SPS: %v", err)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sps = append([]byte(nil), nalu...)
	r.width = width
	r.height = height
	r.profileIDC = profile
	r.constraint = constraint
	r.levelIDC = level
}

func (r *StreamRecorder) handlePPS(nalu []byte) {
	if len(nalu) < 1 {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.pps = append([]byte(nil), nalu...)
}

func (r *StreamRecorder) initFromSDP(fmtp string) {
	if fmtp == "" {
		return
	}
	nalus := parseParameterSetsFromSDP(fmtp)
	for _, nalu := range nalus {
		if len(nalu) == 0 {
			continue
		}
		switch nalu[0] & 0x1F {
		case 7:
			r.handleSPS(nalu)
		case 8:
			r.handlePPS(nalu)
		}
	}
}

func (r *StreamRecorder) convertDuration(d time.Duration) uint32 {
	if d <= 0 {
		return r.clockRate / 30
	}
	seconds := float64(d) / float64(time.Second)
	dur := uint32(seconds * float64(r.clockRate))
	if dur == 0 {
		return 1
	}
	return dur
}

type mp4SegmentWriter struct {
	file         *os.File
	path         string
	start        time.Time
	sps          []byte
	pps          []byte
	profileIDC   uint8
	constraint   uint8
	levelIDC     uint8
	width        uint16
	height       uint16
	sampleCount  uint32
	durations    []uint32
	sizes        []uint32
	chunkOffsets []uint32
	syncSamples  []uint32
	lastDuration uint32
	mdatPos      int64
	mdatSize     uint64
}

func newMP4SegmentWriter(streamID string, sps, pps []byte, profile, constraint, level uint8, width, height uint16) (*mp4SegmentWriter, error) {
	if len(sps) == 0 || len(pps) == 0 {
		return nil, fmt.Errorf("missing SPS/PPS data")
	}
	base := filepath.Join(DirName, streamID)
	if err := os.MkdirAll(base, 0o755); err != nil {
		return nil, err
	}
	now := time.Now()
	name := fmt.Sprintf("%s.mp4", now.UTC().Format("20060102-150405"))
	path := filepath.Join(base, name)
	file, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	if err := writeFTYP(file); err != nil {
		file.Close()
		return nil, err
	}
	pos, err := file.Seek(0, io.SeekCurrent)
	if err != nil {
		file.Close()
		return nil, err
	}
	if err := writeBoxHeader(file, "mdat", 0); err != nil {
		file.Close()
		return nil, err
	}
	return &mp4SegmentWriter{
		file:       file,
		path:       path,
		start:      now,
		sps:        append([]byte(nil), sps...),
		pps:        append([]byte(nil), pps...),
		profileIDC: profile,
		constraint: constraint,
		levelIDC:   level,
		width:      width,
		height:     height,
		mdatPos:    pos,
	}, nil
}

func (w *mp4SegmentWriter) writeSample(data []byte, duration uint32, keyframe bool) error {
	if w.file == nil {
		return fmt.Errorf("segment closed")
	}
	if duration == 0 {
		if w.lastDuration != 0 {
			duration = w.lastDuration
		} else {
			duration = defaultTimescale / 30
		}
	}
	offset, err := w.file.Seek(0, io.SeekCurrent)
	if err != nil {
		return err
	}
	if _, err := w.file.Write(data); err != nil {
		return err
	}
	w.mdatSize += uint64(len(data))
	w.sampleCount++
	w.durations = append(w.durations, duration)
	w.sizes = append(w.sizes, uint32(len(data)))
	w.chunkOffsets = append(w.chunkOffsets, uint32(offset))
	if keyframe {
		w.syncSamples = append(w.syncSamples, w.sampleCount)
	}
	w.lastDuration = duration
	return nil
}

func (w *mp4SegmentWriter) close() error {
	if w.file == nil {
		return nil
	}
	if err := w.finalize(); err != nil {
		_ = w.file.Close()
		w.file = nil
		return err
	}
	err := w.file.Close()
	w.file = nil
	return err
}

func (w *mp4SegmentWriter) finalize() error {
	if w.file == nil {
		return nil
	}
	if _, err := w.file.Seek(w.mdatPos, io.SeekStart); err != nil {
		return err
	}
	total := uint64(8) + w.mdatSize
	if total > math.MaxUint32 {
		if err := writeBoxHeader64(w.file, "mdat", total); err != nil {
			return err
		}
	} else {
		if err := writeBoxHeader(w.file, "mdat", uint32(total)); err != nil {
			return err
		}
	}
	if _, err := w.file.Seek(0, io.SeekEnd); err != nil {
		return err
	}
	moov, err := w.buildMoov()
	if err != nil {
		return err
	}
	if _, err := w.file.Write(moov); err != nil {
		return err
	}
	return nil
}

func (w *mp4SegmentWriter) buildMoov() ([]byte, error) {
	duration := uint32(0)
	for _, d := range w.durations {
		duration += d
	}
	sttsEntries := buildSTTSEntries(w.durations)
	stts := buildSTTSBox(sttsEntries)
	stsc := buildSTSCBox()
	stsz := buildSTSZBox(w.sizes)
	stco := buildSTCOBox(w.chunkOffsets)
	stss := buildSTSSBox(w.syncSamples)
	avcC := buildAVCCBox(w.sps, w.pps, w.profileIDC, w.constraint, w.levelIDC)
	stsd := buildSTSDBox(w.width, w.height, avcC)
	stbl := wrapBox("stbl", concatBoxes(stsd, stts, stsc, stsz, stco, stss))
	minf := wrapBox("minf", concatBoxes(buildVMHDBox(), buildDINFBox(), stbl))
	mdia := wrapBox("mdia", concatBoxes(buildMDHDBox(duration), buildHDLRBox(), minf))
	tkhd := buildTKHDBox(duration, w.width, w.height)
	trak := wrapBox("trak", concatBoxes(tkhd, mdia))
	mvhd := buildMVHDBox(duration)
	return wrapBox("moov", concatBoxes(mvhd, trak)), nil
}

// MP4 box helpers

func writeFTYP(w io.Writer) error {
	payload := []byte{
		'i', 's', 'o', 'm',
		0x00, 0x00, 0x02, 0x00,
		'i', 's', 'o', 'm',
		'i', 's', 'o', '2',
		'a', 'v', 'c', '1',
		'm', 'p', '4', '1',
	}
	_, err := w.Write(wrapBox("ftyp", payload))
	return err
}

func writeBoxHeader(w io.Writer, boxType string, size uint32) error {
	buf := make([]byte, 8)
	binary.BigEndian.PutUint32(buf[:4], size)
	copy(buf[4:], []byte(boxType))
	_, err := w.Write(buf)
	return err
}

func writeBoxHeader64(w io.Writer, boxType string, size uint64) error {
	buf := make([]byte, 16)
	binary.BigEndian.PutUint32(buf[:4], 1)
	copy(buf[4:], []byte(boxType))
	binary.BigEndian.PutUint64(buf[8:], size)
	_, err := w.Write(buf)
	return err
}

func wrapBox(boxType string, payload []byte) []byte {
	buf := make([]byte, 8+len(payload))
	binary.BigEndian.PutUint32(buf[:4], uint32(len(payload)+8))
	copy(buf[4:], []byte(boxType))
	copy(buf[8:], payload)
	return buf
}

func concatBoxes(boxes ...[]byte) []byte {
	total := 0
	for _, box := range boxes {
		total += len(box)
	}
	out := make([]byte, 0, total)
	for _, box := range boxes {
		out = append(out, box...)
	}
	return out
}

func buildMVHDBox(duration uint32) []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 0})
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, defaultTimescale)
	writeUint32(buf, duration)
	writeUint32(buf, 0x00010000)
	writeUint16(buf, 0x0100)
	writeUint16(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	buf.Write([]byte{
		0x00, 0x01, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x01, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x40, 0x00, 0x00, 0x00,
	})
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 2)
	return wrapBox("mvhd", buf.Bytes())
}

func buildTKHDBox(duration uint32, width, height uint16) []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 7})
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 1)
	writeUint32(buf, 0)
	writeUint32(buf, duration)
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint16(buf, 0)
	writeUint16(buf, 0)
	writeUint16(buf, 0)
	writeUint16(buf, 0)
	buf.Write([]byte{
		0x00, 0x01, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x01, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x40, 0x00, 0x00, 0x00,
	})
	writeUint32(buf, uint32(width)<<16)
	writeUint32(buf, uint32(height)<<16)
	return wrapBox("tkhd", buf.Bytes())
}

func buildMDHDBox(duration uint32) []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 0})
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, defaultTimescale)
	writeUint32(buf, duration)
	writeUint16(buf, 0x55c4)
	writeUint16(buf, 0)
	return wrapBox("mdhd", buf.Bytes())
}

func buildHDLRBox() []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 0})
	writeUint32(buf, 0)
	buf.WriteString("vide")
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	writeUint32(buf, 0)
	buf.WriteString("VideoHandler")
	buf.WriteByte(0)
	return wrapBox("hdlr", buf.Bytes())
}

func buildVMHDBox() []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 1})
	writeUint16(buf, 0)
	writeUint16(buf, 0)
	writeUint16(buf, 0)
	return wrapBox("vmhd", buf.Bytes())
}

func buildDINFBox() []byte {
	dref := &bytes.Buffer{}
	dref.WriteByte(0)
	dref.Write([]byte{0, 0, 0})
	writeUint32(dref, 1)
	url := &bytes.Buffer{}
	url.WriteByte(0)
	url.Write([]byte{0, 0, 1})
	drefPayload := append(dref.Bytes(), wrapBox("url ", url.Bytes())...)
	return wrapBox("dinf", wrapBox("dref", drefPayload))
}

func buildSTSDBox(width, height uint16, avcC []byte) []byte {
	avc1 := &bytes.Buffer{}
	avc1.Write(make([]byte, 6))
	writeUint16(avc1, 1)
	avc1.Write(make([]byte, 16))
	writeUint16(avc1, width)
	writeUint16(avc1, height)
	writeUint32(avc1, 0x00480000)
	writeUint32(avc1, 0x00480000)
	writeUint32(avc1, 0)
	writeUint16(avc1, 1)
	avc1.Write(make([]byte, 32))
	writeUint16(avc1, 0x18)
	writeUint16(avc1, 0xffff)
	avc1.Write(avcC)

	payload := &bytes.Buffer{}
	payload.WriteByte(0)
	payload.Write([]byte{0, 0, 0})
	writeUint32(payload, 1)
	payload.Write(wrapBox("avc1", avc1.Bytes()))
	return wrapBox("stsd", payload.Bytes())
}

type sttsEntry struct {
	count uint32
	delta uint32
}

func buildSTTSEntries(durations []uint32) []sttsEntry {
	if len(durations) == 0 {
		return nil
	}
	entries := make([]sttsEntry, 0)
	current := sttsEntry{count: 1, delta: durations[0]}
	for i := 1; i < len(durations); i++ {
		if durations[i] == current.delta {
			current.count++
		} else {
			entries = append(entries, current)
			current = sttsEntry{count: 1, delta: durations[i]}
		}
	}
	entries = append(entries, current)
	return entries
}

func buildSTTSBox(entries []sttsEntry) []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 0})
	writeUint32(buf, uint32(len(entries)))
	for _, e := range entries {
		writeUint32(buf, e.count)
		writeUint32(buf, e.delta)
	}
	return wrapBox("stts", buf.Bytes())
}

func buildSTSCBox() []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 0})
	writeUint32(buf, 1)
	writeUint32(buf, 1)
	writeUint32(buf, 1)
	writeUint32(buf, 1)
	return wrapBox("stsc", buf.Bytes())
}

func buildSTSZBox(sizes []uint32) []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 0})
	writeUint32(buf, 0)
	writeUint32(buf, uint32(len(sizes)))
	for _, size := range sizes {
		writeUint32(buf, size)
	}
	return wrapBox("stsz", buf.Bytes())
}

func buildSTCOBox(offsets []uint32) []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 0})
	writeUint32(buf, uint32(len(offsets)))
	for _, off := range offsets {
		writeUint32(buf, off)
	}
	return wrapBox("stco", buf.Bytes())
}

func buildSTSSBox(syncSamples []uint32) []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(0)
	buf.Write([]byte{0, 0, 0})
	writeUint32(buf, uint32(len(syncSamples)))
	for _, sample := range syncSamples {
		writeUint32(buf, sample)
	}
	return wrapBox("stss", buf.Bytes())
}

func buildAVCCBox(sps, pps []byte, profile, constraint, level uint8) []byte {
	buf := &bytes.Buffer{}
	buf.WriteByte(1)
	buf.WriteByte(profile)
	buf.WriteByte(constraint)
	buf.WriteByte(level)
	buf.WriteByte(0xFF)
	buf.WriteByte(0xE1)
	writeUint16(buf, uint16(len(sps)))
	buf.Write(sps)
	buf.WriteByte(1)
	writeUint16(buf, uint16(len(pps)))
	buf.Write(pps)
	return wrapBox("avcC", buf.Bytes())
}

func writeUint16(buf *bytes.Buffer, value uint16) {
	var tmp [2]byte
	binary.BigEndian.PutUint16(tmp[:], value)
	buf.Write(tmp[:])
}

func writeUint32(buf *bytes.Buffer, value uint32) {
	var tmp [4]byte
	binary.BigEndian.PutUint32(tmp[:], value)
	buf.Write(tmp[:])
}

func splitAnnexB(data []byte) [][]byte {
	var nalus [][]byte
	start := -1
	i := 0
	for i < len(data) {
		if i+3 < len(data) && data[i] == 0 && data[i+1] == 0 {
			if data[i+2] == 1 {
				if start >= 0 {
					nalus = append(nalus, append([]byte(nil), data[start:i]...))
				}
				start = i + 3
				i += 3
				continue
			}
			if i+4 < len(data) && data[i+2] == 0 && data[i+3] == 1 {
				if start >= 0 {
					nalus = append(nalus, append([]byte(nil), data[start:i]...))
				}
				start = i + 4
				i += 4
				continue
			}
		}
		i++
	}
	if start >= 0 && start < len(data) {
		nalus = append(nalus, append([]byte(nil), data[start:]...))
	}
	return nalus
}

func buildAVCCSample(nalus [][]byte) []byte {
	out := &bytes.Buffer{}
	for _, nalu := range nalus {
		writeUint32(out, uint32(len(nalu)))
		out.Write(nalu)
	}
	return out.Bytes()
}

func removeEmulationPrevention(data []byte) []byte {
	out := make([]byte, 0, len(data))
	for i := 0; i < len(data); i++ {
		if i+2 < len(data) && data[i] == 0 && data[i+1] == 0 && data[i+2] == 3 {
			out = append(out, 0, 0)
			i += 2
			continue
		}
		out = append(out, data[i])
	}
	return out
}

func parseSPS(data []byte) (width, height uint16, profile, constraint, level uint8, err error) {
	if len(data) < 4 {
		return 0, 0, 0, 0, 0, fmt.Errorf("SPS too short")
	}
	profile = data[0]
	constraint = data[1]
	level = data[2]
	br := newBitReader(data[3:])

	if _, err = br.readUE(); err != nil {
		return
	}
	chromaFormatIDC := uint32(1)
	if profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 {
		if chromaFormatIDC, err = br.readUE(); err != nil {
			return
		}
		if chromaFormatIDC == 3 {
			if _, err = br.readBits(1); err != nil {
				return
			}
		}
		if _, err = br.readUE(); err != nil {
			return
		}
		if _, err = br.readUE(); err != nil {
			return
		}
		if _, err = br.readBits(1); err != nil {
			return
		}
		scalingListCount := 8
		if chromaFormatIDC == 3 {
			scalingListCount = 12
		}
		for i := 0; i < scalingListCount; i++ {
			present, e := br.readBits(1)
			if e != nil {
				err = e
				return
			}
			if present != 0 {
				size := 16
				if i >= 6 {
					size = 64
				}
				var last, next int32 = 8, 8
				for j := 0; j < size; j++ {
					var delta int32
					if delta, err = br.readSE(); err != nil {
						return
					}
					next = (last + delta + 256) % 256
					if next == 0 {
						last = last
					} else {
						last = next
					}
				}
			}
		}
	}

	if _, err = br.readUE(); err != nil { // log2_max_frame_num_minus4
		return
	}
	var picOrderCntType uint32
	if picOrderCntType, err = br.readUE(); err != nil {
		return
	}
	if picOrderCntType == 0 {
		if _, err = br.readUE(); err != nil {
			return
		}
	} else if picOrderCntType == 1 {
		if _, err = br.readBits(1); err != nil {
			return
		}
		if _, err = br.readSE(); err != nil {
			return
		}
		if _, err = br.readSE(); err != nil {
			return
		}
		var numRef uint32
		if numRef, err = br.readUE(); err != nil {
			return
		}
		for i := uint32(0); i < numRef; i++ {
			if _, err = br.readSE(); err != nil {
				return
			}
		}
	}
	if _, err = br.readUE(); err != nil { // max_num_ref_frames
		return
	}
	if _, err = br.readBits(1); err != nil { // gaps_in_frame_num_value_allowed_flag
		return
	}
	var picWidthInMbsMinus1 uint32
	if picWidthInMbsMinus1, err = br.readUE(); err != nil {
		return
	}
	var picHeightInMapUnitsMinus1 uint32
	if picHeightInMapUnitsMinus1, err = br.readUE(); err != nil {
		return
	}
	frameMbsOnlyFlag, e := br.readBits(1)
	if e != nil {
		err = e
		return
	}
	if frameMbsOnlyFlag == 0 {
		if _, err = br.readBits(1); err != nil {
			return
		}
	}
	if _, err = br.readBits(1); err != nil { // direct_8x8_inference_flag
		return
	}
	frameCroppingFlag, e := br.readBits(1)
	if e != nil {
		err = e
		return
	}
	cropLeft, cropRight, cropTop, cropBottom := uint32(0), uint32(0), uint32(0), uint32(0)
	if frameCroppingFlag != 0 {
		if cropLeft, err = br.readUE(); err != nil {
			return
		}
		if cropRight, err = br.readUE(); err != nil {
			return
		}
		if cropTop, err = br.readUE(); err != nil {
			return
		}
		if cropBottom, err = br.readUE(); err != nil {
			return
		}
	}

	mbsWidth := picWidthInMbsMinus1 + 1
	mbsHeight := (picHeightInMapUnitsMinus1 + 1) * (2 - frameMbsOnlyFlag)
	cropUnitX := uint32(1)
	cropUnitY := uint32(2 - frameMbsOnlyFlag)
	switch chromaFormatIDC {
	case 0:
		cropUnitX = 1
		cropUnitY = 2 - frameMbsOnlyFlag
	case 1:
		cropUnitX = 2
		cropUnitY = 2 * (2 - frameMbsOnlyFlag)
	case 2:
		cropUnitX = 2
		cropUnitY = 1 * (2 - frameMbsOnlyFlag)
	case 3:
		cropUnitX = 1
		cropUnitY = 1 * (2 - frameMbsOnlyFlag)
	}

	width = uint16(mbsWidth*16 - cropUnitX*(cropLeft+cropRight))
	height = uint16(mbsHeight*16 - cropUnitY*(cropTop+cropBottom))
	return
}

func parseParameterSetsFromSDP(fmtp string) [][]byte {
	fields := strings.Split(fmtp, ";")
	for _, field := range fields {
		field = strings.TrimSpace(field)
		if !strings.HasPrefix(field, "sprop-parameter-sets=") {
			continue
		}
		raw := strings.TrimPrefix(field, "sprop-parameter-sets=")
		parts := strings.Split(raw, ",")
		nalus := make([][]byte, 0, len(parts))
		for _, part := range parts {
			data, err := base64.StdEncoding.DecodeString(strings.TrimSpace(part))
			if err != nil {
				log.Printf("failed to decode sprop-parameter-sets value: %v", err)
				continue
			}
			if len(data) == 0 {
				continue
			}
			nalus = append(nalus, data)
		}
		return nalus
	}
	return nil
}

type bitReader struct {
	data []byte
	idx  int
	bit  uint8
}

func newBitReader(data []byte) *bitReader {
	return &bitReader{data: data, idx: 0, bit: 0x80}
}

func (b *bitReader) readBit() (uint8, error) {
	if b.idx >= len(b.data) {
		return 0, fmt.Errorf("bitReader overflow")
	}
	value := uint8(0)
	if b.data[b.idx]&b.bit != 0 {
		value = 1
	}
	b.bit >>= 1
	if b.bit == 0 {
		b.bit = 0x80
		b.idx++
	}
	return value, nil
}

func (b *bitReader) readBits(n int) (uint32, error) {
	var value uint32
	for i := 0; i < n; i++ {
		bit, err := b.readBit()
		if err != nil {
			return 0, err
		}
		value = (value << 1) | uint32(bit)
	}
	return value, nil
}

func (b *bitReader) readUE() (uint32, error) {
	zeros := 0
	for {
		bit, err := b.readBit()
		if err != nil {
			return 0, err
		}
		if bit == 0 {
			zeros++
			continue
		}
		break
	}
	if zeros == 0 {
		return 0, nil
	}
	value, err := b.readBits(zeros)
	if err != nil {
		return 0, err
	}
	return (1<<zeros - 1) + value, nil
}

func (b *bitReader) readSE() (int32, error) {
	ue, err := b.readUE()
	if err != nil {
		return 0, err
	}
	m := int32((ue + 1) / 2)
	if ue%2 == 0 {
		m = -m
	}
	return m, nil
}
