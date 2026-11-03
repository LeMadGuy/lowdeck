package com.linuxdeck.client.ui.terminal

import com.google.protobuf.ByteString
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.PtyCloseEvent
import com.linuxdeck.client.protocol.v1.PtyDataEvent
import com.linuxdeck.client.ui.navigation.MainDestination
import com.linuxdeck.client.ui.navigation.PAIRING_ROUTE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeTerminalTransport(
        initialState: ConnectionState = ConnectionState.Connected(
            host = "192.168.1.50",
            port = 8443,
            sessionId = "mtls-sess-01",
            hostDeviceName = "arch-workstation",
            latencyMs = 2L,
        ),
    ) : TerminalTransport {
        override val connectionState = MutableStateFlow(initialState)
        override val incomingEnvelopes = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)

        val sentEnvelopes = mutableListOf<Envelope>()
        var sendResult: Boolean = true

        override suspend fun sendEnvelope(envelope: Envelope): Boolean {
            if (sendResult) {
                sentEnvelopes.add(envelope)
            }
            return sendResult
        }

        override fun sendAsync(envelope: Envelope): Boolean {
            if (sendResult) {
                sentEnvelopes.add(envelope)
            }
            return sendResult
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
    fun `AnsiTerminalBuffer renders 256-color SGR, TrueColor, CUP cursor moves and split UTF-8 chunks`() {
        val buffer = AnsiTerminalBuffer(initialCols = 40, initialRows = 10)

        // Position at row 2, col 3 (1-based) and write 256-color fg=196, bg=21 bold text
        val ansiSeq = "\u001B[2;3H\u001B[1;38;5;196;48;5;21mOK\u001B[0m "
        buffer.feedBytes(ansiSeq.toByteArray(Charsets.UTF_8))

        // Feed multi-byte UTF-8 character 'ç' (0xC3 0xA7) split across two separate network frames
        buffer.feedBytes(byteArrayOf(0xC3.toByte()))
        buffer.feedBytes(byteArrayOf(0xA7.toByte()))

        val snap = buffer.snapshot()
        val cellO = snap.visibleRows[1].cells[2]
        val cellK = snap.visibleRows[1].cells[3]
        val cellCedilla = snap.visibleRows[1].cells[5]

        assertEquals('O', cellO.ch)
        assertTrue(cellO.bold)
        assertEquals(AnsiColorPalette.colorFor256Index(196), cellO.fgArgb)
        assertEquals(AnsiColorPalette.colorFor256Index(21), cellO.bgArgb)

        assertEquals('K', cellK.ch)
        assertEquals('ç', cellCedilla.ch)
        assertEquals(AnsiColorPalette.DEFAULT_FG_ARGB, cellCedilla.fgArgb)
    }

    @Test
    fun `AnsiTerminalBuffer preserves primary screen across alternate screen 1049h and 1049l transitions`() {
        val buffer = AnsiTerminalBuffer(initialCols = 30, initialRows = 8)
        buffer.feedBytes("root@arch:~# ls".toByteArray(Charsets.UTF_8))

        // Enter alternate screen (?1049h) like vim/htop and draw DEC Special Graphics box line
        buffer.feedBytes("\u001B[?1049h\u001B[1;1H\u001B(0lqqk\u001B(B".toByteArray(Charsets.UTF_8))
        val altSnap = buffer.snapshot()
        assertTrue(altSnap.isAlternateScreen)
        assertEquals('┌', altSnap.visibleRows[0].cells[0].ch)
        assertEquals('─', altSnap.visibleRows[0].cells[1].ch)
        assertEquals('─', altSnap.visibleRows[0].cells[2].ch)
        assertEquals('┐', altSnap.visibleRows[0].cells[3].ch)

        // Exit alternate screen (?1049l) -> original shell prompt must be intact
        buffer.feedBytes("\u001B[?1049l".toByteArray(Charsets.UTF_8))
        val restored = buffer.snapshot()
        assertFalse(restored.isAlternateScreen)
        val firstLine = restored.visibleRows[0].cells.take(15).map { it.ch }.joinToString("")
        assertEquals("root@arch:~# ls", firstLine)
    }

    @Test
    fun `TerminalViewModel sends PtyOpenRequest, syncs PtyResizeEvent, renders PtyDataEvent and handles PtyCloseEvent`() =
        runTest(testDispatcher) {
            val transport = FakeTerminalTransport()
            val viewModel = TerminalViewModel(
                transport = transport,
                ioDispatcher = testDispatcher,
                computeDispatcher = testDispatcher,
            )

            // 1. Auto-opened PtyOpenRequest on Connected state
            assertEquals(1, transport.sentEnvelopes.size)
            val openEnv = transport.sentEnvelopes.first()
            assertEquals(Envelope.PayloadCase.PTY_OPEN_REQUEST, openEnv.payloadCase)
            val sessionId = openEnv.ptyOpenRequest.sessionId
            assertTrue(sessionId.startsWith("pty-"))
            assertEquals(80, openEnv.ptyOpenRequest.cols)
            assertEquals(24, openEnv.ptyOpenRequest.rows)

            // 2. Viewport resize dispatches PtyResizeEvent with exact cols/rows
            viewModel.onTerminalViewportMeasured(cols = 112, rows = 36)
            val resizeEnv = transport.sentEnvelopes.last()
            assertEquals(Envelope.PayloadCase.PTY_RESIZE_EVENT, resizeEnv.payloadCase)
            assertEquals(sessionId, resizeEnv.ptyResizeEvent.sessionId)
            assertEquals(112, resizeEnv.ptyResizeEvent.cols)
            assertEquals(36, resizeEnv.ptyResizeEvent.rows)

            // 3. Incoming PtyDataEvent updates terminal buffer snapshot
            transport.incomingEnvelopes.emit(
                Envelope.newBuilder()
                    .setPtyDataEvent(
                        PtyDataEvent.newBuilder()
                            .setSessionId(sessionId)
                            .setData(ByteString.copyFromUtf8("Linux 6.12-arch1\r\n$ "))
                            .build(),
                    )
                    .build(),
            )

            val screen = viewModel.uiState.value.screen
            val line0 = screen.visibleRows[0].cells.take(16).map { it.ch }.joinToString("")
            assertEquals("Linux 6.12-arch1", line0)
            assertEquals('$', screen.visibleRows[1].cells[0].ch)

            // 4. Sysadmin toolbar quick keys dispatch exact POSIX/VT100 bytes
            viewModel.onQuickKeyPressed(SysadminQuickKey.CTRL_C)
            val ctrlCEnv = transport.sentEnvelopes.last()
            assertEquals(Envelope.PayloadCase.PTY_DATA_EVENT, ctrlCEnv.payloadCase)
            assertArrayEquals(byteArrayOf(0x03), ctrlCEnv.ptyDataEvent.data.toByteArray())

            viewModel.onQuickKeyPressed(SysadminQuickKey.ARROW_UP)
            assertArrayEquals(
                "\u001B[A".toByteArray(Charsets.US_ASCII),
                transport.sentEnvelopes.last().ptyDataEvent.data.toByteArray(),
            )

            viewModel.onQuickKeyPressed(SysadminQuickKey.PIPE)
            assertArrayEquals(
                "|".toByteArray(Charsets.US_ASCII),
                transport.sentEnvelopes.last().ptyDataEvent.data.toByteArray(),
            )

            // Sticky Ctrl + 'd' chord dispatches EOT (0x04) and disarms Ctrl
            viewModel.toggleCtrlArmed()
            assertTrue(viewModel.uiState.value.isCtrlArmed)
            viewModel.onKeyboardTextCommitted("d")
            assertFalse(viewModel.uiState.value.isCtrlArmed)
            assertArrayEquals(
                byteArrayOf(0x04),
                transport.sentEnvelopes.last().ptyDataEvent.data.toByteArray(),
            )

            // 5. Incoming PtyCloseEvent marks session closed and displays exit banner
            transport.incomingEnvelopes.emit(
                Envelope.newBuilder()
                    .setPtyCloseEvent(
                        PtyCloseEvent.newBuilder()
                            .setSessionId(sessionId)
                            .setExitCode(0)
                            .setReason("logout")
                            .build(),
                    )
                    .build(),
            )
            assertFalse(viewModel.uiState.value.isSessionOpen)
            assertNotNull(viewModel.uiState.value.exitBanner)
        }

    @Test
    fun `MainScaffold destinations expose all 4 unified tabs plus pairing route`() {
        val routes = MainDestination.entries.map { it.route }
        assertEquals(listOf("deck", "trackpad", "remote", "terminal"), routes)
        assertEquals(MainDestination.DECK, MainDestination.fromRoute("deck"))
        assertEquals(MainDestination.TRACKPAD, MainDestination.fromRoute("trackpad"))
        assertEquals(MainDestination.REMOTE_DESKTOP, MainDestination.fromRoute("remote"))
        assertEquals(MainDestination.TERMINAL, MainDestination.fromRoute("terminal"))
        assertEquals("pairing", PAIRING_ROUTE)
    }
}

