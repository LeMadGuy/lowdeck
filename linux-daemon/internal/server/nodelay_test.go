package server

import (
	"net"
	"syscall"
	"testing"
)

func TestLowLatencyListenerEnablesTCPNoDelay(t *testing.T) {
	t.Parallel()

	raw, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ln := &lowLatencyListener{Listener: raw}
	defer ln.Close()

	accepted := make(chan net.Conn, 1)
	go func() {
		c, err := ln.Accept()
		if err == nil {
			accepted <- c
		}
		close(accepted)
	}()

	client, err := net.Dial("tcp", raw.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer client.Close()

	srvConn, ok := <-accepted
	if !ok || srvConn == nil {
		t.Fatal("accept failed")
	}
	defer srvConn.Close()

	rawConn, err := srvConn.(*net.TCPConn).SyscallConn()
	if err != nil {
		t.Fatalf("syscall conn: %v", err)
	}
	var noDelay, keepAlive int
	var sockErr error
	_ = rawConn.Control(func(fd uintptr) {
		noDelay, sockErr = syscall.GetsockoptInt(int(fd), syscall.IPPROTO_TCP, syscall.TCP_NODELAY)
		if sockErr == nil {
			keepAlive, sockErr = syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_KEEPALIVE)
		}
	})
	if sockErr != nil {
		t.Fatalf("getsockopt: %v", sockErr)
	}
	if noDelay == 0 {
		t.Error("TCP_NODELAY must be enabled on accepted control connections")
	}
	if keepAlive == 0 {
		t.Error("SO_KEEPALIVE must be enabled on accepted control connections")
	}
}

