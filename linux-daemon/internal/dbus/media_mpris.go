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

// MediaController interacts with MPRIS2 media players over the D-Bus SessionBus.
type MediaController struct {
	logger *slog.Logger
}

// NewMediaController creates a new MPRIS2 D-Bus media controller.
func NewMediaController(logger *slog.Logger) *MediaController {
	if logger == nil {
		logger = slog.Default()
	}
	return &MediaController{logger: logger}
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

// GetStatus queries Metadata and PlaybackStatus from the specified (or first active) MPRIS2 media player.
func (m *MediaController) GetStatus(ctx context.Context, preferredPlayer string) (*pb.MediaStatusEvent, error) {
	conn, err := connectSessionBus()
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	dest, err := selectPlayerDest(ctx, conn, preferredPlayer)
	if err != nil {
		return nil, err
	}

	return readPlayerStatus(ctx, conn, dest)
}

// ExecuteCommand dispatches PlayPause, Play, Pause, Next, Previous, Stop, or Volume commands via MPRIS2.
func (m *MediaController) ExecuteCommand(ctx context.Context, cmd *pb.MediaControlCommand) (*pb.MediaStatusEvent, error) {
	if cmd == nil {
		return nil, errors.New("dbus mpris: nil MediaControlCommand")
	}

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
	case pb.MediaCommandType_VOLUME_UP:
		return m.adjustVolume(ctx, conn, obj, dest, +0.05)
	case pb.MediaCommandType_VOLUME_DOWN:
		return m.adjustVolume(ctx, conn, obj, dest, -0.05)
	case pb.MediaCommandType_MUTE:
		return m.setVolume(ctx, conn, obj, dest, 0.0)
	default:
		return nil, fmt.Errorf("dbus mpris: unsupported media command %v", cmd.Command)
	}

	if call := obj.CallWithContext(ctx, method, 0); call.Err != nil {
		return nil, fmt.Errorf("dbus mpris call %s on %s: %w", method, dest, call.Err)
	}

	status, err := readPlayerStatus(ctx, conn, dest)
	if err != nil {
		return &pb.MediaStatusEvent{Player: strings.TrimPrefix(dest, mprisPrefix)}, nil
	}
	return status, nil
}

func (m *MediaController) adjustVolume(ctx context.Context, conn *godbus.Conn, obj godbus.BusObject, dest string, delta float64) (*pb.MediaStatusEvent, error) {
	curStatus, _ := readPlayerStatus(ctx, conn, dest)
	curVol := 0.5
	if curStatus != nil && curStatus.Volume > 0 {
		curVol = float64(curStatus.Volume)
	}
	nextVol := curVol + delta
	if nextVol < 0 {
		nextVol = 0
	}
	if nextVol > 1.0 {
		nextVol = 1.0
	}
	return m.setVolume(ctx, conn, obj, dest, nextVol)
}

func (m *MediaController) setVolume(ctx context.Context, conn *godbus.Conn, obj godbus.BusObject, dest string, vol float64) (*pb.MediaStatusEvent, error) {
	call := obj.CallWithContext(ctx, dbusPropsSetMethod, 0, mprisPlayerInterface, "Volume", godbus.MakeVariant(vol))
	if call.Err != nil {
		return nil, fmt.Errorf("dbus mpris set Volume on %s: %w", dest, call.Err)
	}
	return readPlayerStatus(ctx, conn, dest)
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

