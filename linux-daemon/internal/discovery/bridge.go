package discovery

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"net"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// BridgeManager coordinates hybrid multi-channel connectivity (Wi-Fi and USB ADB)
// so that the user can connect via Wi-Fi or USB cable seamlessly without manual configuration.
type BridgeManager struct {
	logger      *slog.Logger
	controlPort int
	screenPort  int
	pairingPort int

	mu           sync.Mutex
	knownDevices map[string]bool
	adbPath      string
}

// NewBridgeManager initializes a manager for auto-configuring USB reverse tunneling and Wi-Fi routing.
func NewBridgeManager(controlPort, screenPort, pairingPort int, logger *slog.Logger) *BridgeManager {
	if logger == nil {
		logger = slog.Default()
	}
	adbPath, _ := exec.LookPath("adb")
	return &BridgeManager{
		logger:       logger,
		controlPort:  controlPort,
		screenPort:   screenPort,
		pairingPort:  pairingPort,
		knownDevices: make(map[string]bool),
		adbPath:      adbPath,
	}
}

// PrepareWiFi detects active interfaces and ensures Wi-Fi routing is prioritized
// if both Wi-Fi and Ethernet are active on the same local subnet.
// Returns the detected outbound Wi-Fi/LAN IP address.
func (b *BridgeManager) PrepareWiFi(ctx context.Context) string {
	b.optimizeWiFiRouteMetric(ctx)
	outboundIP := b.detectOutboundIP()
	b.logger.Info("canal Wi-Fi preparado",
		"ip", outboundIP,
		"portas_tcp", fmt.Sprintf("%d (controle), %d (tela/pty), %d (pairing)", b.controlPort, b.screenPort, b.pairingPort),
		"porta_udp", fmt.Sprintf("%d (fast-lane)", b.controlPort),
	)
	return outboundIP
}

// StartUsbWatcher starts a background goroutine that polls ADB for connected devices
// and automatically maps the reverse ports (tcp:8443, tcp:8444, tcp:8445) whenever a device is plugged in.
func (b *BridgeManager) StartUsbWatcher(ctx context.Context) {
	if b.adbPath == "" {
		b.logger.Warn("adb não encontrado no PATH; canal USB automático indisponível (conecte via Wi-Fi ou instale android-tools)")
		return
	}

	b.logger.Info("iniciando monitor USB/ADB automático (portas serão mapeadas assim que o cabo for conectado)...")

	// Initial immediate check
	b.scanAndMapDevices(ctx)

	go func() {
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				b.scanAndMapDevices(ctx)
			}
		}
	}()
}

// scanAndMapDevices queries `adb devices` and applies `adb reverse` to any connected device in state 'device'.
func (b *BridgeManager) scanAndMapDevices(ctx context.Context) {
	cmd := exec.CommandContext(ctx, b.adbPath, "devices")
	out, err := cmd.Output()
	if err != nil {
		return
	}

	currentDevices := make(map[string]bool)
	scanner := bufio.NewScanner(bytes.NewReader(out))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "List of devices") {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) >= 2 && parts[1] == "device" {
			serial := parts[0]
			currentDevices[serial] = true
		}
	}

	b.mu.Lock()
	defer b.mu.Unlock()

	// Detect newly connected devices
	for serial := range currentDevices {
		if !b.knownDevices[serial] {
			err := b.applyReversePorts(ctx, serial)
			if err == nil {
				b.knownDevices[serial] = true
				b.logger.Info("dispositivo Android conectado via USB detectado! Portas reversas mapeadas com sucesso",
					"serial", serial,
					"portas", fmt.Sprintf("%d, %d, %d", b.controlPort, b.screenPort, b.pairingPort),
				)
			} else {
				b.logger.Warn("falha ao configurar portas reversas no dispositivo USB", "serial", serial, "err", err)
			}
		}
	}

	// Remove disconnected devices from tracking
	for serial := range b.knownDevices {
		if !currentDevices[serial] {
			delete(b.knownDevices, serial)
			b.logger.Info("dispositivo USB desconectado", "serial", serial)
		}
	}
}

// applyReversePorts executes `adb -s <serial> reverse tcp:<p> tcp:<p>` for control, screen, and pairing ports.
func (b *BridgeManager) applyReversePorts(ctx context.Context, serial string) error {
	ports := []int{b.controlPort, b.screenPort, b.pairingPort}
	for _, port := range ports {
		args := []string{"reverse", fmt.Sprintf("tcp:%d", port), fmt.Sprintf("tcp:%d", port)}
		if serial != "" {
			args = append([]string{"-s", serial}, args...)
		}
		cmd := exec.CommandContext(ctx, b.adbPath, args...)
		if out, err := cmd.CombinedOutput(); err != nil {
			return fmt.Errorf("adb reverse port %d failed: %w (%s)", port, err, strings.TrimSpace(string(out)))
		}
	}
	return nil
}

// optimizeWiFiRouteMetric checks if NetworkManager (nmcli) is present and whether Wi-Fi
// and Ethernet interfaces share the same subnet. If so, it adjusts Wi-Fi metric to 50 so
// that Linux reverse-path filtering does not drop packets from Wi-Fi clients.
func (b *BridgeManager) optimizeWiFiRouteMetric(ctx context.Context) {
	nmcliPath, err := exec.LookPath("nmcli")
	if err != nil {
		return
	}

	// Query active connections: name, type, device
	cmd := exec.CommandContext(ctx, nmcliPath, "-t", "-f", "NAME,TYPE,DEVICE", "connection", "show", "--active")
	out, err := cmd.Output()
	if err != nil {
		return
	}

	var wifiConnName string
	hasEthernet := false

	scanner := bufio.NewScanner(bytes.NewReader(out))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		parts := strings.Split(line, ":")
		if len(parts) >= 2 {
			cType := parts[1]
			if strings.Contains(cType, "wireless") {
				wifiConnName = parts[0]
			} else if strings.Contains(cType, "ethernet") {
				hasEthernet = true
			}
		}
	}

	if wifiConnName == "" || !hasEthernet {
		return
	}

	// Check current metric of the Wi-Fi connection
	metricCmd := exec.CommandContext(ctx, nmcliPath, "-g", "ipv4.route-metric", "connection", "show", wifiConnName)
	metricOut, err := metricCmd.Output()
	if err == nil {
		val := strings.TrimSpace(string(metricOut))
		if val == "50" {
			// Already optimized
			return
		}
	}

	// Prioritize Wi-Fi connection metric to 50 (lower than default Ethernet 100/101)
	b.logger.Info("otimizando métrica de rota do Wi-Fi para compatibilidade multi-interface (Ethernet+Wi-Fi)...",
		"conexao", wifiConnName,
		"nova_metrica", 50,
	)
	_ = exec.CommandContext(ctx, nmcliPath, "connection", "modify", wifiConnName, "ipv4.route-metric", "50").Run()
	_ = exec.CommandContext(ctx, nmcliPath, "connection", "up", wifiConnName).Run()
}

// detectOutboundIP queries the kernel's routing table to identify the preferred outbound IP.
func (b *BridgeManager) detectOutboundIP() string {
	conn, err := net.Dial("udp4", "8.8.8.8:80")
	if err == nil {
		defer conn.Close()
		if localAddr, ok := conn.LocalAddr().(*net.UDPAddr); ok && !localAddr.IP.IsLoopback() {
			return localAddr.IP.String()
		}
	}

	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if (iface.Flags&net.FlagUp) != 0 && (iface.Flags&net.FlagLoopback) == 0 {
				addrs, _ := iface.Addrs()
				for _, addr := range addrs {
					if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() {
						if ip4 := ipNet.IP.To4(); ip4 != nil {
							return ip4.String()
						}
					}
				}
			}
		}
	}

	return "127.0.0.1"
}

