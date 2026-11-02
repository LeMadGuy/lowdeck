package dbus

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	godbus "github.com/godbus/dbus/v5"
	"github.com/local/linux-deck-daemon/internal/server"
	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

const (
	login1Dest             = "org.freedesktop.login1"
	login1ObjectPath       = "/org/freedesktop/login1"
	login1ManagerInterface = "org.freedesktop.login1.Manager"
)

// PowerController manages system session and power states via systemd-logind on the D-Bus SystemBus.
type PowerController struct {
	logger *slog.Logger
}

// NewPowerController creates a new logind D-Bus power controller.
func NewPowerController(logger *slog.Logger) *PowerController {
	if logger == nil {
		logger = slog.Default()
	}
	return &PowerController{logger: logger}
}

// Register binds the PowerControlCommand handler on router.
func (p *PowerController) Register(router *server.Router) {
	if router == nil {
		return
	}
	router.Register(server.KindPowerControl, p.HandlePowerControl)
}

// HandlePowerControl executes LockSessions, UnlockSessions, Suspend, Reboot, or PowerOff on systemd-logind.
func (p *PowerController) HandlePowerControl(ctx context.Context, _ server.ClientSession, env *pb.Envelope) error {
	cmd := env.GetPowerControlCommand()
	if cmd == nil {
		return errors.New("dbus logind: nil PowerControlCommand")
	}
	if err := p.ExecuteAction(ctx, cmd.Action); err != nil {
		p.logger.Warn("logind power control action failed",
			"action", cmd.Action.String(),
			"err", err,
		)
		return err
	}
	return nil
}

// ExecuteAction invokes the corresponding org.freedesktop.login1.Manager method on the SystemBus.
func (p *PowerController) ExecuteAction(ctx context.Context, action pb.PowerAction) error {
	conn, err := godbus.ConnectSystemBus()
	if err != nil {
		return fmt.Errorf("dbus system bus connect: %w", err)
	}
	defer conn.Close()

	obj := conn.Object(login1Dest, godbus.ObjectPath(login1ObjectPath))

	switch action {
	case pb.PowerAction_LOCK:
		if call := obj.CallWithContext(ctx, login1ManagerInterface+".LockSessions", 0); call.Err != nil {
			return fmt.Errorf("dbus logind LockSessions: %w", call.Err)
		}
		return nil

	case pb.PowerAction_UNLOCK:
		if call := obj.CallWithContext(ctx, login1ManagerInterface+".UnlockSessions", 0); call.Err != nil {
			return fmt.Errorf("dbus logind UnlockSessions: %w", call.Err)
		}
		return nil

	case pb.PowerAction_SUSPEND:
		if call := obj.CallWithContext(ctx, login1ManagerInterface+".Suspend", 0, true); call.Err != nil {
			return fmt.Errorf("dbus logind Suspend: %w", call.Err)
		}
		return nil

	case pb.PowerAction_REBOOT:
		if call := obj.CallWithContext(ctx, login1ManagerInterface+".Reboot", 0, true); call.Err != nil {
			return fmt.Errorf("dbus logind Reboot: %w", call.Err)
		}
		return nil

	case pb.PowerAction_POWEROFF:
		if call := obj.CallWithContext(ctx, login1ManagerInterface+".PowerOff", 0, true); call.Err != nil {
			return fmt.Errorf("dbus logind PowerOff: %w", call.Err)
		}
		return nil

	default:
		return fmt.Errorf("dbus logind: unsupported power action %v", action)
	}
}

