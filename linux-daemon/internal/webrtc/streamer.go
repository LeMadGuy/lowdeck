package webrtc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/local/linux-deck-daemon/internal/capture"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
	"github.com/pion/ice/v4"
	"github.com/pion/interceptor"
	pionwebrtc "github.com/pion/webrtc/v4"
	pionmedia "github.com/pion/webrtc/v4/pkg/media"
)

const (
	defaultStreamID = "linuxdeck-desktop"
	videoTrackID    = "video"
	audioTrackID    = "audio"
)

// MediaSourceFactory constructs a capture.MediaSource for a requested PipelineConfig.
type MediaSourceFactory func(cfg capture.PipelineConfig) capture.MediaSource

// SignalSender transmits a WebRtcSignal back to the authenticated WebSocket client.
type SignalSender func(sig *pb.WebRtcSignal) error

// StreamConfig defines parameters for a single WebRTC Remote Desktop session.
type StreamConfig struct {
	Width        uint32
	Height       uint32
	FPS          uint32
	BitrateKbps  uint32
	AudioEnabled bool
	TestSource   bool
	ConfigDir    string
	Logger       *slog.Logger
}

// NewLocalWebRTCAPI creates a Pion WebRTC API configured for ultra-low-latency operation
// across LAN, USB tethering/adb reverse (loopback), and Tailscale interfaces without
// requiring external internet or public STUN servers.
func NewLocalWebRTCAPI() (*pionwebrtc.API, error) {
	mediaEngine := &pionwebrtc.MediaEngine{}
	if err := mediaEngine.RegisterDefaultCodecs(); err != nil {
		return nil, fmt.Errorf("webrtc register codecs: %w", err)
	}

	interceptorRegistry := &interceptor.Registry{}
	if err := pionwebrtc.RegisterDefaultInterceptors(mediaEngine, interceptorRegistry); err != nil {
		return nil, fmt.Errorf("webrtc register interceptors: %w", err)
	}

	settingEngine := pionwebrtc.SettingEngine{}
	// Allow loopback host candidates (127.0.0.1 / ::1) for USB adb-reverse and in-memory tests.
	settingEngine.SetIncludeLoopbackCandidate(true)
	// Emit direct IP host candidates (LAN, USB, Tailscale) rather than .local mDNS hostnames.
	settingEngine.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	// Fast ICE failure detection so abandoned sessions release GPU/CPU capture promptly.
	settingEngine.SetICETimeouts(4*time.Second, 8*time.Second, 1*time.Second)

	return pionwebrtc.NewAPI(
		pionwebrtc.WithMediaEngine(mediaEngine),
		pionwebrtc.WithInterceptorRegistry(interceptorRegistry),
		pionwebrtc.WithSettingEngine(settingEngine),
	), nil
}

// StreamSession manages an active PeerConnection, local H.264/Opus tracks, and capture pipeline.
type StreamSession struct {
	pc         *pionwebrtc.PeerConnection
	videoTrack *pionwebrtc.TrackLocalStaticSample
	audioTrack *pionwebrtc.TrackLocalStaticSample
	mediaSrc   capture.MediaSource
	sendSignal SignalSender
	logger     *slog.Logger
	onClose    func()

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu                 sync.Mutex
	offerSent          bool
	outboundPendingICE []*pb.WebRtcSignal
	remoteDescSet      bool
	inboundPendingICE  []pionwebrtc.ICECandidateInit
	remoteReady        atomic.Bool
	closeOnce          sync.Once
	closed             atomic.Bool
}

// NewStreamSession initializes a WebRTC PeerConnection with H.264 + Opus tracks, starts the
// capture.MediaSource, and begins pumping samples. Call BeginOffer() to generate and send SDP_OFFER.
func NewStreamSession(
	parentCtx context.Context,
	cfg StreamConfig,
	sourceFactory MediaSourceFactory,
	sendSignal SignalSender,
	onClose func(),
) (*StreamSession, error) {
	if sendSignal == nil {
		return nil, errors.New("webrtc start stream: sendSignal callback is required")
	}
	if sourceFactory == nil {
		sourceFactory = func(pcfg capture.PipelineConfig) capture.MediaSource {
			return capture.NewPipeline(pcfg)
		}
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}
	if os.Getenv(capture.EnvTestSource) == "1" {
		cfg.TestSource = true
	}

	api, err := NewLocalWebRTCAPI()
	if err != nil {
		return nil, err
	}

	// Empty ICEServers slice ensures local host candidates work offline over LAN/USB/Tailscale.
	pc, err := api.NewPeerConnection(pionwebrtc.Configuration{
		ICEServers: []pionwebrtc.ICEServer{},
	})
	if err != nil {
		return nil, fmt.Errorf("webrtc create peer connection: %w", err)
	}

	videoTrack, err := pionwebrtc.NewTrackLocalStaticSample(
		pionwebrtc.RTPCodecCapability{MimeType: pionwebrtc.MimeTypeH264},
		videoTrackID,
		defaultStreamID,
	)
	if err != nil {
		_ = pc.Close()
		return nil, fmt.Errorf("webrtc create h264 video track: %w", err)
	}

	videoSender, err := pc.AddTrack(videoTrack)
	if err != nil {
		_ = pc.Close()
		return nil, fmt.Errorf("webrtc add video track: %w", err)
	}

	audioTrack, err := pionwebrtc.NewTrackLocalStaticSample(
		pionwebrtc.RTPCodecCapability{MimeType: pionwebrtc.MimeTypeOpus},
		audioTrackID,
		defaultStreamID,
	)
	if err != nil {
		_ = pc.Close()
		return nil, fmt.Errorf("webrtc create opus audio track: %w", err)
	}

	audioSender, err := pc.AddTrack(audioTrack)
	if err != nil {
		_ = pc.Close()
		return nil, fmt.Errorf("webrtc add audio track: %w", err)
	}

	mediaSrc := sourceFactory(capture.PipelineConfig{
		Width:       cfg.Width,
		Height:      cfg.Height,
		FPS:         cfg.FPS,
		BitrateKbps: cfg.BitrateKbps,
		EnableAudio: cfg.AudioEnabled,
		TestSource:  cfg.TestSource,
		ConfigDir:   cfg.ConfigDir,
		Logger:      logger,
	})

	streamCtx, cancel := context.WithCancel(parentCtx)
	sess := &StreamSession{
		pc:         pc,
		videoTrack: videoTrack,
		audioTrack: audioTrack,
		mediaSrc:   mediaSrc,
		sendSignal: sendSignal,
		logger:     logger,
		onClose:    onClose,
		ctx:        streamCtx,
		cancel:     cancel,
	}

	// Drain incoming RTCP packets (Receiver Reports, PLI, NACK) for both senders.
	sess.startRTCPDrain(videoSender)
	sess.startRTCPDrain(audioSender)

	pc.OnICECandidate(func(c *pionwebrtc.ICECandidate) {
		if c == nil || sess.closed.Load() {
			return
		}
		raw, mErr := json.Marshal(c.ToJSON())
		if mErr != nil {
			return
		}
		sig := &pb.WebRtcSignal{
			Type:        pb.SignalType_ICE_CANDIDATE,
			PayloadJson: string(raw),
		}

		sess.mu.Lock()
		if !sess.offerSent {
			sess.outboundPendingICE = append(sess.outboundPendingICE, sig)
			sess.mu.Unlock()
			return
		}
		sess.mu.Unlock()

		_ = sess.sendSignal(sig)
	})

	pc.OnConnectionStateChange(func(state pionwebrtc.PeerConnectionState) {
		logger.Info("webrtc peer connection state changed", "state", state.String())
		switch state {
		case pionwebrtc.PeerConnectionStateDisconnected,
			pionwebrtc.PeerConnectionStateFailed,
			pionwebrtc.PeerConnectionStateClosed:
			go func() {
				_ = sess.Close()
			}()
		}
	})

	if err := mediaSrc.Start(streamCtx); err != nil {
		cancel()
		_ = pc.Close()
		return nil, fmt.Errorf("webrtc start media source: %w", err)
	}

	sess.wg.Add(2)
	go sess.pumpVideo()
	go sess.pumpAudio()

	// Ensure cancellation of parentCtx tears down the PeerConnection and capture pipeline.
	go func() {
		<-streamCtx.Done()
		_ = sess.Close()
	}()

	return sess, nil
}

// BeginOffer generates the SDP Offer, sets the local description, sends SDP_OFFER to the client,
// and flushes any trickle ICE candidates gathered during SetLocalDescription.
func (s *StreamSession) BeginOffer() error {
	offer, err := s.pc.CreateOffer(nil)
	if err != nil {
		_ = s.Close()
		return fmt.Errorf("webrtc create sdp offer: %w", err)
	}

	if err := s.pc.SetLocalDescription(offer); err != nil {
		_ = s.Close()
		return fmt.Errorf("webrtc set local description: %w", err)
	}

	offerBytes, err := json.Marshal(offer)
	if err != nil {
		_ = s.Close()
		return fmt.Errorf("webrtc marshal sdp offer: %w", err)
	}

	if err := s.sendSignal(&pb.WebRtcSignal{
		Type:        pb.SignalType_SDP_OFFER,
		PayloadJson: string(offerBytes),
	}); err != nil {
		_ = s.Close()
		return fmt.Errorf("webrtc send sdp offer: %w", err)
	}

	s.mu.Lock()
	s.offerSent = true
	pendingOut := s.outboundPendingICE
	s.outboundPendingICE = nil
	s.mu.Unlock()

	for _, iceSig := range pendingOut {
		_ = s.sendSignal(iceSig)
	}
	return nil
}

// StartStream creates a StreamSession and immediately initiates SDP_OFFER negotiation.
func StartStream(
	parentCtx context.Context,
	cfg StreamConfig,
	sourceFactory MediaSourceFactory,
	sendSignal SignalSender,
	onClose func(),
) (*StreamSession, error) {
	sess, err := NewStreamSession(parentCtx, cfg, sourceFactory, sendSignal, onClose)
	if err != nil {
		return nil, err
	}
	if err := sess.BeginOffer(); err != nil {
		return nil, err
	}
	return sess, nil
}

// HandleSDPAnswer applies the client's SDP_ANSWER and flushes any queued remote ICE candidates.
func (s *StreamSession) HandleSDPAnswer(answer pionwebrtc.SessionDescription) error {
	if s.closed.Load() {
		return errors.New("webrtc session is closed")
	}
	answer.Type = pionwebrtc.SDPTypeAnswer

	if err := s.pc.SetRemoteDescription(answer); err != nil {
		return fmt.Errorf("webrtc set remote description: %w", err)
	}

	s.remoteReady.Store(true)

	s.mu.Lock()
	s.remoteDescSet = true
	pendingIn := s.inboundPendingICE
	s.inboundPendingICE = nil
	s.mu.Unlock()

	for _, cand := range pendingIn {
		if err := s.pc.AddICECandidate(cand); err != nil {
			s.logger.Warn("failed to add queued remote ICE candidate", "err", err)
		}
	}
	return nil
}

// HandleICECandidate adds a trickle ICE candidate from the remote peer, queuing it if
// SetRemoteDescription has not yet completed.
func (s *StreamSession) HandleICECandidate(cand pionwebrtc.ICECandidateInit) error {
	if s.closed.Load() {
		return nil
	}
	if cand.Candidate == "" {
		// End-of-candidates signal.
		return nil
	}

	s.mu.Lock()
	if !s.remoteDescSet {
		s.inboundPendingICE = append(s.inboundPendingICE, cand)
		s.mu.Unlock()
		return nil
	}
	s.mu.Unlock()

	if err := s.pc.AddICECandidate(cand); err != nil {
		return fmt.Errorf("webrtc add ice candidate: %w", err)
	}
	return nil
}

// Close immediately stops the capture pipeline, cancels sample pump goroutines, and closes the PeerConnection.
func (s *StreamSession) Close() error {
	s.closeOnce.Do(func() {
		s.closed.Store(true)
		if s.cancel != nil {
			s.cancel()
		}
		if s.mediaSrc != nil {
			_ = s.mediaSrc.Close()
		}
		if s.pc != nil {
			_ = s.pc.Close()
		}
		s.wg.Wait()
		if s.onClose != nil {
			s.onClose()
		}
	})
	return nil
}

func (s *StreamSession) startRTCPDrain(sender *pionwebrtc.RTPSender) {
	if sender == nil {
		return
	}
	go func() {
		buf := make([]byte, 1500)
		for {
			if _, _, err := sender.Read(buf); err != nil {
				return
			}
		}
	}()
}

func (s *StreamSession) pumpVideo() {
	defer s.wg.Done()

	var (
		auBuf       []byte
		lastSPS     []byte
		lastPPS     []byte
		sentSPSPPS  bool
		auHasSPSPPS bool
	)

	videoCh := s.mediaSrc.VideoSamples()
	for {
		select {
		case <-s.ctx.Done():
			return
		case sample, ok := <-videoCh:
			if !ok {
				return
			}
			if len(sample.Data) == 0 {
				continue
			}

			nalType := extractAnnexBNALType(sample.Data)
			switch nalType {
			case 7: // SPS
				lastSPS = append(lastSPS[:0], sample.Data...)
				auHasSPSPPS = true
			case 8: // PPS
				lastPPS = append(lastPPS[:0], sample.Data...)
				auHasSPSPPS = true
			}

			if !s.remoteReady.Load() {
				// Before SDP_ANSWER completes, keep draining videoCh and caching SPS/PPS
				// without building up stale buffered frames.
				continue
			}

			auBuf = append(auBuf, sample.Data...)

			// Non-VCL NALs (SPS/PPS/SEI) carry Duration == 0; flush the complete Access Unit
			// once the VCL slice (Duration > 0) arrives so the RTP Marker bit marks exact frame boundaries.
			if sample.Duration == 0 {
				continue
			}

			payload := auBuf
			if !sentSPSPPS {
				if !auHasSPSPPS && len(lastSPS) > 0 && len(lastPPS) > 0 {
					combined := make([]byte, 0, len(lastSPS)+len(lastPPS)+len(auBuf))
					combined = append(combined, lastSPS...)
					combined = append(combined, lastPPS...)
					combined = append(combined, auBuf...)
					payload = combined
				}
				if auHasSPSPPS || (len(lastSPS) > 0 && len(lastPPS) > 0) {
					sentSPSPPS = true
				}
			}

			_ = s.videoTrack.WriteSample(pionmedia.Sample{
				Data:      payload,
				Duration:  sample.Duration,
				Timestamp: sample.Timestamp,
			})

			auBuf = auBuf[:0]
			auHasSPSPPS = false
		}
	}
}

func (s *StreamSession) pumpAudio() {
	defer s.wg.Done()

	audioCh := s.mediaSrc.AudioSamples()
	for {
		select {
		case <-s.ctx.Done():
			return
		case sample, ok := <-audioCh:
			if !ok {
				return
			}
			if len(sample.Data) == 0 || !s.remoteReady.Load() {
				continue
			}
			dur := sample.Duration
			if dur <= 0 {
				dur = 20 * time.Millisecond
			}
			_ = s.audioTrack.WriteSample(pionmedia.Sample{
				Data:      sample.Data,
				Duration:  dur,
				Timestamp: sample.Timestamp,
			})
		}
	}
}

func extractAnnexBNALType(data []byte) byte {
	switch {
	case len(data) > 4 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x00 && data[3] == 0x01:
		return data[4] & 0x1F
	case len(data) > 3 && data[0] == 0x00 && data[1] == 0x00 && data[2] == 0x01:
		return data[3] & 0x1F
	case len(data) > 0:
		return data[0] & 0x1F
	default:
		return 0
	}
}
