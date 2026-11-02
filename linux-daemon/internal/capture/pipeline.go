package capture

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	// EnvTestSource enables synthetic video/audio generation (videotestsrc pattern=ball + audiotestsrc).
	EnvTestSource = "DECK_CAPTURE_TEST_SOURCE"

	DefaultWidth       = 1280
	DefaultHeight      = 720
	DefaultFPS         = 60
	DefaultBitrateKbps = 4000
)

// H264EncoderKind identifies the selected hardware or software H.264 encoder.
type H264EncoderKind string

const (
	EncoderVAAPI    H264EncoderKind = "vaapih264enc"
	EncoderNVENC    H264EncoderKind = "nvh264enc"
	EncoderX264     H264EncoderKind = "x264enc"
	EncoderFallback H264EncoderKind = "synthetic-h264"
)

// MediaSample represents an elementary encoded H.264 NAL/AU or Opus audio frame with sample duration.
type MediaSample struct {
	Data       []byte
	Duration   time.Duration
	Timestamp  time.Time
	IsKeyFrame bool
}

// MediaSource produces low-latency elementary H.264 video and Opus audio samples for WebRTC streaming.
type MediaSource interface {
	Start(ctx context.Context) error
	VideoSamples() <-chan MediaSample
	AudioSamples() <-chan MediaSample
	EncoderName() string
	Close() error
}

// PipelineConfig configures resolution, frame rate, bitrate, and synthetic test mode.
type PipelineConfig struct {
	Width        uint32
	Height       uint32
	FPS          uint32
	BitrateKbps  uint32
	EnableAudio  bool
	TestSource   bool
	ConfigDir    string
	Logger       *slog.Logger
}

// GStreamerPipeline implements MediaSource using GStreamer (with PipeWire / X11 / videotestsrc)
// and zero-B-frame H.264 + Opus encoding.
type GStreamerPipeline struct {
	cfg           PipelineConfig
	logger        *slog.Logger
	videoCh       chan MediaSample
	audioCh       chan MediaSample
	encoderKind   H264EncoderKind
	mu            sync.Mutex
	started       bool
	closed        bool
	cancel        context.CancelFunc
	portalSession *PipeWirePortalSession
	videoCmd      *exec.Cmd
	audioCmd      *exec.Cmd
	audioUDPConn  *net.UDPConn
	wg            sync.WaitGroup
	closeChOnce   sync.Once
}

// NewPipeline creates a new low-latency capture & encoding MediaSource.
func NewPipeline(cfg PipelineConfig) *GStreamerPipeline {
	if cfg.Width == 0 {
		cfg.Width = DefaultWidth
	}
	if cfg.Height == 0 {
		cfg.Height = DefaultHeight
	}
	if cfg.FPS == 0 {
		cfg.FPS = DefaultFPS
	}
	if cfg.BitrateKbps == 0 {
		cfg.BitrateKbps = DefaultBitrateKbps
	}
	if os.Getenv(EnvTestSource) == "1" {
		cfg.TestSource = true
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}

	return &GStreamerPipeline{
		cfg:     cfg,
		logger:  logger,
		videoCh: make(chan MediaSample, 128),
		audioCh: make(chan MediaSample, 128),
	}
}

// VideoSamples returns the read-only channel of encoded H.264 Annex-B NAL units.
func (p *GStreamerPipeline) VideoSamples() <-chan MediaSample {
	return p.videoCh
}

// AudioSamples returns the read-only channel of encoded Opus frames.
func (p *GStreamerPipeline) AudioSamples() <-chan MediaSample {
	return p.audioCh
}

// EncoderName returns the active H.264 encoder name.
func (p *GStreamerPipeline) EncoderName() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return string(p.encoderKind)
}

// DetectH264Encoder inspects available GStreamer plugins in strict priority order:
// 1. AMD/Intel VA-API (vaapih264enc)
// 2. NVIDIA NVENC (nvh264enc)
// 3. Software CPU fallback (x264enc)
func DetectH264Encoder(bitrateKbps uint32) (H264EncoderKind, []string, error) {
	if _, err := exec.LookPath("gst-inspect-1.0"); err != nil {
		return "", nil, fmt.Errorf("gst-inspect-1.0 not found: %w", err)
	}
	if _, err := exec.LookPath("gst-launch-1.0"); err != nil {
		return "", nil, fmt.Errorf("gst-launch-1.0 not found: %w", err)
	}

	if hasGstElement("vaapih264enc") {
		args := []string{
			"vaapih264enc",
			"rate-control=cbr",
			"tune=low-power",
			"keyframe-period=60",
			"max-bframes=0",
			fmt.Sprintf("bitrate=%d", bitrateKbps),
		}
		return EncoderVAAPI, args, nil
	}

	if hasGstElement("nvh264enc") {
		args := []string{
			"nvh264enc",
			"preset=low-latency-hq",
			"rc-mode=cbr",
			"zerolatency=true",
			"bframes=0",
			fmt.Sprintf("bitrate=%d", bitrateKbps),
		}
		return EncoderNVENC, args, nil
	}

	if hasGstElement("x264enc") {
		args := []string{
			"x264enc",
			"tune=zerolatency",
			"speed-preset=ultrafast",
			"bframes=0",
			"key-int-max=60",
			fmt.Sprintf("bitrate=%d", bitrateKbps),
		}
		return EncoderX264, args, nil
	}

	return "", nil, errors.New("capture: no supported H.264 GStreamer encoder found (vaapih264enc, nvh264enc, x264enc)")
}

// Start launches the video and audio capture/encoding pipelines until ctx is cancelled or Close is called.
func (p *GStreamerPipeline) Start(ctx context.Context) error {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return errors.New("capture pipeline: already closed")
	}
	if p.started {
		p.mu.Unlock()
		return nil
	}
	runCtx, cancel := context.WithCancel(ctx)
	p.cancel = cancel
	p.started = true
	p.mu.Unlock()

	encKind, encArgs, encErr := DetectH264Encoder(p.cfg.BitrateKbps)
	if encErr != nil {
		if p.cfg.TestSource {
			p.logger.Warn("GStreamer unavailable in test mode; using built-in synthetic H.264/Opus generator", "err", encErr)
			p.mu.Lock()
			p.encoderKind = EncoderFallback
			p.mu.Unlock()
			p.startPureGoSyntheticLoops(runCtx)
			return nil
		}
		cancel()
		return encErr
	}

	p.mu.Lock()
	p.encoderKind = encKind
	p.mu.Unlock()

	if err := p.startGStreamerVideo(runCtx, encKind, encArgs); err != nil {
		cancel()
		return err
	}

	if p.cfg.EnableAudio || p.cfg.TestSource {
		p.startAudioPipeline(runCtx)
	}

	// Ensure channels close once all worker goroutines exit.
	go func() {
		<-runCtx.Done()
		_ = p.Close()
	}()

	return nil
}

// Close immediately terminates all GStreamer processes, closes portals/sockets, and waits for goroutines.
func (p *GStreamerPipeline) Close() error {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		p.wg.Wait()
		return nil
	}
	p.closed = true
	cancel := p.cancel
	videoCmd := p.videoCmd
	audioCmd := p.audioCmd
	udpConn := p.audioUDPConn
	portal := p.portalSession
	p.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	killProcessGroup(videoCmd)
	killProcessGroup(audioCmd)
	if udpConn != nil {
		_ = udpConn.Close()
	}
	if portal != nil {
		_ = portal.Close()
	}

	p.wg.Wait()

	p.closeChOnce.Do(func() {
		close(p.videoCh)
		close(p.audioCh)
	})
	return nil
}

func (p *GStreamerPipeline) startGStreamerVideo(ctx context.Context, encKind H264EncoderKind, encArgs []string) error {
	var (
		srcArgs    []string
		extraFiles []*os.File
	)

	if p.cfg.TestSource {
		srcArgs = []string{
			"videotestsrc", "pattern=ball", "is-live=true",
			"!", fmt.Sprintf("video/x-raw,width=%d,height=%d,framerate=%d/1", p.cfg.Width, p.HeightOrDefault(), p.cfg.FPS),
		}
	} else if IsWaylandSession() {
		portalSess, err := NegotiateWaylandScreenCast(ctx, p.cfg.ConfigDir, p.logger)
		if err != nil {
			return fmt.Errorf("capture negotiate wayland portal: %w", err)
		}
		p.mu.Lock()
		p.portalSession = portalSess
		p.mu.Unlock()

		// ExtraFiles[0] becomes FD 3 in the child process.
		extraFiles = append(extraFiles, portalSess.PipeWireFile)
		srcArgs = []string{
			"pipewiresrc", "fd=3", fmt.Sprintf("path=%d", portalSess.NodeID), "do-timestamp=true", "keepalive-time=1000",
			"!", "videoconvert",
			"!", "videoscale",
			"!", fmt.Sprintf("video/x-raw,width=%d,height=%d,framerate=%d/1", p.cfg.Width, p.cfg.Height, p.cfg.FPS),
		}
	} else if hasGstElement("ximagesrc") {
		srcArgs = []string{
			"ximagesrc", "use-damage=false", "show-pointer=true",
			"!", "videoconvert",
			"!", "videoscale",
			"!", fmt.Sprintf("video/x-raw,width=%d,height=%d,framerate=%d/1", p.cfg.Width, p.cfg.Height, p.cfg.FPS),
		}
	} else if hasGstElement("pipewiresrc") {
		srcArgs = []string{
			"pipewiresrc", "do-timestamp=true",
			"!", "videoconvert",
			"!", "videoscale",
			"!", fmt.Sprintf("video/x-raw,width=%d,height=%d,framerate=%d/1", p.cfg.Width, p.cfg.Height, p.cfg.FPS),
		}
	} else {
		return errors.New("capture: no desktop video capture element found (pipewiresrc or ximagesrc)")
	}

	args := []string{"-q"}
	args = append(args, srcArgs...)
	args = append(args, "!", "videoconvert", "!")
	args = append(args, encArgs...)
	args = append(args,
		"!", "video/x-h264,stream-format=byte-stream,alignment=au,profile=constrained-baseline",
		"!", "fdsink", "fd=1",
	)

	cmd := exec.CommandContext(ctx, "gst-launch-1.0", args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.ExtraFiles = extraFiles

	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("capture video stdout pipe: %w", err)
	}

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("capture start gst-launch-1.0 (%s): %w", encKind, err)
	}

	p.mu.Lock()
	p.videoCmd = cmd
	p.mu.Unlock()

	p.logger.Info("started GStreamer H.264 video pipeline",
		"encoder", encKind,
		"test_source", p.cfg.TestSource,
		"width", p.cfg.Width,
		"height", p.cfg.Height,
		"fps", p.cfg.FPS,
	)

	frameDuration := time.Second / time.Duration(p.cfg.FPS)
	p.wg.Add(1)
	go func() {
		defer p.wg.Done()
		p.readAnnexBH264Stream(ctx, stdoutPipe, frameDuration)
		_ = cmd.Wait()
	}()

	return nil
}

func (p *GStreamerPipeline) HeightOrDefault() uint32 {
	if p.cfg.Height == 0 {
		return DefaultHeight
	}
	return p.cfg.Height
}

func (p *GStreamerPipeline) startAudioPipeline(ctx context.Context) {
	if hasGstElement("opusenc") && (p.cfg.TestSource || hasGstElement("pulsesrc") || hasGstElement("pipewiresrc")) {
		udpAddr, err := net.ResolveUDPAddr("udp", "127.0.0.1:0")
		if err == nil {
			udpConn, listenErr := net.ListenUDP("udp", udpAddr)
			if listenErr == nil {
				port := udpConn.LocalAddr().(*net.UDPAddr).Port
				var srcElem []string
				if p.cfg.TestSource {
					srcElem = []string{"audiotestsrc", "is-live=true", "wave=sine", "volume=0.05"}
				} else if hasGstElement("pipewiresrc") {
					srcElem = []string{"pipewiresrc", "do-timestamp=true"}
				} else {
					srcElem = []string{"pulsesrc"}
				}

				args := []string{"-q"}
				args = append(args, srcElem...)
				args = append(args,
					"!", "audioconvert",
					"!", "audioresample",
					"!", "audio/x-raw,rate=48000,channels=2",
					"!", "opusenc", "frame-size=20", "bitrate=64000",
					"!", "udpsink", "host=127.0.0.1", fmt.Sprintf("port=%d", port),
				)

				cmd := exec.CommandContext(ctx, "gst-launch-1.0", args...)
				cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
				if err := cmd.Start(); err == nil {
					p.mu.Lock()
					p.audioCmd = cmd
					p.audioUDPConn = udpConn
					p.mu.Unlock()

					p.wg.Add(1)
					go func() {
						defer p.wg.Done()
						buf := make([]byte, 4096)
						for {
							n, _, rErr := udpConn.ReadFromUDP(buf)
							if n > 0 {
								pkt := make([]byte, n)
								copy(pkt, buf[:n])
								p.emitAudio(ctx, MediaSample{
									Data:      pkt,
									Duration:  20 * time.Millisecond,
									Timestamp: time.Now(),
								})
							}
							if rErr != nil {
								break
							}
						}
						_ = cmd.Wait()
					}()
					return
				}
				_ = udpConn.Close()
			}
		}
	}

	// Fallback synthetic 20ms RFC 6716 Opus frames (valid 48kHz stereo 20ms DTX/silence Opus packet)
	// when opusenc GStreamer plugin is unavailable.
	if p.cfg.TestSource {
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			ticker := time.NewTicker(20 * time.Millisecond)
			defer ticker.Stop()
			opusSilence := []byte{0xF8, 0xFF, 0xFE}
			for {
				select {
				case <-ctx.Done():
					return
				case now := <-ticker.C:
					pkt := make([]byte, len(opusSilence))
					copy(pkt, opusSilence)
					p.emitAudio(ctx, MediaSample{
						Data:      pkt,
						Duration:  20 * time.Millisecond,
						Timestamp: now,
					})
				}
			}
		}()
	}
}

func (p *GStreamerPipeline) startPureGoSyntheticLoops(ctx context.Context) {
	frameDur := time.Second / time.Duration(p.cfg.FPS)
	// Valid minimal Constrained Baseline SPS, PPS, and IDR NAL units with Annex-B 00 00 00 01 start codes.
	spsNAL := []byte{0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0, 0x1F, 0xDA, 0x01, 0x40, 0x16, 0xEC, 0x04, 0x40, 0x00, 0x00, 0x03, 0x00, 0x40, 0x00, 0x00, 0x0F, 0x03, 0xC5, 0x8B, 0xA8}
	ppsNAL := []byte{0x00, 0x00, 0x00, 0x01, 0x68, 0xCE, 0x3C, 0x80}
	idrNAL := []byte{0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x00, 0x10, 0xFF}

	p.wg.Add(2)
	go func() {
		defer p.wg.Done()
		ticker := time.NewTicker(frameDur)
		defer ticker.Stop()
		var idx uint64
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-ticker.C:
				if idx%30 == 0 {
					p.emitVideo(ctx, MediaSample{Data: append([]byte(nil), spsNAL...), Duration: 0, Timestamp: now, IsKeyFrame: true})
					p.emitVideo(ctx, MediaSample{Data: append([]byte(nil), ppsNAL...), Duration: 0, Timestamp: now, IsKeyFrame: true})
				}
				p.emitVideo(ctx, MediaSample{Data: append([]byte(nil), idrNAL...), Duration: frameDur, Timestamp: now, IsKeyFrame: idx%30 == 0})
				idx++
			}
		}
	}()

	go func() {
		defer p.wg.Done()
		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()
		opusSilence := []byte{0xF8, 0xFF, 0xFE}
		for {
			select {
			case <-ctx.Done():
				return
			case now := <-ticker.C:
				p.emitAudio(ctx, MediaSample{
					Data:      append([]byte(nil), opusSilence...),
					Duration:  20 * time.Millisecond,
					Timestamp: now,
				})
			}
		}
	}()
}

// readAnnexBH264Stream parses an H.264 Annex-B byte-stream into individual NAL units (each prefixed with 0x00000001).
func (p *GStreamerPipeline) readAnnexBH264Stream(ctx context.Context, r io.Reader, frameDuration time.Duration) {
	var carry []byte
	readBuf := make([]byte, 32*1024)

	for {
		n, err := r.Read(readBuf)
		if n > 0 {
			carry = append(carry, readBuf[:n]...)
			carry = p.extractAndEmitNALs(ctx, carry, frameDuration, false)
		}
		if err != nil {
			if len(carry) > 0 {
				_ = p.extractAndEmitNALs(ctx, carry, frameDuration, true)
			}
			return
		}
	}
}

func (p *GStreamerPipeline) extractAndEmitNALs(ctx context.Context, buf []byte, frameDuration time.Duration, flush bool) []byte {
	starts := findAnnexBStartCodes(buf)
	if len(starts) == 0 {
		if len(buf) > 1024*1024 {
			return nil
		}
		return buf
	}

	limit := len(starts) - 1
	if flush {
		limit = len(starts)
	}

	for i := 0; i < limit; i++ {
		scPos := starts[i].pos
		scLen := starts[i].length
		var endPos int
		if i+1 < len(starts) {
			endPos = starts[i+1].pos
		} else {
			endPos = len(buf)
		}

		payload := buf[scPos+scLen : endPos]
		if len(payload) == 0 {
			continue
		}

		nalType := payload[0] & 0x1F
		// Normalize with 4-byte Annex-B start code 0x00000001.
		nalBytes := make([]byte, 4+len(payload))
		nalBytes[0], nalBytes[1], nalBytes[2], nalBytes[3] = 0x00, 0x00, 0x00, 0x01
		copy(nalBytes[4:], payload)

		dur := time.Duration(0)
		if nalType == 1 || nalType == 5 {
			// Coded slice (non-IDR = 1, IDR = 5) carries the frame duration.
			dur = frameDuration
		}

		p.emitVideo(ctx, MediaSample{
			Data:       nalBytes,
			Duration:   dur,
			Timestamp:  time.Now(),
			IsKeyFrame: nalType == 5 || nalType == 7,
		})
	}

	if flush {
		return nil
	}
	lastPos := starts[len(starts)-1].pos
	rem := make([]byte, len(buf)-lastPos)
	copy(rem, buf[lastPos:])
	return rem
}

type startCodeLoc struct {
	pos    int
	length int
}

func findAnnexBStartCodes(b []byte) []startCodeLoc {
	var out []startCodeLoc
	i := 0
	for i+3 <= len(b) {
		idx := bytes.Index(b[i:], []byte{0x00, 0x00, 0x01})
		if idx < 0 {
			break
		}
		pos := i + idx
		if pos > 0 && b[pos-1] == 0x00 {
			out = append(out, startCodeLoc{pos: pos - 1, length: 4})
		} else {
			out = append(out, startCodeLoc{pos: pos, length: 3})
		}
		i = pos + 3
	}
	return out
}

// IsValidAnnexBNAL verifies that data starts with an Annex-B start code (00 00 00 01 or 00 00 01)
// followed by a valid H.264 NAL header (forbidden_zero_bit == 0 and nal_unit_type in 1..31).
func IsValidAnnexBNAL(data []byte) bool {
	var hdr byte
	switch {
	case len(data) > 4 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01:
		hdr = data[4]
	case len(data) > 3 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x01:
		hdr = data[3]
	default:
		return false
	}
	forbiddenBit := hdr & 0x80
	nalType := hdr & 0x1F
	return forbiddenBit == 0 && nalType >= 1 && nalType <= 31
}

func (p *GStreamerPipeline) emitVideo(ctx context.Context, sample MediaSample) {
	select {
	case <-ctx.Done():
	case p.videoCh <- sample:
	default:
		// Drop oldest frame if consumer lags to preserve ultra-low latency.
		select {
		case <-p.videoCh:
		default:
		}
		select {
		case p.videoCh <- sample:
		default:
		}
	}
}

func (p *GStreamerPipeline) emitAudio(ctx context.Context, sample MediaSample) {
	select {
	case <-ctx.Done():
	case p.audioCh <- sample:
	default:
	}
}

func hasGstElement(element string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "gst-inspect-1.0", strings.TrimSpace(element))
	return cmd.Run() == nil
}

func killProcessGroup(cmd *exec.Cmd) {
	if cmd == nil || cmd.Process == nil {
		return
	}
	pid := cmd.Process.Pid
	if pid > 0 {
		_ = syscall.Kill(-pid, syscall.SIGKILL)
	}
	_ = cmd.Process.Kill()
}
