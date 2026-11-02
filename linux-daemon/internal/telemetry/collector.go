package telemetry

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

const (
	// DefaultInterval is the default telemetry emission period (1000ms).
	DefaultInterval = 1000 * time.Millisecond
)

type cpuTick struct {
	idle  uint64
	total uint64
}

type netTick struct {
	rxBytes uint64
	txBytes uint64
	takenAt time.Time
}

// MediaStatusProvider is an optional callback used to attach MPRIS2 metadata to TelemetrySnapshots.
type MediaStatusProvider func(ctx context.Context) (*pb.MediaStatusEvent, error)

// Collector reads CPU, RAM, temperature, and network metrics directly from /proc and /sys.
type Collector struct {
	mu            sync.Mutex
	procRoot      string
	sysRoot       string
	interval      time.Duration
	prevCPU       *cpuTick
	prevNet       *netTick
	mediaProvider MediaStatusProvider
	logger        *slog.Logger
}

// Config configures paths and intervals for the telemetry Collector.
type Config struct {
	ProcRoot      string
	SysRoot       string
	Interval      time.Duration
	MediaProvider MediaStatusProvider
	Logger        *slog.Logger
}

// NewCollector creates a new Linux system telemetry collector.
func NewCollector(cfg Config) *Collector {
	if cfg.ProcRoot == "" {
		cfg.ProcRoot = "/proc"
	}
	if cfg.SysRoot == "" {
		cfg.SysRoot = "/sys"
	}
	if cfg.Interval <= 0 {
		cfg.Interval = DefaultInterval
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}
	return &Collector{
		procRoot:      cfg.ProcRoot,
		sysRoot:       cfg.SysRoot,
		interval:      cfg.Interval,
		mediaProvider: cfg.MediaProvider,
		logger:        logger,
	}
}

// Collect gathers a point-in-time TelemetrySnapshot from /proc/stat, /proc/meminfo, /sys, and /proc/net/dev.
func (c *Collector) Collect(ctx context.Context) (*pb.TelemetrySnapshot, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	cpuPercent, err := c.collectCPULocked(ctx)
	if err != nil {
		return nil, fmt.Errorf("telemetry collect cpu: %w", err)
	}

	ramUsedMB, ramTotalMB, err := c.collectMemory()
	if err != nil {
		return nil, fmt.Errorf("telemetry collect memory: %w", err)
	}

	temps := c.collectTemperatures()

	rxKbps, txKbps, err := c.collectNetworkLocked()
	if err != nil {
		return nil, fmt.Errorf("telemetry collect network: %w", err)
	}

	snap := &pb.TelemetrySnapshot{
		CpuPercent:   cpuPercent,
		RamUsedMb:    ramUsedMB,
		RamTotalMb:   ramTotalMB,
		Temperatures: temps,
		RxKbps:       rxKbps,
		TxKbps:       txKbps,
	}

	if c.mediaProvider != nil {
		if mediaStatus, mErr := c.mediaProvider(ctx); mErr == nil && mediaStatus != nil {
			snap.MediaStatus = mediaStatus
		}
	}

	return snap, nil
}

// Start runs a background loop that emits TelemetrySnapshot envelopes every interval
// ONLY when clientCountFn() > 0 (at least one authenticated client connected).
func (c *Collector) Start(ctx context.Context, clientCountFn func() int, broadcastFn func(*pb.Envelope)) {
	// Prime initial CPU and network counters so the first broadcast has a valid baseline.
	c.mu.Lock()
	if tick, err := readCPUTick(filepath.Join(c.procRoot, "stat")); err == nil {
		c.prevCPU = &tick
	}
	if nTick, err := readNetTick(filepath.Join(c.procRoot, "net", "dev")); err == nil {
		c.prevNet = &nTick
	}
	c.mu.Unlock()

	go func() {
		ticker := time.NewTicker(c.interval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if clientCountFn == nil || broadcastFn == nil || clientCountFn() <= 0 {
					continue
				}
				snap, err := c.Collect(ctx)
				if err != nil {
					c.logger.Warn("failed to collect telemetry snapshot", "err", err)
					continue
				}
				nowMs := time.Now().UnixMilli()
				broadcastFn(&pb.Envelope{
					RequestId:   fmt.Sprintf("telemetry-%d", nowMs),
					TimestampMs: nowMs,
					Payload: &pb.Envelope_TelemetrySnapshot{
						TelemetrySnapshot: snap,
					},
				})
			}
		}
	}()
}

func (c *Collector) collectCPULocked(ctx context.Context) (float32, error) {
	statPath := filepath.Join(c.procRoot, "stat")
	curr, err := readCPUTick(statPath)
	if err != nil {
		return 0, err
	}

	if c.prevCPU == nil {
		c.prevCPU = &curr
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		case <-time.After(25 * time.Millisecond):
		}
		curr2, err := readCPUTick(statPath)
		if err != nil {
			return 0, err
		}
		curr = curr2
	}

	deltaTotal := curr.total - c.prevCPU.total
	deltaIdle := curr.idle - c.prevCPU.idle
	c.prevCPU = &curr

	if deltaTotal == 0 || deltaIdle >= deltaTotal {
		return 0, nil
	}

	usage := (1.0 - float64(deltaIdle)/float64(deltaTotal)) * 100.0
	if usage < 0 {
		usage = 0
	}
	if usage > 100 {
		usage = 100
	}
	return float32(usage), nil
}

func readCPUTick(statPath string) (cpuTick, error) {
	data, err := os.ReadFile(statPath)
	if err != nil {
		return cpuTick{}, err
	}

	scanner := bufio.NewScanner(bytes.NewReader(data))
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "cpu ") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 5 {
			return cpuTick{}, fmt.Errorf("malformed cpu line in %s", statPath)
		}

		var values []uint64
		for _, f := range fields[1:] {
			v, parseErr := strconv.ParseUint(f, 10, 64)
			if parseErr != nil {
				continue
			}
			values = append(values, v)
		}
		if len(values) < 4 {
			return cpuTick{}, fmt.Errorf("insufficient cpu counters in %s", statPath)
		}

		var total uint64
		for _, v := range values {
			total += v
		}
		idle := values[3]
		if len(values) > 4 {
			idle += values[4] // include iowait
		}
		return cpuTick{idle: idle, total: total}, nil
	}

	return cpuTick{}, fmt.Errorf("cpu line not found in %s", statPath)
}

func (c *Collector) collectMemory() (usedMB uint64, totalMB uint64, err error) {
	memPath := filepath.Join(c.procRoot, "meminfo")
	data, err := os.ReadFile(memPath)
	if err != nil {
		return 0, 0, err
	}

	var (
		memTotalKB     uint64
		memAvailableKB uint64
		hasAvailable   bool
		memFreeKB      uint64
		buffersKB      uint64
		cachedKB       uint64
	)

	scanner := bufio.NewScanner(bytes.NewReader(data))
	for scanner.Scan() {
		line := scanner.Text()
		parts := strings.Fields(line)
		if len(parts) < 2 {
			continue
		}
		key := strings.TrimSuffix(parts[0], ":")
		val, parseErr := strconv.ParseUint(parts[1], 10, 64)
		if parseErr != nil {
			continue
		}
		switch key {
		case "MemTotal":
			memTotalKB = val
		case "MemAvailable":
			memAvailableKB = val
			hasAvailable = true
		case "MemFree":
			memFreeKB = val
		case "Buffers":
			buffersKB = val
		case "Cached":
			cachedKB = val
		}
	}

	if memTotalKB == 0 {
		return 0, 0, fmt.Errorf("MemTotal not found in %s", memPath)
	}
	if !hasAvailable {
		memAvailableKB = memFreeKB + buffersKB + cachedKB
	}

	var usedKB uint64
	if memTotalKB > memAvailableKB {
		usedKB = memTotalKB - memAvailableKB
	}

	totalMB = memTotalKB / 1024
	if totalMB == 0 && memTotalKB > 0 {
		totalMB = 1
	}
	usedMB = usedKB / 1024
	return usedMB, totalMB, nil
}

func (c *Collector) collectTemperatures() map[string]float32 {
	temps := make(map[string]float32)

	// 1. Check /sys/class/hwmon/hwmon*/temp*_input
	hwmonPattern := filepath.Join(c.sysRoot, "class", "hwmon", "hwmon*", "temp*_input")
	if matches, err := filepath.Glob(hwmonPattern); err == nil {
		for _, inputPath := range matches {
			tempC, ok := readMillidegreeFile(inputPath)
			if !ok {
				continue
			}
			hwDir := filepath.Dir(inputPath)
			base := filepath.Base(inputPath) // e.g. temp1_input
			prefix := strings.TrimSuffix(base, "_input")

			chipName := readTrimmedFile(filepath.Join(hwDir, "name"))
			label := readTrimmedFile(filepath.Join(hwDir, prefix+"_label"))

			key := chipName
			if key == "" {
				key = filepath.Base(hwDir)
			}
			if label != "" {
				key = key + "_" + label
			} else {
				key = key + "_" + prefix
			}
			temps[key] = tempC
		}
	}

	// 2. Check /sys/class/thermal/thermal_zone*/temp
	thermalPattern := filepath.Join(c.sysRoot, "class", "thermal", "thermal_zone*", "temp")
	if matches, err := filepath.Glob(thermalPattern); err == nil {
		for _, tempPath := range matches {
			tempC, ok := readMillidegreeFile(tempPath)
			if !ok {
				continue
			}
			zoneDir := filepath.Dir(tempPath)
			zoneType := readTrimmedFile(filepath.Join(zoneDir, "type"))
			key := filepath.Base(zoneDir)
			if zoneType != "" {
				key = zoneType
			}
			if _, exists := temps[key]; !exists {
				temps[key] = tempC
			}
		}
	}

	return temps
}

func (c *Collector) collectNetworkLocked() (rxKbps float32, txKbps float32, err error) {
	netPath := filepath.Join(c.procRoot, "net", "dev")
	curr, err := readNetTick(netPath)
	if err != nil {
		return 0, 0, err
	}

	if c.prevNet == nil {
		c.prevNet = &curr
		return 0, 0, nil
	}

	dt := curr.takenAt.Sub(c.prevNet.takenAt).Seconds()
	var deltaRx, deltaTx uint64
	if curr.rxBytes >= c.prevNet.rxBytes {
		deltaRx = curr.rxBytes - c.prevNet.rxBytes
	}
	if curr.txBytes >= c.prevNet.txBytes {
		deltaTx = curr.txBytes - c.prevNet.txBytes
	}
	c.prevNet = &curr

	if dt <= 0 {
		return 0, 0, nil
	}

	// Convert byte delta per second to kilobits per second (kbps = bytes * 8 / 1000).
	rxKbps = float32((float64(deltaRx) * 8.0 / 1000.0) / dt)
	txKbps = float32((float64(deltaTx) * 8.0 / 1000.0) / dt)
	return rxKbps, txKbps, nil
}

func readNetTick(netPath string) (netTick, error) {
	data, err := os.ReadFile(netPath)
	if err != nil {
		return netTick{}, err
	}

	var totalRx, totalTx uint64
	scanner := bufio.NewScanner(bytes.NewReader(data))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		colonIdx := strings.IndexByte(line, ':')
		if colonIdx < 0 {
			continue
		}
		iface := strings.TrimSpace(line[:colonIdx])
		if iface == "" || iface == "lo" {
			continue
		}
		fields := strings.Fields(line[colonIdx+1:])
		if len(fields) < 9 {
			continue
		}
		rx, err1 := strconv.ParseUint(fields[0], 10, 64)
		tx, err2 := strconv.ParseUint(fields[8], 10, 64)
		if err1 == nil {
			totalRx += rx
		}
		if err2 == nil {
			totalTx += tx
		}
	}

	return netTick{
		rxBytes: totalRx,
		txBytes: totalTx,
		takenAt: time.Now(),
	}, nil
}

func readMillidegreeFile(path string) (float32, bool) {
	raw := readTrimmedFile(path)
	if raw == "" {
		return 0, false
	}
	val, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return 0, false
	}
	if val > 250 || val < -250 {
		val /= 1000.0
	}
	if val <= -100 || val >= 200 {
		return 0, false
	}
	return float32(val), true
}

func readTrimmedFile(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

