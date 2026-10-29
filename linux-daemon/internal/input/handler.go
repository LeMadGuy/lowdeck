package input

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"

	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

// Handler bridges Protobuf input events from the WebSocket router to an InputInjector.
type Handler struct {
	injector InputInjector
	logger   *slog.Logger
}

// NewHandler creates a new input Handler backed by injector.
func NewHandler(injector InputInjector, logger *slog.Logger) *Handler {
	if logger == nil {
		logger = slog.Default()
	}
	if injector == nil {
		injector = NewMockInjector()
	}
	return &Handler{
		injector: injector,
		logger:   logger,
	}
}

// Register binds all virtual input handlers (MouseMove, MouseButton, MouseScroll, Key, KeyCombo) on router.
func (h *Handler) Register(router *server.Router) {
	if router == nil {
		return
	}
	router.Register(server.KindMouseMove, h.HandleMouseMove)
	router.Register(server.KindMouseButton, h.HandleMouseButton)
	router.Register(server.KindMouseScroll, h.HandleMouseScroll)
	router.Register(server.KindKey, h.HandleKey)
	router.Register(server.KindKeyCombo, h.HandleKeyCombo)
}

// HandleMouseMove processes relative (dx, dy) and absolute (norm_x, norm_y) mouse movement events.
func (h *Handler) HandleMouseMove(_ context.Context, _ server.ClientSession, env *pb.Envelope) error {
	ev := env.GetMouseMoveEvent()
	if ev == nil {
		return errors.New("input handler: nil MouseMoveEvent")
	}
	if ev.IsAbsolute {
		return h.injector.MoveAbsolute(ev.NormX, ev.NormY)
	}
	return h.injector.MoveRelative(ev.Dx, ev.Dy)
}

// HandleMouseButton processes mouse button press, release, and click events.
func (h *Handler) HandleMouseButton(_ context.Context, _ server.ClientSession, env *pb.Envelope) error {
	ev := env.GetMouseButtonEvent()
	if ev == nil {
		return errors.New("input handler: nil MouseButtonEvent")
	}
	return h.injector.MouseButton(ev.Button, ev.Action)
}

// HandleMouseScroll processes vertical and horizontal mouse wheel scroll events.
func (h *Handler) HandleMouseScroll(_ context.Context, _ server.ClientSession, env *pb.Envelope) error {
	ev := env.GetMouseScrollEvent()
	if ev == nil {
		return errors.New("input handler: nil MouseScrollEvent")
	}
	return h.injector.Scroll(ev.Dx, ev.Dy)
}

// HandleKey processes single key events with optional active modifiers.
func (h *Handler) HandleKey(_ context.Context, _ server.ClientSession, env *pb.Envelope) error {
	ev := env.GetKeyEvent()
	if ev == nil {
		return errors.New("input handler: nil KeyEvent")
	}

	var code uint16
	if ev.KeyCode != 0 {
		code = uint16(ev.KeyCode)
	} else if strings.TrimSpace(ev.Key) != "" {
		resolved, err := ResolveKeyName(ev.Key)
		if err != nil {
			return fmt.Errorf("input handler resolve key: %w", err)
		}
		code = resolved
	} else {
		return errors.New("input handler: KeyEvent requires key_code or key")
	}

	if len(ev.Modifiers) == 0 {
		return h.injector.KeyAction(code, ev.Action)
	}

	mods := make([]uint16, 0, len(ev.Modifiers))
	for _, m := range ev.Modifiers {
		mc, err := ResolveKeyName(m)
		if err != nil {
			return fmt.Errorf("input handler resolve modifier %q: %w", m, err)
		}
		mods = append(mods, mc)
	}

	var steps []KeyStep
	for _, mc := range mods {
		steps = append(steps, KeyStep{KeyCode: mc, Op: KeyPressVal})
	}
	switch ev.Action {
	case pb.ButtonAction_PRESS:
		steps = append(steps, KeyStep{KeyCode: code, Op: KeyPressVal})
	case pb.ButtonAction_RELEASE:
		steps = append(steps, KeyStep{KeyCode: code, Op: KeyReleaseVal})
	default:
		steps = append(steps,
			KeyStep{KeyCode: code, Op: KeyPressVal},
			KeyStep{KeyCode: code, Op: KeyReleaseVal},
		)
	}
	for i := len(mods) - 1; i >= 0; i-- {
		steps = append(steps, KeyStep{KeyCode: mods[i], Op: KeyReleaseVal})
	}
	return h.injector.ExecuteSteps(steps)
}

// HandleKeyCombo processes human-readable key combinations (e.g. "CTRL+ALT+T", "SUPER+SHIFT+Q") or text strings.
func (h *Handler) HandleKeyCombo(_ context.Context, _ server.ClientSession, env *pb.Envelope) error {
	ev := env.GetKeyComboEvent()
	if ev == nil {
		return errors.New("input handler: nil KeyComboEvent")
	}

	if strings.TrimSpace(ev.Combo) != "" {
		return h.injector.ExecuteCombo(ev.Combo)
	}
	if len(ev.Keys) > 0 {
		return h.injector.ExecuteCombo(strings.Join(ev.Keys, "+"))
	}
	if ev.Text != "" {
		return h.injector.TypeText(ev.Text)
	}
	return errors.New("input handler: KeyComboEvent has no combo, keys, or text")
}

