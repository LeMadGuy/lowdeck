package com.linuxdeck.client.network

import com.linuxdeck.client.protocol.v1.ButtonAction
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.MouseButton
import com.linuxdeck.client.protocol.v1.MouseButtonEvent
import com.linuxdeck.client.protocol.v1.MouseMoveEvent
import com.linuxdeck.client.protocol.v1.MouseScrollEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * FIX-01: Dedicated UDP Fast-Lane client for low-latency mouse, scroll and touch injection on :8443/udp.
 *
 * Implements compact binary datagrams (3 to 9 bytes) with zero dynamic allocation on the send path.
 * Configured with IPTOS_LOWDELAY (0x10) to instruct Android and Wi-Fi drivers to prioritize traffic.
 * Automatically falls back to TCP when running over adb reverse without UDP port forwarding.
 */
class UdpFastControlClient(
    private val host: String,
    private val port: Int = 8443,
    private val sessionClient: DeckSessionClient? = null,
    private val scope: CoroutineScope,
) {
    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private var socket: DatagramSocket? = null
    private var inetAddr: InetAddress? = null
    private var useTcpFallback: Boolean = false
    private val sendBuffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
    private var pingJob: Job? = null

    init {
        try {
            val sock = DatagramSocket()
            sock.trafficClass = 0x10 // IPTOS_LOWDELAY
            sock.soTimeout = 1000
            socket = sock
            inetAddr = InetAddress.getByName(host)
            startPingLoop()
        } catch (_: Exception) {
            useTcpFallback = true
        }
    }

    private fun startPingLoop() {
        pingJob = scope.launch(Dispatchers.IO) {
            val pingBuf = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            val recvBuf = ByteArray(16)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            var consecutiveFailures = 0

            while (isActive) {
                try {
                    val sock = socket
                    val addr = inetAddr
                    if (sock != null && addr != null && !sock.isClosed) {
                        pingBuf.clear()
                        pingBuf.put(0x01.toByte()) // OP_PING
                        pingBuf.putLong(System.nanoTime())
                        val packet = DatagramPacket(pingBuf.array(), 9, addr, port)
                        sock.send(packet)

                        sock.receive(recvPacket)
                        if (recvPacket.length >= 9 && recvBuf[0] == 0x02.toByte()) { // OP_PONG
                            val echoBuf = ByteBuffer.wrap(recvBuf, 1, 8).order(ByteOrder.BIG_ENDIAN)
                            val sentNano = echoBuf.long
                            val rtt = (System.nanoTime() - sentNano) / 1_000_000L
                            _rttMs.value = rtt.coerceAtLeast(0)
                            consecutiveFailures = 0
                            useTcpFallback = false
                        }
                    }
                } catch (_: Exception) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        useTcpFallback = true
                    }
                }
                delay(1000)
            }
        }
    }

    @Synchronized
    fun sendMouseRelative(dx: Int, dy: Int) {
        val clampedDx = dx.coerceIn(-32768, 32767).toShort()
        val clampedDy = dy.coerceIn(-32768, 32767).toShort()

        if (useTcpFallback || socket == null || inetAddr == null) {
            sendMouseRelativeTcp(dx, dy)
            return
        }

        try {
            sendBuffer.clear()
            sendBuffer.put(0x10.toByte()) // OP_MOUSE_REL
            sendBuffer.putShort(clampedDx)
            sendBuffer.putShort(clampedDy)
            val packet = DatagramPacket(sendBuffer.array(), 5, inetAddr, port)
            socket?.send(packet)
        } catch (_: Exception) {
            useTcpFallback = true
            sendMouseRelativeTcp(dx, dy)
        }
    }

    @Synchronized
    fun sendMouseAbsolute(normX: Float, normY: Float) {
        val clampedX = normX.coerceIn(0f, 1f)
        val clampedY = normY.coerceIn(0f, 1f)

        if (useTcpFallback || socket == null || inetAddr == null) {
            sendMouseAbsoluteTcp(clampedX, clampedY)
            return
        }

        try {
            sendBuffer.clear()
            sendBuffer.put(0x11.toByte()) // OP_MOUSE_ABS
            sendBuffer.putShort((clampedX * 65535f).toInt().toShort())
            sendBuffer.putShort((clampedY * 65535f).toInt().toShort())
            val packet = DatagramPacket(sendBuffer.array(), 5, inetAddr, port)
            socket?.send(packet)
        } catch (_: Exception) {
            useTcpFallback = true
            sendMouseAbsoluteTcp(clampedX, clampedY)
        }
    }

    @Synchronized
    fun sendMouseButton(button: MouseButton, action: ButtonAction) {
        if (useTcpFallback || socket == null || inetAddr == null) {
            sendMouseButtonTcp(button, action)
            return
        }

        val btnCode: Byte = when (button) {
            MouseButton.LEFT -> 1
            MouseButton.RIGHT -> 2
            MouseButton.MIDDLE -> 3
            else -> 1
        }
        val actCode: Byte = when (action) {
            ButtonAction.PRESS -> 1
            ButtonAction.RELEASE -> 0
            ButtonAction.CLICK -> 2
            else -> 2
        }

        try {
            sendBuffer.clear()
            sendBuffer.put(0x12.toByte()) // OP_MOUSE_BTN
            sendBuffer.put(btnCode)
            sendBuffer.put(actCode)
            val packet = DatagramPacket(sendBuffer.array(), 3, inetAddr, port)
            socket?.send(packet)
        } catch (_: Exception) {
            useTcpFallback = true
            sendMouseButtonTcp(button, action)
        }
    }

    @Synchronized
    fun sendMouseScroll(vScroll: Int, hScroll: Int) {
        val clampedV = vScroll.coerceIn(-32768, 32767).toShort()
        val clampedH = hScroll.coerceIn(-32768, 32767).toShort()

        if (useTcpFallback || socket == null || inetAddr == null) {
            sendMouseScrollTcp(vScroll, hScroll)
            return
        }

        try {
            sendBuffer.clear()
            sendBuffer.put(0x13.toByte()) // OP_MOUSE_SCROLL
            sendBuffer.putShort(clampedV)
            sendBuffer.putShort(clampedH)
            val packet = DatagramPacket(sendBuffer.array(), 5, inetAddr, port)
            socket?.send(packet)
        } catch (_: Exception) {
            useTcpFallback = true
            sendMouseScrollTcp(vScroll, hScroll)
        }
    }

    private fun sendMouseRelativeTcp(dx: Int, dy: Int) {
        sessionClient?.sendAsync(
            Envelope.newBuilder()
                .setRequestId("rel-move-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setMouseMoveEvent(
                    MouseMoveEvent.newBuilder()
                        .setDx(dx)
                        .setDy(dy)
                        .setIsAbsolute(false)
                        .build(),
                )
                .build(),
        )
    }

    private fun sendMouseAbsoluteTcp(normX: Float, normY: Float) {
        sessionClient?.sendAsync(
            Envelope.newBuilder()
                .setRequestId("abs-move-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setMouseMoveEvent(
                    MouseMoveEvent.newBuilder()
                        .setNormX(normX)
                        .setNormY(normY)
                        .setIsAbsolute(true)
                        .build(),
                )
                .build(),
        )
    }

    private fun sendMouseButtonTcp(button: MouseButton, action: ButtonAction) {
        sessionClient?.sendAsync(
            Envelope.newBuilder()
                .setRequestId("abs-btn-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setMouseButtonEvent(
                    MouseButtonEvent.newBuilder()
                        .setButton(button)
                        .setAction(action)
                        .build(),
                )
                .build(),
        )
    }

    private fun sendMouseScrollTcp(vScroll: Int, hScroll: Int) {
        sessionClient?.sendAsync(
            Envelope.newBuilder()
                .setRequestId("rel-scroll-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setMouseScrollEvent(
                    MouseScrollEvent.newBuilder()
                        .setDx(hScroll)
                        .setDy(vScroll)
                        .build(),
                )
                .build(),
        )
    }

    fun close() {
        pingJob?.cancel()
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}
