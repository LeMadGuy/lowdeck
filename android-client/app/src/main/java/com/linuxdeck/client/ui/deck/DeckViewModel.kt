package com.linuxdeck.client.ui.deck

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.network.DeckConnectionService
import com.linuxdeck.client.network.DeckSessionClient
import com.linuxdeck.client.protocol.v1.ButtonType
import com.linuxdeck.client.protocol.v1.DeckButtonSpec
import com.linuxdeck.client.protocol.v1.DeckPageSpec
import com.linuxdeck.client.protocol.v1.DeckProfileSync
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.KeyComboEvent
import com.linuxdeck.client.protocol.v1.MacroExecRequest
import com.linuxdeck.client.protocol.v1.MacroOutputEvent
import com.linuxdeck.client.protocol.v1.MacroRunState
import com.linuxdeck.client.protocol.v1.MacroStateSync
import com.linuxdeck.client.protocol.v1.MediaCommandType
import com.linuxdeck.client.protocol.v1.MediaControlCommand
import com.linuxdeck.client.protocol.v1.MediaStatusEvent
import com.linuxdeck.client.protocol.v1.TelemetrySnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Abstraction over the mTLS 1.3 + WebSocket session client so [DeckViewModel] remains
 * deterministic and unit-testable without requiring an Android [Context].
 */
interface DeckTransport {
    val connectionState: StateFlow<ConnectionState>
    val incomingEnvelopes: SharedFlow<Envelope>
    val latestDeckProfile: StateFlow<DeckProfileSync?>
    val latestTelemetry: StateFlow<TelemetrySnapshot?>

    suspend fun sendEnvelope(envelope: Envelope): Boolean
}

/**
 * Production [DeckTransport] adapter backed by [DeckSessionClient].
 */
class SessionClientDeckTransport(
    private val sessionClient: DeckSessionClient,
) : DeckTransport {
    override val connectionState: StateFlow<ConnectionState> = sessionClient.connectionState
    override val incomingEnvelopes: SharedFlow<Envelope> = sessionClient.incomingEnvelopes
    override val latestDeckProfile: StateFlow<DeckProfileSync?> = sessionClient.latestDeckProfile
    override val latestTelemetry: StateFlow<TelemetrySnapshot?> = sessionClient.latestTelemetry

    override suspend fun sendEnvelope(envelope: Envelope): Boolean =
        sessionClient.sendEnvelope(envelope)
}

/**
 * Immutable UI representation of a single button or widget cell in the Deck grid.
 */
@Immutable
data class DeckButtonUiModel(
    val buttonId: String,
    val type: ButtonType,
    val row: Int,
    val col: Int,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    val title: String,
    val color: String = DEFAULT_BUTTON_COLOR,
    val activeIcon: String = "",
    val inactiveIcon: String = "",
    val command: String = "",
    val env: Map<String, String> = emptyMap(),
    val workingDir: String = "",
    val timeoutSeconds: Int = 0,
    val pollCommand: String = "",
    val pollIntervalSeconds: Int = 0,
    val keyCombo: String = "",
    val mediaAction: String = "",
    val telemetryMetric: String = "",
    val targetFolderId: String = "",
) {
    companion object {
        const val DEFAULT_BUTTON_COLOR = "#1E293B"
    }
}

/**
 * Immutable UI representation of a top-level Deck page or nested folder grid.
 */
@Immutable
data class DeckPageUiModel(
    val pageId: String,
    val title: String,
    val isFolder: Boolean = false,
    val rows: Int,
    val cols: Int,
    val buttons: List<DeckButtonUiModel>,
)

/**
 * Immutable UI representation of the active synchronized Deck profile.
 */
@Immutable
data class DeckProfileUiModel(
    val version: Int = 1,
    val profileId: String,
    val name: String,
    val rows: Int,
    val cols: Int,
    val pages: List<DeckPageUiModel>,
) {
    val topLevelPages: List<DeckPageUiModel>
        get() = pages.filter { !it.isFolder }.ifEmpty { pages }

    fun findFolderPage(folderId: String): DeckPageUiModel? =
        pages.firstOrNull { it.pageId == folderId }
}

/**
 * Real-time execution & dynamic state of an individual button driven by [MacroStateSync]
 * and [MacroOutputEvent].
 */
@Immutable
data class ButtonRuntimeState(
    val buttonId: String,
    val runState: MacroRunState = MacroRunState.IDLE,
    val exitCode: Int = 0,
    val dynamicTitle: String? = null,
    val dynamicColor: String? = null,
    val dynamicActiveIcon: String? = null,
    val dynamicInactiveIcon: String? = null,
    val isActive: Boolean = false,
    val lastOutputLine: String? = null,
)

/**
 * Draft state for long-press quick editing of a Deck button.
 */
@Immutable
data class QuickEditButtonDraft(
    val buttonId: String,
    val type: ButtonType,
    val title: String,
    val color: String,
    val inactiveIcon: String,
    val activeIcon: String,
    val actionValue: String,
)

/**
 * Static/structural Deck UI state.
 *
 * Intentionally excludes 1 Hz [TelemetryUiState] and [MediaUiState] so high-frequency telemetry
 * packets NEVER trigger recomposition of the grid or static [DeckButtonUiModel] cards.
 */
@Immutable
data class DeckUiState(
    val profile: DeckProfileUiModel = DeckProfileJsonCodec.defaultProfile(),
    val buttonStates: Map<String, ButtonRuntimeState> = emptyMap(),
    val folderStack: List<String> = emptyList(),
    val quickEditDraft: QuickEditButtonDraft? = null,
    val lastErrorMessage: String? = null,
) {
    val activeFolderPage: DeckPageUiModel?
        get() = folderStack.lastOrNull()?.let { profile.findFolderPage(it) }
}

/**
 * Isolated 1 Hz telemetry UI state consumed exclusively inside `TelemetryWidgetCard` leaf scopes.
 */
@Immutable
data class TelemetryUiState(
    val cpuPercent: Float = 0f,
    val ramUsedMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val primaryTempCelsius: Float? = null,
    val primaryTempSensor: String = "",
    val temperatures: Map<String, Float> = emptyMap(),
    val rxKbps: Float = 0f,
    val txKbps: Float = 0f,
    val hasSample: Boolean = false,
) {
    val ramUsedGb: Float
        get() = ramUsedMb.toFloat() / 1024f

    val ramTotalGb: Float
        get() = ramTotalMb.toFloat() / 1024f

    val ramFraction: Float
        get() = if (ramTotalMb > 0L) {
            (ramUsedMb.toFloat() / ramTotalMb.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Isolated MPRIS2 media controller UI state consumed exclusively inside `MediaControllerBar`.
 */
@Immutable
data class MediaUiState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val player: String = "",
    val volume: Float = 0f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasActiveSession: Boolean = false,
)

/**
 * Bidirectional converter between `DeckProfileSync` (Protobuf + `profile_schema.json`) and
 * immutable [DeckProfileUiModel] instances.
 */
object DeckProfileJsonCodec {
    private val HEX_COLOR_REGEX = Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")

    fun normalizeHexColor(candidate: String, fallback: String = DeckButtonUiModel.DEFAULT_BUTTON_COLOR): String {
        val trimmed = candidate.trim()
        val withHash = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return if (HEX_COLOR_REGEX.matches(withHash)) {
            withHash.uppercase(Locale.ROOT)
        } else {
            fallback
        }
    }

    fun isValidHexColor(candidate: String): Boolean {
        val trimmed = candidate.trim()
        val withHash = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return HEX_COLOR_REGEX.matches(withHash)
    }

    fun parseProfileSync(sync: DeckProfileSync): DeckProfileUiModel {
        val raw = sync.rawJson.trim()
        if (raw.isNotEmpty()) {
            runCatching { parseRawJson(raw) }.getOrNull()?.let { return it }
        }
        return parseFromProtoSpec(sync)
    }

    fun parseRawJson(rawJson: String): DeckProfileUiModel {
        val root = JSONObject(rawJson)
        val version = root.optInt("version", 1).coerceAtLeast(1)
        val profileId = root.optString("profile_id", "default").ifBlank { "default" }
        val name = root.optString("name", "Linux Workstation Deck").ifBlank { "Linux Workstation Deck" }
        val defaultRows = root.optInt("rows", 3).coerceIn(1, 12)
        val defaultCols = root.optInt("cols", 5).coerceIn(1, 16)

        val pagesJson = root.optJSONArray("pages") ?: JSONArray()
        val pages = ArrayList<DeckPageUiModel>(pagesJson.length())

        for (i in 0 until pagesJson.length()) {
            val pageObj = pagesJson.optJSONObject(i) ?: continue
            val pageId = pageObj.optString("page_id", "page-$i").ifBlank { "page-$i" }
            val pageTitle = pageObj.optString("title", pageId)
            val isFolder = pageObj.optBoolean("is_folder", false)
            val pageRows = pageObj.optInt("rows", defaultRows).let { if (it in 1..12) it else defaultRows }
            val pageCols = pageObj.optInt("cols", defaultCols).let { if (it in 1..16) it else defaultCols }

            val buttonsJson = pageObj.optJSONArray("buttons") ?: JSONArray()
            val buttons = ArrayList<DeckButtonUiModel>(buttonsJson.length())
            for (j in 0 until buttonsJson.length()) {
                val btnObj = buttonsJson.optJSONObject(j) ?: continue
                val btnId = btnObj.optString("button_id", "").trim()
                if (btnId.isEmpty()) continue
                val btnType = parseButtonType(btnObj.optString("type", ""))
                if (btnType == ButtonType.BUTTON_TYPE_UNSPECIFIED) continue

                val envMap = LinkedHashMap<String, String>()
                btnObj.optJSONObject("env")?.let { envObj ->
                    val keys = envObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        envMap[key] = envObj.optString(key, "")
                    }
                }

                buttons.add(
                    DeckButtonUiModel(
                        buttonId = btnId,
                        type = btnType,
                        row = btnObj.optInt("row", 0).coerceAtLeast(0),
                        col = btnObj.optInt("col", 0).coerceAtLeast(0),
                        rowSpan = btnObj.optInt("row_span", 1).coerceAtLeast(1),
                        colSpan = btnObj.optInt("col_span", 1).coerceAtLeast(1),
                        title = btnObj.optString("title", btnId),
                        color = normalizeHexColor(btnObj.optString("color", DeckButtonUiModel.DEFAULT_BUTTON_COLOR)),
                        activeIcon = btnObj.optString("active_icon", ""),
                        inactiveIcon = btnObj.optString("inactive_icon", ""),
                        command = btnObj.optString("command", ""),
                        env = envMap,
                        workingDir = btnObj.optString("working_dir", ""),
                        timeoutSeconds = btnObj.optInt("timeout_seconds", 0).coerceAtLeast(0),
                        pollCommand = btnObj.optString("poll_command", ""),
                        pollIntervalSeconds = btnObj.optInt("poll_interval_seconds", 0).coerceAtLeast(0),
                        keyCombo = btnObj.optString("key_combo", ""),
                        mediaAction = btnObj.optString("media_action", ""),
                        telemetryMetric = btnObj.optString("telemetry_metric", ""),
                        targetFolderId = btnObj.optString("target_folder_id", ""),
                    ),
                )
            }

            pages.add(
                DeckPageUiModel(
                    pageId = pageId,
                    title = pageTitle,
                    isFolder = isFolder,
                    rows = pageRows,
                    cols = pageCols,
                    buttons = buttons,
                ),
            )
        }

        if (pages.isEmpty()) {
            return defaultProfile()
        }

        return DeckProfileUiModel(
            version = version,
            profileId = profileId,
            name = name,
            rows = defaultRows,
            cols = defaultCols,
            pages = pages,
        )
    }

    private fun parseFromProtoSpec(sync: DeckProfileSync): DeckProfileUiModel {
        val defaultRows = sync.rows.takeIf { it in 1..12 } ?: 3
        val defaultCols = sync.cols.takeIf { it in 1..16 } ?: 5
        if (sync.pagesCount == 0) {
            return defaultProfile()
        }

        // Identify folder target IDs referenced by FOLDER buttons when raw_json was omitted.
        val referencedFolderIds = sync.pagesList
            .flatMap { it.buttonsList }
            .filter { it.type == ButtonType.FOLDER && it.targetFolderId.isNotBlank() }
            .map { it.targetFolderId }
            .toSet()

        val pages = sync.pagesList.mapIndexed { idx, p ->
            val pageId = p.pageId.ifBlank { "page-$idx" }
            val pageRows = p.rows.takeIf { it in 1..12 } ?: defaultRows
            val pageCols = p.cols.takeIf { it in 1..16 } ?: defaultCols
            DeckPageUiModel(
                pageId = pageId,
                title = p.title.ifBlank { pageId },
                isFolder = idx > 0 && pageId in referencedFolderIds,
                rows = pageRows,
                cols = pageCols,
                buttons = p.buttonsList.map { b ->
                    DeckButtonUiModel(
                        buttonId = b.buttonId,
                        type = b.type,
                        row = b.row.coerceAtLeast(0),
                        col = b.col.coerceAtLeast(0),
                        rowSpan = b.rowSpan.coerceAtLeast(1),
                        colSpan = b.colSpan.coerceAtLeast(1),
                        title = b.title,
                        color = normalizeHexColor(b.color),
                        activeIcon = b.activeIcon,
                        inactiveIcon = b.inactiveIcon,
                        command = b.command,
                        env = LinkedHashMap(b.envMap),
                        timeoutSeconds = b.timeoutSeconds,
                        keyCombo = b.keyCombo,
                        mediaAction = b.mediaAction,
                        telemetryMetric = b.telemetryMetric,
                        targetFolderId = b.targetFolderId,
                    )
                },
            )
        }

        return DeckProfileUiModel(
            version = 1,
            profileId = sync.profileId.ifBlank { "default" },
            name = sync.name.ifBlank { "Linux Workstation Deck" },
            rows = defaultRows,
            cols = defaultCols,
            pages = pages,
        )
    }

    fun toProfileSyncProto(profile: DeckProfileUiModel): DeckProfileSync {
        val rawJson = toRawJson(profile)
        val protoPages = profile.pages.map { page ->
            val protoButtons = page.buttons.map { btn ->
                DeckButtonSpec.newBuilder()
                    .setButtonId(btn.buttonId)
                    .setType(btn.type)
                    .setRow(btn.row)
                    .setCol(btn.col)
                    .setRowSpan(btn.rowSpan.coerceAtLeast(1))
                    .setColSpan(btn.colSpan.coerceAtLeast(1))
                    .setTitle(btn.title)
                    .setColor(normalizeHexColor(btn.color))
                    .setActiveIcon(btn.activeIcon)
                    .setInactiveIcon(btn.inactiveIcon)
                    .setCommand(btn.command)
                    .putAllEnv(btn.env)
                    .setKeyCombo(btn.keyCombo)
                    .setMediaAction(btn.mediaAction)
                    .setTelemetryMetric(btn.telemetryMetric)
                    .setTargetFolderId(btn.targetFolderId)
                    .setTimeoutSeconds(btn.timeoutSeconds.coerceAtLeast(0))
                    .build()
            }
            DeckPageSpec.newBuilder()
                .setPageId(page.pageId)
                .setTitle(page.title)
                .setRows(page.rows)
                .setCols(page.cols)
                .addAllButtons(protoButtons)
                .build()
        }

        return DeckProfileSync.newBuilder()
            .setProfileId(profile.profileId)
            .setName(profile.name)
            .setRows(profile.rows)
            .setCols(profile.cols)
            .addAllPages(protoPages)
            .setRawJson(rawJson)
            .build()
    }

    fun toRawJson(profile: DeckProfileUiModel): String {
        val root = JSONObject()
        root.put("version", profile.version.coerceAtLeast(1))
        root.put("profile_id", profile.profileId.ifBlank { "default" })
        root.put("name", profile.name.ifBlank { "Linux Workstation Deck" })
        root.put("rows", profile.rows.coerceIn(1, 12))
        root.put("cols", profile.cols.coerceIn(1, 16))

        val pagesArr = JSONArray()
        for (page in profile.pages) {
            val pageObj = JSONObject()
            pageObj.put("page_id", page.pageId)
            pageObj.put("title", page.title)
            if (page.isFolder) {
                pageObj.put("is_folder", true)
            }
            pageObj.put("rows", page.rows.coerceIn(1, 12))
            pageObj.put("cols", page.cols.coerceIn(1, 16))

            val btnsArr = JSONArray()
            for (btn in page.buttons) {
                val btnObj = JSONObject()
                btnObj.put("button_id", btn.buttonId)
                btnObj.put("type", buttonTypeToString(btn.type))
                btnObj.put("row", btn.row.coerceAtLeast(0))
                btnObj.put("col", btn.col.coerceAtLeast(0))
                btnObj.put("row_span", btn.rowSpan.coerceAtLeast(1))
                btnObj.put("col_span", btn.colSpan.coerceAtLeast(1))
                btnObj.put("title", btn.title)
                btnObj.put("color", normalizeHexColor(btn.color))

                if (btn.activeIcon.isNotBlank()) btnObj.put("active_icon", btn.activeIcon)
                if (btn.inactiveIcon.isNotBlank()) btnObj.put("inactive_icon", btn.inactiveIcon)
                if (btn.command.isNotBlank()) btnObj.put("command", btn.command)
                if (btn.env.isNotEmpty()) {
                    val envObj = JSONObject()
                    btn.env.forEach { (k, v) -> envObj.put(k, v) }
                    btnObj.put("env", envObj)
                }
                if (btn.workingDir.isNotBlank()) btnObj.put("working_dir", btn.workingDir)
                if (btn.timeoutSeconds > 0) btnObj.put("timeout_seconds", btn.timeoutSeconds)
                if (btn.pollCommand.isNotBlank()) btnObj.put("poll_command", btn.pollCommand)
                if (btn.pollIntervalSeconds > 0) btnObj.put("poll_interval_seconds", btn.pollIntervalSeconds)
                if (btn.keyCombo.isNotBlank()) btnObj.put("key_combo", btn.keyCombo)
                if (btn.mediaAction.isNotBlank()) btnObj.put("media_action", btn.mediaAction)
                if (btn.telemetryMetric.isNotBlank()) btnObj.put("telemetry_metric", btn.telemetryMetric)
                if (btn.targetFolderId.isNotBlank()) btnObj.put("target_folder_id", btn.targetFolderId)

                btnsArr.put(btnObj)
            }
            pageObj.put("buttons", btnsArr)
            pagesArr.put(pageObj)
        }
        root.put("pages", pagesArr)
        return root.toString()
    }

    fun parseButtonType(raw: String): ButtonType = when (raw.trim().uppercase(Locale.ROOT)) {
        "COMMAND" -> ButtonType.COMMAND
        "KEY_COMBO" -> ButtonType.KEY_COMBO
        "MEDIA" -> ButtonType.MEDIA
        "TELEMETRY_WIDGET" -> ButtonType.TELEMETRY_WIDGET
        "FOLDER" -> ButtonType.FOLDER
        else -> ButtonType.BUTTON_TYPE_UNSPECIFIED
    }

    fun buttonTypeToString(type: ButtonType): String = when (type) {
        ButtonType.COMMAND -> "COMMAND"
        ButtonType.KEY_COMBO -> "KEY_COMBO"
        ButtonType.MEDIA -> "MEDIA"
        ButtonType.TELEMETRY_WIDGET -> "TELEMETRY_WIDGET"
        ButtonType.FOLDER -> "FOLDER"
        else -> "COMMAND"
    }

    fun parseMediaAction(action: String): MediaCommandType =
        when (action.trim().uppercase(Locale.ROOT)) {
            "PLAY_PAUSE" -> MediaCommandType.PLAY_PAUSE
            "PLAY" -> MediaCommandType.PLAY
            "PAUSE" -> MediaCommandType.PAUSE
            "NEXT" -> MediaCommandType.NEXT
            "PREVIOUS" -> MediaCommandType.PREVIOUS
            "STOP" -> MediaCommandType.STOP
            "VOLUME_UP" -> MediaCommandType.VOLUME_UP
            "VOLUME_DOWN" -> MediaCommandType.VOLUME_DOWN
            "MUTE" -> MediaCommandType.MUTE
            else -> MediaCommandType.PLAY_PAUSE
        }

    /**
     * Built-in workstation Deck profile matching `DefaultProfile()` from the Linux daemon so
     * the grid renders immediately while awaiting the initial `DeckProfileSync` packet.
     */
    fun defaultProfile(): DeckProfileUiModel = DeckProfileUiModel(
        version = 1,
        profileId = "default",
        name = "Linux Workstation Deck",
        rows = 3,
        cols = 5,
        pages = listOf(
            DeckPageUiModel(
                pageId = "main",
                title = "Controle Principal",
                isFolder = false,
                rows = 3,
                cols = 5,
                buttons = listOf(
                    DeckButtonUiModel(
                        buttonId = "widget-cpu",
                        type = ButtonType.TELEMETRY_WIDGET,
                        row = 0,
                        col = 0,
                        title = "CPU Load",
                        color = "#0F172A",
                        telemetryMetric = "CPU",
                    ),
                    DeckButtonUiModel(
                        buttonId = "widget-ram",
                        type = ButtonType.TELEMETRY_WIDGET,
                        row = 0,
                        col = 1,
                        title = "RAM Usage",
                        color = "#0F172A",
                        telemetryMetric = "RAM",
                    ),
                    DeckButtonUiModel(
                        buttonId = "shortcut-terminal",
                        type = ButtonType.KEY_COMBO,
                        row = 0,
                        col = 2,
                        title = "Terminal",
                        color = "#1E293B",
                        inactiveIcon = "terminal",
                        activeIcon = "terminal_active",
                        keyCombo = "CTRL+ALT+T",
                    ),
                    DeckButtonUiModel(
                        buttonId = "cmd-lock-screen",
                        type = ButtonType.COMMAND,
                        row = 0,
                        col = 3,
                        title = "Bloquear Tela",
                        color = "#7F1D1D",
                        inactiveIcon = "lock",
                        activeIcon = "lock_open",
                        command = "loginctl lock-session",
                        timeoutSeconds = 10,
                    ),
                    DeckButtonUiModel(
                        buttonId = "folder-sysadmin",
                        type = ButtonType.FOLDER,
                        row = 0,
                        col = 4,
                        title = "SysAdmin",
                        color = "#1D4ED8",
                        inactiveIcon = "folder",
                        targetFolderId = "sysadmin",
                    ),
                    DeckButtonUiModel(
                        buttonId = "media-prev",
                        type = ButtonType.MEDIA,
                        row = 1,
                        col = 0,
                        title = "Anterior",
                        color = "#1E293B",
                        inactiveIcon = "skip_previous",
                        mediaAction = "PREVIOUS",
                    ),
                    DeckButtonUiModel(
                        buttonId = "media-play-pause",
                        type = ButtonType.MEDIA,
                        row = 1,
                        col = 1,
                        title = "Play / Pause",
                        color = "#065F46",
                        inactiveIcon = "play_arrow",
                        activeIcon = "pause",
                        mediaAction = "PLAY_PAUSE",
                    ),
                    DeckButtonUiModel(
                        buttonId = "media-next",
                        type = ButtonType.MEDIA,
                        row = 1,
                        col = 2,
                        title = "Próxima",
                        color = "#1E293B",
                        inactiveIcon = "skip_next",
                        mediaAction = "NEXT",
                    ),
                    DeckButtonUiModel(
                        buttonId = "media-mute",
                        type = ButtonType.MEDIA,
                        row = 1,
                        col = 3,
                        title = "Mudo",
                        color = "#334155",
                        inactiveIcon = "volume_up",
                        activeIcon = "volume_off",
                        mediaAction = "MUTE",
                    ),
                    DeckButtonUiModel(
                        buttonId = "widget-temp",
                        type = ButtonType.TELEMETRY_WIDGET,
                        row = 1,
                        col = 4,
                        title = "Temperatura",
                        color = "#0F172A",
                        telemetryMetric = "TEMPERATURE",
                    ),
                    DeckButtonUiModel(
                        buttonId = "cmd-uptime",
                        type = ButtonType.COMMAND,
                        row = 2,
                        col = 0,
                        rowSpan = 1,
                        colSpan = 3,
                        title = "Uptime do Sistema",
                        color = "#1E293B",
                        inactiveIcon = "schedule",
                        command = "uptime -p || uptime",
                        timeoutSeconds = 5,
                    ),
                    DeckButtonUiModel(
                        buttonId = "widget-net",
                        type = ButtonType.TELEMETRY_WIDGET,
                        row = 2,
                        col = 3,
                        rowSpan = 1,
                        colSpan = 2,
                        title = "Rede (RX/TX)",
                        color = "#0F172A",
                        telemetryMetric = "NETWORK",
                    ),
                ),
            ),
            DeckPageUiModel(
                pageId = "sysadmin",
                title = "Ferramentas SysAdmin",
                isFolder = true,
                rows = 3,
                cols = 5,
                buttons = listOf(
                    DeckButtonUiModel(
                        buttonId = "cmd-disk-usage",
                        type = ButtonType.COMMAND,
                        row = 0,
                        col = 0,
                        title = "Uso de Disco",
                        color = "#1E293B",
                        inactiveIcon = "storage",
                        command = "df -h /",
                        timeoutSeconds = 10,
                    ),
                ),
            ),
        ),
    )
}

/**
 * Unidirectional MVVM ViewModel driving the Dynamic Macro Deck and Live Telemetry UI.
 *
 * Splits state across three independent [StateFlow] streams:
 * 1. [uiState] — Static Deck grid structure, folder navigation stack, and button execution states.
 * 2. [telemetryState] — 1 Hz CPU, RAM, Temperature, and Network counters (consumed ONLY inside
 *    `TelemetryWidgetCard` leaf scopes to isolate Compose recompositions).
 * 3. [mediaState] — MPRIS2 track metadata and playback state (consumed ONLY inside
 *    `MediaControllerBar`).
 */
class DeckViewModel(
    private val transport: DeckTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    private val _uiState = MutableStateFlow(DeckUiState())
    val uiState: StateFlow<DeckUiState> = _uiState.asStateFlow()

    private val _telemetryState = MutableStateFlow(TelemetryUiState())
    val telemetryState: StateFlow<TelemetryUiState> = _telemetryState.asStateFlow()

    private val _mediaState = MutableStateFlow(MediaUiState())
    val mediaState: StateFlow<MediaUiState> = _mediaState.asStateFlow()

    init {
        // Hydrate cached DeckProfileSync if DeckSessionClient received it prior to ViewModel creation.
        viewModelScope.launch {
            transport.latestDeckProfile
                .filterNotNull()
                .collect { sync -> applyDeckProfileSync(sync) }
        }

        // Hydrate latest TelemetrySnapshot if already available on the session client.
        viewModelScope.launch {
            transport.latestTelemetry
                .filterNotNull()
                .collect { snapshot -> applyTelemetrySnapshot(snapshot) }
        }

        // Consume real-time binary Protobuf envelopes over mTLS WebSocket.
        viewModelScope.launch {
            transport.incomingEnvelopes.collect { envelope ->
                handleIncomingEnvelope(envelope)
            }
        }
    }

    private suspend fun handleIncomingEnvelope(envelope: Envelope) {
        when (envelope.payloadCase) {
            Envelope.PayloadCase.DECK_PROFILE_SYNC -> {
                applyDeckProfileSync(envelope.deckProfileSync)
            }

            Envelope.PayloadCase.MACRO_STATE_SYNC -> {
                applyMacroStateSync(envelope.macroStateSync)
            }

            Envelope.PayloadCase.MACRO_OUTPUT_EVENT -> {
                applyMacroOutputEvent(envelope.macroOutputEvent)
            }

            Envelope.PayloadCase.TELEMETRY_SNAPSHOT -> {
                applyTelemetrySnapshot(envelope.telemetrySnapshot)
            }

            Envelope.PayloadCase.MEDIA_STATUS_EVENT -> {
                applyMediaStatus(envelope.mediaStatusEvent)
            }

            Envelope.PayloadCase.ERROR_RESPONSE -> {
                val err = envelope.errorResponse
                _uiState.update { state ->
                    state.copy(lastErrorMessage = err.message.ifBlank { "Erro remoto (${err.code})" })
                }
            }

            else -> Unit
        }
    }

    private suspend fun applyDeckProfileSync(sync: DeckProfileSync) {
        val parsedProfile = withContext(ioDispatcher) {
            DeckProfileJsonCodec.parseProfileSync(sync)
        }
        _uiState.update { current ->
            val validFolderIds = parsedProfile.pages.map { it.pageId }.toSet()
            current.copy(
                profile = parsedProfile,
                folderStack = current.folderStack.filter { it in validFolderIds },
                lastErrorMessage = null,
            )
        }
    }

    private fun applyMacroStateSync(sync: MacroStateSync) {
        val buttonId = sync.buttonId.trim()
        if (buttonId.isEmpty()) return

        _uiState.update { current ->
            val previous = current.buttonStates[buttonId]
            val updatedRuntime = ButtonRuntimeState(
                buttonId = buttonId,
                runState = sync.state,
                exitCode = sync.exitCode,
                dynamicTitle = sync.title.takeIf { it.isNotBlank() } ?: previous?.dynamicTitle,
                dynamicColor = sync.color
                    .takeIf { it.isNotBlank() && DeckProfileJsonCodec.isValidHexColor(it) }
                    ?.let { DeckProfileJsonCodec.normalizeHexColor(it) }
                    ?: previous?.dynamicColor,
                dynamicActiveIcon = sync.activeIcon.takeIf { it.isNotBlank() } ?: previous?.dynamicActiveIcon,
                dynamicInactiveIcon = sync.inactiveIcon.takeIf { it.isNotBlank() } ?: previous?.dynamicInactiveIcon,
                isActive = sync.isActive,
                lastOutputLine = previous?.lastOutputLine,
            )

            val updatedMap = LinkedHashMap<String, ButtonRuntimeState>(current.buttonStates.size + 1)
            updatedMap.putAll(current.buttonStates)
            updatedMap[buttonId] = updatedRuntime
            current.copy(buttonStates = updatedMap)
        }
    }

    private fun applyMacroOutputEvent(output: MacroOutputEvent) {
        val buttonId = output.buttonId.trim()
        if (buttonId.isEmpty()) return

        val rawChunk = when {
            !output.stdout.isEmpty -> output.stdout.toStringUtf8()
            !output.stderr.isEmpty -> output.stderr.toStringUtf8()
            else -> ""
        }
        val cleanLine = rawChunk
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !it.startsWith("DECK_STATE:") }
            ?: return

        _uiState.update { current ->
            val previous = current.buttonStates[buttonId] ?: ButtonRuntimeState(buttonId = buttonId)
            val updatedMap = LinkedHashMap<String, ButtonRuntimeState>(current.buttonStates.size + 1)
            updatedMap.putAll(current.buttonStates)
            updatedMap[buttonId] = previous.copy(lastOutputLine = cleanLine.take(64))
            current.copy(buttonStates = updatedMap)
        }
    }

    private fun applyTelemetrySnapshot(snapshot: TelemetrySnapshot) {
        val temps = LinkedHashMap(snapshot.temperaturesMap)
        val primaryEntry = selectPrimaryTemperature(temps)

        _telemetryState.value = TelemetryUiState(
            cpuPercent = snapshot.cpuPercent.coerceIn(0f, 100f),
            ramUsedMb = snapshot.ramUsedMb.coerceAtLeast(0L),
            ramTotalMb = snapshot.ramTotalMb.coerceAtLeast(0L),
            primaryTempCelsius = primaryEntry?.value,
            primaryTempSensor = primaryEntry?.key.orEmpty(),
            temperatures = temps,
            rxKbps = snapshot.rxKbps.coerceAtLeast(0f),
            txKbps = snapshot.txKbps.coerceAtLeast(0f),
            hasSample = true,
        )

        if (snapshot.hasMediaStatus()) {
            applyMediaStatus(snapshot.mediaStatus)
        }
    }

    private fun applyMediaStatus(status: MediaStatusEvent) {
        val hasSession = status.title.isNotBlank() || status.artist.isNotBlank() || status.player.isNotBlank()
        _mediaState.value = MediaUiState(
            title = status.title,
            artist = status.artist,
            album = status.album,
            isPlaying = status.isPlaying,
            player = status.player,
            volume = status.volume.coerceIn(0f, 1.5f),
            positionMs = status.positionMs.coerceAtLeast(0L),
            durationMs = status.durationMs.coerceAtLeast(0L),
            hasActiveSession = hasSession,
        )
    }

    private fun selectPrimaryTemperature(temps: Map<String, Float>): Map.Entry<String, Float>? {
        if (temps.isEmpty()) return null
        val preferredKeywords = listOf("package", "tctl", "tdie", "cpu", "coretemp", "k10temp", "x86_pkg")
        for (keyword in preferredKeywords) {
            temps.entries.firstOrNull { it.key.lowercase(Locale.ROOT).contains(keyword) }?.let { return it }
        }
        return temps.entries.maxByOrNull { it.value }
    }

    /**
     * Dispatches the action associated with tapping [button].
     * All Protobuf construction and network transmission happen off the main thread on [ioDispatcher].
     */
    fun onButtonClicked(button: DeckButtonUiModel) {
        when (button.type) {
            ButtonType.COMMAND -> dispatchMacroCommand(button)
            ButtonType.KEY_COMBO -> dispatchKeyCombo(button)
            ButtonType.MEDIA -> {
                val cmd = DeckProfileJsonCodec.parseMediaAction(button.mediaAction)
                sendMediaCommand(cmd)
            }
            ButtonType.FOLDER -> {
                if (button.targetFolderId.isNotBlank()) {
                    openFolder(button.targetFolderId)
                }
            }
            ButtonType.TELEMETRY_WIDGET,
            ButtonType.BUTTON_TYPE_UNSPECIFIED,
            ButtonType.UNRECOGNIZED -> Unit
        }
    }

    private fun dispatchMacroCommand(button: DeckButtonUiModel) {
        // Optimistic RUNNING indicator immediately upon tap before daemon round-trip completes.
        _uiState.update { current ->
            val prev = current.buttonStates[button.buttonId]
            val optimistic = (prev ?: ButtonRuntimeState(buttonId = button.buttonId)).copy(
                runState = MacroRunState.RUNNING,
                exitCode = 0,
            )
            val updatedMap = LinkedHashMap<String, ButtonRuntimeState>(current.buttonStates.size + 1)
            updatedMap.putAll(current.buttonStates)
            updatedMap[button.buttonId] = optimistic
            current.copy(buttonStates = updatedMap, lastErrorMessage = null)
        }

        viewModelScope.launch(ioDispatcher) {
            val nowMs = System.currentTimeMillis()
            val req = MacroExecRequest.newBuilder()
                .setButtonId(button.buttonId)
                .setCommand(button.command)
                .putAllEnv(button.env)
                .setTimeoutSeconds(button.timeoutSeconds.coerceAtLeast(0))
                .setWorkingDir(button.workingDir)
                .build()

            val env = Envelope.newBuilder()
                .setRequestId("macro-${UUID.randomUUID()}")
                .setTimestampMs(nowMs)
                .setMacroExecRequest(req)
                .build()

            val sent = transport.sendEnvelope(env)
            if (!sent) {
                _uiState.update { current ->
                    val prev = current.buttonStates[button.buttonId]
                    val failed = (prev ?: ButtonRuntimeState(buttonId = button.buttonId)).copy(
                        runState = MacroRunState.ERROR,
                        exitCode = -1,
                    )
                    val updatedMap = LinkedHashMap<String, ButtonRuntimeState>(current.buttonStates.size + 1)
                    updatedMap.putAll(current.buttonStates)
                    updatedMap[button.buttonId] = failed
                    current.copy(
                        buttonStates = updatedMap,
                        lastErrorMessage = "Sem conexão ativa com o PC Linux para executar '${button.title}'.",
                    )
                }
            }
        }
    }

    private fun dispatchKeyCombo(button: DeckButtonUiModel) {
        val combo = button.keyCombo.trim()
        if (combo.isEmpty()) return

        viewModelScope.launch(ioDispatcher) {
            val nowMs = System.currentTimeMillis()
            val env = Envelope.newBuilder()
                .setRequestId("combo-${UUID.randomUUID()}")
                .setTimestampMs(nowMs)
                .setKeyComboEvent(
                    KeyComboEvent.newBuilder()
                        .setCombo(combo)
                        .build(),
                )
                .build()
            transport.sendEnvelope(env)
        }
    }

    /**
     * Dispatches an MPRIS2 / system volume command on [ioDispatcher].
     */
    fun sendMediaCommand(command: MediaCommandType, volume: Float? = null) {
        if (command == MediaCommandType.MEDIA_COMMAND_TYPE_UNSPECIFIED) return
        val currentPlayer = _mediaState.value.player

        viewModelScope.launch(ioDispatcher) {
            val nowMs = System.currentTimeMillis()
            val builder = MediaControlCommand.newBuilder()
                .setCommand(command)
            if (currentPlayer.isNotBlank()) {
                builder.setPlayer(currentPlayer)
            }
            if (volume != null) {
                builder.setVolume(volume.coerceIn(0f, 1.5f))
            }

            val env = Envelope.newBuilder()
                .setRequestId("media-${UUID.randomUUID()}")
                .setTimestampMs(nowMs)
                .setMediaControlCommand(builder.build())
                .build()
            transport.sendEnvelope(env)
        }
    }

    /**
     * Navigates into a button folder page matching [folderId].
     */
    fun openFolder(folderId: String) {
        _uiState.update { current ->
            val target = current.profile.findFolderPage(folderId) ?: return@update current
            if (current.folderStack.lastOrNull() == target.pageId) {
                current
            } else {
                current.copy(folderStack = current.folderStack + target.pageId)
            }
        }
    }

    /**
     * Pops one folder level from [DeckUiState.folderStack].
     *
     * @return `true` if a folder was popped, or `false` if already at the top-level pager.
     */
    fun navigateBackFromFolder(): Boolean {
        var popped = false
        _uiState.update { current ->
            if (current.folderStack.isNotEmpty()) {
                popped = true
                current.copy(folderStack = current.folderStack.dropLast(1))
            } else {
                current
            }
        }
        return popped
    }

    fun closeAllFolders() {
        _uiState.update { current ->
            if (current.folderStack.isEmpty()) current else current.copy(folderStack = emptyList())
        }
    }

    /**
     * Opens the quick-edit modal for [button] upon long press.
     */
    fun openQuickEdit(button: DeckButtonUiModel) {
        val runtime = _uiState.value.buttonStates[button.buttonId]
        val actionVal = when (button.type) {
            ButtonType.COMMAND -> button.command
            ButtonType.KEY_COMBO -> button.keyCombo
            ButtonType.MEDIA -> button.mediaAction
            ButtonType.TELEMETRY_WIDGET -> button.telemetryMetric
            ButtonType.FOLDER -> button.targetFolderId
            else -> button.command
        }
        _uiState.update { current ->
            current.copy(
                quickEditDraft = QuickEditButtonDraft(
                    buttonId = button.buttonId,
                    type = button.type,
                    title = runtime?.dynamicTitle ?: button.title,
                    color = runtime?.dynamicColor ?: button.color,
                    inactiveIcon = runtime?.dynamicInactiveIcon ?: button.inactiveIcon,
                    activeIcon = runtime?.dynamicActiveIcon ?: button.activeIcon,
                    actionValue = actionVal,
                ),
            )
        }
    }

    fun dismissQuickEdit() {
        _uiState.update { it.copy(quickEditDraft = null) }
    }

    /**
     * Applies [draft] locally and synchronizes the updated `DeckProfileSync` (including schema-valid
     * `raw_json`) back to the Linux daemon on [ioDispatcher].
     */
    fun saveButtonQuickEdit(draft: QuickEditButtonDraft) {
        val cleanTitle = draft.title.trim().ifEmpty { "Botão" }
        val cleanColor = DeckProfileJsonCodec.normalizeHexColor(draft.color)
        val cleanInactiveIcon = draft.inactiveIcon.trim()
        val cleanActiveIcon = draft.activeIcon.trim()
        val cleanAction = draft.actionValue.trim()

        var updatedProfileSnapshot: DeckProfileUiModel? = null

        _uiState.update { current ->
            val updatedPages = current.profile.pages.map { page ->
                val updatedButtons = page.buttons.map { btn ->
                    if (btn.buttonId != draft.buttonId) {
                        btn
                    } else {
                        when (draft.type) {
                            ButtonType.COMMAND -> btn.copy(
                                type = ButtonType.COMMAND,
                                title = cleanTitle,
                                color = cleanColor,
                                inactiveIcon = cleanInactiveIcon,
                                activeIcon = cleanActiveIcon,
                                command = cleanAction,
                            )

                            ButtonType.KEY_COMBO -> btn.copy(
                                type = ButtonType.KEY_COMBO,
                                title = cleanTitle,
                                color = cleanColor,
                                inactiveIcon = cleanInactiveIcon,
                                activeIcon = cleanActiveIcon,
                                keyCombo = cleanAction.uppercase(Locale.ROOT),
                            )

                            ButtonType.MEDIA -> btn.copy(
                                type = ButtonType.MEDIA,
                                title = cleanTitle,
                                color = cleanColor,
                                inactiveIcon = cleanInactiveIcon,
                                activeIcon = cleanActiveIcon,
                                mediaAction = cleanAction.uppercase(Locale.ROOT).ifEmpty { "PLAY_PAUSE" },
                            )

                            ButtonType.TELEMETRY_WIDGET -> btn.copy(
                                type = ButtonType.TELEMETRY_WIDGET,
                                title = cleanTitle,
                                color = cleanColor,
                                telemetryMetric = cleanAction.uppercase(Locale.ROOT).ifEmpty { "CPU" },
                            )

                            ButtonType.FOLDER -> btn.copy(
                                type = ButtonType.FOLDER,
                                title = cleanTitle,
                                color = cleanColor,
                                inactiveIcon = cleanInactiveIcon.ifEmpty { "folder" },
                                activeIcon = cleanActiveIcon,
                                targetFolderId = cleanAction,
                            )

                            else -> btn.copy(
                                title = cleanTitle,
                                color = cleanColor,
                                inactiveIcon = cleanInactiveIcon,
                                activeIcon = cleanActiveIcon,
                            )
                        }
                    }
                }
                page.copy(buttons = updatedButtons)
            }

            val newProfile = current.profile.copy(pages = updatedPages)
            updatedProfileSnapshot = newProfile

            // Clear stale dynamic title/color overrides for the edited button so user edits take precedence immediately.
            val cleanedRuntimeMap = LinkedHashMap(current.buttonStates)
            cleanedRuntimeMap[draft.buttonId]?.let { existing ->
                cleanedRuntimeMap[draft.buttonId] = existing.copy(
                    dynamicTitle = null,
                    dynamicColor = null,
                    dynamicActiveIcon = null,
                    dynamicInactiveIcon = null,
                )
            }

            current.copy(
                profile = newProfile,
                buttonStates = cleanedRuntimeMap,
                quickEditDraft = null,
                lastErrorMessage = null,
            )
        }

        val profileToSync = updatedProfileSnapshot ?: return
        viewModelScope.launch(ioDispatcher) {
            val protoSync = DeckProfileJsonCodec.toProfileSyncProto(profileToSync)
            val env = Envelope.newBuilder()
                .setRequestId("profile-save-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setDeckProfileSync(protoSync)
                .build()
            val sent = transport.sendEnvelope(env)
            if (!sent) {
                _uiState.update { state ->
                    state.copy(
                        lastErrorMessage = "Perfil atualizado localmente, mas sem conexão para sincronizar com o Linux.",
                    )
                }
            }
        }
    }

    /**
     * Requests a fresh authoritative `DeckProfileSync` from the Linux daemon.
     */
    fun requestProfileSync() {
        viewModelScope.launch(ioDispatcher) {
            val env = Envelope.newBuilder()
                .setRequestId("profile-pull-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setDeckProfileSync(DeckProfileSync.getDefaultInstance())
                .build()
            transport.sendEnvelope(env)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(lastErrorMessage = null) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val client = DeckConnectionService.obtainClient(appContext)
                    return DeckViewModel(
                        transport = SessionClientDeckTransport(client),
                        ioDispatcher = Dispatchers.IO,
                    ) as T
                }
            }
        }
    }
}

