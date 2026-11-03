package server

import (
	"context"
	"testing"
	"time"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

func TestClassifyEnvelope(t *testing.T) {
	tests := []struct {
		name     string
		env      *pb.Envelope
		wantPrio Priority
	}{
		{
			name: "Ping is High Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_Ping{Ping: &pb.Ping{Seq: 1}},
			},
			wantPrio: PriorityHigh,
		},
		{
			name: "Pong is High Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_Pong{Pong: &pb.Pong{Seq: 1}},
			},
			wantPrio: PriorityHigh,
		},
		{
			name: "MouseMove is High Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_MouseMoveEvent{MouseMoveEvent: &pb.MouseMoveEvent{Dx: 5, Dy: -2}},
			},
			wantPrio: PriorityHigh,
		},
		{
			name: "Key is High Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_KeyEvent{KeyEvent: &pb.KeyEvent{KeyCode: 42, Action: pb.ButtonAction_PRESS}},
			},
			wantPrio: PriorityHigh,
		},
		{
			name: "WebRtcSignal is High Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_WebrtcSignal{WebrtcSignal: &pb.WebRtcSignal{Type: pb.SignalType_START_STREAM}},
			},
			wantPrio: PriorityHigh,
		},
		{
			name: "PtyData is Bulk Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_PtyDataEvent{PtyDataEvent: &pb.PtyDataEvent{SessionId: "s1", Data: []byte("bash output")}},
			},
			wantPrio: PriorityBulk,
		},
		{
			name: "TelemetrySnapshot is Bulk Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_TelemetrySnapshot{TelemetrySnapshot: &pb.TelemetrySnapshot{CpuPercent: 25.5}},
			},
			wantPrio: PriorityBulk,
		},
		{
			name: "DeckProfileSync is Bulk Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_DeckProfileSync{DeckProfileSync: &pb.DeckProfileSync{ProfileId: "prof1"}},
			},
			wantPrio: PriorityBulk,
		},
		{
			name: "MacroOutput is Bulk Priority",
			env: &pb.Envelope{
				Payload: &pb.Envelope_MacroOutputEvent{MacroOutputEvent: &pb.MacroOutputEvent{Stdout: []byte("log")}},
			},
			wantPrio: PriorityBulk,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := ClassifyEnvelope(tc.env)
			if got != tc.wantPrio {
				t.Errorf("ClassifyEnvelope() = %v, want %v", got, tc.wantPrio)
			}
		})
	}
}

func TestPriorityMultiplexer_StrictPriorityBypass(t *testing.T) {
	mux := NewPriorityMultiplexer(10, 10)

	bulkEnv1 := &pb.Envelope{
		RequestId: "bulk-1",
		Payload:   &pb.Envelope_PtyDataEvent{PtyDataEvent: &pb.PtyDataEvent{Data: []byte("chunk-1")}},
	}
	bulkEnv2 := &pb.Envelope{
		RequestId: "bulk-2",
		Payload:   &pb.Envelope_PtyDataEvent{PtyDataEvent: &pb.PtyDataEvent{Data: []byte("chunk-2")}},
	}
	ctrlEnv := &pb.Envelope{
		RequestId: "ctrl-urgent",
		Payload:   &pb.Envelope_MouseMoveEvent{MouseMoveEvent: &pb.MouseMoveEvent{Dx: 10}},
	}

	// Enqueue bulk data first
	if err := mux.Enqueue(bulkEnv1); err != nil {
		t.Fatalf("enqueue bulk1: %v", err)
	}
	if err := mux.Enqueue(bulkEnv2); err != nil {
		t.Fatalf("enqueue bulk2: %v", err)
	}

	// Enqueue high-priority control
	if err := mux.Enqueue(ctrlEnv); err != nil {
		t.Fatalf("enqueue ctrl: %v", err)
	}

	// Priority reader should observe ctrlEnv bypass bulk data
	select {
	case env := <-mux.ControlQueue():
		if env.RequestId != "ctrl-urgent" {
			t.Errorf("expected ctrl-urgent from ControlQueue, got %s", env.RequestId)
		}
	case <-time.After(50 * time.Millisecond):
		t.Fatal("timeout waiting for control queue")
	}

	// Bulk envelopes remain in their separate queue
	select {
	case env := <-mux.BulkQueue():
		if env.RequestId != "bulk-1" {
			t.Errorf("expected bulk-1 from BulkQueue, got %s", env.RequestId)
		}
	default:
		t.Fatal("expected bulk-1 in BulkQueue")
	}
}

func TestWsSession_DualQueues(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sess := &wsSession{
		id:     "test-sess",
		ctrlCh: make(chan *pb.Envelope, 5),
		bulkCh: make(chan *pb.Envelope, 5),
		ctx:    ctx,
		cancel: cancel,
	}

	ctrlEnv := &pb.Envelope{
		RequestId: "ctrl-ping",
		Payload:   &pb.Envelope_Ping{Ping: &pb.Ping{Seq: 42}},
	}
	bulkEnv := &pb.Envelope{
		RequestId: "bulk-pty",
		Payload:   &pb.Envelope_PtyDataEvent{PtyDataEvent: &pb.PtyDataEvent{Data: []byte("pty data")}},
	}

	if err := sess.Send(ctrlEnv); err != nil {
		t.Fatalf("send ctrlEnv failed: %v", err)
	}
	if err := sess.Send(bulkEnv); err != nil {
		t.Fatalf("send bulkEnv failed: %v", err)
	}

	if len(sess.ctrlCh) != 1 {
		t.Errorf("sess.ctrlCh len = %d, want 1", len(sess.ctrlCh))
	}
	if len(sess.bulkCh) != 1 {
		t.Errorf("sess.bulkCh len = %d, want 1", len(sess.bulkCh))
	}

	gotCtrl := <-sess.ctrlCh
	if gotCtrl.RequestId != "ctrl-ping" {
		t.Errorf("got %s in ctrlCh, want ctrl-ping", gotCtrl.RequestId)
	}

	gotBulk := <-sess.bulkCh
	if gotBulk.RequestId != "bulk-pty" {
		t.Errorf("got %s in bulkCh, want bulk-pty", gotBulk.RequestId)
	}
}
