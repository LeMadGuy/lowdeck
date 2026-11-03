package dbus

import (
	"context"
	"testing"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

type mockSystemAudio struct {
	volume    float32
	isMuted   bool
	volUp     int
	volDown   int
	muteTog   int
	setVolVal float64
}

func (m *mockSystemAudio) VolumeUp(ctx context.Context, step float64) error {
	m.volUp++
	m.volume += float32(step)
	if m.volume > 1.5 {
		m.volume = 1.5
	}
	return nil
}

func (m *mockSystemAudio) VolumeDown(ctx context.Context, step float64) error {
	m.volDown++
	m.volume -= float32(step)
	if m.volume < 0 {
		m.volume = 0
	}
	return nil
}

func (m *mockSystemAudio) ToggleMute(ctx context.Context) error {
	m.muteTog++
	m.isMuted = !m.isMuted
	return nil
}

func (m *mockSystemAudio) SetVolume(ctx context.Context, volume float64) error {
	m.setVolVal = volume
	m.volume = float32(volume)
	return nil
}

func (m *mockSystemAudio) GetVolume(ctx context.Context) (float32, bool, error) {
	return m.volume, m.isMuted, nil
}

func TestMediaController_ExecuteVolumeCommands(t *testing.T) {
	mockAudio := &mockSystemAudio{
		volume:  0.50,
		isMuted: false,
	}

	ctrl := NewMediaControllerWithAudio(nil, mockAudio)
	ctx := context.Background()

	// 1. VOLUME_UP
	res, err := ctrl.ExecuteCommand(ctx, &pb.MediaControlCommand{
		Command: pb.MediaCommandType_VOLUME_UP,
	})
	if err != nil {
		t.Fatalf("VOLUME_UP failed: %v", err)
	}
	if mockAudio.volUp != 1 {
		t.Errorf("mockAudio.volUp = %d, want 1", mockAudio.volUp)
	}
	if res.Volume != 0.55 {
		t.Errorf("res.Volume = %v, want 0.55", res.Volume)
	}

	// 2. VOLUME_DOWN
	res, err = ctrl.ExecuteCommand(ctx, &pb.MediaControlCommand{
		Command: pb.MediaCommandType_VOLUME_DOWN,
	})
	if err != nil {
		t.Fatalf("VOLUME_DOWN failed: %v", err)
	}
	if mockAudio.volDown != 1 {
		t.Errorf("mockAudio.volDown = %d, want 1", mockAudio.volDown)
	}
	if res.Volume != 0.50 {
		t.Errorf("res.Volume = %v, want 0.50", res.Volume)
	}

	// 3. MUTE (Toggle on)
	res, err = ctrl.ExecuteCommand(ctx, &pb.MediaControlCommand{
		Command: pb.MediaCommandType_MUTE,
	})
	if err != nil {
		t.Fatalf("MUTE failed: %v", err)
	}
	if mockAudio.muteTog != 1 {
		t.Errorf("mockAudio.muteTog = %d, want 1", mockAudio.muteTog)
	}
	if !mockAudio.isMuted {
		t.Errorf("expected isMuted true")
	}
	if res.Volume != 0.0 {
		t.Errorf("res.Volume when muted = %v, want 0.0", res.Volume)
	}

	// 4. MUTE (Toggle off)
	res, err = ctrl.ExecuteCommand(ctx, &pb.MediaControlCommand{
		Command: pb.MediaCommandType_MUTE,
	})
	if err != nil {
		t.Fatalf("MUTE toggle off failed: %v", err)
	}
	if mockAudio.muteTog != 2 {
		t.Errorf("mockAudio.muteTog = %d, want 2", mockAudio.muteTog)
	}
	if mockAudio.isMuted {
		t.Errorf("expected isMuted false")
	}
	if res.Volume != 0.50 {
		t.Errorf("res.Volume unmuted = %v, want 0.50", res.Volume)
	}

	// 5. Explicit SetVolume via cmd.Volume
	res, err = ctrl.ExecuteCommand(ctx, &pb.MediaControlCommand{
		Volume: 0.85,
	})
	if err != nil {
		t.Fatalf("SetVolume failed: %v", err)
	}
	if float32(mockAudio.setVolVal) != 0.85 {
		t.Errorf("mockAudio.setVolVal = %v, want 0.85", mockAudio.setVolVal)
	}
	if res.Volume != 0.85 {
		t.Errorf("res.Volume = %v, want 0.85", res.Volume)
	}
}

func TestMediaController_NilCommand(t *testing.T) {
	ctrl := NewMediaControllerWithAudio(nil, &mockSystemAudio{})
	_, err := ctrl.ExecuteCommand(context.Background(), nil)
	if err == nil {
		t.Fatal("expected error on nil command")
	}
}
