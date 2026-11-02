package com.linuxdeck.client.ui.deck

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.protocol.v1.ButtonType
import com.linuxdeck.client.protocol.v1.MediaCommandType
import com.linuxdeck.client.ui.deck.components.DeckButtonCard
import com.linuxdeck.client.ui.deck.components.MediaControllerBar
import com.linuxdeck.client.ui.deck.components.QuickEditButtonDialog
import com.linuxdeck.client.ui.deck.components.TelemetryWidgetCard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val DeckBackgroundColor = Color(0xFF020617)
private val HeaderSurfaceColor = Color(0xFF0F172A)

/**
 * Stateful entry point for the Dynamic Macro Deck & Live Telemetry screen.
 *
 * Enforces `KeepScreenOn` while visible and wires [DeckViewModel] streams with strict
 * Compose recomposition isolation:
 * - [DeckViewModel.uiState] and [DeckViewModel.connectionState] drive the structural grid.
 * - [DeckViewModel.telemetryState] is passed down as an uncollected [StateFlow] reference so only
 *   [TelemetryWidgetCard] leaf scopes recompose on 1 Hz `TelemetrySnapshot` packets.
 * - [DeckViewModel.mediaState] is passed down as an uncollected [StateFlow] reference to
 *   [MediaControllerBar].
 */
@Composable
fun DeckScreen(
    modifier: Modifier = Modifier,
    onNavigateToPairing: (() -> Unit)? = null,
    deckViewModel: DeckViewModel = viewModel(factory = DeckViewModel.factory(LocalContext.current)),
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val previousKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
        }
    }

    val uiState by deckViewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by deckViewModel.connectionState.collectAsStateWithLifecycle()

    BackHandler(enabled = uiState.folderStack.isNotEmpty()) {
        deckViewModel.navigateBackFromFolder()
    }

    val onButtonClick = remember<(DeckButtonUiModel) -> Unit>(deckViewModel) {
        deckViewModel::onButtonClicked
    }
    val onButtonLongClick = remember<(DeckButtonUiModel) -> Unit>(deckViewModel) {
        deckViewModel::openQuickEdit
    }
    val onMediaCommand = remember<(MediaCommandType) -> Unit>(deckViewModel) {
        { cmd -> deckViewModel.sendMediaCommand(cmd) }
    }

    DeckScreenContent(
        uiState = uiState,
        connectionState = connectionState,
        telemetryFlow = deckViewModel.telemetryState,
        mediaFlow = deckViewModel.mediaState,
        onButtonClick = onButtonClick,
        onButtonLongClick = onButtonLongClick,
        onBackFromFolder = deckViewModel::navigateBackFromFolder,
        onRefreshProfile = deckViewModel::requestProfileSync,
        onMediaCommand = onMediaCommand,
        onDismissError = deckViewModel::dismissError,
        onNavigateToPairing = onNavigateToPairing,
        modifier = modifier,
    )

    uiState.quickEditDraft?.let { draft ->
        QuickEditButtonDialog(
            initialDraft = draft,
            onDismiss = deckViewModel::dismissQuickEdit,
            onSave = deckViewModel::saveButtonQuickEdit,
        )
    }
}

@Composable
internal fun DeckScreenContent(
    uiState: DeckUiState,
    connectionState: ConnectionState,
    telemetryFlow: StateFlow<TelemetryUiState>,
    mediaFlow: StateFlow<MediaUiState>,
    onButtonClick: (DeckButtonUiModel) -> Unit,
    onButtonLongClick: (DeckButtonUiModel) -> Unit,
    onBackFromFolder: () -> Unit,
    onRefreshProfile: () -> Unit,
    onMediaCommand: (MediaCommandType) -> Unit,
    onDismissError: () -> Unit,
    onNavigateToPairing: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val topLevelPages = uiState.profile.topLevelPages
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { topLevelPages.size.coerceAtLeast(1) },
    )
    val coroutineScope = rememberCoroutineScope()
    val activeFolder = uiState.activeFolderPage

    val outerVerticalPadding = if (isLandscape) 8.dp else 12.dp
    val outerHorizontalPadding = if (isLandscape) 12.dp else 12.dp
    val gridSpacing = if (isLandscape) 10.dp else 8.dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeckBackgroundColor)
            .safeDrawingPadding()
            .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 10.dp),
    ) {
        // Top status / breadcrumb bar
        DeckTopBar(
            profileName = uiState.profile.name,
            activeFolder = activeFolder,
            currentPageTitle = activeFolder?.title
                ?: topLevelPages.getOrNull(pagerState.currentPage)?.title.orEmpty(),
            connectionState = connectionState,
            pageCount = topLevelPages.size,
            currentPageIndex = pagerState.currentPage,
            onSelectPage = { targetIdx ->
                coroutineScope.launch { pagerState.animateScrollToPage(targetIdx) }
            },
            onBackFromFolder = onBackFromFolder,
            onRefreshProfile = onRefreshProfile,
            onNavigateToPairing = onNavigateToPairing,
        )

        // Non-intrusive dismissible sync/execution error banner when present
        uiState.lastErrorMessage?.let { errorMsg ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismissError),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF7F1D1D).copy(alpha = 0.85f),
            ) {
                Text(
                    text = errorMsg,
                    color = Color(0xFFFECACA),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Main Configurable Deck Grid (Folder View OR HorizontalPager across Top-Level Pages)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (activeFolder != null) {
                DeckPageGrid(
                    page = activeFolder,
                    buttonStates = uiState.buttonStates,
                    telemetryFlow = telemetryFlow,
                    cellSpacing = gridSpacing,
                    onButtonClick = onButtonClick,
                    onButtonLongClick = onButtonLongClick,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    key = { index -> topLevelPages.getOrNull(index)?.pageId ?: "page-$index" },
                    pageSpacing = 12.dp,
                    modifier = Modifier.fillMaxSize(),
                ) { pageIndex ->
                    val page = topLevelPages.getOrNull(pageIndex)
                    if (page != null) {
                        DeckPageGrid(
                            page = page,
                            buttonStates = uiState.buttonStates,
                            telemetryFlow = telemetryFlow,
                            cellSpacing = gridSpacing,
                            onButtonClick = onButtonClick,
                            onButtonLongClick = onButtonLongClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // Bottom MPRIS2 Media & Volume Bar (isolated recomposition scope)
        MediaControllerBar(
            mediaFlow = mediaFlow,
            onMediaCommand = onMediaCommand,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeckTopBar(
    profileName: String,
    activeFolder: DeckPageUiModel?,
    currentPageTitle: String,
    connectionState: ConnectionState,
    pageCount: Int,
    currentPageIndex: Int,
    onSelectPage: (Int) -> Unit,
    onBackFromFolder: () -> Unit,
    onRefreshProfile: () -> Unit,
    onNavigateToPairing: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = HeaderSurfaceColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (activeFolder != null) {
                    IconButton(
                        onClick = onBackFromFolder,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Voltar da pasta",
                            tint = Color(0xFF60A5FA),
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    ConnectionStatusDot(connectionState = connectionState)
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentPageTitle.ifBlank { profileName },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatConnectionSubtitle(profileName, connectionState),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Page indicator dots when at top-level with multiple pages
            if (activeFolder == null && pageCount > 1) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(pageCount) { idx ->
                        val selected = idx == currentPageIndex
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(if (selected) 20.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.25f),
                                )
                                .clickable { onSelectPage(idx) },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRefreshProfile,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Sincronizar perfil",
                        tint = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.size(18.dp),
                    )
                }

                if (onNavigateToPairing != null && connectionState !is ConnectionState.Connected) {
                    IconButton(
                        onClick = onNavigateToPairing,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LinkOff,
                            contentDescription = "Pareamento",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusDot(connectionState: ConnectionState) {
    val dotColor = when (connectionState) {
        is ConnectionState.Connected -> Color(0xFF22C55E)
        is ConnectionState.Connecting,
        is ConnectionState.Reconnecting -> Color(0xFFF59E0B)
        is ConnectionState.AuthFailed,
        is ConnectionState.Disconnected -> Color(0xFFEF4444)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(dotColor),
    )
}

private fun formatConnectionSubtitle(
    profileName: String,
    connectionState: ConnectionState,
): String = when (connectionState) {
    is ConnectionState.Connected -> {
        val rtt = connectionState.latencyMs?.let { " • ${it}ms" }.orEmpty()
        "${connectionState.hostDeviceName}$rtt"
    }
    is ConnectionState.Connecting -> "Conectando ${connectionState.host}:${connectionState.port}..."
    is ConnectionState.Reconnecting -> "Reconectando (#${connectionState.attempt})..."
    is ConnectionState.AuthFailed -> "Falha mTLS: ${connectionState.message}"
    is ConnectionState.Disconnected -> "$profileName • Desconectado"
}

/**
 * Renders a configurable `rows` x `cols` Stream-Deck grid supporting multi-cell spans
 * (`rowSpan`, `colSpan`) in both Portrait and Landscape orientations.
 *
 * Notice [telemetryFlow] is forwarded untouched to [TelemetryWidgetCard] and NEVER collected here.
 */
@Composable
fun DeckPageGrid(
    page: DeckPageUiModel,
    buttonStates: Map<String, ButtonRuntimeState>,
    telemetryFlow: StateFlow<TelemetryUiState>,
    cellSpacing: Dp,
    onButtonClick: (DeckButtonUiModel) -> Unit,
    onButtonLongClick: (DeckButtonUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = page.rows.coerceIn(1, 12)
    val cols = page.cols.coerceIn(1, 16)

    // Compute unoccupied grid slots so empty Deck keys still show subtle recessed slots.
    val emptySlots = remember(page.buttons, rows, cols) {
        computeEmptyGridSlots(page.buttons, rows, cols)
    }

    val placements = remember(page.buttons, emptySlots, rows, cols) {
        buildList {
            page.buttons.forEach { btn ->
                val safeRow = btn.row.coerceIn(0, rows - 1)
                val safeCol = btn.col.coerceIn(0, cols - 1)
                val safeRowSpan = btn.rowSpan.coerceIn(1, rows - safeRow)
                val safeColSpan = btn.colSpan.coerceIn(1, cols - safeCol)
                add(GridCellSpec(safeRow, safeCol, safeRowSpan, safeColSpan))
            }
            emptySlots.forEach { (r, c) ->
                add(GridCellSpec(r, c, 1, 1))
            }
        }
    }

    Layout(
        modifier = modifier,
        content = {
            page.buttons.forEach { button ->
                key(button.buttonId) {
                    if (button.type == ButtonType.TELEMETRY_WIDGET) {
                        TelemetryWidgetCard(
                            button = button,
                            telemetryFlow = telemetryFlow,
                            onLongClick = onButtonLongClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        DeckButtonCard(
                            button = button,
                            runtimeState = buttonStates[button.buttonId],
                            onClick = onButtonClick,
                            onLongClick = onButtonLongClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            emptySlots.forEach { (r, c) ->
                key("empty-${page.pageId}-$r-$c") {
                    EmptyDeckSlot()
                }
            }
        },
    ) { measurables, constraints ->
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        val heightPx = constraints.maxHeight.coerceAtLeast(1)
        val spacingPx = cellSpacing.roundToPx()

        val totalHorizontalSpacing = spacingPx * (cols - 1)
        val totalVerticalSpacing = spacingPx * (rows - 1)

        val cellWidth = ((widthPx - totalHorizontalSpacing) / cols).coerceAtLeast(1)
        val cellHeight = ((heightPx - totalVerticalSpacing) / rows).coerceAtLeast(1)

        val placeables = measurables.mapIndexed { index, measurable ->
            val spec = placements.getOrNull(index) ?: GridCellSpec(0, 0, 1, 1)
            val itemW = (cellWidth * spec.colSpan + spacingPx * (spec.colSpan - 1)).coerceAtLeast(1)
            val itemH = (cellHeight * spec.rowSpan + spacingPx * (spec.rowSpan - 1)).coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(itemW, itemH))
            spec to placeable
        }

        layout(widthPx, heightPx) {
            placeables.forEach { (spec, placeable) ->
                val x = spec.col * (cellWidth + spacingPx)
                val y = spec.row * (cellHeight + spacingPx)
                placeable.placeRelative(x = x, y = y)
            }
        }
    }
}

private data class GridCellSpec(
    val row: Int,
    val col: Int,
    val rowSpan: Int,
    val colSpan: Int,
)

@Composable
private fun EmptyDeckSlot() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.38f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp),
            ),
    )
}

private fun computeEmptyGridSlots(
    buttons: List<DeckButtonUiModel>,
    rows: Int,
    cols: Int,
): List<Pair<Int, Int>> {
    val occupied = Array(rows) { BooleanArray(cols) }
    for (btn in buttons) {
        val startR = btn.row.coerceIn(0, rows - 1)
        val startC = btn.col.coerceIn(0, cols - 1)
        val endR = (startR + btn.rowSpan.coerceAtLeast(1)).coerceAtMost(rows)
        val endC = (startC + btn.colSpan.coerceAtLeast(1)).coerceAtMost(cols)
        for (r in startR until endR) {
            for (c in startC until endC) {
                occupied[r][c] = true
            }
        }
    }

    val result = ArrayList<Pair<Int, Int>>()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            if (!occupied[r][c]) {
                result.add(r to c)
            }
        }
    }
    return result
}

