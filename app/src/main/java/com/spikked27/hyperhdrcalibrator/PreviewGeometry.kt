package com.spikked27.hyperhdrcalibrator

import android.graphics.Matrix
import android.graphics.RectF
import android.view.Surface
import android.view.TextureView
import kotlin.math.max

/** Camera2 preview geometry helper. */
object PreviewGeometry {
    const val BUFFER_WIDTH = 1280
    const val BUFFER_HEIGHT = 720

    /**
     * Beta 9.1 runs in portrait and presents the camera viewport as 9:16. Camera2/SurfaceTexture
     * already supplies the producer transform for the normal portrait case, so we must not force a
     * landscape 16:9 buffer into that portrait viewport. Doing so was the source of the "smushed"
     * Beta 8/9 preview. Landscape transforms are retained as a defensive fallback for OEM rotation
     * behavior, but normal portrait is intentionally identity.
     */
    fun configure(view: TextureView) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return

        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()

        when (rotation) {
            Surface.ROTATION_0 -> {
                // Correct portrait layout is handled by the 9:16 TextureView dimensions.
            }
            Surface.ROTATION_180 -> {
                matrix.postRotate(180f, width / 2f, height / 2f)
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                // Defensive fallback if an OEM reports a transient landscape display rotation.
                val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
                val bufferRect = RectF(0f, 0f, BUFFER_HEIGHT.toFloat(), BUFFER_WIDTH.toFloat())
                val centerX = viewRect.centerX()
                val centerY = viewRect.centerY()
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = max(
                    height.toFloat() / BUFFER_HEIGHT,
                    width.toFloat() / BUFFER_WIDTH,
                )
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(
                    if (rotation == Surface.ROTATION_90) -90f else 90f,
                    centerX,
                    centerY,
                )
            }
        }
        view.setTransform(matrix)
    }
}