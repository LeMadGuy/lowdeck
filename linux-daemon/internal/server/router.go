package server

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"sync"
	"time"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

// ClientSession represents an authenticated mTLS WebSocket client session.
type ClientSession interface {
	ID() string
	DeviceID() string
	DeviceName() string
	CertFingerprint() string
	Context() context.Context
	Send(env *pb.Envelope) error
	SendImmediate(env *pb.Envelope) error
	Close() error
}

// HandlerFunc processes an incoming Protobuf Envelope from an authenticated client session.
type HandlerFunc func(ctx context.Context, client ClientSession, env *pb.Envelope) error

// PayloadKind identifies the active oneof payload inside an Envelope.
type PayloadKind string

const (
	KindUnknown           PayloadKind = "unknown"
	KindHandshakeRequest  PayloadKind = "handshake_request"
	KindHandshakeResponse PayloadKind = "handshake_response"
	KindPing              PayloadKind = "ping"
	KindPong              PayloadKind = "pong"
	KindErrorResponse     PayloadKind = "error_response"
	KindMouseMove         PayloadKind = "mouse_move_event"
	KindMouseButton       PayloadKind = "mouse_button_event"
	KindMouseScroll       PayloadKind = "mouse_scroll_event"
	KindKey               PayloadKind = "key_event"
	KindKeyCombo          PayloadKind = "key_combo_event"
	KindMacroExec         PayloadKind = "macro_exec_request"
	KindMacroOutput       PayloadKind = "macro_output_event"
	KindMacroStateSync    PayloadKind = "macro_state_sync"
	KindDeckProfileSync   PayloadKind = "deck_profile_sync"
	KindPtyOpen           PayloadKind = "pty_open_request"
	KindPtyData           PayloadKind = "pty_data_event"
	KindPtyResize         PayloadKind = "pty_resize_event"
	KindPtyClose          PayloadKind = "pty_close_event"
	KindTelemetrySnapshot PayloadKind = "telemetry_snapshot"
	KindMediaControl      PayloadKind = "media_control_command"
	KindMediaStatus       PayloadKind = "media_status_event"
	KindPowerControl      PayloadKind = "power_control_command"
	KindWebRtcSignal      PayloadKind = "webrtc_signal"
)

// EnvelopeKind inspects env.Payload and returns its PayloadKind.
func EnvelopeKind(env *pb.Envelope) PayloadKind {
	if env == nil || env.Payload == nil {
		return KindUnknown
	}
	switch env.Payload.(type) {
	case *pb.Envelope_HandshakeRequest:
		return KindHandshakeRequest
	case *pb.Envelope_HandshakeResponse:
		return KindHandshakeResponse
	case *pb.Envelope_Ping:
		return KindPing
	case *pb.Envelope_Pong:
		return KindPong
	case *pb.Envelope_ErrorResponse:
		return KindErrorResponse
	case *pb.Envelope_MouseMoveEvent:
		return KindMouseMove
	case *pb.Envelope_MouseButtonEvent:
		return KindMouseButton
	case *pb.Envelope_MouseScrollEvent:
		return KindMouseScroll
	case *pb.Envelope_KeyEvent:
		return KindKey
	case *pb.Envelope_KeyComboEvent:
		return KindKeyCombo
	case *pb.Envelope_MacroExecRequest:
		return KindMacroExec
	case *pb.Envelope_MacroOutputEvent:
		return KindMacroOutput
	case *pb.Envelope_MacroStateSync:
		return KindMacroStateSync
	case *pb.Envelope_DeckProfileSync:
		return KindDeckProfileSync
	case *pb.Envelope_PtyOpenRequest:
		return KindPtyOpen
	case *pb.Envelope_PtyDataEvent:
		return KindPtyData
	case *pb.Envelope_PtyResizeEvent:
		return KindPtyResize
	case *pb.Envelope_PtyCloseEvent:
		return KindPtyClose
	case *pb.Envelope_TelemetrySnapshot:
		return KindTelemetrySnapshot
	case *pb.Envelope_MediaControlCommand:
		return KindMediaControl
	case *pb.Envelope_MediaStatusEvent:
		return KindMediaStatus
	case *pb.Envelope_PowerControlCommand:
		return KindPowerControl
	case *pb.Envelope_WebrtcSignal:
		return KindWebRtcSignal
	default:
		return KindUnknown
	}
}

// Router dispatches incoming Protobuf Envelopes to modular handlers by PayloadKind.
type Router struct {
	mu       sync.RWMutex
	handlers map[PayloadKind]HandlerFunc
	logger   *slog.Logger
}

// NewRouter creates a Router pre-configured with built-in Ping/Pong and Handshake handlers.
func NewRouter(logger *slog.Logger) *Router {
	if logger == nil {
		logger = slog.Default()
	}
	r := &Router{
		handlers: make(map[PayloadKind]HandlerFunc),
		logger:   logger,
	}
	r.registerDefaults()
	return r
}

// Register binds a HandlerFunc to a specific PayloadKind.
func (r *Router) Register(kind PayloadKind, handler HandlerFunc) {
	if handler == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.handlers[kind] = handler
}

// Dispatch routes an incoming Envelope to its registered HandlerFunc.
func (r *Router) Dispatch(ctx context.Context, client ClientSession, env *pb.Envelope) error {
	if env == nil {
		return errors.New("router dispatch: nil envelope")
	}

	kind := EnvelopeKind(env)
	r.mu.RLock()
	handler, ok := r.handlers[kind]
	r.mu.RUnlock()

	if !ok {
		err := fmt.Errorf("router dispatch: no handler registered for payload kind %q", kind)
		_ = client.Send(&pb.Envelope{
			RequestId:   env.RequestId,
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_ErrorResponse{
				ErrorResponse: &pb.ErrorResponse{
					Code:         404,
					Message:      err.Error(),
					RefRequestId: env.RequestId,
				},
			},
		})
		return err
	}

	if err := handler(ctx, client, env); err != nil {
		r.logger.Warn("handler returned error",
			"kind", kind,
			"client_id", client.ID(),
			"request_id", env.RequestId,
			"err", err,
		)
		_ = client.Send(&pb.Envelope{
			RequestId:   env.RequestId,
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_ErrorResponse{
				ErrorResponse: &pb.ErrorResponse{
					Code:         500,
					Message:      err.Error(),
					RefRequestId: env.RequestId,
				},
			},
		})
		return err
	}

	return nil
}

func (r *Router) registerDefaults() {
	r.Register(KindPing, func(_ context.Context, client ClientSession, env *pb.Envelope) error {
		ping := env.GetPing()
		if ping == nil {
			return nil
		}
		nowMs := time.Now().UnixMilli()
		return client.Send(&pb.Envelope{
			RequestId:   env.RequestId,
			TimestampMs: nowMs,
			Payload: &pb.Envelope_Pong{
				Pong: &pb.Pong{
					Seq:               ping.Seq,
					ClientTimestampMs: ping.ClientTimestampMs,
					ServerTimestampMs: nowMs,
				},
			},
		})
	})

	r.Register(KindPong, func(_ context.Context, _ ClientSession, _ *pb.Envelope) error {
		// Heartbeat pong acknowledged.
		return nil
	})

	r.Register(KindHandshakeRequest, func(_ context.Context, client ClientSession, env *pb.Envelope) error {
		req := env.GetHandshakeRequest()
		hostname, err := os.Hostname()
		if err != nil || hostname == "" {
			hostname = "linux-deck-host"
		}
		nowMs := time.Now().UnixMilli()
		status := pb.SessionStatus_SESSION_ACTIVE
		accepted := true
		msg := "session established"
		if req != nil && req.ProtocolVersion != 0 && req.ProtocolVersion != 1 {
			status = pb.SessionStatus_SESSION_VERSION_MISMATCH
			accepted = false
			msg = fmt.Sprintf("unsupported protocol_version %d", req.ProtocolVersion)
		}
		return client.Send(&pb.Envelope{
			RequestId:   env.RequestId,
			TimestampMs: nowMs,
			Payload: &pb.Envelope_HandshakeResponse{
				HandshakeResponse: &pb.HandshakeResponse{
					ProtocolVersion: 1,
					DeviceName:      hostname,
					SessionId:       client.ID(),
					Status:          status,
					Accepted:        accepted,
					Message:         msg,
				},
			},
		})
	})
}
