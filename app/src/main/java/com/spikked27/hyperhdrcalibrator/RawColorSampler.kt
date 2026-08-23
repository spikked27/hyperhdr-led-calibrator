package com.spikked27.hyperhdrcalibrator

import kotlin.math.sqrt

enum class BayerPattern { RGGB, GRBG, GBRG, BGGR }

data class RawFrameStats(
    val rgb: Rgb,
    val clippedFraction: Double,
    val luminanceGradient: Double = 0.0,
    val chromaSpread: Double = 0.0,
)

object RawColorSampler {
    // We intentionally use a small spot instead of a large central rectangle. The wall can have a
    // visible brightness gradient outside this spot; only this region contributes to calibration.
    private const val ROI_HALF_PERCENT = 6 // 12% wide/high centered ROI
    private const val TILE_COUNT = 3

    fun sample(
        width: Int,
        height: Int,
        pattern: BayerPattern,
        whiteLevel: Int,
        blackLevels: DoubleArray,
        valueAt: (x: Int, y: Int) -> Int,
        stride: Int = 4,
    ): RawFrameStats {
        require(width > 16 && height > 16)
        require(whiteLevel > 0)
        require(blackLevels.size >= 4)

        val halfW = (width * ROI_HALF_PERCENT / 100).coerceAtLeast(4)
        val halfH = (height * ROI_HALF_PERCENT / 100).coerceAtLeast(4)
        val left = (width / 2 - halfW).coerceAtLeast(0)
        val right = (width / 2 + halfW).coerceAtMost(width)
        val top = (height / 2 - halfH).coerceAtLeast(0)
        val bottom = (height / 2 + halfH).coerceAtMost(height)

        val sums = DoubleArray(3)
        val counts = IntArray(3)
        val tileSums = Array(TILE_COUNT * TILE_COUNT) { DoubleArray(3) }
        val tileCounts = Array(TILE_COUNT * TILE_COUNT) { IntArray(3) }
        var clipped = 0
        var total = 0

        fun channel(x: Int, y: Int): Int = when (pattern) {
            BayerPattern.RGGB -> when ((y and 1) * 2 + (x and 1)) { 0 -> 0; 3 -> 2; else -> 1 }
            BayerPattern.GRBG -> when ((y and 1) * 2 + (x and 1)) { 1 -> 0; 2 -> 2; else -> 1 }
            BayerPattern.GBRG -> when ((y and 1) * 2 + (x and 1)) { 2 -> 0; 1 -> 2; else -> 1 }
            BayerPattern.BGGR -> when ((y and 1) * 2 + (x and 1)) { 3 -> 0; 0 -> 2; else -> 1 }
        }

        val roiW = (right - left).coerceAtLeast(1)
        val roiH = (bottom - top).coerceAtLeast(1)

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                for (dy in 0..1) for (dx in 0..1) {
                    val xx = (x + dx).coerceAtMost(width - 1)
                    val yy = (y + dy).coerceAtMost(height - 1)
                    val parity = (yy and 1) * 2 + (xx and 1)
                    val black = blackLevels[parity]
                    val raw = valueAt(xx, yy).toDouble()
                    val denom = (whiteLevel - black).coerceAtLeast(1.0)
                    val normalized = ((raw - black) / denom).coerceIn(0.0, 1.0)
                    val c = channel(xx, yy)
                    sums[c] += normalized
                    counts[c]++

                    val tx = (((xx - left) * TILE_COUNT) / roiW).coerceIn(0, TILE_COUNT - 1)
                    val ty = (((yy - top) * TILE_COUNT) / roiH).coerceIn(0, TILE_COUNT - 1)
                    val tile = ty * TILE_COUNT + tx
                    tileSums[tile][c] += normalized
                    tileCounts[tile][c]++

                    if (raw >= black + denom * 0.985) clipped++
                    total++
                }
                x += stride
            }
            y += stride
        }

        require(counts.all { it > 0 }) { "RAW Bayer sample did not contain all color channels" }
        val rgb = Rgb(sums[0] / counts[0], sums[1] / counts[1], sums[2] / counts[2])

        val tiles = tileSums.indices.mapNotNull { i ->
            val tc = tileCounts[i]
            if (tc.any { it == 0 }) null else Rgb(
                tileSums[i][0] / tc[0],
                tileSums[i][1] / tc[1],
                tileSums[i][2] / tc[2],
            )
        }

        val luminances = tiles.map { (it.r + 2.0 * it.g + it.b) / 4.0 }
        val meanLuma = luminances.average().takeIf { it > 1e-6 } ?: 0.0
        val luminanceGradient = if (meanLuma <= 0.0 || luminances.isEmpty()) 0.0
        else ((luminances.maxOrNull()!! - luminances.minOrNull()!!) / meanLuma).coerceAtLeast(0.0)

        fun chroma(v: Rgb): DoubleArray {
            val sum = (v.r + v.g + v.b).coerceAtLeast(1e-9)
            return doubleArrayOf(v.r / sum, v.g / sum, v.b / sum)
        }
        val centerChroma = chroma(rgb)
        val chromaSpread = tiles.maxOfOrNull { tile ->
            val c = chroma(tile)
            sqrt(
                (c[0] - centerChroma[0]) * (c[0] - centerChroma[0]) +
                (c[1] - centerChroma[1]) * (c[1] - centerChroma[1]) +
                (c[2] - centerChroma[2]) * (c[2] - centerChroma[2])
            )
        } ?: 0.0

        return RawFrameStats(
            rgb = rgb,
            clippedFraction = if (total == 0) 0.0 else clipped.toDouble() / total,
            luminanceGradient = luminanceGradient,
            chromaSpread = chromaSpread,
        )
    }
}
