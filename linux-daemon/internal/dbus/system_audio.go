package dbus

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

var (
	pactlVolumeRegex = regexp.MustCompile(`(\d+)%`)
	errNoAudioBackend = errors.New("neither wpctl nor pactl is available or functional")
)

// SystemAudio abstracts master audio volume and mute controls on Linux systems.
type SystemAudio interface {
	VolumeUp(ctx context.Context, step float64) error
	VolumeDown(ctx context.Context, step float64) error
	ToggleMute(ctx context.Context) error
	SetVolume(ctx context.Context, volume float64) error
	GetVolume(ctx context.Context) (volume float32, isMuted bool, err error)
}

// CommandExecutor executes a command with arguments and returns its standard output.
type CommandExecutor func(ctx context.Context, name string, args ...string) ([]byte, error)

// DefaultSystemAudio implements SystemAudio via wpctl (WirePlumber/PipeWire) with pactl (PulseAudio) fallback.
type DefaultSystemAudio struct {
	logger   *slog.Logger
	executor CommandExecutor

	mu            sync.Mutex
	activeBackend string // "wpctl", "pactl", or ""
}

func defaultExecutor(ctx context.Context, name string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	return cmd.Output()
}

// NewSystemAudio creates a DefaultSystemAudio controller with the default system command executor.
func NewSystemAudio(logger *slog.Logger) *DefaultSystemAudio {
	return NewSystemAudioWithExecutor(logger, defaultExecutor)
}

// NewSystemAudioWithExecutor creates a DefaultSystemAudio controller with a custom command executor (useful for testing).
func NewSystemAudioWithExecutor(logger *slog.Logger, executor CommandExecutor) *DefaultSystemAudio {
	if logger == nil {
		logger = slog.Default()
	}
	if executor == nil {
		executor = defaultExecutor
	}
	return &DefaultSystemAudio{
		logger:   logger,
		executor: executor,
	}
}

// VolumeUp increments system volume by step (defaulting to 0.05 / 5% if step <= 0).
func (s *DefaultSystemAudio) VolumeUp(ctx context.Context, step float64) error {
	pct := formatStepPercent(step, 5)
	return s.runWithFallback(ctx,
		func(ctx context.Context) error {
			// WirePlumber / PipeWire
			_, err := s.executor(ctx, "wpctl", "set-volume", "-l", "1.5", "@DEFAULT_AUDIO_SINK@", fmt.Sprintf("%d%%+", pct))
			return err
		},
		func(ctx context.Context) error {
			// PulseAudio
			_, err := s.executor(ctx, "pactl", "set-sink-volume", "@DEFAULT_SINK@", fmt.Sprintf("+%d%%", pct))
			return err
		},
	)
}

// VolumeDown decrements system volume by step (defaulting to 0.05 / 5% if step <= 0).
func (s *DefaultSystemAudio) VolumeDown(ctx context.Context, step float64) error {
	pct := formatStepPercent(step, 5)
	return s.runWithFallback(ctx,
		func(ctx context.Context) error {
			// WirePlumber / PipeWire
			_, err := s.executor(ctx, "wpctl", "set-volume", "-l", "1.5", "@DEFAULT_AUDIO_SINK@", fmt.Sprintf("%d%%-", pct))
			return err
		},
		func(ctx context.Context) error {
			// PulseAudio
			_, err := s.executor(ctx, "pactl", "set-sink-volume", "@DEFAULT_SINK@", fmt.Sprintf("-%d%%", pct))
			return err
		},
	)
}

// ToggleMute toggles mute on the default system audio sink.
func (s *DefaultSystemAudio) ToggleMute(ctx context.Context) error {
	return s.runWithFallback(ctx,
		func(ctx context.Context) error {
			// WirePlumber / PipeWire
			_, err := s.executor(ctx, "wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle")
			return err
		},
		func(ctx context.Context) error {
			// PulseAudio
			_, err := s.executor(ctx, "pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle")
			return err
		},
	)
}

// SetVolume sets the absolute master volume (0.0 to 1.5).
func (s *DefaultSystemAudio) SetVolume(ctx context.Context, volume float64) error {
	if volume < 0 {
		volume = 0
	}
	if volume > 1.5 {
		volume = 1.5
	}
	return s.runWithFallback(ctx,
		func(ctx context.Context) error {
			// WirePlumber accepts decimal representation
			_, err := s.executor(ctx, "wpctl", "set-volume", "-l", "1.5", "@DEFAULT_AUDIO_SINK@", fmt.Sprintf("%.2f", volume))
			return err
		},
		func(ctx context.Context) error {
			// PulseAudio accepts percentage
			pct := int(volume * 100)
			_, err := s.executor(ctx, "pactl", "set-sink-volume", "@DEFAULT_SINK@", fmt.Sprintf("%d%%", pct))
			return err
		},
	)
}

// GetVolume queries the current master audio sink volume and mute status.
func (s *DefaultSystemAudio) GetVolume(ctx context.Context) (float32, bool, error) {
	s.mu.Lock()
	cached := s.activeBackend
	s.mu.Unlock()

	// Try cached backend first, or start with wpctl then fallback to pactl.
	if cached == "pactl" {
		vol, muted, err := s.getVolumePactl(ctx)
		if err == nil {
			return vol, muted, nil
		}
		// If cached pactl failed, try wpctl
		volWp, mutedWp, errWp := s.getVolumeWpctl(ctx)
		if errWp == nil {
			s.setActiveBackend("wpctl")
			return volWp, mutedWp, nil
		}
		return 0, false, fmt.Errorf("audio get-volume failed (pactl: %v, wpctl: %w)", err, errWp)
	}

	// Default attempt: wpctl first
	volWp, mutedWp, errWp := s.getVolumeWpctl(ctx)
	if errWp == nil {
		s.setActiveBackend("wpctl")
		return volWp, mutedWp, nil
	}

	// Fallback attempt: pactl
	volPa, mutedPa, errPa := s.getVolumePactl(ctx)
	if errPa == nil {
		s.setActiveBackend("pactl")
		return volPa, mutedPa, nil
	}

	return 0, false, fmt.Errorf("audio get-volume failed (wpctl: %v, pactl: %w)", errWp, errPa)
}

func (s *DefaultSystemAudio) getVolumeWpctl(ctx context.Context) (float32, bool, error) {
	out, err := s.executor(ctx, "wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@")
	if err != nil {
		return 0, false, err
	}
	return parseWpctlOutput(string(out))
}

func (s *DefaultSystemAudio) getVolumePactl(ctx context.Context) (float32, bool, error) {
	volOut, err := s.executor(ctx, "pactl", "get-sink-volume", "@DEFAULT_SINK@")
	if err != nil {
		return 0, false, err
	}
	vol, err := parsePactlVolume(string(volOut))
	if err != nil {
		return 0, false, err
	}

	muteOut, err := s.executor(ctx, "pactl", "get-sink-mute", "@DEFAULT_SINK@")
	if err != nil {
		return vol, false, nil // Assume unmuted if mute query fails but volume succeeded
	}
	isMuted := parsePactlMute(string(muteOut))

	return vol, isMuted, nil
}

func (s *DefaultSystemAudio) runWithFallback(ctx context.Context, runWpctl func(context.Context) error, runPactl func(context.Context) error) error {
	s.mu.Lock()
	cached := s.activeBackend
	s.mu.Unlock()

	if cached == "pactl" {
		err := runPactl(ctx)
		if err == nil {
			return nil
		}
		// Try wpctl if pactl failed
		if errWp := runWpctl(ctx); errWp == nil {
			s.setActiveBackend("wpctl")
			return nil
		}
		return fmt.Errorf("pactl failed: %v; fallback wpctl also failed", err)
	}

	// Default: wpctl first
	errWp := runWpctl(ctx)
	if errWp == nil {
		s.setActiveBackend("wpctl")
		return nil
	}

	// Fallback to pactl
	errPa := runPactl(ctx)
	if errPa == nil {
		s.setActiveBackend("pactl")
		return nil
	}

	return fmt.Errorf("wpctl failed: %v; fallback pactl failed: %w", errWp, errPa)
}

func (s *DefaultSystemAudio) setActiveBackend(backend string) {
	s.mu.Lock()
	s.activeBackend = backend
	s.mu.Unlock()
}

func formatStepPercent(step float64, defaultPct int) int {
	if step <= 0 {
		return defaultPct
	}
	pct := int(step * 100)
	if pct <= 0 {
		return 1
	}
	return pct
}

func parseWpctlOutput(output string) (float32, bool, error) {
	trimmed := strings.TrimSpace(output)
	if trimmed == "" {
		return 0, false, errors.New("empty wpctl output")
	}

	isMuted := strings.Contains(trimmed, "[MUTED]")
	fields := strings.Fields(trimmed)
	if len(fields) < 2 {
		return 0, false, fmt.Errorf("unexpected wpctl output format: %q", output)
	}

	vol, err := strconv.ParseFloat(fields[1], 32)
	if err != nil {
		return 0, false, fmt.Errorf("parse wpctl volume %q: %w", fields[1], err)
	}

	return float32(vol), isMuted, nil
}

func parsePactlVolume(output string) (float32, error) {
	matches := pactlVolumeRegex.FindStringSubmatch(output)
	if len(matches) < 2 {
		return 0, fmt.Errorf("no volume percentage found in pactl output: %q", output)
	}
	pct, err := strconv.Atoi(matches[1])
	if err != nil {
		return 0, err
	}
	return float32(pct) / 100.0, nil
}

func parsePactlMute(output string) bool {
	return strings.Contains(strings.ToLower(output), "yes")
}

