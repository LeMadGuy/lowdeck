package webrtc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/local/linux-deck-daemon/fixes"
	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
	pionwebrtc "github.com/pion/webrtc/v4"
)

// SignalingHandler coordinates WebRTC SDP Offer/Answer and trickle ICE exchange
// over authenticated mTLS WebSocket sessions.
type SignalingHandler struct {
	configDir     string
	sourceFactory MediaSourceFactory
	logger        *slog.Logger

	mu        sync.Mutex
	sessions  map[string]*StreamSession
	watchdogs map[string]*fixes.WebRTCHandshakeWatchdog
	fallbacks map[string]*fixes.ScreenJpegFallbackStreamer
}

// NewSignalingHandler creates a SignalingHandler for managing per-client WebRTC streams.
func NewSignalingHandler(configDir string, sourceFactory MediaSourceFactory, logger *slog.Logger) *SignalingHandler {
	if logger == nil {
		logger = slog.Default()
	}
	return &SignalingHandler{
		configDir:     configDir,
		sourceFactory: sourceFactory,
		logger:        logger,
		sessions:      make(map[string]*StreamSession),
		watchdogs:     make(map[string]*fixes.WebRTCHandshakeWatchdog),
		fallbacks:     make(map[string]*fixes.ScreenJpegFallbackStreamer),
	}
}

// Register binds KindWebRtcSignal on the server Router.
func (h *SignalingHandler) Register(router *server.Router) {
	if router == nil {
		return
	}
	router.Register(server.KindWebRtcSignal, h.HandleSignal)
}

// HandleSignal processes START_STREAM, STOP_STREAM, SDP_ANSWER, and ICE_CANDIDATE messages.
func (h *SignalingHandler) HandleSignal(ctx context.Context, client server.ClientSession, env *pb.Envelope) error {
	if client == nil || env == nil {
		return errors.New("webrtc signaling: nil client or envelope")
	}
	sig := env.GetWebrtcSignal()
	if sig == nil {
		return errors.New("webrtc signaling: missing webrtc_signal payload")
	}

	clientID := client.ID()

	switch sig.Type {
	case pb.SignalType_START_STREAM:
		// Tear down any previous stream belonging to this client session first.
		h.CloseClientStream(clientID)

		reqID := env.RequestId
		sendFn := func(outSig *pb.WebRtcSignal) error {
			return client.Send(&pb.Envelope{
				RequestId:   reqID,
				TimestampMs: time.Now().UnixMilli(),
				Payload: &pb.Envelope_WebrtcSignal{
					WebrtcSignal: outSig,
				},
			})
		}

		var created *StreamSession
		var createdMu sync.Mutex

		onClose := func() {
			createdMu.Lock()
			target := created
			createdMu.Unlock()

			h.mu.Lock()
			if target != nil && h.sessions[clientID] == target {
				delete(h.sessions, clientID)
				if wd, ok := h.watchdogs[clientID]; ok {
					delete(h.watchdogs, clientID)
					wd.Stop()
				}
				if fb, ok := h.fallbacks[clientID]; ok {
					delete(h.fallbacks, clientID)
					fb.Stop()
				}
			}
			h.mu.Unlock()
		}

		sessCtx := client.Context()
		if sessCtx == nil {
			sessCtx = ctx
		}

		sess, err := NewStreamSession(sessCtx, StreamConfig{
			Width:        sig.Width,
			Height:       sig.Height,
			FPS:          sig.Fps,
			BitrateKbps:  sig.BitrateKbps,
			AudioEnabled: sig.AudioEnabled,
			ConfigDir:    h.configDir,
			Logger:       h.logger,
		}, h.sourceFactory, sendFn, onClose)
		if err != nil {
			return fmt.Errorf("webrtc start stream: %w", err)
		}

		fallback := fixes.NewScreenJpegFallbackStreamer(fixes.FallbackConfig{
			Width:       sig.Width,
			Height:      sig.Height,
			FPS:         12,
			JpegQuality: 65,
			Logger:      h.logger,
		}, client.Send)

		watchdog := fixes.NewWebRTCHandshakeWatchdog(
			fixes.DefaultWebRTCHandshakeTimeout,
			h.logger,
			func() {
				_ = fallback.Start(sessCtx)
			},
			func() {
				fallback.Stop()
			},
		)

		sess.SetICEConnectionStateListener(watchdog.NotifyICEState)
		watchdog.Start(sessCtx)

		createdMu.Lock()
		created = sess
		createdMu.Unlock()

		h.mu.Lock()
		h.sessions[clientID] = sess
		h.watchdogs[clientID] = watchdog
		h.fallbacks[clientID] = fallback
		h.mu.Unlock()

		if err := sess.BeginOffer(); err != nil {
			return err
		}
		return nil

	case pb.SignalType_STOP_STREAM:
		h.CloseClientStream(clientID)
		return nil

	case pb.SignalType_SDP_ANSWER:
		sess := h.getSession(clientID)
		if sess == nil {
			return errors.New("webrtc sdp_answer: no active stream session for client")
		}
		desc, err := ParseSessionDescriptionJSON(sig.PayloadJson, pionwebrtc.SDPTypeAnswer)
		if err != nil {
			return fmt.Errorf("webrtc parse sdp_answer: %w", err)
		}
		return sess.HandleSDPAnswer(desc)

	case pb.SignalType_ICE_CANDIDATE:
		sess := h.getSession(clientID)
		if sess == nil {
			// Ignore late trickle ICE candidates after stream teardown.
			return nil
		}
		cand, err := ParseICECandidateJSON(sig.PayloadJson)
		if err != nil {
			return fmt.Errorf("webrtc parse ice_candidate: %w", err)
		}
		return sess.HandleICECandidate(cand)

	default:
		return fmt.Errorf("webrtc signaling: unsupported signal type %s", sig.Type.String())
	}
}

// CloseClientStream terminates and removes any active WebRTC stream for clientID.
func (h *SignalingHandler) CloseClientStream(clientID string) {
	h.mu.Lock()
	sess, ok := h.sessions[clientID]
	if ok {
		delete(h.sessions, clientID)
	}
	wd, okWd := h.watchdogs[clientID]
	if okWd {
		delete(h.watchdogs, clientID)
	}
	fb, okFb := h.fallbacks[clientID]
	if okFb {
		delete(h.fallbacks, clientID)
	}
	h.mu.Unlock()

	if wd != nil {
		wd.Stop()
	}
	if fb != nil {
		fb.Stop()
	}
	if sess != nil {
		_ = sess.Close()
	}
}

// CloseAll terminates all active WebRTC streams across all connected clients.
func (h *SignalingHandler) CloseAll() {
	h.mu.Lock()
	list := make([]*StreamSession, 0, len(h.sessions))
	for id, sess := range h.sessions {
		list = append(list, sess)
		delete(h.sessions, id)
	}
	for id, wd := range h.watchdogs {
		wd.Stop()
		delete(h.watchdogs, id)
	}
	for id, fb := range h.fallbacks {
		fb.Stop()
		delete(h.fallbacks, id)
	}
	h.mu.Unlock()

	for _, sess := range list {
		_ = sess.Close()
	}
}

// ActiveStreamCount returns the number of currently active WebRTC streams.
func (h *SignalingHandler) ActiveStreamCount() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.sessions)
}

func (h *SignalingHandler) getSession(clientID string) *StreamSession {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.sessions[clientID]
}

type rawSDPJSON struct {
	Type string `json:"type"`
	SDP  string `json:"sdp"`
}

// ParseSessionDescriptionJSON decodes W3C / org.webrtc JSON SDP payloads (case-insensitive type).
func ParseSessionDescriptionJSON(payload string, defaultType pionwebrtc.SDPType) (pionwebrtc.SessionDescription, error) {
	trimmed := strings.TrimSpace(payload)
	if trimmed == "" {
		return pionwebrtc.SessionDescription{}, errors.New("empty sdp payload_json")
	}

	var parsed rawSDPJSON
	if err := json.Unmarshal([]byte(trimmed), &parsed); err != nil {
		return pionwebrtc.SessionDescription{}, err
	}
	if strings.TrimSpace(parsed.SDP) == "" {
		return pionwebrtc.SessionDescription{}, errors.New("missing sdp field in payload_json")
	}

	sdpType := defaultType
	switch strings.ToLower(strings.TrimSpace(parsed.Type)) {
	case "offer":
		sdpType = pionwebrtc.SDPTypeOffer
	case "answer":
		sdpType = pionwebrtc.SDPTypeAnswer
	case "pranswer":
		sdpType = pionwebrtc.SDPTypePranswer
	case "rollback":
		sdpType = pionwebrtc.SDPTypeRollback
	}

	return pionwebrtc.SessionDescription{
		Type: sdpType,
		SDP:  parsed.SDP,
	}, nil
}

type rawICECandidateJSON struct {
	Candidate        string  `json:"candidate"`
	SDP              string  `json:"sdp"`
	SDPMid           *string `json:"sdpMid"`
	SDPMLineIndex    *uint16 `json:"sdpMLineIndex"`
	UsernameFragment *string `json:"usernameFragment"`
}

// ParseICECandidateJSON decodes both W3C RTCIceCandidateInit ("candidate") and Android org.webrtc ("sdp") JSON formats.
func ParseICECandidateJSON(payload string) (pionwebrtc.ICECandidateInit, error) {
	trimmed := strings.TrimSpace(payload)
	if trimmed == "" || trimmed == "null" || trimmed == "{}" {
		return pionwebrtc.ICECandidateInit{}, nil
	}

	var raw rawICECandidateJSON
	if err := json.Unmarshal([]byte(trimmed), &raw); err != nil {
		return pionwebrtc.ICECandidateInit{}, err
	}

	candStr := strings.TrimSpace(raw.Candidate)
	if candStr == "" {
		candStr = strings.TrimSpace(raw.SDP)
	}

	return pionwebrtc.ICECandidateInit{
		Candidate:        candStr,
		SDPMid:           raw.SDPMid,
		SDPMLineIndex:    raw.SDPMLineIndex,
		UsernameFragment: raw.UsernameFragment,
	}, nil
}

