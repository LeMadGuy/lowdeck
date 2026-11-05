package input

import (
	"context"
	"math"
	"reflect"
	"testing"

	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

func TestParseKeyComboSequences(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name      string
		combo     string
		wantSteps []KeyStep
		wantErr   bool
	}{
		{
			name:  "CTRL+SHIFT+ESC",
			combo: "CTRL+SHIFT+ESC",
			wantSteps: []KeyStep{
				{KeyCode: KeyLeftCtrl, Op: KeyPressVal},
				{KeyCode: KeyLeftShift, Op: KeyPressVal},
				{KeyCode: KeyEsc, Op: KeyPressVal},
				{KeyCode: KeyEsc, Op: KeyReleaseVal},
				{KeyCode: KeyLeftShift, Op: KeyReleaseVal},
				{KeyCode: KeyLeftCtrl, Op: KeyReleaseVal},
			},
		},
		{
			name:  "SUPER+1",
			combo: "SUPER+1",
			wantSteps: []KeyStep{
				{KeyCode: KeyLeftMeta, Op: KeyPressVal},
				{KeyCode: Key1, Op: KeyPressVal},
				{KeyCode: Key1, Op: KeyReleaseVal},
				{KeyCode: KeyLeftMeta, Op: KeyReleaseVal},
			},
		},
		{
			name:  "SUPER+SHIFT+Q",
			combo: "SUPER+SHIFT+Q",
			wantSteps: []KeyStep{
				{KeyCode: KeyLeftMeta, Op: KeyPressVal},
				{KeyCode: KeyLeftShift, Op: KeyPressVal},
				{KeyCode: KeyQ, Op: KeyPressVal},
				{KeyCode: KeyQ, Op: KeyReleaseVal},
				{KeyCode: KeyLeftShift, Op: KeyReleaseVal},
				{KeyCode: KeyLeftMeta, Op: KeyReleaseVal},
			},
		},
		{
			name:  "lowercase ctrl+alt+t",
			combo: "ctrl+alt+t",
			wantSteps: []KeyStep{
				{KeyCode: KeyLeftCtrl, Op: KeyPressVal},
				{KeyCode: KeyLeftAlt, Op: KeyPressVal},
				{KeyCode: KeyT, Op: KeyPressVal},
				{KeyCode: KeyT, Op: KeyReleaseVal},
				{KeyCode: KeyLeftAlt, Op: KeyReleaseVal},
				{KeyCode: KeyLeftCtrl, Op: KeyReleaseVal},
			},
		},
		{
			name:  "single multimedia key VOLUMEDOWN",
			combo: "VOLUMEDOWN",
			wantSteps: []KeyStep{
				{KeyCode: KeyVolumeDown, Op: KeyPressVal},
				{KeyCode: KeyVolumeDown, Op: KeyReleaseVal},
			},
		},
		{
			name:    "unknown key returns error",
			combo:   "CTRL+NONEXISTENTKEY",
			wantErr: true,
		},
		{
			name:    "empty combo returns error",
			combo:   "   ",
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			parsed, err := ParseKeyCombo(tc.combo)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error for %q, got nil", tc.combo)
				}
				return
			}
			if err != nil {
				t.Fatalf("ParseKeyCombo(%q) unexpected error: %v", tc.combo, err)
			}
			if !reflect.DeepEqual(parsed.Steps, tc.wantSteps) {
				t.Errorf("ParseKeyCombo(%q) steps =\n  %+v\nwant:\n  %+v", tc.combo, parsed.Steps, tc.wantSteps)
			}
		})
	}
}

func TestNormalizeToAbsCoord(t *testing.T) {
	t.Parallel()

	cases := []struct {
		norm float32
		want int32
	}{
		{norm: -0.5, want: 0},
		{norm: 0.0, want: 0},
		{norm: 0.25, want: 16384},
		{norm: 0.5, want: 32768},
		{norm: 0.75, want: 49151},
		{norm: 1.0, want: 65535},
		{norm: 1.5, want: 65535},
		{norm: float32(math.NaN()), want: 0},
	}

	for _, tc := range cases {
		got := NormalizeToAbsCoord(tc.norm)
		if got != tc.want {
			t.Errorf("NormalizeToAbsCoord(%v) = %d, want %d", tc.norm, got, tc.want)
		}
	}
}

func TestRouterInputHandlersWithMockInjector(t *testing.T) {
	t.Parallel()

	mockInj := NewMockInjector()
	defer mockInj.Close()

	router := server.NewRouter(nil)
	h := NewHandler(mockInj, nil)
	h.Register(router)

	ctx := context.Background()

	// 1. Relative MouseMoveEvent
	if err := router.Dispatch(ctx, nil, &pb.Envelope{
		RequestId: "m-rel",
		Payload: &pb.Envelope_MouseMoveEvent{
			MouseMoveEvent: &pb.MouseMoveEvent{Dx: 100, Dy: -50, IsAbsolute: false},
		},
	}); err != nil {
		t.Fatalf("dispatch relative MouseMoveEvent: %v", err)
	}

	// 2. Absolute MouseMoveEvent
	if err := router.Dispatch(ctx, nil, &pb.Envelope{
		RequestId: "m-abs",
		Payload: &pb.Envelope_MouseMoveEvent{
			MouseMoveEvent: &pb.MouseMoveEvent{NormX: 0.5, NormY: 1.0, IsAbsolute: true},
		},
	}); err != nil {
		t.Fatalf("dispatch absolute MouseMoveEvent: %v", err)
	}

	// 3. MouseButtonEvent
	if err := router.Dispatch(ctx, nil, &pb.Envelope{
		RequestId: "m-btn",
		Payload: &pb.Envelope_MouseButtonEvent{
			MouseButtonEvent: &pb.MouseButtonEvent{
				Button: pb.MouseButton_RIGHT,
				Action: pb.ButtonAction_CLICK,
			},
		},
	}); err != nil {
		t.Fatalf("dispatch MouseButtonEvent: %v", err)
	}

	// 4. MouseScrollEvent
	if err := router.Dispatch(ctx, nil, &pb.Envelope{
		RequestId: "m-scroll",
		Payload: &pb.Envelope_MouseScrollEvent{
			MouseScrollEvent: &pb.MouseScrollEvent{Dx: 0, Dy: -3},
		},
	}); err != nil {
		t.Fatalf("dispatch MouseScrollEvent: %v", err)
	}

	// 5. KeyComboEvent
	if err := router.Dispatch(ctx, nil, &pb.Envelope{
		RequestId: "k-combo",
		Payload: &pb.Envelope_KeyComboEvent{
			KeyComboEvent: &pb.KeyComboEvent{Combo: "CTRL+ALT+T"},
		},
	}); err != nil {
		t.Fatalf("dispatch KeyComboEvent: %v", err)
	}

	events := mockInj.Events()
	if len(events) != 5 {
		t.Fatalf("expected 5 recorded events, got %d", len(events))
	}
	if events[0].Kind != "move_rel" || events[0].DX != 100 || events[0].DY != -50 {
		t.Errorf("unexpected event[0]: %+v", events[0])
	}
	if events[1].Kind != "move_abs" || events[1].AbsX != 32768 || events[1].AbsY != 65535 {
		t.Errorf("unexpected event[1]: %+v", events[1])
	}
	if events[2].Kind != "mouse_btn" || events[2].MouseBtn != pb.MouseButton_RIGHT || events[2].Action != pb.ButtonAction_CLICK {
		t.Errorf("unexpected event[2]: %+v", events[2])
	}
	if events[3].Kind != "scroll" || events[3].DY != -3 {
		t.Errorf("unexpected event[3]: %+v", events[3])
	}
	if events[4].Kind != "key_combo" || len(events[4].KeySteps) != 6 {
		t.Errorf("unexpected event[4]: %+v", events[4])
	}
}

func TestMockInjector_ReleaseAll(t *testing.T) {
	t.Parallel()
	mock := NewMockInjector()
	if err := mock.KeyAction(KeyLeftCtrl, pb.ButtonAction_PRESS); err != nil {
		t.Fatalf("KeyAction PRESS error: %v", err)
	}
	if err := mock.ReleaseAll(); err != nil {
		t.Fatalf("ReleaseAll error: %v", err)
	}
	events := mock.Events()
	if len(events) != 2 {
		t.Fatalf("expected 2 events, got %d", len(events))
	}
	if events[1].Kind != "release_all" {
		t.Fatalf("expected release_all event, got %s", events[1].Kind)
	}
}

