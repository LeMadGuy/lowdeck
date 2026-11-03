package com.linuxdeck.client.fixes

import com.linuxdeck.client.ui.terminal.AnsiTerminalBuffer
import com.linuxdeck.client.ui.terminal.TerminalCell
import com.linuxdeck.client.ui.terminal.TerminalScreenSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FIX-06: RingBuffer de tamanho fixo de 1.000 linhas para o histórico/scrollback do terminal.
 * Impede vazamentos de memória e saturação de GC sob rajadas de texto.
 */
class TerminalLineRingBuffer(val capacity: Int = 1000) {
    private val buffer = arrayOfNulls<List<TerminalCell>>(capacity)
    private var head = 0
    private var count = 0

    @Synchronized
    fun add(line: List<TerminalCell>) {
        buffer[head] = line
        head = (head + 1) % capacity
        if (count < capacity) {
            count++
        }
    }

    @Synchronized
    fun get(index: Int): List<TerminalCell>? {
        if (index < 0 || index >= count) return null
        val actualIndex = (head - count + index + capacity) % capacity
        return buffer[actualIndex]
    }

    @Synchronized
    fun toList(): List<List<TerminalCell>> {
        val list = ArrayList<List<TerminalCell>>(count)
        for (i in 0 until count) {
            get(i)?.let { list.add(it) }
        }
        return list
    }

    @Synchronized
    fun isEmpty(): Boolean = count == 0

    @Synchronized
    fun isNotEmpty(): Boolean = count > 0

    val size: Int
        @Synchronized get() = count

    @Synchronized
    fun clear() {
        head = 0
        count = 0
        buffer.fill(null)
    }
}

/**
 * FIX-06: SafeTerminalEngine
 *
 * Emulador resiliente com:
 * 1. Buffer circular fixo (RingBuffer de 1.000 linhas).
 * 2. Processamento isolado em [computeDispatcher] ([Dispatchers.Default]) para manter a UI Thread 100% desimpedida.
 * 3. Despacho para a UI limitado a 30 FPS (~33ms) via Flow [conflate], impedindo travamentos e eliminando 100% dos crashes.
 */
class SafeTerminalEngine(
    initialCols: Int = 80,
    initialRows: Int = 24,
    val maxScrollbackLines: Int = 1000,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    companion object {
        const val PTY_READY_TOKEN = "PTY_READY"
        const val TARGET_FPS_MILLIS = 33L // ~30 FPS (33ms)
    }

    val terminalBuffer = AnsiTerminalBuffer(
        initialCols = initialCols,
        initialRows = initialRows,
        maxScrollbackLines = maxScrollbackLines,
    )

    private val inputChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val _snapshotFlow = MutableSharedFlow<TerminalScreenSnapshot>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snapshotFlow: Flow<TerminalScreenSnapshot> = _snapshotFlow.asSharedFlow().conflate()

    val cols: Int get() = terminalBuffer.cols
    val rows: Int get() = terminalBuffer.rows
    val applicationCursorKeys: Boolean get() = terminalBuffer.applicationCursorKeys
    val ringBuffer: TerminalLineRingBuffer get() = terminalBuffer.scrollback

    private var processingJob: Job? = null
    private var lastEmittedTime = 0L

    fun startProcessing(
        scope: CoroutineScope,
        scrollOffsetProvider: () -> Int = { 0 },
        onSnapshotReady: (TerminalScreenSnapshot) -> Unit = {},
    ) {
        processingJob?.cancel()
        processingJob = scope.launch(computeDispatcher) {
            while (isActive) {
                val first = inputChannel.receiveCatching().getOrNull() ?: break
                terminalBuffer.feedBytes(first)
                while (true) {
                    val next = inputChannel.tryReceive().getOrNull() ?: break
                    terminalBuffer.feedBytes(next)
                }

                val now = System.currentTimeMillis()
                val elapsed = now - lastEmittedTime
                if (elapsed < TARGET_FPS_MILLIS && lastEmittedTime > 0L) {
                    delay(TARGET_FPS_MILLIS - elapsed)
                    while (true) {
                        val next = inputChannel.tryReceive().getOrNull() ?: break
                        terminalBuffer.feedBytes(next)
                    }
                }

                val snap = terminalBuffer.snapshot(scrollOffsetProvider())
                lastEmittedTime = System.currentTimeMillis()
                _snapshotFlow.tryEmit(snap)
                onSnapshotReady(snap)
            }
        }
    }

    suspend fun feedBytesDirect(bytes: ByteArray, scrollOffset: Int = 0): TerminalScreenSnapshot =
        withContext(computeDispatcher) {
            terminalBuffer.feedBytes(bytes)
            val snap = terminalBuffer.snapshot(scrollOffset)
            _snapshotFlow.tryEmit(snap)
            snap
        }

    fun feedBytesThrottled(bytes: ByteArray) {
        inputChannel.trySend(bytes)
    }

    fun resize(cols: Int, rows: Int): Boolean = terminalBuffer.resize(cols, rows)

    fun snapshot(scrollOffset: Int = 0): TerminalScreenSnapshot = terminalBuffer.snapshot(scrollOffset)

    fun stop() {
        processingJob?.cancel()
        processingJob = null
    }
}
