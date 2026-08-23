package com.spikked27.hyperhdrcalibrator

import android.graphics.Matrix
import android.graphics.RectF
import android.view.Surface
import android.view.TextureView
import kotlin.math.max

/** Camera2 does not rotate/scale a TextureView preview for us. */
object PreviewGeometry {
    const val BUFFER_WIDTH = 1280
    const val BUFFER_HEIGHT = 720

    /**
     * Apply the standard Camera2 center-crop transform for a 1280x720 stream. Beta 9 runs the
     * calibration activity in sensorLandscape so the displayed preview and the TV are both 16:9.
     * The same transform is seen by TextureView.getBitmap(), keeping overlay/detection coordinates
     * aligned with what the user actually sees.
     */
    fun configure(view: TextureView) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return

        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                val bufferRect = RectF(0f, 0f, BUFFER_HEIGHT.toFloat(), BUFFER_WIDTH.toFloat())
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
            Surface.ROTATION_180 -> matrix.postRotate(180f, centerX, centerY)
            else -> {
                // Some OEMs report ROTATION_0 even after a fixed-orientation activity is created.
                // Preserve aspect rather than stretching in that case.
                val sourceAspect = BUFFER_WIDTH.toFloat() / BUFFER_HEIGHT
                val viewAspect = width.toFloat() / height
                if (kotlin.math.abs(sourceAspect - viewAspect) > 0.01f) {
                    val scale = max(width.toFloat() / BUFFER_WIDTH, height.toFloat() / BUFFER_HEIGHT)
                    matrix.postScale(
                        BUFFER_WIDTH * scale / width,
                        BUFFER_HEIGHT * scale / height,
                        centerX,
                        centerY,
                    )
                }
            }
        }
        view.setTransform(matrix)
    }
}