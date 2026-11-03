package server

import (
	"errors"
	"fmt"
	"time"

	"github.com/gorilla/websocket"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
	"google.golang.org/protobuf/proto"
)

// Priority represents the transmission priority of an outbound Protobuf Envelope.
type Priority int

const (
	// PriorityHigh is reserved for latency-critical control, user input, handshake, ping/pong, and WebRTC signals.
	PriorityHigh Priority = 0

	// PriorityBulk is assigned to high-throughput payloads (PTY terminal output, macro logs, periodic telemetry snapshots).
	PriorityBulk Priority = 1
)

const (
	// DefaultControlBufferSize is the capacity of the high-priority outbound channel.
	DefaultControlBufferSize = 256
	// DefaultBulkBufferSize is the capacity of the heavy data outbound channel.
	DefaultBulkBufferSize = 512
)

// ClassifyEnvelope inspects env.Payload and categorizes it into PriorityHigh or PriorityBulk.
func ClassifyEnvelope(env *pb.Envelope) Priority {
	if env == nil || env.Payload == nil {
		return PriorityHigh
	}

	switch env.Payload.(type) {
	case *pb.Envelope_PtyDataEvent,
		*pb.Envelope_MacroOutputEvent,
		*pb.Envelope_TelemetrySnapshot,
		*pb.Envelope_DeckProfileSync:
		return PriorityBulk
	default:
		// Control, Handshake, Ping, Pong, Mouse, Key, Media, Power, WebRTC Signal, Errors
		return PriorityHigh
	}
}

// IsHighPriority returns true if the envelope requires strict priority and bypasses bulk data queues.
func IsHighPriority(env *pb.Envelope) bool {
	return ClassifyEnvelope(env) == PriorityHigh
}

// PriorityMultiplexer coordinates dual outbound queues with strict priority scheduling.
type PriorityMultiplexer struct {
	ctrlCh chan *pb.Envelope
	bulkCh chan *pb.Envelope
}

// NewPriorityMultiplexer creates a new dual-queue priority multiplexer.
func NewPriorityMultiplexer(ctrlCap, bulkCap int) *PriorityMultiplexer {
	if ctrlCap <= 0 {
		ctrlCap = DefaultControlBufferSize
	}
	if bulkCap <= 0 {
		bulkCap = DefaultBulkBufferSize
	}
	return &PriorityMultiplexer{
		ctrlCh: make(chan *pb.Envelope, ctrlCap),
		bulkCh: make(chan *pb.Envelope, bulkCap),
	}
}

// ControlQueue returns the read channel for high-priority control envelopes.
func (m *PriorityMultiplexer) ControlQueue() <-chan *pb.Envelope {
	return m.ctrlCh
}

// BulkQueue returns the read channel for heavy data envelopes.
func (m *PriorityMultiplexer) BulkQueue() <-chan *pb.Envelope {
	return m.bulkCh
}

// Enqueue routes env into either the high-priority or bulk queue based on payload type.
func (m *PriorityMultiplexer) Enqueue(env *pb.Envelope) error {
	if env == nil {
		return nil
	}

	if IsHighPriority(env) {
		select {
		case m.ctrlCh <- env:
			return nil
		default:
			return ErrSendQueueFull
		}
	}

	select {
	case m.bulkCh <- env:
		return nil
	default:
		return ErrSendQueueFull
	}
}

// SendImmediateEnvelope writes an envelope directly to the websocket connection with lock protection,
// completely bypassing all outbound queues for zero-latency responses (such as network-layer Pings).
func SendImmediateEnvelope(conn *websocket.Conn, env *pb.Envelope, timeout time.Duration) error {
	if conn == nil {
		return errors.New("cannot send on nil connection")
	}
	if env == nil {
		return nil
	}

	raw, err := proto.Marshal(env)
	if err != nil {
		return fmt.Errorf("marshal envelope: %w", err)
	}

	if timeout > 0 {
		_ = conn.SetWriteDeadline(time.Now().Add(timeout))
	}
	return conn.WriteMessage(websocket.BinaryMessage, raw)
}

