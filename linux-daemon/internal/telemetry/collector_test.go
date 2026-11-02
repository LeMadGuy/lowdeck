package telemetry

import (
	"context"
	"math"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	pb "github.com/local/linux-deck-daemon/pkg/protocol/v1"
)

func TestCollectorRealHostMetrics(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	collector := NewCollector(Config{
		Interval: 100 * time.Millisecond,
	})

	snap1, err := collector.Collect(ctx)
	if err != nil {
		t.Fatalf("first Collect() failed: %v", err)
	}
	time.Sleep(50 * time.Millisecond)
	snap2, err := collector.Collect(ctx)
	if err != nil {
		t.Fatalf("second Collect() failed: %v", err)
	}

	for i, snap := range []*pb.TelemetrySnapshot{snap1, snap2} {
		if snap.CpuPercent < 0.0 || snap.CpuPercent > 100.0 || math.IsNaN(float64(snap.CpuPercent)) {
			t.Errorf("snap[%d] cpu_percent = %f, want in [0, 100]", i, snap.CpuPercent)
		}
		if snap.RamTotalMb == 0 {
			t.Errorf("snap[%d] ram_total_mb = 0, want > 0", i)
		}
		if snap.RamUsedMb > snap.RamTotalMb {
			t.Errorf("snap[%d] ram_used_mb (%d) > ram_total_mb (%d)", i, snap.RamUsedMb, snap.RamTotalMb)
		}
		if snap.RxKbps < 0.0 || math.IsNaN(float64(snap.RxKbps)) {
			t.Errorf("snap[%d] rx_kbps = %f, want >= 0", i, snap.RxKbps)
		}
		if snap.TxKbps < 0.0 || math.IsNaN(float64(snap.TxKbps)) {
			t.Errorf("snap[%d] tx_kbps = %f, want >= 0", i, snap.TxKbps)
		}
	}
}

func TestCollectorSyntheticProcAndSysAndBroadcastGating(t *testing.T) {
	t.Parallel()

	tmpDir := t.TempDir()
	procDir := filepath.Join(tmpDir, "proc")
	sysDir := filepath.Join(tmpDir, "sys")

	if err := os.MkdirAll(filepath.Join(procDir, "net"), 0o755); err != nil {
		t.Fatalf("mkdir proc/net: %v", err)
	}
	hwmonDir := filepath.Join(sysDir, "class", "hwmon", "hwmon0")
	if err := os.MkdirAll(hwmonDir, 0o755); err != nil {
		t.Fatalf("mkdir hwmon0: %v", err)
	}

	// Initial /proc/stat: total = 1000, idle+iowait = 800
	_ = os.WriteFile(filepath.Join(procDir, "stat"), []byte("cpu  100 0 100 750 50 0 0 0 0 0\n"), 0o644)
	// /proc/meminfo: MemTotal = 16384000 kB (16000 MB), MemAvailable = 6144000 kB (6000 MB) -> used = 10000 MB
	_ = os.WriteFile(filepath.Join(procDir, "meminfo"), []byte("MemTotal:       16384000 kB\nMemFree:         2048000 kB\nMemAvailable:    6144000 kB\n"), 0o644)
	// /proc/net/dev
	_ = os.WriteFile(filepath.Join(procDir, "net", "dev"), []byte(`Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 9999999       0    0    0    0     0          0         0  9999999       0    0    0    0     0       0          0
 wlan0:  100000      10    0    0    0     0          0         0    50000      10    0    0    0     0       0          0
`), 0o644)

	// /sys/class/hwmon/hwmon0: k10temp Tctl = 54500 (54.5°C)
	_ = os.WriteFile(filepath.Join(hwmonDir, "name"), []byte("k10temp\n"), 0o644)
	_ = os.WriteFile(filepath.Join(hwmonDir, "temp1_label"), []byte("Tctl\n"), 0o644)
	_ = os.WriteFile(filepath.Join(hwmonDir, "temp1_input"), []byte("54500\n"), 0o644)

	collector := NewCollector(Config{
		ProcRoot: procDir,
		SysRoot:  sysDir,
		Interval: 30 * time.Millisecond,
	})

	// Prime first tick.
	tick1, err := readCPUTick(filepath.Join(procDir, "stat"))
	if err != nil {
		t.Fatalf("readCPUTick: %v", err)
	}
	collector.prevCPU = &tick1
	net1, err := readNetTick(filepath.Join(procDir, "net", "dev"))
	if err != nil {
		t.Fatalf("readNetTick: %v", err)
	}
	collector.prevNet = &net1

	// Advance /proc/stat by deltaTotal = 200, deltaIdle = 50 -> CPU = (1 - 50/200) * 100 = 75%
	_ = os.WriteFile(filepath.Join(procDir, "stat"), []byte("cpu  200 0 150 790 60 0 0 0 0 0\n"), 0o644)
	time.Sleep(20 * time.Millisecond)
	_ = os.WriteFile(filepath.Join(procDir, "net", "dev"), []byte(`Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 9999999       0    0    0    0     0          0         0  9999999       0    0    0    0     0       0          0
 wlan0:  125000      20    0    0    0     0          0         0    62500      20    0    0    0     0       0          0
`), 0o644)

	snap, err := collector.Collect(context.Background())
	if err != nil {
		t.Fatalf("Collect() failed: %v", err)
	}

	if math.Abs(float64(snap.CpuPercent)-75.0) > 0.1 {
		t.Errorf("CpuPercent = %.2f, want 75.0", snap.CpuPercent)
	}
	if snap.RamTotalMb != 16000 || snap.RamUsedMb != 10000 {
		t.Errorf("RAM = %d / %d MB, want 10000 / 16000 MB", snap.RamUsedMb, snap.RamTotalMb)
	}
	if gotTemp, ok := snap.Temperatures["k10temp_Tctl"]; !ok || math.Abs(float64(gotTemp)-54.5) > 0.1 {
		t.Errorf("Temperatures[k10temp_Tctl] = %v (ok=%v), want 54.5", gotTemp, ok)
	}
	if snap.RxKbps <= 0 || snap.TxKbps <= 0 {
		t.Errorf("expected positive network rates, got rx=%f tx=%f", snap.RxKbps, snap.TxKbps)
	}

	// Verify broadcast gating: no emissions when clientCount == 0, emissions resume when clientCount > 0.
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var connectedClients atomic.Int32
	var emittedCount atomic.Int32

	collector.Start(ctx, func() int {
		return int(connectedClients.Load())
	}, func(_ *pb.Envelope) {
		emittedCount.Add(1)
	})

	time.Sleep(80 * time.Millisecond)
	if got := emittedCount.Load(); got != 0 {
		t.Fatalf("expected 0 emissions when no clients connected, got %d", got)
	}

	connectedClients.Store(1)
	deadline := time.After(500 * time.Millisecond)
	for emittedCount.Load() == 0 {
		select {
		case <-deadline:
			t.Fatalf("expected telemetry emissions after client connected")
		case <-time.After(10 * time.Millisecond):
		}
	}
}

