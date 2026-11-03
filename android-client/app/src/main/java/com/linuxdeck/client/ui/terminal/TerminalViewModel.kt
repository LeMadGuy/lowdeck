package com.linuxdeck.client.ui.terminal

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.protobuf.ByteString
import com.linuxdeck.client.fixes.SafeTerminalEngine
import com.linuxdeck.client.fixes.TerminalLineRingBuffer
import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.network.DeckConnectionService
import com.linuxdeck.client.network.DeckSessionClient
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.PtyCloseEvent
import com.linuxdeck.client.protocol.v1.PtyDataEvent
import com.linuxdeck.client.protocol.v1.PtyOpenRequest
import com.linuxdeck.client.protocol.v1.PtyResizeEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * Transport abstraction for interactive PTY binary envelopes over mTLS 1.3 WebSocket.
 */
interface TerminalTransport {
    val connectionState: StateFlow<ConnectionState>
    val incomingEnvelopes: SharedFlow<Envelope>
    suspend fun sendEnvelope(envelope: Envelope): Boolean
    fun sendAsync(envelope: Envelope): Boolean
}

class SessionClientTerminalTransport(
    private val sessionClient: DeckSessionClient,
) : TerminalTransport {
    override val connectionState: StateFlow<ConnectionState> = sessionClient.connectionState
    override val incomingEnvelopes: SharedFlow<Envelope> = sessionClient.incomingEnvelopes
    override suspend fun sendEnvelope(envelope: Envelope): Boolean = sessionClient.sendEnvelope(envelope)
    override fun sendAsync(envelope: Envelope): Boolean = sessionClient.sendAsync(envelope)
}

/**
 * Quick Sysadmin keys displayed above the keyboard on `TerminalScreen` (`TASK-13`).
 */
enum class SysadminQuickKey(val label: String) {
    ESC("ESC"),
    TAB("TAB"),
    CTRL_C("CTRL+C"),
    CTRL_Z("CTRL+Z"),
    CTRL_D("CTRL+D"),
    CTRL_L("CTRL+L"),
    ARROW_UP("↑"),
    ARROW_DOWN("↓"),
    ARROW_LEFT("←"),
    ARROW_RIGHT("→"),
    PIPE("|"),
    SLASH("/"),
    DASH("-"),
}

/**
 * Resolves 16-color ANSI, full 256-color xterm (`0..255`), and 24-bit TrueColor ARGB values.
 */
object AnsiColorPalette {
    const val DEFAULT_FG_ARGB: Long = 0xFFE2E8F0L
    const val DEFAULT_BG_ARGB: Long = 0xFF020617L

    private val ansi16 = longArrayOf(
        0xFF0F172AL, // 0 Black
        0xFFEF4444L, // 1 Red
        0xFF22C55EL, // 2 Green
        0xFFEAB308L, // 3 Yellow
        0xFF3B82F6L, // 4 Blue
        0xFFA855F7L, // 5 Magenta
        0xFF06B6D4L, // 6 Cyan
        0xFFCBD5E1L, // 7 White
        0xFF64748BL, // 8 Bright Black (Gray)
        0xFFF87171L, // 9 Bright Red
        0xFF4ADE80L, // 10 Bright Green
        0xFFFACC15L, // 11 Bright Yellow
        0xFF60A5FAL, // 12 Bright Blue
        0xFFC084FCL, // 13 Bright Magenta
        0xFF22D3EEL, // 14 Bright Cyan
        0xFFF8FAFCL, // 15 Bright White
    )

    private val cubeSteps = intArrayOf(0, 95, 135, 175, 215, 255)

    fun colorFor256Index(index: Int): Long {
        val safe = index.coerceIn(0, 255)
        if (safe < 16) {
            return ansi16[safe]
        }
        if (safe in 16..231) {
            val offset = safe - 16
            val r = cubeSteps[(offset / 36) % 6]
            val g = cubeSteps[(offset / 6) % 6]
            val b = cubeSteps[offset % 6]
            return packRgb(r, g, b)
        }
        // 232..255: 24-step grayscale ramp
        val gray = (8 + (safe - 232) * 10).coerceIn(0, 255)
        return packRgb(gray, gray, gray)
    }

    fun packRgb(r: Int, g: Int, b: Int): Long {
        val rr = (r.coerceIn(0, 255).toLong() and 0xFFL) shl 16
        val gg = (g.coerceIn(0, 255).toLong() and 0xFFL) shl 8
        val bb = b.coerceIn(0, 255).toLong() and 0xFFL
        return 0xFF000000L or rr or gg or bb
    }
}

/**
 * Single rendered character cell inside the terminal grid.
 */
@Immutable
data class TerminalCell(
    val ch: Char = ' ',
    val fgArgb: Long = AnsiColorPalette.DEFAULT_FG_ARGB,
    val bgArgb: Long = AnsiColorPalette.DEFAULT_BG_ARGB,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    val effectiveFgArgb: Long
        get() = if (inverse) bgArgb else fgArgb

    val effectiveBgArgb: Long
        get() = if (inverse) fgArgb else bgArgb

    companion object {
        val BLANK = TerminalCell()
    }
}

/**
 * Immutable row snapshot of [TerminalCell]s for Compose rendering.
 */
@Immutable
data class TerminalRowSnapshot(
    val cells: List<TerminalCell>,
)

/**
 * Immutable snapshot of the active terminal viewport, scrollback buffer, and cursor state.
 */
@Immutable
data class TerminalScreenSnapshot(
    val cols: Int = 80,
    val rows: Int = 24,
    val visibleRows: List<TerminalRowSnapshot> = emptyList(),
    val scrollbackSize: Int = 0,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursorVisible: Boolean = true,
    val isAlternateScreen: Boolean = false,
    val applicationCursorKeys: Boolean = false,
    val windowTitle: String = "bash",
    val revision: Long = 0L,
)

/**
 * Pure offline VT100 / ANSI / xterm-256color & 24-bit TrueColor terminal state machine.
 *
 * Handles arbitrary chunked UTF-8 byte streams from `PtyDataEvent`, cursor movement (`CUP`, `CUU`,
 * `CUD`, `CUF`, `CUB`, `CHA`, `VPA`), screen/line erasing (`ED`, `EL`, `ECH`, `DCH`, `ICH`, `IL`, `DL`),
 * scrolling regions (`DECSTBM`), alternate screen buffer (`?1049h`/`?1049l` for `vim`/`htop`/`less`),
 * DEC Special Graphics box-drawing characters, and 16/256/TrueColor SGR attributes.
 */
class AnsiTerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    val maxScrollbackLines: Int = 1000,
) {
    private enum class ParserState {
        GROUND,
        ESC,
        ESC_CHARSET,
        CSI,
        OSC,
        OSC_ESC,
    }

    var cols: Int = initialCols.coerceIn(10, 300)
        private set
    var rows: Int = initialRows.coerceIn(4, 150)
        private set

    private var primaryGrid: Array<Array<TerminalCell>> = createEmptyGrid(rows, cols)
    private var altGrid: Array<Array<TerminalCell>> = createEmptyGrid(rows, cols)
    val scrollback = TerminalLineRingBuffer(maxScrollbackLines)

    private var usingAltScreen: Boolean = false
    private val activeGrid: Array<Array<TerminalCell>>
        get() = if (usingAltScreen) altGrid else primaryGrid

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    private var wrapPending: Boolean = false

    private var savedPrimaryRow: Int = 0
    private var savedPrimaryCol: Int = 0
    private var savedCursorRow: Int = 0
    private var savedCursorCol: Int = 0

    private var scrollTop: Int = 0
    private var scrollBottom: Int = rows - 1

    var cursorVisible: Boolean = true
        private set
    var applicationCursorKeys: Boolean = false
        private set
    private var autoWrapMode: Boolean = true
    private var decGraphicsCharset: Boolean = false

    var windowTitle: String = "Terminal Linux"
        private set

    // Current SGR attributes
    private var currentFg: Long = AnsiColorPalette.DEFAULT_FG_ARGB
    private var currentBg: Long = AnsiColorPalette.DEFAULT_BG_ARGB
    private var currentBold: Boolean = false
    private var currentUnderline: Boolean = false
    private var currentInverse: Boolean = false

    // Parser state
    private var parserState: ParserState = ParserState.GROUND
    private val csiBuffer = StringBuilder(32)
    private val oscBuffer = StringBuilder(64)
    private var revisionCounter: Long = 0L

    // Incremental UTF-8 decoder preserving split multi-byte sequences across network packets
    private val utf8Decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carryBytes = ByteArray(8)
    private var carryLen: Int = 0

    @Synchronized
    fun feedBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        val totalLen = carryLen + bytes.size
        val combined = ByteArray(totalLen)
        if (carryLen > 0) {
            System.arraycopy(carryBytes, 0, combined, 0, carryLen)
            carryLen = 0
        }
        System.arraycopy(bytes, 0, combined, totalLen - bytes.size, bytes.size)

        val inBuf = ByteBuffer.wrap(combined)
        val outBuf = CharBuffer.allocate(combined.size * 2 + 4)
        utf8Decoder.decode(inBuf, outBuf, /* endOfInput = */ false)

        if (inBuf.hasRemaining()) {
            carryLen = inBuf.remaining().coerceAtMost(carryBytes.size)
            inBuf.get(carryBytes, 0, carryLen)
        }

        outBuf.flip()
        while (outBuf.hasRemaining()) {
            processChar(outBuf.get())
        }
        revisionCounter++
    }

    @Synchronized
    fun feedText(text: String) {
        if (text.isEmpty()) return
        for (ch in text) {
            processChar(ch)
        }
        revisionCounter++
    }

    @Synchronized
    fun resize(newCols: Int, newRows: Int): Boolean {
        val safeCols = newCols.coerceIn(10, 300)
        val safeRows = newRows.coerceIn(4, 150)
        if (safeCols == cols && safeRows == rows) return false

        primaryGrid = resizeGrid(primaryGrid, safeRows, safeCols)
        altGrid = resizeGrid(altGrid, safeRows, safeCols)
        cols = safeCols
        rows = safeRows
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        wrapPending = false
        revisionCounter++
        return true
    }

    @Synchronized
    fun snapshot(scrollOffsetFromBottom: Int = 0): TerminalScreenSnapshot {
        val grid = activeGrid
        val visible = ArrayList<TerminalRowSnapshot>(rows)

        if (usingAltScreen || scrollOffsetFromBottom <= 0 || scrollback.isEmpty()) {
            for (r in 0 until rows) {
                visible.add(TerminalRowSnapshot(grid[r].toList()))
            }
        } else {
            val clampedOffset = scrollOffsetFromBottom.coerceIn(0, scrollback.size)
            val totalLines = scrollback.size + rows
            val startIdx = (totalLines - rows - clampedOffset).coerceAtLeast(0)
            for (i in 0 until rows) {
                val globalIdx = startIdx + i
                if (globalIdx < scrollback.size) {
                    val histRow = scrollback.get(globalIdx) ?: emptyList()
                    val padded = ArrayList<TerminalCell>(cols)
                    for (c in 0 until cols) {
                        padded.add(histRow.getOrElse(c) { TerminalCell.BLANK })
                    }
                    visible.add(TerminalRowSnapshot(padded))
                } else {
                    val screenR = (globalIdx - scrollback.size).coerceIn(0, rows - 1)
                    visible.add(TerminalRowSnapshot(grid[screenR].toList()))
                }
            }
        }

        return TerminalScreenSnapshot(
            cols = cols,
            rows = rows,
            visibleRows = visible,
            scrollbackSize = if (usingAltScreen) 0 else scrollback.size,
            cursorRow = cursorRow.coerceIn(0, rows - 1),
            cursorCol = cursorCol.coerceIn(0, cols - 1),
            cursorVisible = cursorVisible,
            isAlternateScreen = usingAltScreen,
            applicationCursorKeys = applicationCursorKeys,
            windowTitle = windowTitle,
            revision = revisionCounter,
        )
    }

    private fun processChar(ch: Char) {
        when (parserState) {
            ParserState.GROUND -> processGroundChar(ch)
            ParserState.ESC -> processEscChar(ch)
            ParserState.ESC_CHARSET -> {
                decGraphicsCharset = (ch == '0')
                parserState = ParserState.GROUND
            }
            ParserState.CSI -> processCsiChar(ch)
            ParserState.OSC -> {
                when (ch) {
                    '\u0007' -> {
                        finishOscSequence()
                        parserState = ParserState.GROUND
                    }
                    '\u001B' -> parserState = ParserState.OSC_ESC
                    else -> if (oscBuffer.length < 256) oscBuffer.append(ch)
                }
            }
            ParserState.OSC_ESC -> {
                if (ch == '\\') {
                    finishOscSequence()
                }
                parserState = ParserState.GROUND
            }
        }
    }

    private fun processGroundChar(ch: Char) {
        when (ch) {
            '\u001B' -> parserState = ParserState.ESC
            '\r' -> {
                cursorCol = 0
                wrapPending = false
            }
            '\n', '\u000B', '\u000C' -> {
                wrapPending = false
                lineFeed()
            }
            '\b' -> {
                wrapPending = false
                if (cursorCol > 0) cursorCol--
            }
            '\t' -> {
                wrapPending = false
                val nextStop = ((cursorCol / 8) + 1) * 8
                cursorCol = nextStop.coerceAtMost(cols - 1)
            }
            '\u0007', '\u0000' -> Unit
            '\u000E', '\u000F' -> Unit
            else -> {
                if (ch.code >= 32) {
                    val mappedChar = if (decGraphicsCharset) mapDecSpecialGraphics(ch) else ch
                    putPrintableChar(mappedChar)
                }
            }
        }
    }

    private fun processEscChar(ch: Char) {
        when (ch) {
            '[' -> {
                csiBuffer.setLength(0)
                parserState = ParserState.CSI
            }
            ']' -> {
                oscBuffer.setLength(0)
                parserState = ParserState.OSC
            }
            '(', ')', '*', '+' -> {
                parserState = ParserState.ESC_CHARSET
            }
            '7' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
                parserState = ParserState.GROUND
            }
            '8' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                wrapPending = false
                parserState = ParserState.GROUND
            }
            'D' -> {
                lineFeed()
                parserState = ParserState.GROUND
            }
            'M' -> {
                reverseIndex()
                parserState = ParserState.GROUND
            }
            'E' -> {
                cursorCol = 0
                lineFeed()
                parserState = ParserState.GROUND
            }
            'c' -> {
                resetFull()
                parserState = ParserState.GROUND
            }
            else -> parserState = ParserState.GROUND
        }
    }

    private fun processCsiChar(ch: Char) {
        // C0 controls inside CSI are still executed immediately per VT100 spec
        when (ch) {
            '\r' -> {
                cursorCol = 0
                wrapPending = false
                return
            }
            '\n' -> {
                lineFeed()
                return
            }
            '\b' -> {
                if (cursorCol > 0) cursorCol--
                return
            }
        }

        if (ch.code in 0x40..0x7E) {
            val paramsRaw = csiBuffer.toString()
            parserState = ParserState.GROUND
            executeCsi(paramsRaw, ch)
        } else if (csiBuffer.length < 64) {
            csiBuffer.append(ch)
        } else {
            parserState = ParserState.GROUND
        }
    }

    private fun executeCsi(rawParams: String, finalByte: Char) {
        val isPrivate = rawParams.startsWith("?")
        val cleanParams = if (isPrivate) rawParams.drop(1) else rawParams.trimStart('>', '!')
        val parts = if (cleanParams.isEmpty()) {
            emptyList()
        } else {
            cleanParams.split(';').map { it.toIntOrNull() ?: 0 }
        }

        fun param(idx: Int, defaultVal: Int): Int {
            val v = parts.getOrNull(idx) ?: return defaultVal
            return if (v <= 0) defaultVal else v
        }

        if (isPrivate) {
            val setMode = finalByte == 'h'
            if (finalByte == 'h' || finalByte == 'l') {
                for (code in parts) {
                    handleDecPrivateMode(code, setMode)
                }
            }
            return
        }

        when (finalByte) {
            'A' -> {
                wrapPending = false
                cursorRow = (cursorRow - param(0, 1)).coerceAtLeast(0)
            }
            'B' -> {
                wrapPending = false
                cursorRow = (cursorRow + param(0, 1)).coerceAtMost(rows - 1)
            }
            'C' -> {
                wrapPending = false
                cursorCol = (cursorCol + param(0, 1)).coerceAtMost(cols - 1)
            }
            'D' -> {
                wrapPending = false
                cursorCol = (cursorCol - param(0, 1)).coerceAtLeast(0)
            }
            'E' -> {
                wrapPending = false
                cursorRow = (cursorRow + param(0, 1)).coerceAtMost(rows - 1)
                cursorCol = 0
            }
            'F' -> {
                wrapPending = false
                cursorRow = (cursorRow - param(0, 1)).coerceAtLeast(0)
                cursorCol = 0
            }
            'G', '`' -> {
                wrapPending = false
                cursorCol = (param(0, 1) - 1).coerceIn(0, cols - 1)
            }
            'd' -> {
                wrapPending = false
                cursorRow = (param(0, 1) - 1).coerceIn(0, rows - 1)
            }
            'H', 'f' -> {
                wrapPending = false
                cursorRow = (param(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (param(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> {
                wrapPending = false
                val mode = parts.getOrNull(0) ?: 0
                eraseInDisplay(mode)
            }
            'K' -> {
                wrapPending = false
                val mode = parts.getOrNull(0) ?: 0
                eraseInLine(mode)
            }
            'X' -> {
                val count = param(0, 1)
                val end = (cursorCol + count).coerceAtMost(cols)
                val blank = currentBlankCell()
                for (c in cursorCol until end) {
                    activeGrid[cursorRow][c] = blank
                }
            }
            'P' -> {
                val count = param(0, 1).coerceAtMost(cols - cursorCol)
                val rowArr = activeGrid[cursorRow]
                val blank = currentBlankCell()
                for (c in cursorCol until cols) {
                    rowArr[c] = if (c + count < cols) rowArr[c + count] else blank
                }
            }
            '@' -> {
                val count = param(0, 1).coerceAtMost(cols - cursorCol)
                val rowArr = activeGrid[cursorRow]
                val blank = currentBlankCell()
                for (c in (cols - 1) downTo cursorCol) {
                    rowArr[c] = if (c - count >= cursorCol) rowArr[c - count] else blank
                }
            }
            'L' -> {
                if (cursorRow in scrollTop..scrollBottom) {
                    val count = param(0, 1)
                    repeat(count) { scrollRegionDown(cursorRow, scrollBottom) }
                }
            }
            'M' -> {
                if (cursorRow in scrollTop..scrollBottom) {
                    val count = param(0, 1)
                    repeat(count) { scrollRegionUp(cursorRow, scrollBottom, recordScrollback = false) }
                }
            }
            'S' -> {
                val count = param(0, 1)
                repeat(count) { scrollRegionUp(scrollTop, scrollBottom, recordScrollback = false) }
            }
            'T' -> {
                val count = param(0, 1)
                repeat(count) { scrollRegionDown(scrollTop, scrollBottom) }
            }
            'r' -> {
                val top = (param(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (param(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                } else {
                    scrollTop = 0
                    scrollBottom = rows - 1
                }
                cursorRow = 0
                cursorCol = 0
                wrapPending = false
            }
            's' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
            }
            'u' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                wrapPending = false
            }
            'm' -> applySgr(parts)
        }
    }

    private fun handleDecPrivateMode(code: Int, enabled: Boolean) {
        when (code) {
            1 -> applicationCursorKeys = enabled
            7 -> autoWrapMode = enabled
            25 -> cursorVisible = enabled
            47, 1047, 1049 -> {
                if (enabled && !usingAltScreen) {
                    savedPrimaryRow = cursorRow
                    savedPrimaryCol = cursorCol
                    altGrid = createEmptyGrid(rows, cols)
                    usingAltScreen = true
                    scrollTop = 0
                    scrollBottom = rows - 1
                    cursorRow = 0
                    cursorCol = 0
                    wrapPending = false
                } else if (!enabled && usingAltScreen) {
                    usingAltScreen = false
                    scrollTop = 0
                    scrollBottom = rows - 1
                    cursorRow = savedPrimaryRow.coerceIn(0, rows - 1)
                    cursorCol = savedPrimaryCol.coerceIn(0, cols - 1)
                    wrapPending = false
                }
            }
        }
    }

    private fun applySgr(codes: List<Int>) {
        if (codes.isEmpty()) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> resetAttributes()
                1 -> currentBold = true
                2 -> currentBold = false
                4 -> currentUnderline = true
                7 -> currentInverse = true
                22 -> currentBold = false
                24 -> currentUnderline = false
                27 -> currentInverse = false
                in 30..37 -> {
                    val idx = if (currentBold) (code - 30) + 8 else (code - 30)
                    currentFg = AnsiColorPalette.colorFor256Index(idx)
                }
                38 -> {
                    val mode = codes.getOrNull(i + 1) ?: 0
                    if (mode == 5 && i + 2 < codes.size) {
                        currentFg = AnsiColorPalette.colorFor256Index(codes[i + 2])
                        i += 2
                    } else if (mode == 2 && i + 4 < codes.size) {
                        currentFg = AnsiColorPalette.packRgb(codes[i + 2], codes[i + 3], codes[i + 4])
                        i += 4
                    }
                }
                39 -> currentFg = AnsiColorPalette.DEFAULT_FG_ARGB
                in 40..47 -> currentBg = AnsiColorPalette.colorFor256Index(code - 40)
                48 -> {
                    val mode = codes.getOrNull(i + 1) ?: 0
                    if (mode == 5 && i + 2 < codes.size) {
                        currentBg = AnsiColorPalette.colorFor256Index(codes[i + 2])
                        i += 2
                    } else if (mode == 2 && i + 4 < codes.size) {
                        currentBg = AnsiColorPalette.packRgb(codes[i + 2], codes[i + 3], codes[i + 4])
                        i += 4
                    }
                }
                49 -> currentBg = AnsiColorPalette.DEFAULT_BG_ARGB
                in 90..97 -> currentFg = AnsiColorPalette.colorFor256Index((code - 90) + 8)
                in 100..107 -> currentBg = AnsiColorPalette.colorFor256Index((code - 100) + 8)
            }
            i++
        }
    }

    private fun putPrintableChar(ch: Char) {
        if (wrapPending && autoWrapMode) {
            cursorCol = 0
            lineFeed()
            wrapPending = false
        }

        val cell = TerminalCell(
            ch = ch,
            fgArgb = currentFg,
            bgArgb = currentBg,
            bold = currentBold,
            underline = currentUnderline,
            inverse = currentInverse,
        )

        val safeRow = cursorRow.coerceIn(0, rows - 1)
        val safeCol = cursorCol.coerceIn(0, cols - 1)
        activeGrid[safeRow][safeCol] = cell

        if (safeCol >= cols - 1) {
            if (autoWrapMode) {
                wrapPending = true
            }
        } else {
            cursorCol = safeCol + 1
        }
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollRegionUp(
                top = scrollTop,
                bottom = scrollBottom,
                recordScrollback = (!usingAltScreen && scrollTop == 0),
            )
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollRegionDown(scrollTop, scrollBottom)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun scrollRegionUp(top: Int, bottom: Int, recordScrollback: Boolean) {
        val grid = activeGrid
        if (recordScrollback) {
            scrollback.add(grid[top].toList())
        }
        for (r in top until bottom) {
            grid[r] = grid[r + 1]
        }
        grid[bottom] = Array(cols) { currentBlankCell() }
    }

    private fun scrollRegionDown(top: Int, bottom: Int) {
        val grid = activeGrid
        for (r in bottom downTo (top + 1)) {
            grid[r] = grid[r - 1]
        }
        grid[top] = Array(cols) { currentBlankCell() }
    }

    private fun eraseInDisplay(mode: Int) {
        val grid = activeGrid
        val blank = currentBlankCell()
        when (mode) {
            0 -> {
                for (c in cursorCol until cols) grid[cursorRow][c] = blank
                for (r in (cursorRow + 1) until rows) {
                    for (c in 0 until cols) grid[r][c] = blank
                }
            }
            1 -> {
                for (r in 0 until cursorRow) {
                    for (c in 0 until cols) grid[r][c] = blank
                }
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) grid[cursorRow][c] = blank
            }
            2, 3 -> {
                for (r in 0 until rows) {
                    for (c in 0 until cols) grid[r][c] = blank
                }
                if (mode == 3 && !usingAltScreen) {
                    scrollback.clear()
                }
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        val rowArr = activeGrid[cursorRow]
        val blank = currentBlankCell()
        when (mode) {
            0 -> for (c in cursorCol until cols) rowArr[c] = blank
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) rowArr[c] = blank
            2 -> for (c in 0 until cols) rowArr[c] = blank
        }
    }

    private fun finishOscSequence() {
        val raw = oscBuffer.toString()
        val semiIdx = raw.indexOf(';')
        if (semiIdx > 0) {
            val code = raw.substring(0, semiIdx)
            if (code == "0" || code == "2") {
                val title = raw.substring(semiIdx + 1).trim()
                if (title.isNotEmpty()) {
                    windowTitle = title.take(64)
                }
            }
        }
    }

    private fun resetAttributes() {
        currentFg = AnsiColorPalette.DEFAULT_FG_ARGB
        currentBg = AnsiColorPalette.DEFAULT_BG_ARGB
        currentBold = false
        currentUnderline = false
        currentInverse = false
    }

    private fun resetFull() {
        resetAttributes()
        usingAltScreen = false
        primaryGrid = createEmptyGrid(rows, cols)
        altGrid = createEmptyGrid(rows, cols)
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
        cursorVisible = true
        applicationCursorKeys = false
        autoWrapMode = true
        decGraphicsCharset = false
    }

    private fun currentBlankCell(): TerminalCell = TerminalCell(
        ch = ' ',
        fgArgb = currentFg,
        bgArgb = currentBg,
    )

    private fun mapDecSpecialGraphics(ch: Char): Char = when (ch) {
        'j' -> '┘'
        'k' -> '┐'
        'l' -> '┌'
        'm' -> '└'
        'n' -> '┼'
        'q' -> '─'
        't' -> '├'
        'u' -> '┤'
        'v' -> '┴'
        'w' -> '┬'
        'x' -> '│'
        'a' -> '▒'
        '`' -> '◆'
        else -> ch
    }

    companion object {
        private fun createEmptyGrid(rows: Int, cols: Int): Array<Array<TerminalCell>> =
            Array(rows) { Array(cols) { TerminalCell.BLANK } }

        private fun resizeGrid(
            oldGrid: Array<Array<TerminalCell>>,
            newRows: Int,
            newCols: Int,
        ): Array<Array<TerminalCell>> {
            val out = createEmptyGrid(newRows, newCols)
            val copyRows = minOf(oldGrid.size, newRows)
            for (r in 0 until copyRows) {
                val srcRow = oldGrid[r]
                val copyCols = minOf(srcRow.size, newCols)
                for (c in 0 until copyCols) {
                    out[r][c] = srcRow[c]
                }
            }
            return out
        }
    }
}

/**
 * Immutable UI state exposed by [TerminalViewModel].
 */
@Immutable
data class TerminalUiState(
    val sessionId: String = "",
    val isSessionOpen: Boolean = false,
    val screen: TerminalScreenSnapshot = TerminalScreenSnapshot(),
    val scrollOffsetLines: Int = 0,
    val isCtrlArmed: Boolean = false,
    val exitBanner: String? = null,
)

/**
 * Unidirectional MVVM ViewModel managing the interactive Linux PTY session (`PtyOpenRequest`,
 * `PtyDataEvent`, `PtyResizeEvent`, `PtyCloseEvent`) and the offline ANSI/256-color terminal buffer.
 */
class TerminalViewModel(
    private val transport: TerminalTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    val terminalEngine = SafeTerminalEngine(
        initialCols = 80,
        initialRows = 24,
        maxScrollbackLines = 1000,
        computeDispatcher = computeDispatcher,
    )
    val terminalBuffer: AnsiTerminalBuffer get() = terminalEngine.terminalBuffer

    private val _uiState = MutableStateFlow(
        TerminalUiState(screen = terminalEngine.snapshot()),
    )
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    private var activeSessionId: String = ""
    private var lastSyncedCols: Int = 0
    private var lastSyncedRows: Int = 0
    private var pendingAutoOpenOnConnect: Boolean = true

    init {
        terminalEngine.startProcessing(
            scope = viewModelScope,
            scrollOffsetProvider = { _uiState.value.scrollOffsetLines },
        ) { snapshot ->
            _uiState.update { current ->
                current.copy(
                    isSessionOpen = true,
                    screen = snapshot,
                    exitBanner = null,
                )
            }
        }

        viewModelScope.launch {
            transport.incomingEnvelopes.collect { envelope ->
                handleIncomingEnvelope(envelope)
            }
        }

        // Automatically open (or re-open after reconnect) when WebSocket mTLS session becomes Connected.
        viewModelScope.launch {
            transport.connectionState.collect { conn ->
                if (conn is ConnectionState.Connected && pendingAutoOpenOnConnect && !_uiState.value.isSessionOpen) {
                    openSession(
                        cols = terminalBuffer.cols,
                        rows = terminalBuffer.rows,
                    )
                }
            }
        }
    }

    private suspend fun handleIncomingEnvelope(envelope: Envelope) {
        when (envelope.payloadCase) {
            Envelope.PayloadCase.PTY_DATA_EVENT -> {
                val ev = envelope.ptyDataEvent
                if (ev.sessionId == activeSessionId && !ev.data.isEmpty) {
                    val bytes = ev.data.toByteArray()
                    // FIX-06: Offload burst ANSI processing to Dispatchers.Default and throttle snapshot
                    // dispatch to max 30 FPS (~33ms) via SafeTerminalEngine conflate flow to eliminate UI lockups / ANRs.
                    terminalEngine.feedBytesThrottled(bytes)
                }
            }

            Envelope.PayloadCase.PTY_CLOSE_EVENT -> {
                val ev = envelope.ptyCloseEvent
                if (ev.sessionId == activeSessionId) {
                    _uiState.update { current ->
                        current.copy(
                            isSessionOpen = false,
                            exitBanner = "Sessão encerrada (código ${ev.exitCode}: ${ev.reason.ifBlank { "EOF" }})",
                        )
                    }
                }
            }

            Envelope.PayloadCase.ERROR_RESPONSE -> {
                val err = envelope.errorResponse
                if (err.message.contains("pty", ignoreCase = true)) {
                    _uiState.update { current ->
                        current.copy(exitBanner = err.message)
                    }
                }
            }

            else -> Unit
        }
    }

    /**
     * Opens a new interactive PTY session on the Linux daemon with `(cols, rows)`.
     */
    fun openSession(
        cols: Int = terminalBuffer.cols,
        rows: Int = terminalBuffer.rows,
        shell: String = "",
    ) {
        val safeCols = cols.coerceIn(10, 300)
        val safeRows = rows.coerceIn(4, 150)
        val newSessionId = "pty-${UUID.randomUUID().toString().take(12)}"

        activeSessionId = newSessionId
        lastSyncedCols = safeCols
        lastSyncedRows = safeRows
        pendingAutoOpenOnConnect = false

        terminalBuffer.resize(safeCols, safeRows)
        _uiState.update { current ->
            current.copy(
                sessionId = newSessionId,
                isSessionOpen = true,
                screen = terminalBuffer.snapshot(0),
                scrollOffsetLines = 0,
                exitBanner = null,
            )
        }

        viewModelScope.launch(ioDispatcher) {
            val openReq = PtyOpenRequest.newBuilder()
                .setSessionId(newSessionId)
                .setCols(safeCols)
                .setRows(safeRows)
                .apply {
                    if (shell.isNotBlank()) setShell(shell.trim())
                }
                .build()

            val env = Envelope.newBuilder()
                .setRequestId("pty-open-$newSessionId")
                .setTimestampMs(System.currentTimeMillis())
                .setPtyOpenRequest(openReq)
                .build()

            val sent = transport.sendEnvelope(env)
            if (!sent) {
                pendingAutoOpenOnConnect = true
                _uiState.update { current ->
                    current.copy(
                        isSessionOpen = false,
                        exitBanner = "Aguardando conexão mTLS com o PC Linux para abrir o terminal PTY...",
                    )
                }
            }
        }
    }

    /**
     * FIX-06: Dispatches PtyReady handshake to the Linux daemon, unblocking the sliding window
     * and signaling that Android UI has rendered and is ready to ingest PTY stdout streams.
     */
    fun sendPtyReady() {
        val sid = activeSessionId
        if (sid.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            val readyEnv = Envelope.newBuilder()
                .setRequestId("pty-ready-$sid")
                .setTimestampMs(System.currentTimeMillis())
                .setPtyDataEvent(
                    PtyDataEvent.newBuilder()
                        .setSessionId(sid)
                        .setData(ByteString.copyFromUtf8(SafeTerminalEngine.PTY_READY_TOKEN))
                        .build(),
                )
                .build()
            transport.sendEnvelope(readyEnv)
        }
    }

    /**
     * Synchronizes terminal dimensions `(cols, rows)` calculated from the Compose canvas metrics
     * via `PtyResizeEvent` (or opens the initial session if not yet started).
     */
    fun onTerminalViewportMeasured(cols: Int, rows: Int) {
        val safeCols = cols.coerceIn(10, 300)
        val safeRows = rows.coerceIn(4, 150)

        if (activeSessionId.isEmpty()) {
            openSession(cols = safeCols, rows = safeRows)
            return
        }

        viewModelScope.launch(computeDispatcher) {
            val resized = terminalBuffer.resize(safeCols, safeRows)
            if (resized) {
                val snap = terminalBuffer.snapshot(_uiState.value.scrollOffsetLines)
                _uiState.update { current ->
                    current.copy(screen = snap)
                }
            }
        }

        if (safeCols == lastSyncedCols && safeRows == lastSyncedRows) return
        lastSyncedCols = safeCols
        lastSyncedRows = safeRows
        val sessionSnapshot = activeSessionId

        viewModelScope.launch(ioDispatcher) {
            val resizeEnvelope = Envelope.newBuilder()
                .setRequestId("pty-resize-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setPtyResizeEvent(
                    PtyResizeEvent.newBuilder()
                        .setSessionId(sessionSnapshot)
                        .setCols(safeCols)
                        .setRows(safeRows)
                        .build(),
                )
                .build()
            transport.sendEnvelope(resizeEnvelope)
        }
    }

    /**
     * Dispatches raw bytes to the active PTY stdin (`PtyDataEvent`) on [ioDispatcher].
     */
    fun sendPtyBytes(bytes: ByteArray) {
        val sid = activeSessionId
        if (sid.isEmpty() || bytes.isEmpty()) return

        // Snap scrollback to bottom whenever the user types
        if (_uiState.value.scrollOffsetLines != 0) {
            _uiState.update { current ->
                current.copy(
                    scrollOffsetLines = 0,
                    screen = terminalBuffer.snapshot(0),
                )
            }
        }

        viewModelScope.launch(ioDispatcher) {
            val dataEnv = Envelope.newBuilder()
                .setRequestId("pty-in-${UUID.randomUUID()}")
                .setTimestampMs(System.currentTimeMillis())
                .setPtyDataEvent(
                    PtyDataEvent.newBuilder()
                        .setSessionId(sid)
                        .setData(ByteString.copyFrom(bytes))
                        .build(),
                )
                .build()
            transport.sendEnvelope(dataEnv)
        }
    }

    /**
     * Handles text typed on the Android Soft Keyboard, converting `<char>` into `CTRL+<char>`
     * control code (`0x01..0x1A`) if the toolbar `Ctrl` key is armed.
     */
    fun onKeyboardTextCommitted(text: String) {
        if (text.isEmpty()) return
        val ctrlArmed = _uiState.value.isCtrlArmed
        if (ctrlArmed && text.length == 1) {
            val upper = text[0].uppercaseChar()
            if (upper in 'A'..'Z') {
                _uiState.update { it.copy(isCtrlArmed = false) }
                val ctrlByte = (upper.code - 64).toByte()
                sendPtyBytes(byteArrayOf(ctrlByte))
                return
            }
        }
        sendPtyBytes(text.toByteArray(Charsets.UTF_8))
    }

    fun toggleCtrlArmed() {
        _uiState.update { it.copy(isCtrlArmed = !it.isCtrlArmed) }
    }

    /**
     * Dispatches any Sysadmin toolbar quick key (`ESC`, `TAB`, `CTRL+C`, `CTRL+Z`, `CTRL+D`,
     * `CTRL+L`, `↑`, `↓`, `←`, `→`, `|`, `/`, `-`) as exact VT100/POSIX bytes.
     */
    fun onQuickKeyPressed(key: SysadminQuickKey) {
        val appCursor = terminalBuffer.applicationCursorKeys
        val bytes: ByteArray = when (key) {
            SysadminQuickKey.ESC -> byteArrayOf(0x1B)
            SysadminQuickKey.TAB -> byteArrayOf(0x09)
            SysadminQuickKey.CTRL_C -> byteArrayOf(0x03)
            SysadminQuickKey.CTRL_Z -> byteArrayOf(0x1A)
            SysadminQuickKey.CTRL_D -> byteArrayOf(0x04)
            SysadminQuickKey.CTRL_L -> byteArrayOf(0x0C)
            SysadminQuickKey.ARROW_UP -> (if (appCursor) "\u001BOA" else "\u001B[A").toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.ARROW_DOWN -> (if (appCursor) "\u001BOB" else "\u001B[B").toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.ARROW_RIGHT -> (if (appCursor) "\u001BOC" else "\u001B[C").toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.ARROW_LEFT -> (if (appCursor) "\u001BOD" else "\u001B[D").toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.PIPE -> "|".toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.SLASH -> "/".toByteArray(Charsets.US_ASCII)
            SysadminQuickKey.DASH -> "-".toByteArray(Charsets.US_ASCII)
        }
        sendPtyBytes(bytes)
    }

    fun scrollHistoryBy(deltaLines: Int) {
        viewModelScope.launch(computeDispatcher) {
            val maxHistory = terminalBuffer.snapshot().scrollbackSize
            val currentOffset = _uiState.value.scrollOffsetLines
            val nextOffset = (currentOffset + deltaLines).coerceIn(0, maxHistory)
            val snap = terminalBuffer.snapshot(nextOffset)
            _uiState.update { current ->
                current.copy(
                    scrollOffsetLines = nextOffset,
                    screen = snap,
                )
            }
        }
    }

    /**
     * Explicitly closes the remote PTY session via `PtyCloseEvent`.
     */
    fun closeSession(reason: String = "closed by client") {
        val sid = activeSessionId
        if (sid.isEmpty()) return
        activeSessionId = ""
        _uiState.update { it.copy(isSessionOpen = false) }

        val closeEnv = Envelope.newBuilder()
            .setRequestId("pty-close-${UUID.randomUUID()}")
            .setTimestampMs(System.currentTimeMillis())
            .setPtyCloseEvent(
                PtyCloseEvent.newBuilder()
                    .setSessionId(sid)
                    .setReason(reason)
                    .build(),
            )
            .build()
        transport.sendAsync(closeEnv)
    }

    override fun onCleared() {
        closeSession("viewmodel cleared")
        super.onCleared()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val client = DeckConnectionService.obtainClient(appContext)
                    return TerminalViewModel(
                        transport = SessionClientTerminalTransport(client),
                        ioDispatcher = Dispatchers.IO,
                        computeDispatcher = Dispatchers.Default,
                    ) as T
                }
            }
        }
    }
}
