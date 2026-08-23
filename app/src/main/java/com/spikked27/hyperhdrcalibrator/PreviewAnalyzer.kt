package com.spikked27.hyperhdrcalibrator

import kotlin.math.abs
import kotlin.math.sqrt

/** Lightweight, processed-preview analysis used only for video synchronization and the on-screen TV box.
 * Calibration values still come from Camera2 RAW_SENSOR whenever available.
 */
data class PreviewFrame(
    val width: Int,
    val height: Int,
    val pixels: List<Rgb>,
) {
    init { require(width > 0 && height > 0 && pixels.size == width * height) }
    operator fun get(x: Int, y: Int): Rgb = pixels[y * width + x]
}

object PreviewAnalyzer {
    fun fromArgb(argb: IntArray, width: Int, height: Int): PreviewFrame {
        require(argb.size == width * height)
        return PreviewFrame(
            width,
            height,
            argb.map { p ->
                Rgb(
                    ((p ushr 16) and 0xff) / 255.0,
                    ((p ushr 8) and 0xff) / 255.0,
                    (p and 0xff) / 255.0,
                )
            },
        )
    }

    /** Finds the large bright white rectangle near the center of the camera preview. */
    fun detectWhiteTv(frame: PreviewFrame): NormalizedRect? {
        val lumas = frame.pixels.map(::luma)
        val border = mutableListOf<Double>()
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            if (x < frame.width / 7 || x >= frame.width * 6 / 7 || y < frame.height / 7 || y >= frame.height * 6 / 7) {
                border += lumas[y * frame.width + x]
            }
        }
        val background = median(border)
        val center = mutableListOf<Double>()
        for (y in frame.height / 4 until frame.height * 3 / 4) for (x in frame.width / 4 until frame.width * 3 / 4) {
            center += lumas[y * frame.width + x]
        }
        val foreground = percentile(center, 0.90)
        if (foreground < 0.22 || foreground <= background + 0.08) return null

        val threshold = background + (foreground - background) * 0.48
        val mask = BooleanArray(frame.width * frame.height) { i ->
            val c = frame.pixels[i]
            lumas[i] >= threshold && whiteBalanceScore(c) >= 0.55
        }
        return componentNearCenter(frame.width, frame.height, mask, null)?.takeIf { rect ->
            val area = rect.width * rect.height
            area in 0.055..0.86 && rect.width > 0.20 && rect.height > 0.12
        }
    }

    fun sample(frame: PreviewFrame, rect: NormalizedRect): Rgb {
        val safe = rect.inset(0.18)
        val values = mutableListOf<Rgb>()
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val nx = (x + 0.5) / frame.width
            val ny = (y + 0.5) / frame.height
            if (safe.contains(nx, ny)) values += frame[x, y]
        }
        if (values.isEmpty()) return Rgb(0.0, 0.0, 0.0)
        return Rgb(
            median(values.map { it.r }),
            median(values.map { it.g }),
            median(values.map { it.b }),
        )
    }

    /**
     * Follows the full-screen patch if the phone drifts a little while being hand-held.
     * The previous TV rectangle supplies the search window; the known patch color supplies segmentation.
     */
    fun trackExpectedRect(
        frame: PreviewFrame,
        previous: NormalizedRect,
        expected: Patch,
        whiteReference: Rgb,
    ): NormalizedRect? {
        if (expected == Patch.BLACK) return previous
        val search = previous.expand(0.45, 0.55)
        val centerSample = sample(frame, previous)
        val centerLuma = luma(centerSample).coerceAtLeast(luma(whiteReference) * 0.08)
        val mask = BooleanArray(frame.width * frame.height)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val nx = (x + 0.5) / frame.width
            val ny = (y + 0.5) / frame.height
            if (!search.contains(nx, ny)) continue
            val c = frame[x, y]
            if (luma(c) < centerLuma * 0.38) continue
            if (patchDistance(c, expected, whiteReference) <= 0.43) mask[y * frame.width + x] = true
        }
        val rect = componentNearCenter(frame.width, frame.height, mask, previous) ?: return null
        val areaRatio = (rect.width * rect.height) / (previous.width * previous.height).coerceAtLeast(1e-6)
        val aspectRatio = (rect.width / rect.height) / (previous.width / previous.height)
        return rect.takeIf { areaRatio in 0.42..1.85 && aspectRatio in 0.62..1.62 }
    }

    fun matchesExpected(sample: Rgb, expected: Patch, whiteReference: Rgb): Boolean {
        if (expected == Patch.BLACK) {
            return luma(sample) <= luma(whiteReference).coerceAtLeast(0.10) * 0.105
        }
        val minimum = luma(whiteReference) * if (expected == Patch.BLUE || expected == Patch.RED) 0.035 else 0.055
        if (luma(sample) < minimum) return false
        val limit = if (expected == Patch.WHITE) 0.27 else 0.39
        return patchDistance(sample, expected, whiteReference) <= limit
    }

    fun patchDistance(sample: Rgb, expected: Patch, whiteReference: Rgb): Double {
        if (expected == Patch.BLACK) return luma(sample) / luma(whiteReference).coerceAtLeast(1e-6)
        val corrected = Rgb(
            sample.r / whiteReference.r.coerceAtLeast(0.04),
            sample.g / whiteReference.g.coerceAtLeast(0.04),
            sample.b / whiteReference.b.coerceAtLeast(0.04),
        ).normalized()
        val target = Rgb(
            expected.rgb[0] / 255.0,
            expected.rgb[1] / 255.0,
            expected.rgb[2] / 255.0,
        ).normalized()
        return sqrt(
            (corrected.r - target.r) * (corrected.r - target.r) +
                (corrected.g - target.g) * (corrected.g - target.g) +
                (corrected.b - target.b) * (corrected.b - target.b)
        ) / sqrt(3.0)
    }

    private fun componentNearCenter(
        width: Int,
        height: Int,
        mask: BooleanArray,
        previous: NormalizedRect?,
    ): NormalizedRect? {
        val visited = BooleanArray(mask.size)
        val centerX = ((previous?.let { (it.left + it.right) / 2.0 } ?: 0.5) * width).toInt().coerceIn(0, width - 1)
        val centerY = ((previous?.let { (it.top + it.bottom) / 2.0 } ?: 0.5) * height).toInt().coerceIn(0, height - 1)
        var bestRect: NormalizedRect? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            val q = ArrayDeque<Int>()
            q += start
            visited[start] = true
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            var count = 0
            var sx = 0.0
            var sy = 0.0
            while (q.isNotEmpty()) {
                val i = q.removeFirst()
                if (!mask[i]) continue
                val x = i % width
                val y = i / width
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                sx += x; sy += y; count++
                val n = intArrayOf(i - 1, i + 1, i - width, i + width)
                for (j in n) {
                    if (j !in mask.indices || visited[j]) continue
                    val jx = j % width
                    val jy = j / width
                    if (abs(jx - x) + abs(jy - y) != 1) continue
                    visited[j] = true
                    if (mask[j]) q += j
                }
            }
            if (count < (width * height * 0.008).toInt().coerceAtLeast(8)) continue
            val cx = sx / count
            val cy = sy / count
            val distance = sqrt(((cx - centerX) / width) * ((cx - centerX) / width) + ((cy - centerY) / height) * ((cy - centerY) / height))
            val score = count.toDouble() - distance * width * height * 0.22
            if (score > bestScore) {
                bestScore = score
                bestRect = NormalizedRect(
                    minX.toDouble() / width,
                    minY.toDouble() / height,
                    (maxX + 1).toDouble() / width,
                    (maxY + 1).toDouble() / height,
                )
            }
        }
        return bestRect
    }

    private fun whiteBalanceScore(c: Rgb): Double {
        val max = maxOf(c.r, c.g, c.b).coerceAtLeast(1e-6)
        val min = minOf(c.r, c.g, c.b)
        return (1.0 - (max - min) / max).coerceIn(0.0, 1.0)
    }

    private fun luma(c: Rgb): Double = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return s[((s.lastIndex) * p.coerceIn(0.0, 1.0)).toInt()]
    }
}
