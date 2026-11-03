package dbus

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestParseWpctlOutput(t *testing.T) {
	tests := []struct {
		name       string
		input      string
		wantVol    float32
		wantMuted  bool
		wantErr    bool
	}{
		{
			name:      "normal unmuted",
			input:     "Volume: 0.65\n",
			wantVol:   0.65,
			wantMuted: false,
			wantErr:   false,
		},
		{
			name:      "muted with bracket",
			input:     "Volume: 0.40 [MUTED]\n",
			wantVol:   0.40,
			wantMuted: true,
			wantErr:   false,
		},
		{
			name:      "volume zero",
			input:     "Volume: 0.00",
			wantVol:   0.00,
			wantMuted: false,
			wantErr:   false,
		},
		{
			name:      "volume 1.25 over 100%",
			input:     "Volume: 1.25",
			wantVol:   1.25,
			wantMuted: false,
			wantErr:   false,
		},
		{
			name:    "empty output",
			input:   "",
			wantErr: true,
		},
		{
			name:    "malformed output",
			input:   "Something went wrong",
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			vol, muted, err := parseWpctlOutput(tc.input)
			if (err != nil) != tc.wantErr {
				t.Fatalf("parseWpctlOutput(%q) error = %v, wantErr %v", tc.input, err, tc.wantErr)
			}
			if !tc.wantErr {
				if vol != tc.wantVol {
					t.Errorf("vol = %v, want %v", vol, tc.wantVol)
				}
				if muted != tc.wantMuted {
					t.Errorf("muted = %v, want %v", muted, tc.wantMuted)
				}
			}
		})
	}
}

func TestParsePactlVolume(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		wantVol float32
		wantErr bool
	}{
		{
			name:    "typical pactl sink volume output",
			input:   "Volume: front-left: 39322 /  60% / -13.31 dB,   front-right: 39322 /  60% / -13.31 dB\n        balance 0.00\n",
			wantVol: 0.60,
			wantErr: false,
		},
		{
			name:    "100 percent volume",
			input:   "Volume: front-left: 65536 / 100% / 0.00 dB\n",
			wantVol: 1.00,
			wantErr: false,
		},
		{
			name:    "zero percent volume",
			input:   "Volume: front-left: 0 /   0% / -inf dB\n",
			wantVol: 0.00,
			wantErr: false,
		},
		{
			name:    "invalid format",
			input:   "No volume information here\n",
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			vol, err := parsePactlVolume(tc.input)
			if (err != nil) != tc.wantErr {
				t.Fatalf("parsePactlVolume() error = %v, wantErr %v", err, tc.wantErr)
			}
			if !tc.wantErr && vol != tc.wantVol {
				t.Errorf("vol = %v, want %v", vol, tc.wantVol)
			}
		})
	}
}

func TestParsePactlMute(t *testing.T) {
	if !parsePactlMute("Mute: yes\n") {
		t.Errorf("expected true for 'Mute: yes'")
	}
	if parsePactlMute("Mute: no\n") {
		t.Errorf("expected false for 'Mute: no'")
	}
}

func TestDefaultSystemAudio_WpctlPrimary(t *testing.T) {
	var executedCmds []string

	mockExec := func(ctx context.Context, name string, args ...string) ([]byte, error) {
		fullCmd := name + " " + strings.Join(args, " ")
		executedCmds = append(executedCmds, fullCmd)

		if name == "wpctl" {
			if strings.Contains(fullCmd, "get-volume") {
				return []byte("Volume: 0.75\n"), nil
			}
			return []byte(""), nil
		}
		return nil, errors.New("unexpected command: " + name)
	}

	audio := NewSystemAudioWithExecutor(nil, mockExec)
	ctx := context.Background()

	// Test VolumeUp
	if err := audio.VolumeUp(ctx, 0.05); err != nil {
		t.Fatalf("VolumeUp failed: %v", err)
	}
	if len(executedCmds) != 1 || !strings.Contains(executedCmds[0], "wpctl set-volume -l 1.5 @DEFAULT_AUDIO_SINK@ 5%+") {
		t.Errorf("unexpected command: %v", executedCmds)
	}

	// Test VolumeDown
	if err := audio.VolumeDown(ctx, 0.10); err != nil {
		t.Fatalf("VolumeDown failed: %v", err)
	}
	if !strings.Contains(executedCmds[1], "wpctl set-volume -l 1.5 @DEFAULT_AUDIO_SINK@ 10%-") {
		t.Errorf("unexpected command: %v", executedCmds[1])
	}

	// Test ToggleMute
	if err := audio.ToggleMute(ctx); err != nil {
		t.Fatalf("ToggleMute failed: %v", err)
	}
	if !strings.Contains(executedCmds[2], "wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle") {
		t.Errorf("unexpected command: %v", executedCmds[2])
	}

	// Test SetVolume
	if err := audio.SetVolume(ctx, 0.82); err != nil {
		t.Fatalf("SetVolume failed: %v", err)
	}
	if !strings.Contains(executedCmds[3], "wpctl set-volume -l 1.5 @DEFAULT_AUDIO_SINK@ 0.82") {
		t.Errorf("unexpected command: %v", executedCmds[3])
	}

	// Test GetVolume
	vol, muted, err := audio.GetVolume(ctx)
	if err != nil {
		t.Fatalf("GetVolume failed: %v", err)
	}
	if vol != 0.75 || muted {
		t.Errorf("GetVolume returned vol=%v, muted=%v; want 0.75, false", vol, muted)
	}
}

func TestDefaultSystemAudio_PactlFallback(t *testing.T) {
	var executedCmds []string

	mockExec := func(ctx context.Context, name string, args ...string) ([]byte, error) {
		fullCmd := name + " " + strings.Join(args, " ")
		executedCmds = append(executedCmds, fullCmd)

		if name == "wpctl" {
			return nil, errors.New("wpctl: command not found")
		}
		if name == "pactl" {
			if strings.Contains(fullCmd, "get-sink-volume") {
				return []byte("Volume: front-left: 32768 / 50% / -18.00 dB\n"), nil
			}
			if strings.Contains(fullCmd, "get-sink-mute") {
				return []byte("Mute: yes\n"), nil
			}
			return []byte(""), nil
		}
		return nil, errors.New("unexpected command: " + name)
	}

	audio := NewSystemAudioWithExecutor(nil, mockExec)
	ctx := context.Background()

	// VolumeUp should fail on wpctl and succeed on pactl
	if err := audio.VolumeUp(ctx, 0.05); err != nil {
		t.Fatalf("VolumeUp fallback failed: %v", err)
	}
	if len(executedCmds) != 2 {
		t.Fatalf("expected 2 executions (wpctl fail + pactl success), got: %v", executedCmds)
	}
	if !strings.Contains(executedCmds[0], "wpctl") || !strings.Contains(executedCmds[1], "pactl set-sink-volume @DEFAULT_SINK@ +5%") {
		t.Errorf("commands sequence unexpected: %v", executedCmds)
	}

	// Active backend should now be pactl, so ToggleMute invokes pactl directly
	if err := audio.ToggleMute(ctx); err != nil {
		t.Fatalf("ToggleMute fallback failed: %v", err)
	}
	if !strings.Contains(executedCmds[2], "pactl set-sink-mute @DEFAULT_SINK@ toggle") {
		t.Errorf("unexpected command: %v", executedCmds[2])
	}

	// GetVolume should query pactl volume and mute
	vol, muted, err := audio.GetVolume(ctx)
	if err != nil {
		t.Fatalf("GetVolume pactl failed: %v", err)
	}
	if vol != 0.50 || !muted {
		t.Errorf("GetVolume returned vol=%v, muted=%v; want 0.50, true", vol, muted)
	}
}

