package com.linuxdeck.client.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private const val PTY_IME_SENTINEL = " "

/**
 * Interactive 100% offline VT100/ANSI & 256-color Linux PTY Terminal Emulator screen (`TASK-13`).
 *
 * - Opens a remote PTY session (`PtyOpenRequest`) with exact `(cols, rows)` computed from monospace
 *   cell metrics and dynamically synchronizes terminal geometry via `PtyResizeEvent` when rotating
 *   the screen, resizing font, or toggling the Android soft keyboard (`imePadding()`).
 * - Renders 16/256-bit xterm colors, TrueColor RGB, inverse video, bold/underline, box-drawing
 *   graphics, and alternate screen buffers (`vim`, `htop`, `less`) natively on a zero-latency
 *   Compose `Canvas` without any external CDN or WebView network dependency.
 * - Includes the Sysadmin quick toolbar above the keyboard with `ESC`, `TAB`, `CTRL+C`, `CTRL+Z`,
 *   `CTRL+D`, `CTRL+L`, directional arrows (`↑`, `↓`, `←`, `→`), `|`, `/`, and `-`.
 */
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    terminalViewModel: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(LocalContext.current)),
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val prevKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = prevKeepScreenOn
        }
    }

    val uiState by terminalViewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var fontSizeSp by remember { mutableIntStateOf(13) }
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }
    var isKeyboardRequested by remember { mutableStateOf(true) }
    var imeBuffer by remember {
        mutableStateOf(
            TextFieldValue(
                text = PTY_IME_SENTINEL,
                selection = TextRange(PTY_IME_SENTINEL.length),
            ),
        )
    }

    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current

    val baseTextStyle = remember(fontSizeSp) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 3).sp,
        )
    }

    val cellMetrics = remember(baseTextStyle, density) {
        val measured = textMeasurer.measure(
            text = "M",
            style = baseTextStyle,
            softWrap = false,
            maxLines = 1,
        )
        val w = measured.size.width.coerceAtLeast(6).toFloat()
        val h = measured.size.height.coerceAtLeast(10).toFloat()
        w to h
    }
    val cellWidthPx = cellMetrics.first
    val cellHeightPx = cellMetrics.second

    val padPx = with(density) { 8.dp.toPx() }

    // Synchronize (cols, rows) with Linux PTY master whenever canvas dimensions or font size change.
    LaunchedEffect(canvasSizePx, cellWidthPx, cellHeightPx) {
        if (canvasSizePx.width > 0 && canvasSizePx.height > 0) {
            val usableW = (canvasSizePx.width - padPx * 2f).coerceAtLeast(cellWidthPx * 20f)
            val usableH = (canvasSizePx.height - padPx * 2f).coerceAtLeast(cellHeightPx * 5f)
            val computedCols = (usableW / cellWidthPx).toInt().coerceIn(20, 240)
            val computedRows = (usableH / cellHeightPx).toInt().coerceIn(5, 120)
            terminalViewModel.onTerminalViewportMeasured(cols = computedCols, rows = computedRows)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun summonKeyboard() {
        isKeyboardRequested = true
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .imePadding(),
    ) {
        // Hidden IME input bridge feeding raw UTF-8 bytes & Backspace/Enter directly to PTY stdin
        BasicTextField(
            value = imeBuffer,
            onValueChange = { next ->
                val raw = next.text
                when {
                    raw.isEmpty() -> {
                        // DEL (0x7F) is the standard xterm-256color Backspace byte on Linux PTY
                        terminalViewModel.sendPtyBytes(byteArrayOf(0x7F))
                    }
                    raw.startsWith(PTY_IME_SENTINEL) && raw.length > PTY_IME_SENTINEL.length -> {
                        val typed = raw.substring(PTY_IME_SENTINEL.length)
                        val normalized = typed.replace("\n", "\r")
                        terminalViewModel.onKeyboardTextCommitted(normalized)
                    }
                    raw != PTY_IME_SENTINEL -> {
                        terminalViewModel.onKeyboardTextCommitted(raw.replace("\n", "\r"))
                    }
                }
                imeBuffer = TextFieldValue(
                    text = PTY_IME_SENTINEL,
                    selection = TextRange(PTY_IME_SENTINEL.length),
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = { terminalViewModel.sendPtyBytes(byteArrayOf('\r'.code.toByte())) },
                onDone = { terminalViewModel.sendPtyBytes(byteArrayOf('\r'.code.toByte())) },
            ),
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )

        // Compact Terminal Title, Geometry Badge, Font Zoom, Paste & Session Restart Bar
        TerminalSessionHeader(
            windowTitle = uiState.screen.windowTitle,
            cols = uiState.screen.cols,
            rows = uiState.screen.rows,
            isSessionOpen = uiState.isSessionOpen,
            scrollOffsetLines = uiState.scrollOffsetLines,
            onDecreaseFont = { fontSizeSp = (fontSizeSp - 1).coerceIn(9, 24) },
            onIncreaseFont = { fontSizeSp = (fontSizeSp + 1).coerceIn(9, 24) },
            onPasteClipboard = {
                val clipText = clipboardManager.getText()?.text
                if (!clipText.isNullOrEmpty()) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    terminalViewModel.sendPtyBytes(clipText.toByteArray(Charsets.UTF_8))
                }
            },
            onToggleKeyboard = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (isKeyboardRequested) {
                    isKeyboardRequested = false
                    keyboardController?.hide()
                } else {
                    summonKeyboard()
                }
            },
            onRestartSession = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                terminalViewModel.openSession(
                    cols = uiState.screen.cols,
                    rows = uiState.screen.rows,
                )
            },
        )

        // Optional PTY exit / status banner
        uiState.exitBanner?.let { banner ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable {
                        terminalViewModel.openSession(
                            cols = uiState.screen.cols,
                            rows = uiState.screen.rows,
                        )
                    },
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
            ) {
                Text(
                    text = "$banner — Toque para reabrir sessão",
                    color = Color(0xFFE0F2FE),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // Native Zero-Latency ANSI/VT100 & 256-Color Terminal Canvas
        val renderedRows = remember(uiState.screen.revision, uiState.scrollOffsetLines, baseTextStyle) {
            uiState.screen.visibleRows.map { row -> buildStyledRow(row) }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSizePx = it }
                .pointerInput(cellHeightPx) {
                    var dragRemainderPx = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragRemainderPx += dragAmount
                            val deltaLines = (dragRemainderPx / cellHeightPx).toInt()
                            if (deltaLines != 0) {
                                dragRemainderPx -= deltaLines * cellHeightPx
                                terminalViewModel.scrollHistoryBy(deltaLines)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { summonKeyboard() },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val snapshot = uiState.screen
                val defaultBg = AnsiColorPalette.DEFAULT_BG_ARGB

                // 1. Draw non-default cell background color runs
                for (r in snapshot.visibleRows.indices) {
                    val cells = snapshot.visibleRows[r].cells
                    val y = padPx + r * cellHeightPx
                    var c = 0
                    while (c < cells.size) {
                        val bg = cells[c].effectiveBgArgb
                        if (bg != defaultBg) {
                            var runEnd = c + 1
                            while (runEnd < cells.size && cells[runEnd].effectiveBgArgb == bg) {
                                runEnd++
                            }
                            drawRect(
                                color = Color(bg),
                                topLeft = Offset(padPx + c * cellWidthPx, y),
                                size = Size((runEnd - c) * cellWidthPx, cellHeightPx),
                            )
                            c = runEnd
                        } else {
                            c++
                        }
                    }
                }

                // 2. Draw monospace ANSI/256-color text per row
                for (r in renderedRows.indices) {
                    val annotatedRow = renderedRows[r]
                    if (annotatedRow.isNotEmpty()) {
                        drawText(
                            textMeasurer = textMeasurer,
                            text = annotatedRow,
                            style = baseTextStyle,
                            topLeft = Offset(padPx, padPx + r * cellHeightPx),
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }

                // 3. Draw active terminal block cursor when at bottom of scrollback
                if (snapshot.cursorVisible && uiState.scrollOffsetLines == 0 && uiState.isSessionOpen) {
                    val cursorX = padPx + snapshot.cursorCol * cellWidthPx
                    val cursorY = padPx + snapshot.cursorRow * cellHeightPx
                    drawRect(
                        color = Color(0xFF38BDF8).copy(alpha = 0.65f),
                        topLeft = Offset(cursorX, cursorY),
                        size = Size(cellWidthPx, cellHeightPx),
                    )
                }
            }
        }

        // Sysadmin Quick Keys Toolbar positioned directly above the keyboard
        SysadminQuickToolbar(
            isCtrlArmed = uiState.isCtrlArmed,
            onToggleCtrl = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                terminalViewModel.toggleCtrlArmed()
                summonKeyboard()
            },
            onQuickKey = { quickKey ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                terminalViewModel.onQuickKeyPressed(quickKey)
            },
        )
    }
}

/**
 * Builds a compact [AnnotatedString] coalescing adjacent cells with identical SGR color/style runs.
 */
private fun buildStyledRow(row: TerminalRowSnapshot): AnnotatedString {
    val cells = row.cells
    // Trim trailing default blank spaces to skip unnecessary glyph rasterization
    var lastNonBlank = cells.size - 1
    while (lastNonBlank >= 0) {
        val cell = cells[lastNonBlank]
        if (cell.ch != ' ' || cell.underline || cell.effectiveBgArgb != AnsiColorPalette.DEFAULT_BG_ARGB) {
            break
        }
        lastNonBlank--
    }
    if (lastNonBlank < 0) return AnnotatedString("")

    return buildAnnotatedString {
        var i = 0
        while (i <= lastNonBlank) {
            val first = cells[i]
            val fg = first.effectiveFgArgb
            val bold = first.bold
            val underline = first.underline

            var j = i + 1
            while (j <= lastNonBlank) {
                val next = cells[j]
                if (next.effectiveFgArgb != fg || next.bold != bold || next.underline != underline) {
                    break
                }
                j++
            }

            withStyle(
                SpanStyle(
                    color = Color(fg),
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
                ),
            ) {
                for (k in i until j) {
                    append(cells[k].ch)
                }
            }
            i = j
        }
    }
}

@Composable
private fun TerminalSessionHeader(
    windowTitle: String,
    cols: Int,
    rows: Int,
    isSessionOpen: Boolean,
    scrollOffsetLines: Int,
    onDecreaseFont: () -> Unit,
    onIncreaseFont: () -> Unit,
    onPasteClipboard: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onRestartSession: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0F172A),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isSessionOpen) Color(0xFF22C55E) else Color(0xFFF59E0B)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = windowTitle.ifBlank { "xterm-256color" },
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (scrollOffsetLines > 0) {
                        "${cols}x${rows} [↑$scrollOffsetLines]"
                    } else {
                        "${cols}x${rows}"
                    },
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDecreaseFont, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = "Diminuir fonte",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onIncreaseFont, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Aumentar fonte",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onPasteClipboard, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = "Colar área de transferência",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onToggleKeyboard, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = "Alternar teclado",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(onClick = onRestartSession, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reiniciar sessão PTY",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/**
 * Sysadmin quick-key bar rendered directly above the keyboard containing:
 * `ESC`, `TAB`, `CTRL` (sticky chord modifier), `CTRL+C`, `CTRL+Z`, `CTRL+D`, `CTRL+L`,
 * `Setas (↑ ↓ ← →)`, `|` (pipe), `/`, and `-`.
 */
@Composable
private fun SysadminQuickToolbar(
    isCtrlArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onQuickKey: (SysadminQuickKey) -> Unit,
) {
    val orderedKeys = remember {
        listOf(
            SysadminQuickKey.ESC,
            SysadminQuickKey.TAB,
            SysadminQuickKey.CTRL_C,
            SysadminQuickKey.CTRL_Z,
            SysadminQuickKey.CTRL_D,
            SysadminQuickKey.CTRL_L,
            SysadminQuickKey.ARROW_UP,
            SysadminQuickKey.ARROW_DOWN,
            SysadminQuickKey.ARROW_LEFT,
            SysadminQuickKey.ARROW_RIGHT,
            SysadminQuickKey.PIPE,
            SysadminQuickKey.SLASH,
            SysadminQuickKey.DASH,
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sticky CTRL toggle for arbitrary Ctrl+<letter> terminal chords
            SysadminKeyPill(
                label = "CTRL",
                highlighted = isCtrlArmed,
                dangerAccent = false,
                onClick = onToggleCtrl,
            )

            orderedKeys.forEach { key ->
                SysadminKeyPill(
                    label = key.label,
                    highlighted = false,
                    dangerAccent = key == SysadminQuickKey.CTRL_C || key == SysadminQuickKey.CTRL_D,
                    onClick = { onQuickKey(key) },
                )
            }
        }
    }
}

@Composable
private fun SysadminKeyPill(
    label: String,
    highlighted: Boolean,
    dangerAccent: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = when {
        highlighted -> Color(0xFF38BDF8)
        dangerAccent -> Color(0xFFF87171).copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.16f)
    }
    val textColor = when {
        highlighted -> Color(0xFFE0F2FE)
        dangerAccent -> Color(0xFFFCA5A5)
        else -> Color.White
    }

    Surface(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) Color(0xFF0284C7).copy(alpha = 0.35f) else Color(0xFF1E293B),
        border = BorderStroke(if (highlighted) 1.5.dp else 1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

