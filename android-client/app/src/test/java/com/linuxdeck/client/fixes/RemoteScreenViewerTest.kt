package com.linuxdeck.client.fixes

import android.graphics.Bitmap
import com.google.protobuf.ByteString
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.ScreenFrame
import com.linuxdeck.client.protocol.v1.SignalType
import com.linuxdeck.client.protocol.v1.WebRtcSignal
import com.linuxdeck.client.webrtc.StreamConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteScreenViewerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createDummyBitmap(): Bitmap {
        val constructor = Bitmap::class.java.declaredConstructors.first()
        constructor.isAccessible = true
        val args = constructor.parameterTypes.map { type ->
            when (type) {
                Long::class.javaPrimitiveType -> 0L
                Int::class.javaPrimitiveType -> 0
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return constructor.newInstance(*args) as Bitmap
    }

    @Test
    fun `RemoteScreenViewerController transparently activates fallback on ScreenFrame`() = runTest(testDispatcher) {
        val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 16)
        val dummyBitmap = createDummyBitmap()

        val controller = RemoteScreenViewerController(
            incomingEnvelopes = incoming,
            scope = this,
            computeDispatcher = testDispatcher,
            bitmapDecoder = { dummyBitmap },
        )
        advanceUntilIdle()

        assertFalse(controller.isFallbackActive.value)
        assertFalse(controller.isWebRtcStreaming.value)
        assertNull(controller.fallbackBitmap.value)

        // 1. Emit ScreenFrame
        val frameEnvelope = Envelope.newBuilder()
            .setRequestId("frame-01")
            .setTimestampMs(System.currentTimeMillis())
            .setScreenFrame(
                ScreenFrame.newBuilder()
                    .setData(ByteString.copyFrom(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
                    .setWidth(1280)
                    .setHeight(720)
                    .setSequence(1)
                    .build()
            )
            .build()

        incoming.emit(frameEnvelope)
        advanceUntilIdle()

        assertTrue(controller.isFallbackActive.value)
        assertNotNull(controller.fallbackBitmap.value)
        assertEquals(dummyBitmap, controller.fallbackBitmap.value)
        assertEquals(1280, controller.resolution.value.width)
        assertEquals(720, controller.resolution.value.height)

        // 2. WebRTC connects successfully -> switches to WebRTC, deactivating fallback
        controller.onWebRtcStateChanged(
            StreamConnectionState.Streaming(
                firstFrameRendered = true,
                videoWidth = 1920,
                videoHeight = 1080,
            )
        )
        advanceUntilIdle()

        assertTrue(controller.isWebRtcStreaming.value)
        assertFalse(controller.isFallbackActive.value)
        assertEquals(1920, controller.resolution.value.width)
        assertEquals(1080, controller.resolution.value.height)

        // 3. Fallback active signal from daemon
        val signalEnv = Envelope.newBuilder()
            .setRequestId("sig-fallback")
            .setTimestampMs(System.currentTimeMillis())
            .setWebrtcSignal(
                WebRtcSignal.newBuilder()
                    .setType(SignalType.FALLBACK_ACTIVE)
                    .build()
            )
            .build()

        incoming.emit(signalEnv)
        advanceUntilIdle()
        assertTrue(controller.isFallbackActive.value)

        // 4. Release cleans up
        controller.release()
        assertFalse(controller.isFallbackActive.value)
        assertFalse(controller.isWebRtcStreaming.value)
        assertNull(controller.fallbackBitmap.value)
    }
}

