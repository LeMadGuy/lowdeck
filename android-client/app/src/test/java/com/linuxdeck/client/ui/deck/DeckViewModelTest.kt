package com.linuxdeck.client.ui.deck

import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.protocol.v1.ButtonType
import com.linuxdeck.client.protocol.v1.DeckProfileSync
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.MacroRunState
import com.linuxdeck.client.protocol.v1.MacroStateSync
import com.linuxdeck.client.protocol.v1.MediaCommandType
import com.linuxdeck.client.protocol.v1.MediaStatusEvent
import com.linuxdeck.client.protocol.v1.TelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeckViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeDeckTransport : DeckTransport {
        override val connectionState = MutableStateFlow<ConnectionState>(
            ConnectionState.Connected(
                host = "192.168.1.50",
                port = 8443,
                sessionId = "sess-01",
                hostDeviceName = "arch-workstation",
                latencyMs = 3L,
            ),
        )
        override val incomingEnvelopes = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
        override val latestDeckProfile = MutableStateFlow<DeckProfileSync?>(null)
        override val latestTelemetry = MutableStateFlow<TelemetrySnapshot?>(null)

        val sentEnvelopes = mutableListOf<Envelope>()
        var sendResult: Boolean = true

        override suspend fun sendEnvelope(envelope: Envelope): Boolean {
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
    fun `MacroStateSync updates button execution state, exit code, and dynamic properties`() = runTest(testDispatcher) {
        val transport = FakeDeckTransport()
        val viewModel = DeckViewModel(transport = transport, ioDispatcher = testDispatcher)

        // 1. Transition to RUNNING
        transport.incomingEnvelopes.emit(
            Envelope.newBuilder()
                .setMacroStateSync(
                    MacroStateSync.newBuilder()
                        .setButtonId("cmd-lock-screen")
                        .setState(MacroRunState.RUNNING)
                        .build(),
                )
                .build(),
        )

        val runningState = viewModel.uiState.value.buttonStates["cmd-lock-screen"]
        assertNotNull(runningState)
        assertEquals(MacroRunState.RUNNING, runningState?.runState)

        // 2. Transition to SUCCESS with dynamic title, color, and active toggle
        transport.incomingEnvelopes.emit(
            Envelope.newBuilder()
                .setMacroStateSync(
                    MacroStateSync.newBuilder()
                        .setButtonId("cmd-lock-screen")
                        .setState(MacroRunState.SUCCESS)
                        .setExitCode(0)
                        .setTitle("Sessão Bloqueada")
                        .setColor("#065F46")
                        .setIsActive(true)
                        .build(),
                )
                .build(),
        )

        val successState = viewModel.uiState.value.buttonStates["cmd-lock-screen"]
        assertEquals(MacroRunState.SUCCESS, successState?.runState)
        assertEquals(0, successState?.exitCode)
        assertEquals("Sessão Bloqueada", successState?.dynamicTitle)
        assertEquals("#065F46", successState?.dynamicColor)
        assertTrue(successState?.isActive == true)

        // 3. Transition to ERROR with non-zero exit code preserves previous dynamic title if omitted
        transport.incomingEnvelopes.emit(
            Envelope.newBuilder()
                .setMacroStateSync(
                    MacroStateSync.newBuilder()
                        .setButtonId("cmd-lock-screen")
                        .setState(MacroRunState.ERROR)
                        .setExitCode(127)
                        .setIsActive(false)
                        .build(),
                )
                .build(),
        )

        val errorState = viewModel.uiState.value.buttonStates["cmd-lock-screen"]
        assertEquals(MacroRunState.ERROR, errorState?.runState)
        assertEquals(127, errorState?.exitCode)
        assertEquals("Sessão Bloqueada", errorState?.dynamicTitle)
        assertFalse(errorState?.isActive == true)
    }

    @Test
    fun `TelemetrySnapshot updates telemetryState and mediaState without mutating DeckUiState`() = runTest(testDispatcher) {
        val transport = FakeDeckTransport()
        val viewModel = DeckViewModel(transport = transport, ioDispatcher = testDispatcher)

        val initialDeckUiState = viewModel.uiState.value

        val snapshot = TelemetrySnapshot.newBuilder()
            .setCpuPercent(47.5f)
            .setRamUsedMb(8192L)
            .setRamTotalMb(32768L)
            .putTemperatures("Package id 0", 63.5f)
            .putTemperatures("nvme0", 41.0f)
            .setRxKbps(16384f)
            .setTxKbps(4096f)
            .setMediaStatus(
                MediaStatusEvent.newBuilder()
                    .setTitle("Strobe")
                    .setArtist("deadmau5")
                    .setAlbum("For Lack of a Better Name")
                    .setIsPlaying(true)
                    .setPlayer("spotify")
                    .setVolume(0.85f)
                    .build(),
            )
            .build()

        transport.incomingEnvelopes.emit(
            Envelope.newBuilder()
                .setTelemetrySnapshot(snapshot)
                .build(),
        )

        val telemetry = viewModel.telemetryState.value
        assertTrue(telemetry.hasSample)
        assertEquals(47.5f, telemetry.cpuPercent, 0.01f)
        assertEquals(8.0f, telemetry.ramUsedGb, 0.01f)
        assertEquals(32.0f, telemetry.ramTotalGb, 0.01f)
        assertEquals(0.25f, telemetry.ramFraction, 0.001f)
        assertEquals(63.5f, telemetry.primaryTempCelsius ?: 0f, 0.01f)
        assertEquals(16384f, telemetry.rxKbps, 0.01f)
        assertEquals(4096f, telemetry.txKbps, 0.01f)

        val media = viewModel.mediaState.value
        assertTrue(media.hasActiveSession)
        assertEquals("Strobe", media.title)
        assertEquals("deadmau5", media.artist)
        assertTrue(media.isPlaying)
        assertEquals("spotify", media.player)

        // Strict recomposition isolation guarantee: DeckUiState instance MUST remain identical.
        assertSame(initialDeckUiState, viewModel.uiState.value)
    }

    @Test
    fun `button clicks dispatch MacroExecRequest, KeyComboEvent, MediaControlCommand and navigate folders`() = runTest(testDispatcher) {
        val transport = FakeDeckTransport()
        val viewModel = DeckViewModel(transport = transport, ioDispatcher = testDispatcher)

        val mainButtons = viewModel.uiState.value.profile.pages.first().buttons

        // 1. COMMAND button
        val lockBtn = mainButtons.first { it.buttonId == "cmd-lock-screen" }
        viewModel.onButtonClicked(lockBtn)
        assertEquals(MacroRunState.RUNNING, viewModel.uiState.value.buttonStates["cmd-lock-screen"]?.runState)
        assertEquals(Envelope.PayloadCase.MACRO_EXEC_REQUEST, transport.sentEnvelopes.last().payloadCase)
        assertEquals("loginctl lock-session", transport.sentEnvelopes.last().macroExecRequest.command)

        // 2. KEY_COMBO button
        val termBtn = mainButtons.first { it.buttonId == "shortcut-terminal" }
        viewModel.onButtonClicked(termBtn)
        assertEquals(Envelope.PayloadCase.KEY_COMBO_EVENT, transport.sentEnvelopes.last().payloadCase)
        assertEquals("CTRL+ALT+T", transport.sentEnvelopes.last().keyComboEvent.combo)

        // 3. MEDIA button
        val playPauseBtn = mainButtons.first { it.buttonId == "media-play-pause" }
        viewModel.onButtonClicked(playPauseBtn)
        assertEquals(Envelope.PayloadCase.MEDIA_CONTROL_COMMAND, transport.sentEnvelopes.last().payloadCase)
        assertEquals(MediaCommandType.PLAY_PAUSE, transport.sentEnvelopes.last().mediaControlCommand.command)

        // 4. FOLDER navigation and back stack
        val folderBtn = mainButtons.first { it.buttonId == "folder-sysadmin" }
        viewModel.onButtonClicked(folderBtn)
        assertEquals("sysadmin", viewModel.uiState.value.activeFolderPage?.pageId)
        assertTrue(viewModel.navigateBackFromFolder())
        assertNull(viewModel.uiState.value.activeFolderPage)
    }

    @Test
    fun `quick edit saves updated button locally and sends schema-valid DeckProfileSync to daemon`() = runTest(testDispatcher) {
        val transport = FakeDeckTransport()
        val viewModel = DeckViewModel(transport = transport, ioDispatcher = testDispatcher)

        val targetBtn = viewModel.uiState.value.profile.pages.first().buttons.first { it.buttonId == "shortcut-terminal" }
        viewModel.openQuickEdit(targetBtn)
        assertNotNull(viewModel.uiState.value.quickEditDraft)

        viewModel.saveButtonQuickEdit(
            QuickEditButtonDraft(
                buttonId = "shortcut-terminal",
                type = ButtonType.COMMAND,
                title = "Abrir Alacritty",
                color = "#6D28D9",
                inactiveIcon = "terminal",
                activeIcon = "terminal_active",
                actionValue = "alacritty",
            ),
        )

        assertNull(viewModel.uiState.value.quickEditDraft)
        val updatedBtn = viewModel.uiState.value.profile.pages.first().buttons.first { it.buttonId == "shortcut-terminal" }
        assertEquals(ButtonType.COMMAND, updatedBtn.type)
        assertEquals("Abrir Alacritty", updatedBtn.title)
        assertEquals("#6D28D9", updatedBtn.color)
        assertEquals("alacritty", updatedBtn.command)

        val syncEnv = transport.sentEnvelopes.last()
        assertEquals(Envelope.PayloadCase.DECK_PROFILE_SYNC, syncEnv.payloadCase)
        val roundTrip = DeckProfileJsonCodec.parseRawJson(syncEnv.deckProfileSync.rawJson)
        val roundTripBtn = roundTrip.pages.first().buttons.first { it.buttonId == "shortcut-terminal" }
        assertEquals("Abrir Alacritty", roundTripBtn.title)
        assertEquals("#6D28D9", roundTripBtn.color)
        assertEquals("alacritty", roundTripBtn.command)
    }
}

