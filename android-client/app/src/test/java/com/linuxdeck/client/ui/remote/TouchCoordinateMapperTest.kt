package com.linuxdeck.client.ui.remote

import com.linuxdeck.client.webrtc.WebRtcSignalJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class TouchCoordinateMapperTest {

    @Test
    fun `maps touches in 1080x2400 view displaying 1920x1080 stream at 2_0x zoom accurately into 0_0 to 1_0`() {
        val viewW = 1080f
        val viewH = 2400f
        val streamW = 1920
        val streamH = 1080
        val zoom = 2.0f

        // Verify base 1.0x letterboxed bounds: width = 1080, height = 607.5, top = 896.25
        val baseBounds = TouchCoordinateMapper.computeBaseVideoBounds(viewW, viewH, streamW, streamH)
        assertEquals(0f, baseBounds.left, 1e-3f)
        assertEquals(896.25f, baseBounds.top, 1e-3f)
        assertEquals(1080f, baseBounds.width, 1e-3f)
        assertEquals(607.5f, baseBounds.height, 1e-3f)

        // Verify 2.0x transformed bounds around center (540, 1200):
        // width = 2160 ([-540, 1620]), height = 1215 ([592.5, 1807.5])
        val zoomedBounds = TouchCoordinateMapper.computeTransformedVideoBounds(
            viewWidth = viewW,
            viewHeight = viewH,
            streamWidth = streamW,
            streamHeight = streamH,
            zoom = zoom,
        )
        assertEquals(-540f, zoomedBounds.left, 1e-3f)
        assertEquals(592.5f, zoomedBounds.top, 1e-3f)
        assertEquals(2160f, zoomedBounds.width, 1e-3f)
        assertEquals(1215f, zoomedBounds.height, 1e-3f)

        // 1. Center of viewport (540, 1200) -> exact center of monitor (0.5, 0.5)
        val center = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 540f,
            touchY = 1200f,
            viewWidth = viewW,
            viewHeight = viewH,
            streamWidth = streamW,
            streamHeight = streamH,
            zoom = zoom,
        )
        assertTrue(center.isInsideVideo)
        assertEquals(0.50f, center.normX, 1e-4f)
        assertEquals(0.50f, center.normY, 1e-4f)

        // 2. Left & right viewport edges at 2.0x (pan = 0) correspond to 25% and 75% of monitor width
        val leftEdge = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 0f,
            touchY = 1200f,
            viewWidth = viewW,
            viewHeight = viewH,
            streamWidth = streamW,
            streamHeight = streamH,
            zoom = zoom,
        )
        assertTrue(leftEdge.isInsideVideo)
        assertEquals(0.25f, leftEdge.normX, 1e-4f)
        assertEquals(0.50f, leftEdge.normY, 1e-4f)

        val rightEdge = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 1080f,
            touchY = 1200f,
            viewWidth = viewW,
            viewHeight = viewH,
            streamWidth = streamW,
            streamHeight = streamH,
            zoom = zoom,
        )
        assertTrue(rightEdge.isInsideVideo)
        assertEquals(0.75f, rightEdge.normX, 1e-4f)
        assertEquals(0.50f, rightEdge.normY, 1e-4f)

        // 3. Top & bottom edges of the zoomed video frame (y = 592.5 and y = 1807.5) -> normY = 0.0 and 1.0
        val topVideoEdge = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 540f,
            touchY = 592.5f,
            viewWidth = viewW,
            viewHeight = viewH,
            streamWidth = streamW,
            streamHeight = streamH,
            zoom = zoom,
        )
        assertTrue(topVideoEdge.isInsideVideo)
        assertEquals(0.50f, topVideoEdge.normX, 1e-4f)
        assertEquals(0.00f, topVideoEdge.normY, 1e-4f)

        val bottomVideoEdge = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 540f,
            touchY = 1807.5f,
            viewWidth = viewW,
            viewHeight = viewH,
            streamWidth = streamW,
            streamHeight = streamH,
            zoom = zoom,
        )
        assertTrue(bottomVideoEdge.isInsideVideo)
        assertEquals(0.50f, bottomVideoEdge.normX, 1e-4f)
        assertEquals(1.00f, bottomVideoEdge.normY, 1e-4f)

        // 4. Panned all the way right (panX = +540f brings monitor's left edge to x = 0)
        val topLeftCornerPanned = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 0f,
            touchY = 592.5f,
            viewWidth = viewW,
            viewHeight = viewH,
            streamWidth = streamW,
            streamHeight = streamH,
            zoom = zoom,
            panX = 540f,
            panY = 0f,
        )
        assertTrue(topLeftCornerPanned.isInsideVideo)
        assertEquals(0.00f, topLeftCornerPanned.normX, 1e-4f)
        assertEquals(0.00f, topLeftCornerPanned.normY, 1e-4f)

        // 5. Panned all the way left (panX = -540f brings monitor's right edge to x = 1080)
        val bottomRightCornerPanned = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 1080f,
            touchY = 1807.5f,
            viewWidth = viewW,
            viewHeight = viewH,
            streamWidth = streamW,
            streamHeight = streamH,
            zoom = zoom,
            panX = -540f,
            panY = 0f,
        )
        assertTrue(bottomRightCornerPanned.isInsideVideo)
        assertEquals(1.00f, bottomRightCornerPanned.normX, 1e-4f)
        assertEquals(1.00f, bottomRightCornerPanned.normY, 1e-4f)
    }

    @Test
    fun `discounts black letterbox and pillarbox bars and clamps pan within zoomed video bounds`() {
        // Touch inside top letterbox bar (y = 200 < 592.5) on 1080x2400 @ 2.0x zoom
        val letterboxTap = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 540f,
            touchY = 200f,
            viewWidth = 1080f,
            viewHeight = 2400f,
            streamWidth = 1920,
            streamHeight = 1080,
            zoom = 2.0f,
        )
        assertFalse(letterboxTap.isInsideVideo)
        assertEquals(0.50f, letterboxTap.normX, 1e-4f)
        assertEquals(0.00f, letterboxTap.normY, 1e-4f)

        // Landscape 2400x1080 displaying 1920x1080 @ 1.0x has 240px left/right pillarbox bars
        val pillarboxTap = TouchCoordinateMapper.mapTouchToNormalized(
            touchX = 100f,
            touchY = 540f,
            viewWidth = 2400f,
            viewHeight = 1080f,
            streamWidth = 1920,
            streamHeight = 1080,
            zoom = 1.0f,
        )
        assertFalse(pillarboxTap.isInsideVideo)
        assertEquals(0.00f, pillarboxTap.normX, 1e-4f)
        assertEquals(0.50f, pillarboxTap.normY, 1e-4f)

        // clampPan on 1080x2400 @ 2.0x: horizontal maxPan = (2160 - 1080)/2 = 540, vertical maxPan = 0 (1215 < 2400)
        val clamped = TouchCoordinateMapper.clampPan(
            viewWidth = 1080f,
            viewHeight = 2400f,
            streamWidth = 1920,
            streamHeight = 1080,
            zoom = 2.0f,
            panX = 999f,
            panY = 450f,
        )
        assertEquals(540f, clamped.panX, 1e-3f)
        assertEquals(0f, clamped.panY, 1e-3f)
    }

    @Test
    fun `WebRtcSignalJsonCodec parses Pion SDP offers and trickle ICE candidates`() {
        val offerJson = """{"type":"offer","sdp":"v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n"}"""
        val parsedOffer = WebRtcSignalJsonCodec.parseSessionDescription(offerJson, SessionDescription.Type.OFFER)
        assertEquals(SessionDescription.Type.OFFER, parsedOffer.type)
        assertTrue(parsedOffer.description.startsWith("v=0"))

        val answerJson = WebRtcSignalJsonCodec.encodeSessionDescription(
            SessionDescription(SessionDescription.Type.ANSWER, "v=0\r\na=sendrecv\r\n"),
        )
        val parsedAnswer = WebRtcSignalJsonCodec.parseSessionDescription(answerJson, SessionDescription.Type.ANSWER)
        assertEquals(SessionDescription.Type.ANSWER, parsedAnswer.type)

        val pionCandidateJson = """{"candidate":"candidate:1 1 UDP 2130706431 192.168.1.10 51234 typ host","sdpMid":"0","sdpMLineIndex":0}"""
        val ice = WebRtcSignalJsonCodec.parseIceCandidate(pionCandidateJson)
        assertNotNull(ice)
        assertEquals("0", ice?.sdpMid)
        assertEquals(0, ice?.sdpMLineIndex)
        assertTrue(ice?.sdp?.startsWith("candidate:1") == true)

        val encodedIce = WebRtcSignalJsonCodec.encodeIceCandidate(
            IceCandidate("1", 1, "candidate:2 1 UDP 2130706431 100.64.0.2 54321 typ host"),
        )
        val roundTripIce = WebRtcSignalJsonCodec.parseIceCandidate(encodedIce)
        assertEquals("1", roundTripIce?.sdpMid)
        assertEquals(1, roundTripIce?.sdpMLineIndex)

        assertNull(WebRtcSignalJsonCodec.parseIceCandidate("{}"))
    }
}

