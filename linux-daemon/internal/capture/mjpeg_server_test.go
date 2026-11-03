package capture

import (
	"bytes"
	"context"
	"fmt"
	"image/jpeg"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestMjpegServer_StreamsFramesAndTerminatesPromptly(t *testing.T) {
	// Enable test source mode as specified in FIX-03
	t.Setenv(EnvTestSource, "1")

	server := NewMjpegServer("127.0.0.1:0", nil)
	ts := httptest.NewServer(server)
	defer ts.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, ts.URL+DefaultMjpegPath, nil)
	if err != nil {
		t.Fatalf("failed to create http request: %v", err)
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("failed to connect to mjpeg stream: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected status 200 OK, got %d", resp.StatusCode)
	}

	contentType := resp.Header.Get("Content-Type")
	if contentType != "multipart/x-mixed-replace; boundary=frame" {
		t.Fatalf("unexpected Content-Type: %s", contentType)
	}

	// Read and validate first 3 complete JPEG frames
	framesRead := 0
	buf := make([]byte, 0, 1024*1024)
	chunk := make([]byte, 16*1024)

	for framesRead < 3 {
		n, rErr := resp.Body.Read(chunk)
		if n > 0 {
			buf = append(buf, chunk[:n]...)
		}
		if rErr != nil && rErr != io.EOF {
			t.Fatalf("read error during frame %d: %v", framesRead+1, rErr)
		}

		// Look for JPEG SOI (0xFF, 0xD8) and EOI (0xFF, 0xD9)
		soiIdx := bytes.Index(buf, []byte{0xFF, 0xD8})
		if soiIdx >= 0 {
			eoiRelIdx := bytes.Index(buf[soiIdx+2:], []byte{0xFF, 0xD9})
			if eoiRelIdx >= 0 {
				eoiIdx := soiIdx + 2 + eoiRelIdx + 2 // inclusive end
				jpegBytes := buf[soiIdx:eoiIdx]

				// Validate JPEG structure
				img, err := jpeg.Decode(bytes.NewReader(jpegBytes))
				if err != nil {
					t.Fatalf("failed to decode frame %d: %v", framesRead+1, err)
				}
				if img.Bounds().Dx() != 1280 || img.Bounds().Dy() != 720 {
					t.Fatalf("frame %d dimensions mismatch: got %dx%d, want 1280x720",
						framesRead+1, img.Bounds().Dx(), img.Bounds().Dy())
				}

				framesRead++
				// Slice remaining buffer after EOI
				buf = buf[eoiIdx:]
			}
		}

		if rErr == io.EOF && framesRead < 3 {
			t.Fatalf("reached EOF after only %d frames", framesRead)
		}
	}

	if framesRead != 3 {
		t.Fatalf("expected 3 frames, got %d", framesRead)
	}

	// Cancel request and verify teardown terminates in less than 100ms
	cancelStart := time.Now()
	cancel()
	_ = resp.Body.Close()

	// Wait for server active connection count to drop to 0
	deadline := time.Now().Add(100 * time.Millisecond)
	for server.ActiveConnections() > 0 {
		if time.Now().After(deadline) {
			t.Fatalf("capture goroutine did not terminate within 100ms: still active after %v", time.Since(cancelStart))
		}
		time.Sleep(5 * time.Millisecond)
	}

	elapsed := time.Since(cancelStart)
	if elapsed > 100*time.Millisecond {
		t.Fatalf("cleanup took too long: %v (must be < 100ms)", elapsed)
	}
}

func TestMjpegServer_StartAndShutdown(t *testing.T) {
	server := NewMjpegServer("127.0.0.1:0", nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	addr, err := server.Start(ctx)
	if err != nil {
		t.Fatalf("server start failed: %v", err)
	}
	if !server.IsRunning() {
		t.Fatal("expected server.IsRunning() to be true")
	}

	// Verify health check responds
	healthURL := fmt.Sprintf("http://%s/health", addr.String())
	resp, err := http.Get(healthURL)
	if err != nil {
		t.Fatalf("failed to query /health: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK from /health, got %d", resp.StatusCode)
	}

	// Shutdown
	shutCtx, shutCancel := context.WithTimeout(context.Background(), time.Second)
	defer shutCancel()
	if err := server.Shutdown(shutCtx); err != nil {
		t.Fatalf("server shutdown error: %v", err)
	}
	if server.IsRunning() {
		t.Fatal("expected server.IsRunning() to be false after shutdown")
	}
}
