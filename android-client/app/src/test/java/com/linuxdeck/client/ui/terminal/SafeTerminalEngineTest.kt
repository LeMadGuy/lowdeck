package com.linuxdeck.client.ui.terminal

import com.linuxdeck.client.fixes.SafeTerminalEngine
import com.linuxdeck.client.fixes.TerminalLineRingBuffer
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.protocol.v1.Envelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafeTerminalEngineTest {

    private val testDispatcher = StandardTestDispatcher()

    private class MockTerminalTransport : TerminalTransport {
        override val connectionState = MutableStateFlow<ConnectionState>(
            ConnectionState.Connected("127.0.0.1", 8443, "sess-1", "host", 1L)
        )
        override val incomingEnvelopes = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
        val sentEnvelopes = mutableListOf<Envelope>()

        override suspend fun sendEnvelope(envelope: Envelope): Boolean {
            sentEnvelopes.add(envelope)
            return true
        }

        override fun sendAsync(envelope: Envelope): Boolean {
            sentEnvelopes.add(envelope)
            return true
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `TerminalLineRingBuffer respects 1000 line limit and safely overwrites oldest items`() {
        val ring = TerminalLineRingBuffer(capacity = 1000)
        assertTrue(ring.isEmpty())
        assertEquals(0, ring.size)

        // Insert 1500 lines
        for (i in 0 until 1500) {
            val cell = TerminalCell(ch = ('A'.code + (i % 26)).toChar())
            ring.add(listOf(cell))
        }

        assertEquals(1000, ring.size)
        assertFalse(ring.isEmpty())
        assertTrue(ring.isNotEmpty())

        // Line 0 should be item index 500
        val line0 = ring.get(0)
        assertNotNull(line0)
        val expectedChar0 = ('A'.code + (500 % 26)).toChar()
        assertEquals(expectedChar0, line0!!.first().ch)

        // Line 999 should be item index 1499
        val line999 = ring.get(999)
        assertNotNull(line999)
        val expectedChar999 = ('A'.code + (1499 % 26)).toChar()
        assertEquals(expectedChar999, line999!!.first().ch)

        // Clear resets
        ring.clear()
        assertEquals(0, ring.size)
        assertTrue(ring.isEmpty())
    }

    @Test
    fun `SafeTerminalEngine throttles high frequency bursts to 30 FPS`() = runTest(testDispatcher) {
        val engine = SafeTerminalEngine(
            initialCols = 80,
            initialRows = 24,
            maxScrollbackLines = 1000,
            computeDispatcher = testDispatcher,
        )

        val snapshots = mutableListOf<TerminalScreenSnapshot>()
        engine.startProcessing(this) { snap ->
            snapshots.add(snap)
        }

        // Send 10 rapid chunks within virtually 0 virtual time
        for (i in 1..10) {
            engine.feedBytesThrottled("Line $i\r\n".toByteArray(Charsets.UTF_8))
        }

        advanceTimeBy(10) // less than 33ms
        // At most 1 initial snapshot was dispatched
        assertTrue(snapshots.size <= 1)

        // Advance past 33ms target window
        advanceTimeBy(50)
        advanceUntilIdle()

        // Consolidated final state is delivered
        assertTrue(snapshots.isNotEmpty())
        val lastSnap = engine.snapshot()
        val text = lastSnap.visibleRows.flatMap { row -> row.cells.map { it.ch } }.joinToString("")
        assertTrue(text.contains("Line 10"))

        engine.stop()
    }

    @Test
    fun `TerminalViewModel sendPtyReady sends PTY_READY token envelope`() = runTest(testDispatcher) {
        val transport = MockTerminalTransport()
        val viewModel = TerminalViewModel(
            transport = transport,
            ioDispatcher = testDispatcher,
            computeDispatcher = testDispatcher,
        )

        advanceUntilIdle()
        // Auto-opened PtyOpenRequest
        assertEquals(1, transport.sentEnvelopes.size)

        // Send PtyReady handshake
        viewModel.sendPtyReady()
        advanceUntilIdle()

        assertEquals(2, transport.sentEnvelopes.size)
        val readyEnv = transport.sentEnvelopes.last()
        assertEquals(Envelope.PayloadCase.PTY_DATA_EVENT, readyEnv.payloadCase)
        assertEquals(SafeTerminalEngine.PTY_READY_TOKEN, readyEnv.ptyDataEvent.data.toStringUtf8())
    }
}

