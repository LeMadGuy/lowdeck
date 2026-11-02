package capture

import (
	"context"
	"runtime"
	"testing"
	"time"
)

func TestSyntheticMediaSourceEmitsValidH264NALsAndStopsCleanly(t *testing.T) {
	t.Setenv(EnvTestSource, "1")

	baselineGoroutines := runtime.NumGoroutine()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var source MediaSource = NewPipeline(PipelineConfig{
		Width:       640,
		Height:      360,
		FPS:         60,
		BitrateKbps: 2000,
		EnableAudio: true,
	})

	if err := source.Start(ctx); err != nil {
		t.Fatalf("MediaSource.Start failed: %v", err)
	}

	validNALCount := 0
	audioFrameCount := 0
	sawSPSOrIDR := false

	windowTimer := time.NewTimer(1 * time.Second)
	defer windowTimer.Stop()

collectLoop:
	for {
		select {
		case sample, ok := <-source.VideoSamples():
			if !ok {
				break collectLoop
			}
			if IsValidAnnexBNAL(sample.Data) {
				validNALCount++
				if sample.IsKeyFrame {
					sawSPSOrIDR = true
				}
			} else {
				t.Errorf("received malformed Annex-B NAL unit (len=%d)", len(sample.Data))
			}
			if validNALCount >= 20 && audioFrameCount >= 5 {
				break collectLoop
			}

		case aSample, ok := <-source.AudioSamples():
			if !ok {
				break collectLoop
			}
			if len(aSample.Data) > 0 {
				audioFrameCount++
			}

		case <-windowTimer.C:
			break collectLoop
		}
	}

	t.Logf("encoder=%s valid_h264_nals=%d audio_frames=%d keyframe_seen=%v",
		source.EncoderName(), validNALCount, audioFrameCount, sawSPSOrIDR)

	if validNALCount < 15 {
		t.Fatalf("expected at least 15 valid H.264 NAL units within 1s, got %d", validNALCount)
	}
	if !sawSPSOrIDR {
		t.Errorf("expected at least one SPS or IDR keyframe NAL unit")
	}
	if audioFrameCount == 0 {
		t.Errorf("expected at least 1 Opus audio sample in synthetic test mode")
	}

	// Shut down pipeline immediately and verify zero leaked processes or goroutines.
	if err := source.Close(); err != nil {
		t.Fatalf("MediaSource.Close failed: %v", err)
	}

	// Drain channels until closed confirmation.
	for range source.VideoSamples() {
	}
	for range source.AudioSamples() {
	}

	// Allow brief scheduling settle for runtime goroutines to exit.
	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if runtime.NumGoroutine() <= baselineGoroutines+2 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	afterGoroutines := runtime.NumGoroutine()
	if afterGoroutines > baselineGoroutines+2 {
		t.Errorf("potential goroutine leak after Close(): before=%d, after=%d", baselineGoroutines, afterGoroutines)
	}
}

func TestAnnexBNALParserAndValidation(t *testing.T) {
	t.Parallel()

	p := NewPipeline(PipelineConfig{FPS: 60, TestSource: true})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Construct stream containing SPS (type 7), PPS (type 8), and IDR slice (type 5).
	rawStream := []byte{
		0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,
		0x00, 0x00, 0x01, 0x68, 0xCE, 0x3C, 0x80,
		0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x21,
	}

	rem := p.extractAndEmitNALs(ctx, rawStream, time.Second/60, true)
	if len(rem) != 0 {
		t.Fatalf("expected empty remainder on flush, got %d bytes", len(rem))
	}

	for i := 0; i < 3; i++ {
		select {
		case sample := <-p.VideoSamples():
			if !IsValidAnnexBNAL(sample.Data) {
				t.Fatalf("NAL #%d failed IsValidAnnexBNAL: %x", i, sample.Data)
			}
		default:
			t.Fatalf("expected 3 NAL units, missing #%d", i)
		}
	}
}

