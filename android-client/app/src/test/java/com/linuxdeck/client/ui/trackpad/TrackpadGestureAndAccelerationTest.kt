package com.linuxdeck.client.ui.trackpad

import com.linuxdeck.client.network.ConnectionState
import com.linuxdeck.client.protocol.v1.ButtonAction
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.MouseButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackpadGestureAndAccelerationTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `TrackpadDeltaCalculator preserves sub-pixel precision at low speed and accelerates fast flicks`() {
        val config = TrackpadAccelerationConfig(
            baseSensitivity = 1.0f,
            accelerationFactor = 0.5f,
            velocityThresholdPxPerMs = 0.5f,
            maxMultiplier = 3.5f,
        )
        val calc = TrackpadDeltaCalculator(config)

        // Slow precision glide: 0.4px over 16ms (velocity = 0.025 px/ms <= 0.5 threshold -> gain = 1.0)
        // Three steps of 0.4px = 1.2px total -> must accumulate to 1px without losing sub-pixel remainder.
        val step1 = calc.computeAcceleratedMove(0.4f, 0f, dtMs = 16L)
        val step2 = calc.computeAcceleratedMove(0.4f, 0f, dtMs = 16L)
        val step3 = calc.computeAcceleratedMove(0.4f, 0f, dtMs = 16L)
        assertEquals(0, step1.dx)
        assertEquals(0, step2.dx)
        assertEquals(1, step3.dx)

        calc.resetRemainders()

        // Compare slow 20px drag (dt = 80ms -> 0.25 px/ms) vs fast 20px flick (dt = 5ms -> 4.0 px/ms)
        val slowMove = calc.computeAcceleratedMove(20f, 0f, dtMs = 80L)
        calc.resetRemainders()
        val fastFlick = calc.computeAcceleratedMove(20f, 0f, dtMs = 5L)

        assertEquals(20, slowMove.dx)
        assertTrue(
            "Fast flick (${fastFlick.dx}) must exceed linear slow move (${slowMove.dx})",
            fastFlick.dx > slowMove.dx,
        )
        // Max multiplier cap (20 * 3.5 = 70)
        assertTrue(fastFlick.dx <= 70)
    }

    @Test
    fun `MotionCoalescer batches high-rate touch samples to 60Hz or 120Hz and flushes before clicks`() {
        val coalescer = MotionCoalescer(frameIntervalMs = 8L) // 120Hz cadence

        // Initial sample at t=0ms flushes immediately (since no prior emission)
        val t0 = coalescer.recordOrFlush(listOf(TrackpadAction.MoveRelative(2, 1)), nowMs = 100L)
        assertEquals(listOf(TrackpadAction.MoveRelative(2, 1)), t0)

        // Two 240Hz samples arriving at t=103ms and t=106ms (< 8ms window) are coalesced
        assertTrue(coalescer.recordOrFlush(listOf(TrackpadAction.MoveRelative(3, -1)), nowMs = 103L).isEmpty())
        assertTrue(coalescer.recordOrFlush(listOf(TrackpadAction.MoveRelative(4, -2)), nowMs = 106L).isEmpty())

        // At t=109ms (>= 8ms from 100ms), accumulated delta (3+4, -1+-2) = (7, -3) emits as a single packet
        val t109 = coalescer.recordOrFlush(listOf(TrackpadAction.MoveRelative(1, 0)), nowMs = 109L)
        assertEquals(listOf(TrackpadAction.MoveRelative(8, -3)), t109)

        // Sub-frame movement followed immediately by a click MUST flush the move BEFORE the click
        assertTrue(coalescer.recordOrFlush(listOf(TrackpadAction.MoveRelative(5, 5)), nowMs = 112L).isEmpty())
        val withClick = coalescer.recordOrFlush(
            listOf(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.CLICK)),
            nowMs = 113L,
        )
        assertEquals(
            listOf(
                TrackpadAction.MoveRelative(5, 5),
                TrackpadAction.Button(MouseButton.LEFT, ButtonAction.CLICK),
            ),
            withClick,
        )
    }

    @Test
    fun `1-finger tap emits LEFT CLICK and 1-finger drag emits MoveRelative without stray click`() {
        val machine = TrackpadGestureMachine()

        // 1-finger tap
        assertTrue(machine.onPointerEvent(listOf(TrackpadPointer(1L, 100f, 100f)), timestampMs = 0L).isEmpty())
        assertTrue(machine.onPointerEvent(emptyList(), timestampMs = 55L).isEmpty())
        val tapResult = machine.onTimeout(nowMs = 55L + machine.config.doubleTapTimeoutMs + 5L)
        assertEquals(
            listOf(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.CLICK)),
            tapResult,
        )

        // 1-finger drag beyond tapSlopPx
        machine.onPointerEvent(listOf(TrackpadPointer(2L, 100f, 100f)), timestampMs = 500L)
        val moveActions = machine.onPointerEvent(listOf(TrackpadPointer(2L, 145f, 125f)), timestampMs = 516L)
        assertEquals(TrackpadGestureMachine.State.ONE_FINGER_MOVE, machine.state)
        assertTrue(moveActions.any { it is TrackpadAction.MoveRelative })

        val releaseActions = machine.onPointerEvent(emptyList(), timestampMs = 532L)
        assertTrue(releaseActions.isEmpty())
        assertEquals(TrackpadGestureMachine.State.IDLE, machine.state)
    }

    @Test
    fun `2-finger tap emits RIGHT CLICK, 3-finger tap emits MIDDLE CLICK, and 2-finger drag emits Scroll`() {
        val machine = TrackpadGestureMachine()

        // 2-finger tap -> RIGHT CLICK
        machine.onPointerEvent(
            listOf(TrackpadPointer(1L, 100f, 200f), TrackpadPointer(2L, 180f, 200f)),
            timestampMs = 1000L,
        )
        val rightTap = machine.onPointerEvent(emptyList(), timestampMs = 1070L)
        assertEquals(
            listOf(TrackpadAction.Button(MouseButton.RIGHT, ButtonAction.CLICK)),
            rightTap,
        )

        // 3-finger tap -> MIDDLE CLICK
        machine.onPointerEvent(
            listOf(
                TrackpadPointer(1L, 100f, 200f),
                TrackpadPointer(2L, 160f, 200f),
                TrackpadPointer(3L, 220f, 200f),
            ),
            timestampMs = 2000L,
        )
        val middleTap = machine.onPointerEvent(emptyList(), timestampMs = 2080L)
        assertEquals(
            listOf(TrackpadAction.Button(MouseButton.MIDDLE, ButtonAction.CLICK)),
            middleTap,
        )

        // 2-finger vertical drag -> MouseScrollEvent without right click on lift
        machine.onPointerEvent(
            listOf(TrackpadPointer(1L, 100f, 300f), TrackpadPointer(2L, 180f, 300f)),
            timestampMs = 3000L,
        )
        val scrollActions = machine.onPointerEvent(
            listOf(TrackpadPointer(1L, 100f, 240f), TrackpadPointer(2L, 180f, 240f)),
            timestampMs = 3020L,
        )
        assertEquals(TrackpadGestureMachine.State.TWO_FINGER_SCROLL, machine.state)
        val scroll = scrollActions.filterIsInstance<TrackpadAction.Scroll>().single()
        // Natural scrolling: swiping fingers UP (dy = -60px) emits negative wheel steps (scroll down)
        assertTrue(scroll.dy < 0)

        val liftAfterScroll = machine.onPointerEvent(emptyList(), timestampMs = 3050L)
        assertTrue(liftAfterScroll.isEmpty())
    }

    @Test
    fun `double-tap and hold enters Drag and Drop with LEFT PRESS, relative moves, and LEFT RELEASE`() {
        val machine = TrackpadGestureMachine()

        // First tap down & up
        machine.onPointerEvent(listOf(TrackpadPointer(1L, 200f, 200f)), timestampMs = 0L)
        machine.onPointerEvent(emptyList(), timestampMs = 50L)
        assertEquals(TrackpadGestureMachine.State.AWAITING_SECOND_TAP, machine.state)

        // Second tap down within doubleTapTimeoutMs & hold past dragHoldDelayMs
        machine.onPointerEvent(listOf(TrackpadPointer(2L, 204f, 202f)), timestampMs = 130L)
        assertEquals(TrackpadGestureMachine.State.SECOND_TAP_DOWN, machine.state)

        val holdActions = machine.onTimeout(nowMs = 130L + machine.config.dragHoldDelayMs + 5L)
        assertTrue(machine.isDraggingLocked)
        assertEquals(
            listOf(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.PRESS)),
            holdActions,
        )

        // Dragging moves cursor while LEFT button stays pressed
        val dragMove = machine.onPointerEvent(listOf(TrackpadPointer(2L, 260f, 240f)), timestampMs = 290L)
        assertTrue(dragMove.any { it is TrackpadAction.MoveRelative })
        assertFalse(dragMove.any { it is TrackpadAction.Button })

        // Lifting finger emits LEFT RELEASE
        val release = machine.onPointerEvent(emptyList(), timestampMs = 320L)
        assertFalse(machine.isDraggingLocked)
        assertEquals(
            listOf(TrackpadAction.Button(MouseButton.LEFT, ButtonAction.RELEASE)),
            release,
        )
    }

    @Test
    fun `TrackpadViewModel sticky modifiers toggle PRESS and RELEASE and preserve lock across workspace combos`() = runTest(testDispatcher) {
        val sent = mutableListOf<Envelope>()
        val fakeSink = object : TrackpadEventSink {
            override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
            override fun sendAsync(envelope: Envelope): Boolean {
                sent.add(envelope)
                return true
            }
        }

        val vm = TrackpadViewModel(sink = fakeSink, ioDispatcher = testDispatcher)

        // Lock SUPER
        vm.toggleStickyModifier(StickyModifier.SUPER)
        assertTrue(StickyModifier.SUPER in vm.uiState.value.lockedModifiers)
        assertEquals("SUPER", sent.last().keyEvent.key)
        assertEquals(ButtonAction.PRESS, sent.last().keyEvent.action)

        // Trigger workspace Super+3 while SUPER is sticky-locked -> sends combo AND re-asserts SUPER PRESS
        sent.clear()
        vm.sendKeyCombo("SUPER+3")
        assertEquals(2, sent.size)
        assertEquals("SUPER+3", sent[0].keyComboEvent.combo)
        assertEquals("SUPER", sent[1].keyEvent.key)
        assertEquals(ButtonAction.PRESS, sent[1].keyEvent.action)

        // Clear all modifiers -> sends SUPER RELEASE
        sent.clear()
        vm.clearStickyModifiers()
        assertTrue(vm.uiState.value.lockedModifiers.isEmpty())
        assertEquals(1, sent.size)
        assertEquals("SUPER", sent[0].keyEvent.key)
        assertEquals(ButtonAction.RELEASE, sent[0].keyEvent.action)
    }
}

