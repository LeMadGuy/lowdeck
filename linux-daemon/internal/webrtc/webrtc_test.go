package webrtc

import (
	"context"
	"encoding/json"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/local/linux-deck-daemon/internal/capture"
	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
	pionwebrtc "github.com/pion/webrtc/v4"
)

type memoryClientSession struct {
	id     string
	ctx    context.Context
	cancel context.CancelFunc
	outCh  chan *pb.Envelope
	closed atomic.Bool
}

func newMemoryClientSession(parent context.Context, id string) *memoryClientSession {
	ctx, cancel := context.WithCancel(parent)
	return &memoryClientSession{
		id:     id,
		ctx:    ctx,
		cancel: cancel,
		outCh:  make(chan *pb.Envelope, 256),
	}
}

func (m *memoryClientSession) ID() string                { return m.id }
func (m *memoryClientSession) DeviceID() string          { return "test-android-01" }
func (m *memoryClientSession) DeviceName() string        { return "Test Android Client" }
func (m *memoryClientSession) CertFingerprint() string   { return "deadbeef" }
func (m *memoryClientSession) Context() context.Context  { return m.ctx }
func (m *memoryClientSession) Send(env *pb.Envelope) error {
	if m.closed.Load() {
		return server.ErrSessionClosed
	}
	select {
	case <-m.ctx.Done():
		return server.ErrSessionClosed
	case m.outCh <- env:
		return nil
	}
}
func (m *memoryClientSession) Close() error {
	m.closed.Store(true)
	m.cancel()
	return nil
}

func TestDaemonToClientWebRTCH264StreamAndAutoTeardown(t *testing.T) {
	t.Setenv(capture.EnvTestSource, "1")

	baselineGoroutines := runtime.NumGoroutine()

	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()

	var sourceClosed atomic.Bool
	wrappedFactory := func(cfg capture.PipelineConfig) capture.MediaSource {
		cfg.TestSource = true
		realSrc := capture.NewPipeline(cfg)
		return &closeTrackingMediaSource{
			MediaSource: realSrc,
			closed:      &sourceClosed,
		}
	}

	router := server.NewRouter(nil)
	sigHandler := NewSignalingHandler(t.TempDir(), wrappedFactory, nil)
	sigHandler.Register(router)
	defer sigHandler.CloseAll()

	clientSess := newMemoryClientSession(ctx, "mem-sess-1")
	defer clientSess.Close()

	// Create in-memory Client PeerConnection (offline local host ICE, no STUN).
	clientAPI, err := NewLocalWebRTCAPI()
	if err != nil {
		t.Fatalf("NewLocalWebRTCAPI for client failed: %v", err)
	}
	clientPC, err := clientAPI.NewPeerConnection(pionwebrtc.Configuration{
		ICEServers: []pionwebrtc.ICEServer{},
	})
	if err != nil {
		t.Fatalf("client NewPeerConnection failed: %v", err)
	}
	defer func() { _ = clientPC.Close() }()

	var (
		h264Frames   atomic.Int32
		h264Packets  atomic.Int32
		opusPackets  atomic.Int32
		framesDoneCh = make(chan struct{})
		doneOnce     sync.Once
	)

	clientPC.OnTrack(func(track *pionwebrtc.TrackRemote, _ *pionwebrtc.RTPReceiver) {
		mime := strings.ToLower(track.Codec().MimeType)
		for {
			pkt, _, rErr := track.ReadRTP()
			if rErr != nil {
				return
			}
			if strings.Contains(mime, "h264") {
				if len(pkt.Payload) > 0 {
					h264Packets.Add(1)
					if pkt.Header.Marker {
						if h264Frames.Add(1) >= 10 {
							doneOnce.Do(func() { close(framesDoneCh) })
						}
					}
				}
			} else if strings.Contains(mime, "opus") {
				if len(pkt.Payload) > 0 {
					opusPackets.Add(1)
				}
			}
		}
	})

	var (
		clientDescSetMu  sync.Mutex
		clientDescSet    bool
		pendingDaemonICE []pionwebrtc.ICECandidateInit
	)

	// Forward client local ICE candidates to daemon SignalingHandler.
	clientPC.OnICECandidate(func(c *pionwebrtc.ICECandidate) {
		if c == nil {
			return
		}
		raw, mErr := json.Marshal(c.ToJSON())
		if mErr != nil {
			return
		}
		_ = router.Dispatch(ctx, clientSess, &pb.Envelope{
			RequestId:   "ice-from-client",
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_WebrtcSignal{
				WebrtcSignal: &pb.WebRtcSignal{
					Type:        pb.SignalType_ICE_CANDIDATE,
					PayloadJson: string(raw),
				},
			},
		})
	})

	// Process signaling messages emitted by daemon (SDP_OFFER + ICE_CANDIDATE).
	sigErrCh := make(chan error, 4)
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case env := <-clientSess.outCh:
				sig := env.GetWebrtcSignal()
				if sig == nil {
					continue
				}
				switch sig.Type {
				case pb.SignalType_SDP_OFFER:
					offer, pErr := ParseSessionDescriptionJSON(sig.PayloadJson, pionwebrtc.SDPTypeOffer)
					if pErr != nil {
						sigErrCh <- pErr
						return
					}
					if sErr := clientPC.SetRemoteDescription(offer); sErr != nil {
						sigErrCh <- sErr
						return
					}
					answer, aErr := clientPC.CreateAnswer(nil)
					if aErr != nil {
						sigErrCh <- aErr
						return
					}
					if lErr := clientPC.SetLocalDescription(answer); lErr != nil {
						sigErrCh <- lErr
						return
					}

					clientDescSetMu.Lock()
					clientDescSet = true
					queued := pendingDaemonICE
					pendingDaemonICE = nil
					clientDescSetMu.Unlock()

					for _, ic := range queued {
						_ = clientPC.AddICECandidate(ic)
					}

					ansBytes, _ := json.Marshal(answer)
					if dErr := router.Dispatch(ctx, clientSess, &pb.Envelope{
						RequestId:   "sdp-answer",
						TimestampMs: time.Now().UnixMilli(),
						Payload: &pb.Envelope_WebrtcSignal{
							WebrtcSignal: &pb.WebRtcSignal{
								Type:        pb.SignalType_SDP_ANSWER,
								PayloadJson: string(ansBytes),
							},
						},
					}); dErr != nil {
						sigErrCh <- dErr
						return
					}

				case pb.SignalType_ICE_CANDIDATE:
					cand, pErr := ParseICECandidateJSON(sig.PayloadJson)
					if pErr != nil || cand.Candidate == "" {
						continue
					}
					clientDescSetMu.Lock()
					if !clientDescSet {
						pendingDaemonICE = append(pendingDaemonICE, cand)
						clientDescSetMu.Unlock()
						continue
					}
					clientDescSetMu.Unlock()
					_ = clientPC.AddICECandidate(cand)
				}
			}
		}
	}()

	// Trigger START_STREAM from client.
	if err := router.Dispatch(ctx, clientSess, &pb.Envelope{
		RequestId:   "start-stream-1",
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_WebrtcSignal{
			WebrtcSignal: &pb.WebRtcSignal{
				Type:         pb.SignalType_START_STREAM,
				Width:        640,
				Height:       360,
				Fps:          60,
				BitrateKbps:  2000,
				AudioEnabled: true,
			},
		},
	}); err != nil {
		t.Fatalf("START_STREAM dispatch failed: %v", err)
	}

	if sigHandler.ActiveStreamCount() != 1 {
		t.Fatalf("expected 1 active stream session, got %d", sigHandler.ActiveStreamCount())
	}

	select {
	case sErr := <-sigErrCh:
		t.Fatalf("signaling exchange failed: %v", sErr)
	case <-framesDoneCh:
		t.Logf("received %d H.264 frames (%d video RTP packets, %d Opus RTP packets) over WebRTC",
			h264Frames.Load(), h264Packets.Load(), opusPackets.Load())
	case <-ctx.Done():
		t.Fatalf("timed out waiting for 10 H.264 frames (got frames=%d, packets=%d)",
			h264Frames.Load(), h264Packets.Load())
	}

	if h264Frames.Load() < 10 {
		t.Fatalf("expected >= 10 H.264 frames received on client PeerConnection, got %d", h264Frames.Load())
	}
	if h264Packets.Load() < 10 {
		t.Fatalf("expected >= 10 H.264 RTP packets received, got %d", h264Packets.Load())
	}

	// Close client PeerConnection directly (simulating network drop / peer close) and verify
	// OnConnectionStateChange immediately shuts down the daemon capture MediaSource.
	if err := clientPC.Close(); err != nil {
		t.Fatalf("clientPC.Close failed: %v", err)
	}

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if sourceClosed.Load() && sigHandler.ActiveStreamCount() == 0 {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}

	if !sourceClosed.Load() {
		t.Fatalf("expected capture MediaSource.Close() to be triggered on PeerConnection state change")
	}
	if sigHandler.ActiveStreamCount() != 0 {
		t.Fatalf("expected ActiveStreamCount() == 0 after peer close, got %d", sigHandler.ActiveStreamCount())
	}

	// Allow Pion internal DTLS/ICE cleanup goroutines to exit.
	settleDeadline := time.Now().Add(1 * time.Second)
	for time.Now().Before(settleDeadline) {
		if runtime.NumGoroutine() <= baselineGoroutines+4 {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
}

func TestSignalJSONInteropAndStopStream(t *testing.T) {
	t.Setenv(capture.EnvTestSource, "1")

	desc, err := ParseSessionDescriptionJSON(`{"type":"ANSWER","sdp":"v=0\r\n"}`, pionwebrtc.SDPTypeAnswer)
	if err != nil {
		t.Fatalf("ParseSessionDescriptionJSON failed: %v", err)
	}
	if desc.Type != pionwebrtc.SDPTypeAnswer || desc.SDP != "v=0\r\n" {
		t.Fatalf("unexpected SessionDescription: %+v", desc)
	}

	cand, err := ParseICECandidateJSON(`{"sdp":"candidate:1 1 UDP 2130706431 192.168.1.10 50000 typ host","sdpMid":"0","sdpMLineIndex":0}`)
	if err != nil {
		t.Fatalf("ParseICECandidateJSON failed: %v", err)
	}
	if !strings.HasPrefix(cand.Candidate, "candidate:1") {
		t.Fatalf("expected Android 'sdp' field to map to Candidate, got %q", cand.Candidate)
	}

	emptyCand, err := ParseICECandidateJSON(`null`)
	if err != nil || emptyCand.Candidate != "" {
		t.Fatalf("expected null ICE candidate to parse as empty end-of-candidates, got %+v, err=%v", emptyCand, err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	router := server.NewRouter(nil)
	sigHandler := NewSignalingHandler(t.TempDir(), nil, nil)
	sigHandler.Register(router)
	defer sigHandler.CloseAll()

	clientSess := newMemoryClientSession(ctx, "mem-sess-stop")
	defer clientSess.Close()

	if err := router.Dispatch(ctx, clientSess, &pb.Envelope{
		RequestId:   "start-stop",
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_WebrtcSignal{
			WebrtcSignal: &pb.WebRtcSignal{
				Type:  pb.SignalType_START_STREAM,
				Width: 320, Height: 240, Fps: 30, BitrateKbps: 1000,
			},
		},
	}); err != nil {
		t.Fatalf("START_STREAM failed: %v", err)
	}

	if err := router.Dispatch(ctx, clientSess, &pb.Envelope{
		RequestId:   "stop-now",
		TimestampMs: time.Now().UnixMilli(),
		Payload: &pb.Envelope_WebrtcSignal{
			WebrtcSignal: &pb.WebRtcSignal{
				Type: pb.SignalType_STOP_STREAM,
			},
		},
	}); err != nil {
		t.Fatalf("STOP_STREAM failed: %v", err)
	}

	if sigHandler.ActiveStreamCount() != 0 {
		t.Fatalf("expected ActiveStreamCount() == 0 after STOP_STREAM, got %d", sigHandler.ActiveStreamCount())
	}
}

type closeTrackingMediaSource struct {
	capture.MediaSource
	closed *atomic.Bool
}

func (c *closeTrackingMediaSource) Close() error {
	c.closed.Store(true)
	return c.MediaSource.Close()
}

