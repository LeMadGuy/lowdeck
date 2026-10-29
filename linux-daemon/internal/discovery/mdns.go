package discovery

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"sync"

	"github.com/grandcat/zeroconf"
)

const (
	// ServiceType is the mDNS service type advertised on the LAN.
	ServiceType = "_linuxdeck._tcp"
	// ServiceDomain is the default mDNS domain.
	ServiceDomain = "local."
	// DefaultPort is the default control server port.
	DefaultPort = 8443
	// ProtocolVersion is the advertised protocol version in TXT records.
	ProtocolVersion = "1"
	// ShortFingerprintLen is the length of the truncated SHA-256 fingerprint in TXT records.
	ShortFingerprintLen = 16
)

// Config holds parameters for the mDNS service advertisement.
type Config struct {
	InstanceName      string
	Hostname          string
	Port              int
	ServerFingerprint string
	Logger            *slog.Logger
}

// Advertiser represents a startable/stoppable mDNS discovery advertiser.
type Advertiser interface {
	Start(ctx context.Context) error
	Shutdown()
	TXTRecords() []string
}

// MdnsAdvertiser announces the LinuxDeck daemon on the local network via Zeroconf/mDNS.
type MdnsAdvertiser struct {
	mu         sync.Mutex
	cfg        Config
	txtRecords []string
	server     *zeroconf.Server
	logger     *slog.Logger
}

// NewMdnsAdvertiser creates a new mDNS advertiser configured with version, hostname, and short fingerprint TXT records.
func NewMdnsAdvertiser(cfg Config) (*MdnsAdvertiser, error) {
	if cfg.Port <= 0 {
		cfg.Port = DefaultPort
	}
	if strings.TrimSpace(cfg.Hostname) == "" {
		h, err := os.Hostname()
		if err != nil || strings.TrimSpace(h) == "" {
			h = "linux-deck-host"
		}
		cfg.Hostname = h
	}
	if strings.TrimSpace(cfg.InstanceName) == "" {
		cfg.InstanceName = fmt.Sprintf("LinuxDeck-%s", cfg.Hostname)
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}

	txt := BuildTXTRecords(cfg.Hostname, cfg.ServerFingerprint)
	return &MdnsAdvertiser{
		cfg:        cfg,
		txtRecords: txt,
		logger:     logger,
	}, nil
}

// BuildTXTRecords constructs the required mDNS TXT records: version=1, hostname=<os.Hostname>, fp=<short_fp>.
func BuildTXTRecords(hostname, serverFingerprint string) []string {
	if strings.TrimSpace(hostname) == "" {
		h, err := os.Hostname()
		if err != nil || h == "" {
			h = "linux-deck-host"
		}
		hostname = h
	}
	fp := strings.ToLower(strings.TrimSpace(serverFingerprint))
	fp = strings.TrimPrefix(fp, "sha256:")
	fp = strings.ReplaceAll(fp, ":", "")
	if len(fp) > ShortFingerprintLen {
		fp = fp[:ShortFingerprintLen]
	}
	return []string{
		"version=" + ProtocolVersion,
		"hostname=" + hostname,
		"fp=" + fp,
	}
}

// TXTRecords returns a copy of the TXT records advertised by this instance.
func (m *MdnsAdvertiser) TXTRecords() []string {
	out := make([]string, len(m.txtRecords))
	copy(out, m.txtRecords)
	return out
}

// Start begins advertising _linuxdeck._tcp on the local network until ctx is cancelled or Shutdown is called.
// If multicast networking is unavailable (e.g., restricted container), it logs a warning and degrades gracefully.
func (m *MdnsAdvertiser) Start(ctx context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.server != nil {
		return nil
	}

	srv, err := zeroconf.Register(
		m.cfg.InstanceName,
		ServiceType,
		ServiceDomain,
		m.cfg.Port,
		m.txtRecords,
		nil,
	)
	if err != nil {
		m.logger.Warn("mdns advertisement unavailable; continuing in degraded mode",
			"service", ServiceType,
			"port", m.cfg.Port,
			"err", err,
		)
		return nil
	}

	m.server = srv
	m.logger.Info("mdns service advertised",
		"instance", m.cfg.InstanceName,
		"service", ServiceType,
		"port", m.cfg.Port,
		"txt", m.txtRecords,
	)

	go func() {
		<-ctx.Done()
		m.Shutdown()
	}()

	return nil
}

// Shutdown stops the mDNS responder and releases UDP sockets.
func (m *MdnsAdvertiser) Shutdown() {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.server != nil {
		m.server.Shutdown()
		m.server = nil
		m.logger.Info("mdns service stopped")
	}
}

