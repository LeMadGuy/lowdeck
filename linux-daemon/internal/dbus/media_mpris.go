package dbus

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	godbus "github.com/godbus/dbus/v5"
	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

const (
	dbusDest               = "org.freedesktop.DBus"
	dbusObjectPath         = "/org/freedesktop/DBus"
	dbusListNamesMethod    = "org.freedesktop.DBus.ListNames"
	dbusPropsGetMethod     = "org.freedesktop.DBus.Properties.Get"
	dbusPropsSetMethod     = "org.freedesktop.DBus.Properties.Set"
	mprisPrefix            = "org.mpris.MediaPlayer2."
	mprisObjectPath        = "/org/mpris/MediaPlayer2"
	mprisPlayerInterface   = "org.mpris.MediaPlayer2.Player"
)

// ErrNoMediaPlayers is returned when no active org.mpris.MediaPlayer2.* destination exists on the SessionBus.
var ErrNoMediaPlayers = errors.New("dbus mpris: no active MPRIS2 media players found")

// MediaController interacts with MPRIS2 media players over the D-Bus SessionBus
// and delegates system audio volume/mute controls to SystemAudio (wpctl / pactl).
type MediaController struct {
	logger *slog.Logger
	audio  SystemAudio
}

// NewMediaController creates a new MPRIS2 D-Bus media controller with default system audio control.
func NewMediaController(logger *slog.Logger) *MediaController {
	if logger == nil {
		logger = slog.Default()
	}
	return &MediaController{
		logger: logger,
		audio:  NewSystemAudio(logger),
	}
}

// NewMediaControllerWithAudio creates a new MediaController with a custom SystemAudio provider (useful for testing).
func NewMediaControllerWithAudio(logger *slog.Logger, audio SystemAudio) *MediaController {
	if logger == nil {
		logger = slog.Default()
	}
	if audio == nil {
		audio = NewSystemAudio(logger)
	}
	return &MediaController{
		logger: logger,
		audio:  audio,
	}
}

// Register binds the MediaControlCommand handler on router.
func (m *MediaController) Register(router *server.Router) {
	if router == nil {
		return
	}
	router.Register(server.KindMediaControl, m.HandleMediaControl)
}

// HandleMediaControl executes an MPRIS2 media command and sends the updated MediaStatusEvent back to the client.
func (m *MediaController) HandleMediaControl(ctx context.Context, client server.ClientSession, env *pb.Envelope) error {
	cmd := env.GetMediaControlCommand()
	if cmd == nil {
		return errors.New("dbus mpris: nil MediaControlCommand")
	}

	status, err := m.ExecuteCommand(ctx, cmd)
	if err != nil {
		m.logger.Warn("mpris media control command failed",
			"command", cmd.Command.String(),
			"player", cmd.Player,
			"err", err,
		)
		return err
	}

	if client != nil && status != nil {
		_ = client.Send(&pb.Envelope{
			RequestId:   env.RequestId,
			TimestampMs: time.Now().UnixMilli(),
			Payload: &pb.Envelope_MediaStatusEvent{
				MediaStatusEvent: status,
			},
		})
	}
	return nil
}

// ListPlayers returns all active org.mpris.MediaPlayer2.* bus names on the user's SessionBus.
func (m *MediaController) ListPlayers(ctx context.Context) ([]string, error) {
	conn, err := connectSessionBus()
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	return listPlayersOnConn(ctx, conn)
}

// GetStatus queries Metadata and PlaybackStatus from the specified (or first active) MPRIS2 media player,
// enriched with current system audio volume and mute state.
func (m *MediaController) GetStatus(ctx context.Context, preferredPlayer string) (*pb.MediaStatusEvent, error) {
	var status *pb.MediaStatusEvent
	conn, err := connectSessionBus()
	if err == nil {
		defer conn.Close()
		if dest, err := selectPlayerDest(ctx, conn, preferredPlayer); err == nil {
			status, _ = readPlayerStatus(ctx, conn, dest)
		}
	}

	if status == nil {
		status = &pb.MediaStatusEvent{}
	}

	if m.audio != nil {
		if vol, isMuted, aErr := m.audio.GetVolume(ctx); aErr == nil {
			if isMuted {
				status.Volume = 0.0
			} else {
				status.Volume = vol
			}
		}
	}

	return status, nil
}

// ExecuteCommand dispatches VolumeUp, VolumeDown, Mute via system audio (wpctl with pactl fallback),
// and PlayPause, Play, Pause, Next, Previous, Stop via MPRIS2 over D-Bus.
func (m *MediaController) ExecuteCommand(ctx context.Context, cmd *pb.MediaControlCommand) (*pb.MediaStatusEvent, error) {
	if cmd == nil {
		return nil, errors.New("dbus mpris: nil MediaControlCommand")
	}

	switch cmd.Command {
	case pb.MediaCommandType_VOLUME_UP:
		if m.audio != nil {
			if err := m.audio.VolumeUp(ctx, 0.05); err != nil {
				return nil, fmt.Errorf("system audio volume up: %w", err)
			}
		}
		return m.GetStatus(ctx, cmd.Player)

	case pb.MediaCommandType_VOLUME_DOWN:
		if m.audio != nil {
			if err := m.audio.VolumeDown(ctx, 0.05); err != nil {
				return nil, fmt.Errorf("system audio volume down: %w", err)
			}
		}
		return m.GetStatus(ctx, cmd.Player)

	case pb.MediaCommandType_MUTE:
		if m.audio != nil {
			if err := m.audio.ToggleMute(ctx); err != nil {
				return nil, fmt.Errorf("system audio toggle mute: %w", err)
			}
		}
		return m.GetStatus(ctx, cmd.Player)

	case pb.MediaCommandType_PLAY_PAUSE,
		pb.MediaCommandType_PLAY,
		pb.MediaCommandType_PAUSE,
		pb.MediaCommandType_NEXT,
		pb.MediaCommandType_PREVIOUS,
		pb.MediaCommandType_STOP:
		return m.executeMprisTransportCommand(ctx, cmd)

	default:
		if cmd.Volume > 0 && m.audio != nil {
			if err := m.audio.SetVolume(ctx, float64(cmd.Volume)); err != nil {
				return nil, fmt.Errorf("system audio set volume: %w", err)
			}
			return m.GetStatus(ctx, cmd.Player)
		}
		return nil, fmt.Errorf("dbus mpris: unsupported media command %v", cmd.Command)
	}
}

func (m *MediaController) executeMprisTransportCommand(ctx context.Context, cmd *pb.MediaControlCommand) (*pb.MediaStatusEvent, error) {
	conn, err := connectSessionBus()
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	dest, err := selectPlayerDest(ctx, conn, cmd.Player)
	if err != nil {
		return nil, err
	}

	obj := conn.Object(dest, godbus.ObjectPath(mprisObjectPath))

	var method string
	switch cmd.Command {
	case pb.MediaCommandType_PLAY_PAUSE:
		method = mprisPlayerInterface + ".PlayPause"
	case pb.MediaCommandType_PLAY:
		method = mprisPlayerInterface + ".Play"
	case pb.MediaCommandType_PAUSE:
		method = mprisPlayerInterface + ".Pause"
	case pb.MediaCommandType_NEXT:
		method = mprisPlayerInterface + ".Next"
	case pb.MediaCommandType_PREVIOUS:
		method = mprisPlayerInterface + ".Previous"
	case pb.MediaCommandType_STOP:
		method = mprisPlayerInterface + ".Stop"
	}

	if call := obj.CallWithContext(ctx, method, 0); call.Err != nil {
		return nil, fmt.Errorf("dbus mpris call %s on %s: %w", method, dest, call.Err)
	}

	return m.GetStatus(ctx, dest)
}

func connectSessionBus() (*godbus.Conn, error) {
	conn, err := godbus.ConnectSessionBus()
	if err != nil {
		return nil, fmt.Errorf("dbus session bus connect: %w", err)
	}
	return conn, nil
}

func listPlayersOnConn(ctx context.Context, conn *godbus.Conn) ([]string, error) {
	var names []string
	err := conn.BusObject().CallWithContext(ctx, dbusListNamesMethod, 0).Store(&names)
	if err != nil {
		return nil, fmt.Errorf("dbus list names: %w", err)
	}

	var players []string
	for _, n := range names {
		if strings.HasPrefix(n, mprisPrefix) {
			players = append(players, n)
		}
	}
	return players, nil
}

func selectPlayerDest(ctx context.Context, conn *godbus.Conn, preferred string) (string, error) {
	players, err := listPlayersOnConn(ctx, conn)
	if err != nil {
		return "", err
	}
	if len(players) == 0 {
		return "", ErrNoMediaPlayers
	}

	pref := strings.TrimSpace(preferred)
	if pref != "" {
		if !strings.HasPrefix(pref, mprisPrefix) {
			pref = mprisPrefix + pref
		}
		for _, p := range players {
			if strings.EqualFold(p, pref) {
				return p, nil
			}
		}
	}
	return players[0], nil
}

func readPlayerStatus(ctx context.Context, conn *godbus.Conn, dest string) (*pb.MediaStatusEvent, error) {
	obj := conn.Object(dest, godbus.ObjectPath(mprisObjectPath))

	statusEvent := &pb.MediaStatusEvent{
		Player: strings.TrimPrefix(dest, mprisPrefix),
	}

	// Read PlaybackStatus
	var playbackVar godbus.Variant
	if err := obj.CallWithContext(ctx, dbusPropsGetMethod, 0, mprisPlayerInterface, "PlaybackStatus").Store(&playbackVar); err == nil {
		if statusStr, ok := playbackVar.Value().(string); ok {
			statusEvent.IsPlaying = strings.EqualFold(statusStr, "Playing")
		}
	}

	// Read Metadata
	var metaVar godbus.Variant
	if err := obj.CallWithContext(ctx, dbusPropsGetMethod, 0, mprisPlayerInterface, "Metadata").Store(&metaVar); err == nil {
		if metaMap, ok := metaVar.Value().(map[string]godbus.Variant); ok {
			if v, exists := metaMap["xesam:title"]; exists {
				if title, ok := v.Value().(string); ok {
					statusEvent.Title = title
				}
			}
			if v, exists := metaMap["xesam:album"]; exists {
				if album, ok := v.Value().(string); ok {
					statusEvent.Album = album
				}
			}
			if v, exists := metaMap["xesam:artist"]; exists {
				switch artistVal := v.Value().(type) {
				case []string:
					statusEvent.Artist = strings.Join(artistVal, ", ")
				case string:
					statusEvent.Artist = artistVal
				}
			}
			if v, exists := metaMap["mpris:length"]; exists {
				switch lengthVal := v.Value().(type) {
				case int64:
					statusEvent.DurationMs = lengthVal / 1000
				case uint64:
					statusEvent.DurationMs = int64(lengthVal / 1000)
				}
			}
		}
	}

	// Read Volume
	var volVar godbus.Variant
	if err := obj.CallWithContext(ctx, dbusPropsGetMethod, 0, mprisPlayerInterface, "Volume").Store(&volVar); err == nil {
		if vol, ok := volVar.Value().(float64); ok {
			statusEvent.Volume = float32(vol)
		}
	}

	// Read Position (microseconds -> milliseconds)
	var posVar godbus.Variant
	if err := obj.CallWithContext(ctx, dbusPropsGetMethod, 0, mprisPlayerInterface, "Position").Store(&posVar); err == nil {
		if posUs, ok := posVar.Value().(int64); ok {
			statusEvent.PositionMs = posUs / 1000
		}
	}

	return statusEvent, nil
}

