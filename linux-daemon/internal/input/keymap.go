package input

import (
	"errors"
	"fmt"
	"strings"
)

// Linux evdev keycodes (<linux/input-event-codes.h>).
const (
	KeyEsc        uint16 = 1
	Key1          uint16 = 2
	Key2          uint16 = 3
	Key3          uint16 = 4
	Key4          uint16 = 5
	Key5          uint16 = 6
	Key6          uint16 = 7
	Key7          uint16 = 8
	Key8          uint16 = 9
	Key9          uint16 = 10
	Key0          uint16 = 11
	KeyMinus      uint16 = 12
	KeyEqual      uint16 = 13
	KeyBackspace  uint16 = 14
	KeyTab        uint16 = 15
	KeyQ          uint16 = 16
	KeyW          uint16 = 17
	KeyE          uint16 = 18
	KeyR          uint16 = 19
	KeyT          uint16 = 20
	KeyY          uint16 = 21
	KeyU          uint16 = 22
	KeyI          uint16 = 23
	KeyO          uint16 = 24
	KeyP          uint16 = 25
	KeyLeftBrace  uint16 = 26
	KeyRightBrace uint16 = 27
	KeyEnter      uint16 = 28
	KeyLeftCtrl   uint16 = 29
	KeyA          uint16 = 30
	KeyS          uint16 = 31
	KeyD          uint16 = 32
	KeyF          uint16 = 33
	KeyG          uint16 = 34
	KeyH          uint16 = 35
	KeyJ          uint16 = 36
	KeyK          uint16 = 37
	KeyL          uint16 = 38
	KeySemicolon  uint16 = 39
	KeyApostrophe uint16 = 40
	KeyGrave      uint16 = 41
	KeyLeftShift  uint16 = 42
	KeyBackslash  uint16 = 43
	KeyZ          uint16 = 44
	KeyX          uint16 = 45
	KeyC          uint16 = 46
	KeyV          uint16 = 47
	KeyB          uint16 = 48
	KeyN          uint16 = 49
	KeyM          uint16 = 50
	KeyComma      uint16 = 51
	KeyDot        uint16 = 52
	KeySlash      uint16 = 53
	KeyRightShift uint16 = 54
	KeyLeftAlt    uint16 = 56
	KeySpace      uint16 = 57
	KeyCapsLock   uint16 = 58
	KeyF1         uint16 = 59
	KeyF2         uint16 = 60
	KeyF3         uint16 = 61
	KeyF4         uint16 = 62
	KeyF5         uint16 = 63
	KeyF6         uint16 = 64
	KeyF7         uint16 = 65
	KeyF8         uint16 = 66
	KeyF9         uint16 = 67
	KeyF10        uint16 = 68
	KeyF11        uint16 = 87
	KeyF12        uint16 = 88
	KeyRightCtrl  uint16 = 97
	KeyRightAlt   uint16 = 100
	KeyHome       uint16 = 102
	KeyUp         uint16 = 103
	KeyPageUp     uint16 = 104
	KeyLeft       uint16 = 105
	KeyRight      uint16 = 106
	KeyEnd        uint16 = 107
	KeyDown       uint16 = 108
	KeyPageDown   uint16 = 109
	KeyInsert     uint16 = 110
	KeyDelete     uint16 = 111
	KeyMute       uint16 = 113
	KeyVolumeDown uint16 = 114
	KeyVolumeUp   uint16 = 115
	KeyPower      uint16 = 116
	KeyLeftMeta   uint16 = 125
	KeyRightMeta  uint16 = 126
	KeyNextSong   uint16 = 163
	KeyPlayPause  uint16 = 164
	KeyPrevSong   uint16 = 165
	KeyStopCD     uint16 = 166
	KeyPrint      uint16 = 210
)

// Linux evdev mouse buttons.
const (
	BtnLeft   uint16 = 0x110
	BtnRight  uint16 = 0x111
	BtnMiddle uint16 = 0x112
	BtnSide   uint16 = 0x113 // Back
	BtnExtra  uint16 = 0x114 // Forward
	BtnTouch  uint16 = 0x14a
)

// KeyStepOp defines whether a key is pressed down or released in a combo sequence.
type KeyStepOp int32

const (
	KeyReleaseVal KeyStepOp = 0
	KeyPressVal   KeyStepOp = 1
)

// KeyStep represents an atomic key press or release operation.
type KeyStep struct {
	KeyCode uint16
	Op      KeyStepOp
}

// ParsedCombo holds the parsed modifier keycodes, primary keycode, and full execution sequence.
type ParsedCombo struct {
	Modifiers  []uint16
	PrimaryKey uint16
	Steps      []KeyStep
}

var keyNameTable = map[string]uint16{
	// Modifiers
	"CTRL":       KeyLeftCtrl,
	"CONTROL":    KeyLeftCtrl,
	"LCTRL":      KeyLeftCtrl,
	"LEFTCTRL":   KeyLeftCtrl,
	"RCTRL":      KeyRightCtrl,
	"RIGHTCTRL":  KeyRightCtrl,
	"ALT":        KeyLeftAlt,
	"LALT":       KeyLeftAlt,
	"LEFTALT":    KeyLeftAlt,
	"RALT":       KeyRightAlt,
	"RIGHTALT":   KeyRightAlt,
	"ALTGR":      KeyRightAlt,
	"SHIFT":      KeyLeftShift,
	"LSHIFT":     KeyLeftShift,
	"LEFTSHIFT":  KeyLeftShift,
	"RSHIFT":     KeyRightShift,
	"RIGHTSHIFT": KeyRightShift,
	"SUPER":      KeyLeftMeta,
	"META":       KeyLeftMeta,
	"WIN":        KeyLeftMeta,
	"GUI":        KeyLeftMeta,
	"CMD":        KeyLeftMeta,
	"LEFTMETA":   KeyLeftMeta,
	"RIGHTMETA":  KeyRightMeta,

	// Letters
	"A": KeyA, "B": KeyB, "C": KeyC, "D": KeyD, "E": KeyE,
	"F": KeyF, "G": KeyG, "H": KeyH, "I": KeyI, "J": KeyJ,
	"K": KeyK, "L": KeyL, "M": KeyM, "N": KeyN, "O": KeyO,
	"P": KeyP, "Q": KeyQ, "R": KeyR, "S": KeyS, "T": KeyT,
	"U": KeyU, "V": KeyV, "W": KeyW, "X": KeyX, "Y": KeyY,
	"Z": KeyZ,

	// Digits
	"0": Key0, "1": Key1, "2": Key2, "3": Key3, "4": Key4,
	"5": Key5, "6": Key6, "7": Key7, "8": Key8, "9": Key9,

	// Function keys
	"F1": KeyF1, "F2": KeyF2, "F3": KeyF3, "F4": KeyF4,
	"F5": KeyF5, "F6": KeyF6, "F7": KeyF7, "F8": KeyF8,
	"F9": KeyF9, "F10": KeyF10, "F11": KeyF11, "F12": KeyF12,

	// Navigation & editing
	"ESC":        KeyEsc,
	"ESCAPE":     KeyEsc,
	"TAB":        KeyTab,
	"ENTER":      KeyEnter,
	"RETURN":     KeyEnter,
	"SPACE":      KeySpace,
	"BACKSPACE":  KeyBackspace,
	"BS":         KeyBackspace,
	"CAPSLOCK":   KeyCapsLock,
	"DELETE":     KeyDelete,
	"DEL":        KeyDelete,
	"INSERT":     KeyInsert,
	"INS":        KeyInsert,
	"HOME":       KeyHome,
	"END":        KeyEnd,
	"PAGEUP":     KeyPageUp,
	"PGUP":       KeyPageUp,
	"PAGEDOWN":   KeyPageDown,
	"PGDN":       KeyPageDown,
	"UP":         KeyUp,
	"ARROWUP":    KeyUp,
	"DOWN":       KeyDown,
	"ARROWDOWN":  KeyDown,
	"LEFT":       KeyLeft,
	"ARROWLEFT":  KeyLeft,
	"RIGHT":      KeyRight,
	"ARROWRIGHT": KeyRight,
	"PRINT":      KeyPrint,
	"PRTSC":      KeyPrint,

	// Punctuation
	"MINUS":      KeyMinus,
	"-":          KeyMinus,
	"EQUAL":      KeyEqual,
	"=":          KeyEqual,
	"LEFTBRACE":  KeyLeftBrace,
	"[":          KeyLeftBrace,
	"RIGHTBRACE": KeyRightBrace,
	"]":          KeyRightBrace,
	"SEMICOLON":  KeySemicolon,
	";":          KeySemicolon,
	"APOSTROPHE": KeyApostrophe,
	"'":          KeyApostrophe,
	"GRAVE":      KeyGrave,
	"`":          KeyGrave,
	"BACKSLASH":  KeyBackslash,
	"\\":         KeyBackslash,
	"COMMA":      KeyComma,
	",":          KeyComma,
	"DOT":        KeyDot,
	".":          KeyDot,
	"SLASH":      KeySlash,
	"/":          KeySlash,

	// Multimedia & System
	"MUTE":           KeyMute,
	"AUDIOMUTE":      KeyMute,
	"VOLUMEDOWN":     KeyVolumeDown,
	"VOLDOWN":        KeyVolumeDown,
	"AUDIOVOLDOWN":   KeyVolumeDown,
	"VOLUMEUP":       KeyVolumeUp,
	"VOLUP":          KeyVolumeUp,
	"AUDIOVOLUP":     KeyVolumeUp,
	"PLAYPAUSE":      KeyPlayPause,
	"MEDIAPLAYPAUSE": KeyPlayPause,
	"NEXTSONG":       KeyNextSong,
	"MEDIANEXT":      KeyNextSong,
	"PREVIOUSSONG":   KeyPrevSong,
	"MEDIAPREV":      KeyPrevSong,
	"STOPCD":         KeyStopCD,
	"MEDIASTOP":      KeyStopCD,
	"POWER":          KeyPower,
}

var modifierCodes = map[uint16]bool{
	KeyLeftCtrl:   true,
	KeyRightCtrl:  true,
	KeyLeftAlt:    true,
	KeyRightAlt:   true,
	KeyLeftShift:  true,
	KeyRightShift: true,
	KeyLeftMeta:   true,
	KeyRightMeta:  true,
}

// ResolveKeyName translates a human-readable key identifier (e.g. "SUPER", "ESC", "KEY_A", "F5")
// into a Linux evdev keycode.
func ResolveKeyName(name string) (uint16, error) {
	if name == " " {
		return KeySpace, nil
	}
	cleaned := strings.ToUpper(strings.TrimSpace(name))
	cleaned = strings.TrimPrefix(cleaned, "KEY_")
	if cleaned == "" {
		return 0, errors.New("input keymap: empty key name")
	}
	if code, ok := keyNameTable[cleaned]; ok {
		return code, nil
	}
	return 0, fmt.Errorf("input keymap: unknown key %q", name)
}

// IsModifierKeyCode returns true if code is a modifier key (Ctrl, Alt, Shift, Super/Meta).
func IsModifierKeyCode(code uint16) bool {
	return modifierCodes[code]
}

// ParseKeyCombo parses a human-readable shortcut expression (e.g., "SUPER+SHIFT+Q", "CTRL+ALT+T", "VOLUMEDOWN")
// into a deterministic sequence: KeyDown(modifiers in order) -> KeyDown(primary) -> KeyUp(primary) -> KeyUp(modifiers in reverse order).
func ParseKeyCombo(combo string) (*ParsedCombo, error) {
	trimmed := strings.TrimSpace(combo)
	if trimmed == "" {
		return nil, errors.New("input keymap: empty key combo")
	}

	rawTokens := strings.Split(trimmed, "+")
	codes := make([]uint16, 0, len(rawTokens))
	for _, tok := range rawTokens {
		code, err := ResolveKeyName(tok)
		if err != nil {
			return nil, fmt.Errorf("parse combo %q: %w", combo, err)
		}
		codes = append(codes, code)
	}

	if len(codes) == 1 {
		primary := codes[0]
		return &ParsedCombo{
			Modifiers:  nil,
			PrimaryKey: primary,
			Steps: []KeyStep{
				{KeyCode: primary, Op: KeyPressVal},
				{KeyCode: primary, Op: KeyReleaseVal},
			},
		}, nil
	}

	modifiers := codes[:len(codes)-1]
	primary := codes[len(codes)-1]

	// Ensure all leading tokens are valid modifiers (or allow multi-key chords).
	steps := make([]KeyStep, 0, 2*len(modifiers)+2)
	for _, mod := range modifiers {
		steps = append(steps, KeyStep{KeyCode: mod, Op: KeyPressVal})
	}
	steps = append(steps,
		KeyStep{KeyCode: primary, Op: KeyPressVal},
		KeyStep{KeyCode: primary, Op: KeyReleaseVal},
	)
	for i := len(modifiers) - 1; i >= 0; i-- {
		steps = append(steps, KeyStep{KeyCode: modifiers[i], Op: KeyReleaseVal})
	}

	return &ParsedCombo{
		Modifiers:  modifiers,
		PrimaryKey: primary,
		Steps:      steps,
	}, nil
}

// TextToKeySteps converts an ASCII string into a sequence of evdev key steps (handling Shift for uppercase/symbols).
func TextToKeySteps(text string) ([]KeyStep, error) {
	var steps []KeyStep
	for _, r := range text {
		code, needShift, ok := runeToKeyCode(r)
		if !ok {
			return nil, fmt.Errorf("input keymap: unsupported character %q in text", r)
		}
		if needShift {
			steps = append(steps,
				KeyStep{KeyCode: KeyLeftShift, Op: KeyPressVal},
				KeyStep{KeyCode: code, Op: KeyPressVal},
				KeyStep{KeyCode: code, Op: KeyReleaseVal},
				KeyStep{KeyCode: KeyLeftShift, Op: KeyReleaseVal},
			)
		} else {
			steps = append(steps,
				KeyStep{KeyCode: code, Op: KeyPressVal},
				KeyStep{KeyCode: code, Op: KeyReleaseVal},
			)
		}
	}
	return steps, nil
}

func runeToKeyCode(r rune) (code uint16, shift bool, ok bool) {
	if r >= 'a' && r <= 'z' {
		c, err := ResolveKeyName(string(r - ('a' - 'A')))
		return c, false, err == nil
	}
	if r >= 'A' && r <= 'Z' {
		c, err := ResolveKeyName(string(r))
		return c, true, err == nil
	}
	if r >= '0' && r <= '9' {
		c, err := ResolveKeyName(string(r))
		return c, false, err == nil
	}
	switch r {
	case ' ':
		return KeySpace, false, true
	case '\n', '\r':
		return KeyEnter, false, true
	case '\t':
		return KeyTab, false, true
	case '-':
		return KeyMinus, false, true
	case '_':
		return KeyMinus, true, true
	case '=':
		return KeyEqual, false, true
	case '+':
		return KeyEqual, true, true
	case '[':
		return KeyLeftBrace, false, true
	case '{':
		return KeyLeftBrace, true, true
	case ']':
		return KeyRightBrace, false, true
	case '}':
		return KeyRightBrace, true, true
	case '\\':
		return KeyBackslash, false, true
	case '|':
		return KeyBackslash, true, true
	case ';':
		return KeySemicolon, false, true
	case ':':
		return KeySemicolon, true, true
	case '\'':
		return KeyApostrophe, false, true
	case '"':
		return KeyApostrophe, true, true
	case '`':
		return KeyGrave, false, true
	case '~':
		return KeyGrave, true, true
	case ',':
		return KeyComma, false, true
	case '<':
		return KeyComma, true, true
	case '.':
		return KeyDot, false, true
	case '>':
		return KeyDot, true, true
	case '/':
		return KeySlash, false, true
	case '?':
		return KeySlash, true, true
	case '!':
		return Key1, true, true
	case '@':
		return Key2, true, true
	case '#':
		return Key3, true, true
	case '$':
		return Key4, true, true
	case '%':
		return Key5, true, true
	case '^':
		return Key6, true, true
	case '&':
		return Key7, true, true
	case '*':
		return Key8, true, true
	case '(':
		return Key9, true, true
	case ')':
		return Key0, true, true
	default:
		return 0, false, false
	}
}

