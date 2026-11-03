package com.linuxdeck.client.network

import com.linuxdeck.client.protocol.v1.ButtonAction
import com.linuxdeck.client.protocol.v1.MouseButton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class UdpFastControlClientTest {

    private var serverSocket: DatagramSocket? = null
    private var serverPort: Int = 0
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        serverSocket = DatagramSocket(0)
        serverSocket?.soTimeout = 2000
        serverPort = serverSocket!!.localPort
    }

    @After
    fun tearDown() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    @Test
    fun testSendMouseAbsoluteDatagram() {
        val client = UdpFastControlClient(
            host = "127.0.0.1",
            port = serverPort,
            sessionClient = null,
            scope = testScope,
        )

        try {
            client.sendMouseAbsolute(0.5f, 0.75f)

            var packet: DatagramPacket
            val buf = ByteArray(32)
            do {
                packet = DatagramPacket(buf, buf.size)
                serverSocket?.receive(packet)
            } while (buf[0] == 0x01.toByte()) // Skip background OP_PING

            assertEquals(5, packet.length)
            assertEquals(0x11.toByte(), buf[0]) // OP_MOUSE_ABS

            val bb = ByteBuffer.wrap(buf, 1, 4).order(ByteOrder.BIG_ENDIAN)
            val xUint16 = bb.short.toInt() and 0xFFFF
            val yUint16 = bb.short.toInt() and 0xFFFF

            val expectedX = (0.5f * 65535f).toInt()
            val expectedY = (0.75f * 65535f).toInt()

            assertEquals(expectedX, xUint16)
            assertEquals(expectedY, yUint16)
        } finally {
            client.close()
        }
    }

    @Test
    fun testSendMouseButtonDatagram() {
        val client = UdpFastControlClient(
            host = "127.0.0.1",
            port = serverPort,
            sessionClient = null,
            scope = testScope,
        )

        try {
            client.sendMouseButton(MouseButton.LEFT, ButtonAction.PRESS)

            var packet: DatagramPacket
            val buf = ByteArray(32)
            do {
                packet = DatagramPacket(buf, buf.size)
                serverSocket?.receive(packet)
            } while (buf[0] == 0x01.toByte()) // Skip background OP_PING

            assertEquals(3, packet.length)
            assertEquals(0x12.toByte(), buf[0]) // OP_MOUSE_BTN
            assertEquals(1.toByte(), buf[1])    // BTN_LEFT
            assertEquals(1.toByte(), buf[2])    // PRESS
        } finally {
            client.close()
        }
    }
}
