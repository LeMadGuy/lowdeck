package com.linuxdeck.client.ui.remote

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

/**
 * Axis-aligned rectangle in viewport pixel coordinates representing the aspect-fitted
 * video frame inside the container view.
 */
@Immutable
data class VideoViewportBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float
        get() = left + width

    val bottom: Float
        get() = top + height
}

/**
 * Constrained pan offset `(panX, panY)` in viewport pixels for a given zoom factor.
 */
@Immutable
data class PanOffset(
    val panX: Float,
    val panY: Float,
)

/**
 * Normalized touch coordinate result in `[0.0, 1.0]` mapped onto the remote Linux monitor.
 *
 * @property normX Normalized horizontal coordinate clamped to `[0.0, 1.0]`.
 * @property normY Normalized vertical coordinate clamped to `[0.0, 1.0]`.
 * @property isInsideVideo True when the touch landed inside the active monitor image (excluding
 *                         top/bottom or left/right black letterbox/pillarbox bars).
 */
@Immutable
data class MappedTouchCoordinate(
    val normX: Float,
    val normY: Float,
    val isInsideVideo: Boolean,
)

/**
 * Pure geometric mapper converting touch coordinates `(touchX, touchY)` inside an Android viewport
 * `(viewWidth x viewHeight)` displaying an aspect-fitted WebRTC video stream
 * `(streamWidth x streamHeight)` under arbitrary `zoom` and `(panX, panY)` transformations into
 * normalized Linux `/dev/uinput` absolute coordinates `(norm_x, norm_y) in [0.0, 1.0]`.
 *
 * Transformation model (matching `Modifier.graphicsLayer` with default `TransformOrigin.Center`):
 * 1. At `zoom = 1.0` and `pan = (0, 0)`, the stream `(streamWidth, streamHeight)` is centered inside
 *    `(viewWidth, viewHeight)` with uniform scale `s = min(viewWidth / streamWidth, viewHeight / streamHeight)`,
 *    producing letterbox/pillarbox margins `(baseLeft, baseTop)`.
 * 2. Applying `scaleX = zoom, scaleY = zoom` around viewport center `(cx, cy) = (viewWidth / 2, viewHeight / 2)`
 *    followed by translation `(panX, panY)` maps any unzoomed viewport point `(ux, uy)` to screen point:
 *    `screenX = cx + (ux - cx) * zoom + panX`
 *    `screenY = cy + (uy - cy) * zoom + panY`
 * 3. Inverting this transform yields `(ux, uy)`, which is then projected onto `[baseLeft, baseRight]`
 *    and `[baseTop, baseBottom]` to obtain exact normalized monitor coordinates `(normX, normY)`.
 */
object TouchCoordinateMapper {

    const val MIN_ZOOM: Float = 1.0f
    const val MAX_ZOOM: Float = 5.0f

    /**
     * Computes the unzoomed (`zoom = 1.0`, `pan = 0`) aspect-fitted video rectangle inside
     * a container of size `[viewWidth] x [viewHeight]`.
     */
    fun computeBaseVideoBounds(
        viewWidth: Float,
        viewHeight: Float,
        streamWidth: Int,
        streamHeight: Int,
    ): VideoViewportBounds {
        if (viewWidth <= 0f || viewHeight <= 0f || streamWidth <= 0 || streamHeight <= 0) {
            return VideoViewportBounds(0f, 0f, max(0f, viewWidth), max(0f, viewHeight))
        }

        val streamW = streamWidth.toFloat()
        val streamH = streamHeight.toFloat()
        val fitScale = min(viewWidth / streamW, viewHeight / streamH)

        val fittedWidth = streamW * fitScale
        val fittedHeight = streamH * fitScale
        val baseLeft = (viewWidth - fittedWidth) * 0.5f
        val baseTop = (viewHeight - fittedHeight) * 0.5f

        return VideoViewportBounds(
            left = baseLeft,
            top = baseTop,
            width = fittedWidth,
            height = fittedHeight,
        )
    }

    /**
     * Computes the zoomed and panned video rectangle in screen pixel coordinates.
     */
    fun computeTransformedVideoBounds(
        viewWidth: Float,
        viewHeight: Float,
        streamWidth: Int,
        streamHeight: Int,
        zoom: Float = 1.0f,
        panX: Float = 0f,
        panY: Float = 0f,
    ): VideoViewportBounds {
        val safeZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val base = computeBaseVideoBounds(viewWidth, viewHeight, streamWidth, streamHeight)
        val cx = viewWidth * 0.5f
        val cy = viewHeight * 0.5f

        val transformedLeft = cx + (base.left - cx) * safeZoom + panX
        val transformedTop = cy + (base.top - cy) * safeZoom + panY
        return VideoViewportBounds(
            left = transformedLeft,
            top = transformedTop,
            width = base.width * safeZoom,
            height = base.height * safeZoom,
        )
    }

    /**
     * Maps a touch point `(touchX, touchY)` inside `[0..viewWidth] x [0..viewHeight]` to
     * normalized monitor coordinates `(normX, normY)` in `[0.0, 1.0]`, discounting letterboxing
     * and the active `zoom` and `(panX, panY)` state.
     */
    fun mapTouchToNormalized(
        touchX: Float,
        touchY: Float,
        viewWidth: Float,
        viewHeight: Float,
        streamWidth: Int,
        streamHeight: Int,
        zoom: Float = 1.0f,
        panX: Float = 0f,
        panY: Float = 0f,
    ): MappedTouchCoordinate {
        if (viewWidth <= 0f || viewHeight <= 0f || streamWidth <= 0 || streamHeight <= 0) {
            return MappedTouchCoordinate(normX = 0f, normY = 0f, isInsideVideo = false)
        }

        val safeZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val base = computeBaseVideoBounds(viewWidth, viewHeight, streamWidth, streamHeight)
        if (base.width <= 0f || base.height <= 0f) {
            return MappedTouchCoordinate(normX = 0f, normY = 0f, isInsideVideo = false)
        }

        val cx = viewWidth * 0.5f
        val cy = viewHeight * 0.5f

        // Invert graphicsLayer(scaleX = safeZoom, scaleY = safeZoom, translationX = panX, translationY = panY)
        val unzoomedX = cx + (touchX - cx - panX) / safeZoom
        val unzoomedY = cy + (touchY - cy - panY) / safeZoom

        val rawNormX = (unzoomedX - base.left) / base.width
        val rawNormY = (unzoomedY - base.top) / base.height

        val epsilon = 1e-4f
        val inside = rawNormX in -epsilon..(1f + epsilon) && rawNormY in -epsilon..(1f + epsilon)

        return MappedTouchCoordinate(
            normX = rawNormX.coerceIn(0f, 1f),
            normY = rawNormY.coerceIn(0f, 1f),
            isInsideVideo = inside,
        )
    }

    /**
     * Clamps `(panX, panY)` so the zoomed video frame never drifts beyond the edges of the
     * container viewport when its scaled dimension exceeds the viewport, and stays centered (`0f`)
     * along any axis where the zoomed video still fits wholly inside the viewport.
     */
    fun clampPan(
        viewWidth: Float,
        viewHeight: Float,
        streamWidth: Int,
        streamHeight: Int,
        zoom: Float,
        panX: Float,
        panY: Float,
    ): PanOffset {
        if (viewWidth <= 0f || viewHeight <= 0f || streamWidth <= 0 || streamHeight <= 0) {
            return PanOffset(0f, 0f)
        }
        val safeZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val base = computeBaseVideoBounds(viewWidth, viewHeight, streamWidth, streamHeight)

        val scaledWidth = base.width * safeZoom
        val scaledHeight = base.height * safeZoom

        val maxPanX = max(0f, (scaledWidth - viewWidth) * 0.5f)
        val maxPanY = max(0f, (scaledHeight - viewHeight) * 0.5f)

        return PanOffset(
            panX = panX.coerceIn(-maxPanX, maxPanX),
            panY = panY.coerceIn(-maxPanY, maxPanY),
        )
    }
}

