package com.linuxdeck.client.ui.trackpad

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linuxdeck.client.protocol.v1.ButtonAction
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.MouseButton
import com.linuxdeck.client.protocol.v1.MouseButtonEvent
import com.linuxdeck.client.protocol.v1.MouseMoveEvent
import com.linuxdeck.client.protocol.v1.MouseScrollEvent
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.hypot

/**
 * Configurable pointer sensitivity, velocity-based acceleration curve, and gesture thresholds
 * for the precision multi-gesture trackpad.
 */
@Immutable
data class TrackpadAccelerationConfig(
    val baseSensitivity: Float = 1.35f,
    val accelerationFactor: Float = 0.30f,
    val velocityThresholdPxPerMs: Float = 0.25f,
    val maxMultiplier: Float = 3.8f,
    val scrollPixelsPerStep: Float = 26f,
    val naturalScrolling: Boolean = true,
    val tapSlopPx: Float = 18f,
    val scrollSlopPx: Float = 12f,
    val doubleTapSlopPx: Float = 48f,
    val tapTimeoutMs: Long = 240L,
    val doubleTapTimeoutMs: Long = 180L,
    val dragHoldDelayMs: Long = 130L,
)

/**
 * Integer 2D delta emitted after applying pointer acceleration or scroll step quantization.
 */
@Immutable
data class IntDelta(
    val dx: Int,
    val dy: Int,
) {
    val isNonZero: Boolean
        get() = dx != 0 || dy != 0
}

/**
 * Pure pointer velocity/acceleration & scroll-step calculator with sub-pixel remainder retention
 * so slow 1-pixel movements never suffer dead-zones.
 */
class TrackpadDeltaCalculator(
    var config: TrackpadAccelerationConfig = TrackpadAccelerationConfig(),
) {
    private var moveRemainderX: Float = 0f
    private var moveRemainderY: Float = 0f
    private var scrollRemainderX: Float = 0f
    private var scrollRemainderY: Float = 0f

    fun resetRemainders() {
        moveRemainderX = 0f
        moveRemainderY = 0f
        scrollRemainderX = 0f
        scrollRemainderY = 0f
    }

    /**
     * Computes accelerated relative cursor movement `(dx, dy)` from raw finger movement `(rawDxPx, rawDyPx)`
     * elapsed over [dtMs] milliseconds.
     */
    fun computeAcceleratedMove(rawDxPx: Float, rawDyPx: Float, dtMs: Long): IntDelta {
        if (rawDxPx == 0f && rawDyPx == 0f) {
            return IntDelta(0, 0)
        }
        val safeDtMs = dtMs.coerceIn(1L, 100L).toFloat()
        val distancePx = hypot(rawDxPx, rawDyPx)
        val velocityPxPerMs = distancePx / safeDtMs

        val boost = if (velocityPxPerMs <= config.velocityThresholdPxPerMs) {
            1f
        } else {
            val excess = velocityPxPerMs - config.velocityThresholdPxPerMs
            (1f + excess * config.accelerationFactor).coerceIn(1f, config.maxMultiplier)
        }

        val gain = (config.baseSensitivity.coerceAtLeast(0.1f)) * boost
        val exactX = rawDxPx * gain + moveRemainderX
        val exactY = rawDyPx * gain + moveRemainderY

        val outDx = exactX.toInt()
        val outDy = exactY.toInt()
        moveRemainderX = exactX - outDx
        moveRemainderY = exactY - outDy

        return IntDelta(outDx, outDy)
    }

    /**
     * Converts 2-finger pan deltas `(rawDxPx, rawDyPx)` into discrete `evdev` scroll wheel steps.
     *
     * On Linux `/dev/uinput`:
     * - `REL_WHEEL > 0` scrolls UP; `REL_WHEEL < 0` scrolls DOWN.
     * - In natural scrolling mode (`naturalScrolling == true`), dragging fingers UP (`rawDyPx < 0`)
     *   moves document content UP (emits `dy < 0`).
     */
    fun computeScrollSteps(rawDxPx: Float, rawDyPx: Float): IntDelta {
        val stepPx = config.scrollPixelsPerStep.coerceAtLeast(4f)
        val sign = if (config.naturalScrolling) 1f else -1f

        // Horizontal: natural scroll dragging left (rawDxPx < 0) moves viewport right (positive HWheel)
        scrollRemainderX += (-sign * rawDxPx)
        scrollRemainderY += (sign * rawDyPx)

        val stepsX = (scrollRemainderX / stepPx).toInt()
        val stepsY = (scrollRemainderY / stepPx).toInt()

        if (stepsX != 0) {
            scrollRemainderX -= stepsX * stepPx
        }
        if (stepsY != 0) {
            scrollRemainderY -= stepsY * stepPx
        }

        return IntDelta(stepsX, stepsY)
    }
}

/**
 * Typed domain actions produced by [TrackpadGestureMachine] and [MotionCoalescer] before
 * binary Protobuf [Envelope] marshaling.
 */
sealed interface TrackpadAction {
    data class MoveRelative(val dx: Int, val dy: Int) : TrackpadAction
    data class Button(val button: MouseButton, val action: ButtonAction) : TrackpadAction
    data class Scroll(val dx: Int, val dy: Int) : TrackpadAction

    fun toEnvelope(nowMs: Long = System.currentTimeMillis()): Envelope {
        val builder = Envelope.newBuilder()
            .setRequestId("tp-${UUID.randomUUID()}")
            .setTimestampMs(nowMs)

        return when (this) {
            is MoveRelative -> builder.setMouseMoveEvent(
                MouseMoveEvent.newBuilder()
                    .setDx(dx)
                    .setDy(dy)
                    .setIsAbsolute(false)
                    .build(),
            ).build()

            is Button -> builder.setMouseButtonEvent(
                MouseButtonEvent.newBuilder()
                    .setButton(button)
                    .setAction(action)
                    .build(),
            ).build()

            is Scroll -> builder.setMouseScrollEvent(
                MouseScrollEvent.newBuilder()
                    .setDx(dx)
                    .setDy(dy)
                    .build(),
            ).build()
        }
    }
}

/**
 * Coalesces high-frequency digitizer touch deltas (240Hz–480Hz) to the display refresh cadence
 * (60Hz = 16ms or 120Hz = 8ms) so the WebSocket outbound channel is never saturated.
 */
class MotionCoalescer(
    frameIntervalMs: Long = 8L,
) {
    var frameIntervalMs: Long = frameIntervalMs.coerceIn(4L, 33L)

    private var pendingMoveDx: Int = 0
    private var pendingMoveDy: Int = 0
    private var pendingScrollDx: Int = 0
    private var pendingScrollDy: Int = 0

    private var lastMoveEmitMs: Long = Long.MIN_VALUE / 2
    private var lastScrollEmitMs: Long = Long.MIN_VALUE / 2

    @Synchronized
    fun recordOrFlush(actions: List<TrackpadAction>, nowMs: Long): List<TrackpadAction> {
        if (actions.isEmpty()) return emptyList()
        val out = ArrayList<TrackpadAction>(actions.size + 2)

        for (action in actions) {
            when (action) {
                is TrackpadAction.MoveRelative -> {
                    pendingMoveDx += action.dx
                    pendingMoveDy += action.dy
                    if (nowMs - lastMoveEmitMs >= frameIntervalMs && (pendingMoveDx != 0 || pendingMoveDy != 0)) {
                        out.add(TrackpadAction.MoveRelative(pendingMoveDx, pendingMoveDy))
                        pendingMoveDx = 0
                        pendingMoveDy = 0
                        lastMoveEmitMs = nowMs
                    }
                }

                is TrackpadAction.Scroll -> {
                    pendingScrollDx += action.dx
                    pendingScrollDy += action.dy
                    if (nowMs - lastScrollEmitMs >= frameIntervalMs && (pendingScrollDx != 0 || pendingScrollDy != 0)) {
                        out.add(TrackpadAction.Scroll(pendingScrollDx, pendingScrollDy))
                        pendingScrollDx = 0
                        pendingScrollDy = 0
                        lastScrollEmitMs = nowMs
                    }
                }

                is TrackpadAction.Button -> {
                    // Always flush pending movement/scroll BEFORE button state changes to preserve cursor ordering.
                    flushInto(out, nowMs)
                    out.add(action)
                }
            }
        }
        return out
    }

    @Synchronized
    fun flushPending(nowMs: Long): List<TrackpadAction> {
        if (pendingMoveDx == 0 && pendingMoveDy == 0 && pendingScrollDx == 0 && pendingScrollDy == 0) {
            return emptyList()
        }
        val out = ArrayList<TrackpadAction>(2)
        flushInto(out, nowMs)
        return out
    }

    private fun flushInto(out: MutableList<TrackpadAction>, nowMs: Long) {
        if (pendingMoveDx != 0 || pendingMoveDy != 0) {
            out.add(TrackpadAction.MoveRelative(pendingMoveDx, pendingMoveDy))
            pendingMoveDx = 0
            pendingMoveDy = 0
            lastMoveEmitMs = nowMs
        }
        if (pendingScrollDx != 0 || pendingScrollDy != 0) {
            out.add(TrackpadAction.Scroll(pendingScrollDx, pendingScrollDy))
            pendingScrollDx = 0
            pendingScrollDy = 0
            lastScrollEmitMs = nowMs
        }
    }

    companion object {
        fun frameIntervalForRefreshRate(refreshRateHz: Float): Long = when {
            refreshRateHz >= 100f -> 8L // ~120Hz
            refreshRateHz >= 80f -> 11L // ~90Hz
            else -> 16L // ~60Hz
        }
    }
}

/**
 * Snapshot of a single active finger pointer on the trackpad surface.
 */
@Immutable
data class TrackpadPointer(
    val id: Long,
    val x: Float,
    val y: Float,
)

/**
 * Pure multi-gesture finite state machine translating 1, 2, and 3-finger touch sequences into
 * [TrackpadAction]s:
 * - 1-finger drag -> [TrackpadAction.MoveRelative] with velocity acceleration
 * - 1-finger tap -> `MouseButtonEvent(LEFT, CLICK)`
 * - 2-finger tap -> `MouseButtonEvent(RIGHT, CLICK)`
 * - 3-finger tap -> `MouseButtonEvent(MIDDLE, CLICK)`
 * - 2-finger vertical/horizontal drag -> [TrackpadAction.Scroll]
 * - Double-tap + hold (Drag & Drop) -> `MouseButtonEvent(LEFT, PRESS)` on lock/move and `RELEASE` on lift
 */
class TrackpadGestureMachine(
    config: TrackpadAccelerationConfig = TrackpadAccelerationConfig(),
) {
    enum class State {
        IDLE,
        TOUCHING,
        ONE_FINGER_MOVE,
        TWO_FINGER_SCROLL,
        AWAITING_SECOND_TAP,
        SECOND_TAP_DOWN,
        DRAGGING,
    }

    private val calculator = TrackpadDeltaCalculator(config)

    var config: TrackpadAccelerationConfig
        get() = calculator.config
        set(value) {
            calculator.config = value
        }

    var state: State = State.IDLE
        private set

    val isDraggingLocked: Boolean
        get() = state == State.DRAGGING

    private var gestureStartTimeMs: Long = 0L
    private var lastEventTimeMs: Long = 0L
    private var maxPointersInGesture: Int = 0
    private var activePointerCount: Int = 0

    private var startCentroidX: Float = 0f
    private var startCentroidY: Float = 0f
    private var lastCentroidX: Float = 0f
    private var lastCentroidY: Float = 0f

    private var firstTapUpTimeMs: Long = 0L
    private var firstTapX: Float = 0f
    private var firstTapY: Float = 0f
    private var secondTapDownTimeMs: Long = 0L

    /**
     * Next timestamp at which [onTimeout] should be invoked if no touch event occurs first,
     * or `null` when no timer is pending.
     */
    val nextTimeoutDeadlineMs: Long?
        get() = when (state) {
            State.AWAITING_SECOND_TAP -> firstTapUpTimeMs + config.doubleTapTimeoutMs
            State.SECOND_TAP_DOWN -> secondTapDownTimeMs + config.dragHoldDelayMs
            else -> null
        }

    fun onPointerEvent(pointers: List<TrackpadPointer>, timestampMs: Long): List<TrackpadAction> {
        val out = ArrayList<TrackpadAction>(4)

        // Expire any elapsed timeout before processing the new event at timestampMs.
        out.addAll(onTimeout(timestampMs))

        if (pointers.isEmpty()) {
            handleAllPointersUp(timestampMs, out)
            activePointerCount = 0
            return out
        }

        val centroidX = pointers.sumOf { it.x.toDouble() }.toFloat() / pointers.size
        val centroidY = pointers.sumOf { it.y.toDouble() }.toFloat() / pointers.size
        val count = pointers.size

        when (state) {
            State.IDLE -> {
                calculator.resetRemainders()
                state = State.TOUCHING
                gestureStartTimeMs = timestampMs
                lastEventTimeMs = timestampMs
                maxPointersInGesture = count
                activePointerCount = count
                startCentroidX = centroidX
                startCentroidY = centroidY
                lastCentroidX = centroidX
                lastCentroidY = centroidY
            }

            State.AWAITING_SECOND_TAP -> {
                val distFromFirstTap = hypot(centroidX - firstTapX, centroidY - firstTapY)
                if (count == 1 &&
                    timestampMs - firstTapUpTimeMs <= config.doubleTapTimeoutMs &&
                    distFromFirstTap <= config.doubleTapSlopPx
                ) {
                    // Candidate second tap / Drag & Drop hold!
                    calculator.resetRemainders()
                    state = State.SECOND_TAP_DOWN
                    secondTapDownTimeMs = timestampMs
                    lastEventTimeMs = timestampMs
                    maxPointersInGesture = 1
                    activePointerCount = 1
                    startCentroidX = centroidX
                    startCentroidY = centroidY
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                } else {
                    // Flush the pending first single-click and start a fresh gesture.
                    out.add(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.CLICK))
                    calculator.resetRemainders()
                    state = State.TOUCHING
                    gestureStartTimeMs = timestampMs
                    lastEventTimeMs = timestampMs
                    maxPointersInGesture = count
                    activePointerCount = count
                    startCentroidX = centroidX
                    startCentroidY = centroidY
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                }
            }

            State.SECOND_TAP_DOWN -> {
                if (count > 1) {
                    // Multi-finger joined: flush pending tap and reclassify as multi-finger touch.
                    out.add(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.CLICK))
                    state = State.TOUCHING
                    gestureStartTimeMs = timestampMs
                    maxPointersInGesture = count
                    activePointerCount = count
                    startCentroidX = centroidX
                    startCentroidY = centroidY
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                } else {
                    val distFromStart = hypot(centroidX - startCentroidX, centroidY - startCentroidY)
                    if (distFromStart > config.tapSlopPx) {
                        // Finger started dragging right after double-tap -> enter Drag & Drop!
                        state = State.DRAGGING
                        out.add(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.PRESS))
                        val dt = (timestampMs - lastEventTimeMs).coerceAtLeast(1L)
                        val move = calculator.computeAcceleratedMove(
                            rawDxPx = centroidX - lastCentroidX,
                            rawDyPx = centroidY - lastCentroidY,
                            dtMs = dt,
                        )
                        if (move.isNonZero) {
                            out.add(TrackpadAction.MoveRelative(move.dx, move.dy))
                        }
                    }
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                    activePointerCount = count
                }
            }

            State.DRAGGING -> {
                // If pointer count changed (e.g. accidental palm touch), re-anchor centroid smoothly without jump.
                if (count != activePointerCount) {
                    activePointerCount = count
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                } else {
                    val dt = (timestampMs - lastEventTimeMs).coerceAtLeast(1L)
                    val move = calculator.computeAcceleratedMove(
                        rawDxPx = centroidX - lastCentroidX,
                        rawDyPx = centroidY - lastCentroidY,
                        dtMs = dt,
                    )
                    if (move.isNonZero) {
                        out.add(TrackpadAction.MoveRelative(move.dx, move.dy))
                    }
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                }
            }

            State.TOUCHING -> {
                if (count != activePointerCount) {
                    maxPointersInGesture = maxOf(maxPointersInGesture, count)
                    activePointerCount = count
                    // Re-anchor centroid when additional fingers land/lift so centroid shift doesn't look like motion.
                    startCentroidX = centroidX
                    startCentroidY = centroidY
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                } else {
                    val totalDist = hypot(centroidX - startCentroidX, centroidY - startCentroidY)
                    when {
                        count == 1 && maxPointersInGesture == 1 && totalDist > config.tapSlopPx -> {
                            state = State.ONE_FINGER_MOVE
                            val dt = (timestampMs - lastEventTimeMs).coerceAtLeast(1L)
                            val move = calculator.computeAcceleratedMove(
                                rawDxPx = centroidX - lastCentroidX,
                                rawDyPx = centroidY - lastCentroidY,
                                dtMs = dt,
                            )
                            if (move.isNonZero) {
                                out.add(TrackpadAction.MoveRelative(move.dx, move.dy))
                            }
                        }

                        count == 2 && totalDist > config.scrollSlopPx -> {
                            state = State.TWO_FINGER_SCROLL
                            val scroll = calculator.computeScrollSteps(
                                rawDxPx = centroidX - lastCentroidX,
                                rawDyPx = centroidY - lastCentroidY,
                            )
                            if (scroll.isNonZero) {
                                out.add(TrackpadAction.Scroll(scroll.dx, scroll.dy))
                            }
                        }
                    }
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                }
            }

            State.ONE_FINGER_MOVE -> {
                if (count >= 2) {
                    // User placed second finger down -> switch smoothly into two-finger scroll.
                    calculator.resetRemainders()
                    state = State.TWO_FINGER_SCROLL
                    maxPointersInGesture = maxOf(maxPointersInGesture, count)
                    activePointerCount = count
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                } else {
                    val dt = (timestampMs - lastEventTimeMs).coerceAtLeast(1L)
                    val move = calculator.computeAcceleratedMove(
                        rawDxPx = centroidX - lastCentroidX,
                        rawDyPx = centroidY - lastCentroidY,
                        dtMs = dt,
                    )
                    if (move.isNonZero) {
                        out.add(TrackpadAction.MoveRelative(move.dx, move.dy))
                    }
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                }
            }

            State.TWO_FINGER_SCROLL -> {
                if (count != activePointerCount) {
                    activePointerCount = count
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                } else if (count >= 2) {
                    val scroll = calculator.computeScrollSteps(
                        rawDxPx = centroidX - lastCentroidX,
                        rawDyPx = centroidY - lastCentroidY,
                    )
                    if (scroll.isNonZero) {
                        out.add(TrackpadAction.Scroll(scroll.dx, scroll.dy))
                    }
                    lastCentroidX = centroidX
                    lastCentroidY = centroidY
                    lastEventTimeMs = timestampMs
                }
            }
        }

        return out
    }

    /**
     * Advances timers for pending single-tap confirmation or stationary double-tap-and-hold drag lock.
     */
    fun onTimeout(nowMs: Long): List<TrackpadAction> {
        return when (state) {
            State.AWAITING_SECOND_TAP -> {
                if (nowMs - firstTapUpTimeMs >= config.doubleTapTimeoutMs) {
                    state = State.IDLE
                    listOf(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.CLICK))
                } else {
                    emptyList()
                }
            }

            State.SECOND_TAP_DOWN -> {
                if (nowMs - secondTapDownTimeMs >= config.dragHoldDelayMs) {
                    state = State.DRAGGING
                    listOf(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.PRESS))
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    /**
     * Cancels any active gesture and guarantees `LEFT RELEASE` if Drag & Drop was active.
     */
    fun cancel(): List<TrackpadAction> {
        val wasDragging = state == State.DRAGGING
        state = State.IDLE
        activePointerCount = 0
        maxPointersInGesture = 0
        calculator.resetRemainders()
        return if (wasDragging) {
            listOf(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.RELEASE))
        } else {
            emptyList()
        }
    }

    private fun handleAllPointersUp(timestampMs: Long, out: MutableList<TrackpadAction>) {
        when (state) {
            State.TOUCHING -> {
                val durationMs = timestampMs - gestureStartTimeMs
                if (durationMs <= config.tapTimeoutMs) {
                    when (maxPointersInGesture) {
                        1 -> {
                            state = State.AWAITING_SECOND_TAP
                            firstTapUpTimeMs = timestampMs
                            firstTapX = lastCentroidX
                            firstTapY = lastCentroidY
                            return
                        }
                        2 -> out.add(TrackpadAction.Button(MouseButton.RIGHT, ButtonAction.CLICK))
                        3 -> out.add(TrackpadAction.Button(MouseButton.MIDDLE, ButtonAction.CLICK))
                    }
                }
                state = State.IDLE
            }

            State.SECOND_TAP_DOWN -> {
                // Quick double-tap without holding -> emit two rapid left clicks.
                out.add(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.CLICK))
                out.add(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.CLICK))
                state = State.IDLE
            }

            State.DRAGGING -> {
                out.add(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.RELEASE))
                state = State.IDLE
            }

            State.ONE_FINGER_MOVE,
            State.TWO_FINGER_SCROLL,
            State.IDLE -> {
                state = State.IDLE
            }

            State.AWAITING_SECOND_TAP -> Unit
        }
        maxPointersInGesture = 0
        calculator.resetRemainders()
    }
}

/**
 * Low-latency Compose multi-gesture touchpad surface capturing raw `pointerInput` events,
 * applying [TrackpadAccelerationConfig], coalescing movement/scroll to the display refresh rate
 * (60Hz/120Hz), and emitting typed [TrackpadAction]s.
 */
@Composable
fun GestureDetectorSurface(
    config: TrackpadAccelerationConfig,
    onAction: (TrackpadAction) -> Unit,
    modifier: Modifier = Modifier,
    showVisualChrome: Boolean = true,
) {
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val currentOnAction by rememberUpdatedState(onAction)

    val refreshRateHz = remember(view) {
        @Suppress("DEPRECATION")
        view.display?.refreshRate ?: 60f
    }

    val machine = remember { TrackpadGestureMachine(config) }
    val coalescer = remember(refreshRateHz) {
        MotionCoalescer(MotionCoalescer.frameIntervalForRefreshRate(refreshRateHz))
    }

    LaunchedEffect(config) {
        machine.config = config
    }

    var isDraggingUi by remember { mutableStateOf(false) }
    var timerSignalVersion by remember { mutableStateOf(0) }
    val activeTouchPoints = remember { mutableStateListOf<Offset>() }

    fun dispatchActions(rawActions: List<TrackpadAction>, nowMs: Long) {
        val wasDragging = isDraggingUi
        val nowDragging = machine.isDraggingLocked
        if (nowDragging != wasDragging) {
            isDraggingUi = nowDragging
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (rawActions.any { it is TrackpadAction.Button && it.action == ButtonAction.CLICK }) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        val coalesced = coalescer.recordOrFlush(rawActions, nowMs)
        for (act in coalesced) {
            currentOnAction(act)
        }
    }

    // Drive gesture timeout transitions (single-tap confirmation & stationary drag-hold lock)
    // and flush any trailing sub-frame coalesced motion deltas using monotonic SystemClock.uptimeMillis().
    LaunchedEffect(timerSignalVersion) {
        val deadline = machine.nextTimeoutDeadlineMs
        if (deadline != null) {
            val waitMs = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)
            delay(waitMs)
            val nowUptimeMs = SystemClock.uptimeMillis()
            val timeoutActions = machine.onTimeout(nowUptimeMs)
            if (timeoutActions.isNotEmpty()) {
                dispatchActions(timeoutActions, nowUptimeMs)
            }
            val trailing = coalescer.flushPending(nowUptimeMs)
            trailing.forEach { currentOnAction(it) }
            if (machine.nextTimeoutDeadlineMs != null) {
                timerSignalVersion++
            }
        }
    }

    // Safety release if the composable leaves composition mid-drag.
    DisposableEffect(Unit) {
        onDispose {
            val nowUptimeMs = SystemClock.uptimeMillis()
            coalescer.flushPending(nowUptimeMs).forEach { currentOnAction(it) }
            machine.cancel().forEach { currentOnAction(it) }
        }
    }

    val shape = RoundedCornerShape(22.dp)
    val surfaceModifier = modifier
        .clip(shape)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val nowUptimeMs = event.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis()

                    val pressedPointers = event.changes
                        .filter { it.pressed }
                        .map { change ->
                            TrackpadPointer(
                                id = change.id.value,
                                x = change.position.x,
                                y = change.position.y,
                            )
                        }

                    event.changes.forEach { it.consume() }

                    activeTouchPoints.clear()
                    pressedPointers.forEach { pt -> activeTouchPoints.add(Offset(pt.x, pt.y)) }

                    val rawActions = machine.onPointerEvent(pressedPointers, nowUptimeMs)
                    dispatchActions(rawActions, nowUptimeMs)

                    if (pressedPointers.isEmpty()) {
                        val remaining = coalescer.flushPending(nowUptimeMs)
                        remaining.forEach { currentOnAction(it) }
                    }
                    timerSignalVersion++
                }
            }
        }

    if (!showVisualChrome) {
        Box(modifier = surfaceModifier)
        return
    }

    Surface(
        modifier = surfaceModifier,
        shape = shape,
        color = Color(0xFF0F172A),
        border = BorderStroke(
            width = if (isDraggingUi) 2.dp else 1.dp,
            color = if (isDraggingUi) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.14f),
        ),
        tonalElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle precision grid hairlines + live touch contact rings
            Canvas(modifier = Modifier.fillMaxSize()) {
                val thirdW = size.width / 3f
                val thirdH = size.height / 3f
                val guideColor = Color.White.copy(alpha = 0.035f)

                drawLine(guideColor, Offset(thirdW, 24f), Offset(thirdW, size.height - 24f), strokeWidth = 1f)
                drawLine(guideColor, Offset(thirdW * 2f, 24f), Offset(thirdW * 2f, size.height - 24f), strokeWidth = 1f)
                drawLine(guideColor, Offset(24f, thirdH), Offset(size.width - 24f, thirdH), strokeWidth = 1f)
                drawLine(guideColor, Offset(24f, thirdH * 2f), Offset(size.width - 24f, thirdH * 2f), strokeWidth = 1f)

                val ringColor = if (isDraggingUi) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                for (pt in activeTouchPoints) {
                    drawCircle(
                        color = ringColor.copy(alpha = 0.18f),
                        radius = 34.dp.toPx(),
                        center = pt,
                    )
                    drawCircle(
                        color = ringColor.copy(alpha = 0.65f),
                        radius = 34.dp.toPx(),
                        center = pt,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // Gesture hint watermark at bottom
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isDraggingUi) {
                        "ARRASTANDO (SOLTE PARA LIBERAR)"
                    } else {
                        "1 dedo: Mover/Clique • 2 dedos: Scroll/Dir • 3 dedos: Meio • 2x+Segurar: Arrastar"
                    },
                    color = if (isDraggingUi) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.36f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isDraggingUi) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
