package input

import (
	"errors"
	"fmt"
	"log/slog"
	"math"
	"os"
	"sync"
	"syscall"
	"unsafe"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

// Virtual device names registered with the Linux kernel via /dev/uinput.
const (
	DeviceNameRelativeMouse = "LinuxDeck Virtual Mouse (Relative)"
	DeviceNameTouchScreen   = "LinuxDeck Virtual TouchScreen (Absolute)"
	DeviceNameKeyboard      = "LinuxDeck Virtual Keyboard"

	UinputDevicePath = "/dev/uinput"
	MaxAbsCoord      = 65535
)

// Linux uinput / evdev constants (<linux/input.h>, <linux/uinput.h>).
const (
	evSyn uint16 = 0x00
	evKey uint16 = 0x01
	evRel uint16 = 0x02
	evAbs uint16 = 0x03

	synReport uint16 = 0x00

	relX      uint16 = 0x00
	relY      uint16 = 0x01
	relHWheel uint16 = 0x06
	relWheel  uint16 = 0x08

	absX   uint16 = 0x00
	absY   uint16 = 0x01
	absCnt        = 64

	busVirtual uint16 = 0x06

	uiSetEvBit   uintptr = 0x40045564
	uiSetKeyBit  uintptr = 0x40045565
	uiSetRelBit  uintptr = 0x40045566
	uiSetAbsBit  uintptr = 0x40045567
	uiDevCreate  uintptr = 0x5501
	uiDevDestroy uintptr = 0x5502
)

// InputInjector abstracts kernel-level virtual input devices (/dev/uinput)
// and provides a safe mock/fallback implementation when /dev/uinput is inaccessible.
type InputInjector interface {
	MoveRelative(dx, dy int32) error
	MoveAbsolute(normX, normY float32) error
	MouseButton(button pb.MouseButton, action pb.ButtonAction) error
	Scroll(dx, dy int32) error
	KeyAction(keyCode uint16, action pb.ButtonAction) error
	ExecuteSteps(steps []KeyStep) error
	ExecuteCombo(combo string) error
	TypeText(text string) error
	IsDegraded() bool
	Close() error
}

// NormalizeToAbsCoord converts a normalized coordinate in [0.0, 1.0] to the kernel ABS range [0, 65535].
func NormalizeToAbsCoord(norm float32) int32 {
	if math.IsNaN(float64(norm)) || norm <= 0.0 {
		return 0
	}
	if norm >= 1.0 {
		return MaxAbsCoord
	}
	return int32(math.Round(float64(norm) * float64(MaxAbsCoord)))
}

type inputID struct {
	Bustype uint16
	Vendor  uint16
	Product uint16
	Version uint16
}

// uinputUserDev matches struct uinput_user_dev in <linux/uinput.h>.
type uinputUserDev struct {
	Name       [80]byte
	ID         inputID
	FfEffects  uint32
	Absmax     [absCnt]int32
	Absmin     [absCnt]int32
	Absfuzz    [absCnt]int32
	Absflat    [absCnt]int32
}

// inputEvent matches struct input_event in <linux/input.h> on 64-bit Linux.
type inputEvent struct {
	Time  syscall.Timeval
	Type  uint16
	Code  uint16
	Value int32
}

// KernelUinputInjector manages the 3 virtual /dev/uinput devices (Mouse Rel, TouchScreen Abs, Keyboard).
type KernelUinputInjector struct {
	mu       sync.Mutex
	relMouse *os.File
	absTouch *os.File
	keyboard *os.File
	closed   bool
}

// NewInputInjector attempts to initialize the 3 kernel /dev/uinput devices.
// If /dev/uinput is unavailable or lacks permissions, it logs a clear warning instructing to run
// scripts/setup-permissions.sh and returns a degraded MockInjector without crashing the daemon.
func NewInputInjector(logger *slog.Logger) InputInjector {
	if logger == nil {
		logger = slog.Default()
	}
	inj, err := NewKernelUinputInjector()
	if err != nil {
		logger.Warn("unable to open /dev/uinput; starting input subsystem in degraded mock mode. Run './scripts/setup-permissions.sh' to enable kernel virtual devices.",
			"device", UinputDevicePath,
			"err", err,
		)
		return NewMockInjector()
	}
	logger.Info("initialized kernel /dev/uinput virtual devices",
		"mouse_rel", DeviceNameRelativeMouse,
		"touch_abs", DeviceNameTouchScreen,
		"keyboard", DeviceNameKeyboard,
	)
	return inj
}

// NewKernelUinputInjector creates the 3 virtual devices directly in /dev/uinput.
func NewKernelUinputInjector() (*KernelUinputInjector, error) {
	relMouse, err := createRelativeMouseDevice()
	if err != nil {
		return nil, fmt.Errorf("input uinput init relative mouse: %w", err)
	}

	absTouch, err := createAbsoluteTouchDevice()
	if err != nil {
		destroyDevice(relMouse)
		return nil, fmt.Errorf("input uinput init absolute touchscreen: %w", err)
	}

	keyboard, err := createKeyboardDevice()
	if err != nil {
		destroyDevice(absTouch)
		destroyDevice(relMouse)
		return nil, fmt.Errorf("input uinput init keyboard: %w", err)
	}

	return &KernelUinputInjector{
		relMouse: relMouse,
		absTouch: absTouch,
		keyboard: keyboard,
	}, nil
}

func (k *KernelUinputInjector) IsDegraded() bool { return false }

func (k *KernelUinputInjector) MoveRelative(dx, dy int32) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.closed {
		return errors.New("input uinput: injector closed")
	}
	events := []inputEvent{
		{Type: evRel, Code: relX, Value: dx},
		{Type: evRel, Code: relY, Value: dy},
		{Type: evSyn, Code: synReport, Value: 0},
	}
	return writeEvents(k.relMouse, events)
}

func (k *KernelUinputInjector) MoveAbsolute(normX, normY float32) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.closed {
		return errors.New("input uinput: injector closed")
	}
	x := NormalizeToAbsCoord(normX)
	y := NormalizeToAbsCoord(normY)
	events := []inputEvent{
		{Type: evAbs, Code: absX, Value: x},
		{Type: evAbs, Code: absY, Value: y},
		{Type: evSyn, Code: synReport, Value: 0},
	}
	return writeEvents(k.absTouch, events)
}

func (k *KernelUinputInjector) MouseButton(button pb.MouseButton, action pb.ButtonAction) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.closed {
		return errors.New("input uinput: injector closed")
	}
	btnCode, err := mapMouseButton(button)
	if err != nil {
		return err
	}
	return emitButtonOrKey(k.relMouse, btnCode, action)
}

func (k *KernelUinputInjector) Scroll(dx, dy int32) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.closed {
		return errors.New("input uinput: injector closed")
	}
	var events []inputEvent
	if dy != 0 {
		events = append(events, inputEvent{Type: evRel, Code: relWheel, Value: dy})
	}
	if dx != 0 {
		events = append(events, inputEvent{Type: evRel, Code: relHWheel, Value: dx})
	}
	if len(events) == 0 {
		return nil
	}
	events = append(events, inputEvent{Type: evSyn, Code: synReport, Value: 0})
	return writeEvents(k.relMouse, events)
}

func (k *KernelUinputInjector) KeyAction(keyCode uint16, action pb.ButtonAction) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.closed {
		return errors.New("input uinput: injector closed")
	}
	return emitButtonOrKey(k.keyboard, keyCode, action)
}

func (k *KernelUinputInjector) ExecuteSteps(steps []KeyStep) error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.closed {
		return errors.New("input uinput: injector closed")
	}
	for _, step := range steps {
		ev := []inputEvent{
			{Type: evKey, Code: step.KeyCode, Value: int32(step.Op)},
			{Type: evSyn, Code: synReport, Value: 0},
		}
		if err := writeEvents(k.keyboard, ev); err != nil {
			return err
		}
	}
	return nil
}

func (k *KernelUinputInjector) ExecuteCombo(combo string) error {
	parsed, err := ParseKeyCombo(combo)
	if err != nil {
		return err
	}
	return k.ExecuteSteps(parsed.Steps)
}

func (k *KernelUinputInjector) TypeText(text string) error {
	steps, err := TextToKeySteps(text)
	if err != nil {
		return err
	}
	return k.ExecuteSteps(steps)
}

func (k *KernelUinputInjector) Close() error {
	k.mu.Lock()
	defer k.mu.Unlock()
	if k.closed {
		return nil
	}
	k.closed = true
	destroyDevice(k.relMouse)
	destroyDevice(k.absTouch)
	destroyDevice(k.keyboard)
	return nil
}

func createRelativeMouseDevice() (*os.File, error) {
	f, err := os.OpenFile(UinputDevicePath, syscall.O_WRONLY|syscall.O_NONBLOCK, 0o660)
	if err != nil {
		return nil, err
	}
	fd := f.Fd()

	for _, ev := range []uintptr{ uintptr(evKey), uintptr(evRel), uintptr(evSyn) } {
		if err := ioctl(fd, uiSetEvBit, ev); err != nil {
			_ = f.Close()
			return nil, err
		}
	}
	for _, btn := range []uint16{BtnLeft, BtnRight, BtnMiddle, BtnSide, BtnExtra} {
		if err := ioctl(fd, uiSetKeyBit, uintptr(btn)); err != nil {
			_ = f.Close()
			return nil, err
		}
	}
	for _, rel := range []uint16{relX, relY, relWheel, relHWheel} {
		if err := ioctl(fd, uiSetRelBit, uintptr(rel)); err != nil {
			_ = f.Close()
			return nil, err
		}
	}

	var udev uinputUserDev
	copy(udev.Name[:], DeviceNameRelativeMouse)
	udev.ID = inputID{Bustype: busVirtual, Vendor: 0x1d6b, Product: 0x0101, Version: 1}

	if err := registerUinputDev(f, &udev); err != nil {
		_ = f.Close()
		return nil, err
	}
	return f, nil
}

func createAbsoluteTouchDevice() (*os.File, error) {
	f, err := os.OpenFile(UinputDevicePath, syscall.O_WRONLY|syscall.O_NONBLOCK, 0o660)
	if err != nil {
		return nil, err
	}
	fd := f.Fd()

	for _, ev := range []uintptr{uintptr(evKey), uintptr(evAbs), uintptr(evSyn)} {
		if err := ioctl(fd, uiSetEvBit, ev); err != nil {
			_ = f.Close()
			return nil, err
		}
	}
	for _, btn := range []uint16{BtnTouch, BtnLeft} {
		if err := ioctl(fd, uiSetKeyBit, uintptr(btn)); err != nil {
			_ = f.Close()
			return nil, err
		}
	}
	for _, abs := range []uint16{absX, absY} {
		if err := ioctl(fd, uiSetAbsBit, uintptr(abs)); err != nil {
			_ = f.Close()
			return nil, err
		}
	}

	var udev uinputUserDev
	copy(udev.Name[:], DeviceNameTouchScreen)
	udev.ID = inputID{Bustype: busVirtual, Vendor: 0x1d6b, Product: 0x0102, Version: 1}
	udev.Absmin[absX] = 0
	udev.Absmax[absX] = MaxAbsCoord
	udev.Absmin[absY] = 0
	udev.Absmax[absY] = MaxAbsCoord

	if err := registerUinputDev(f, &udev); err != nil {
		_ = f.Close()
		return nil, err
	}
	return f, nil
}

func createKeyboardDevice() (*os.File, error) {
	f, err := os.OpenFile(UinputDevicePath, syscall.O_WRONLY|syscall.O_NONBLOCK, 0o660)
	if err != nil {
		return nil, err
	}
	fd := f.Fd()

	for _, ev := range []uintptr{uintptr(evKey), uintptr(evSyn)} {
		if err := ioctl(fd, uiSetEvBit, ev); err != nil {
			_ = f.Close()
			return nil, err
		}
	}
	// Enable standard Linux keycodes (1..248).
	for key := uintptr(1); key <= 248; key++ {
		if err := ioctl(fd, uiSetKeyBit, key); err != nil {
			_ = f.Close()
			return nil, err
		}
	}

	var udev uinputUserDev
	copy(udev.Name[:], DeviceNameKeyboard)
	udev.ID = inputID{Bustype: busVirtual, Vendor: 0x1d6b, Product: 0x0103, Version: 1}

	if err := registerUinputDev(f, &udev); err != nil {
		_ = f.Close()
		return nil, err
	}
	return f, nil
}

func registerUinputDev(f *os.File, udev *uinputUserDev) error {
	raw := (*[unsafe.Sizeof(*udev)]byte)(unsafe.Pointer(udev))[:]
	if _, err := f.Write(raw); err != nil {
		return err
	}
	return ioctl(f.Fd(), uiDevCreate, 0)
}

func destroyDevice(f *os.File) {
	if f == nil {
		return
	}
	_ = ioctl(f.Fd(), uiDevDestroy, 0)
	_ = f.Close()
}

func emitButtonOrKey(f *os.File, code uint16, action pb.ButtonAction) error {
	switch action {
	case pb.ButtonAction_PRESS:
		return writeEvents(f, []inputEvent{
			{Type: evKey, Code: code, Value: 1},
			{Type: evSyn, Code: synReport, Value: 0},
		})
	case pb.ButtonAction_RELEASE:
		return writeEvents(f, []inputEvent{
			{Type: evKey, Code: code, Value: 0},
			{Type: evSyn, Code: synReport, Value: 0},
		})
	case pb.ButtonAction_CLICK, pb.ButtonAction_BUTTON_ACTION_UNSPECIFIED:
		return writeEvents(f, []inputEvent{
			{Type: evKey, Code: code, Value: 1},
			{Type: evSyn, Code: synReport, Value: 0},
			{Type: evKey, Code: code, Value: 0},
			{Type: evSyn, Code: synReport, Value: 0},
		})
	default:
		return fmt.Errorf("input uinput: unsupported button action %v", action)
	}
}

func mapMouseButton(btn pb.MouseButton) (uint16, error) {
	switch btn {
	case pb.MouseButton_LEFT, pb.MouseButton_MOUSE_BUTTON_UNSPECIFIED:
		return BtnLeft, nil
	case pb.MouseButton_RIGHT:
		return BtnRight, nil
	case pb.MouseButton_MIDDLE:
		return BtnMiddle, nil
	case pb.MouseButton_BACK:
		return BtnSide, nil
	case pb.MouseButton_FORWARD:
		return BtnExtra, nil
	default:
		return 0, fmt.Errorf("input uinput: unsupported mouse button %v", btn)
	}
}

func writeEvents(f *os.File, events []inputEvent) error {
	for i := range events {
		ev := &events[i]
		raw := (*[unsafe.Sizeof(*ev)]byte)(unsafe.Pointer(ev))[:]
		if _, err := f.Write(raw); err != nil {
			return err
		}
	}
	return nil
}

func ioctl(fd, req, arg uintptr) error {
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg)
	if errno != 0 {
		return errno
	}
	return nil
}

// RecordedEvent captures an operation performed on MockInjector during tests or degraded mode.
type RecordedEvent struct {
	Kind      string
	DX        int32
	DY        int32
	AbsX      int32
	AbsY      int32
	NormX     float32
	NormY     float32
	MouseBtn  pb.MouseButton
	Action    pb.ButtonAction
	KeyCode   uint16
	KeySteps  []KeyStep
	ComboText string
}

// MockInjector is a thread-safe in-memory implementation of InputInjector used when /dev/uinput
// is inaccessible or in automated unit/integration tests.
type MockInjector struct {
	mu     sync.Mutex
	events []RecordedEvent
	closed bool
}

// NewMockInjector constructs a new in-memory MockInjector.
func NewMockInjector() *MockInjector {
	return &MockInjector{}
}

func (m *MockInjector) IsDegraded() bool { return true }

func (m *MockInjector) MoveRelative(dx, dy int32) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("mock injector closed")
	}
	m.events = append(m.events, RecordedEvent{
		Kind: "move_rel",
		DX:   dx,
		DY:   dy,
	})
	return nil
}

func (m *MockInjector) MoveAbsolute(normX, normY float32) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("mock injector closed")
	}
	m.events = append(m.events, RecordedEvent{
		Kind:  "move_abs",
		NormX: normX,
		NormY: normY,
		AbsX:  NormalizeToAbsCoord(normX),
		AbsY:  NormalizeToAbsCoord(normY),
	})
	return nil
}

func (m *MockInjector) MouseButton(button pb.MouseButton, action pb.ButtonAction) error {
	if _, err := mapMouseButton(button); err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("mock injector closed")
	}
	m.events = append(m.events, RecordedEvent{
		Kind:     "mouse_btn",
		MouseBtn: button,
		Action:   action,
	})
	return nil
}

func (m *MockInjector) Scroll(dx, dy int32) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("mock injector closed")
	}
	m.events = append(m.events, RecordedEvent{
		Kind: "scroll",
		DX:   dx,
		DY:   dy,
	})
	return nil
}

func (m *MockInjector) KeyAction(keyCode uint16, action pb.ButtonAction) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("mock injector closed")
	}
	m.events = append(m.events, RecordedEvent{
		Kind:    "key_action",
		KeyCode: keyCode,
		Action:  action,
	})
	return nil
}

func (m *MockInjector) ExecuteSteps(steps []KeyStep) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("mock injector closed")
	}
	copied := make([]KeyStep, len(steps))
	copy(copied, steps)
	m.events = append(m.events, RecordedEvent{
		Kind:     "key_steps",
		KeySteps: copied,
	})
	return nil
}

func (m *MockInjector) ExecuteCombo(combo string) error {
	parsed, err := ParseKeyCombo(combo)
	if err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("mock injector closed")
	}
	m.events = append(m.events, RecordedEvent{
		Kind:      "key_combo",
		ComboText: combo,
		KeySteps:  parsed.Steps,
	})
	return nil
}

func (m *MockInjector) TypeText(text string) error {
	steps, err := TextToKeySteps(text)
	if err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return errors.New("mock injector closed")
	}
	m.events = append(m.events, RecordedEvent{
		Kind:      "type_text",
		ComboText: text,
		KeySteps:  steps,
	})
	return nil
}

func (m *MockInjector) Events() []RecordedEvent {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]RecordedEvent, len(m.events))
	copy(out, m.events)
	return out
}

func (m *MockInjector) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closed = true
	return nil
}

