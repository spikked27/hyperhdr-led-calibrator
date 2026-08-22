package com.spikked27.hyperhdrcalibrator

enum class BayerPattern { RGGB, GRBG, GBRG, BGGR }

data class RawFrameStats(
    val rgb: Rgb,
    val clippedFraction: Double,
)

object RawColorSampler {
    fun sample(
        width: Int,
        height: Int,
        pattern: BayerPattern,
        whiteLevel: Int,
        blackLevels: DoubleArray,
        valueAt: (x: Int, y: Int) -> Int,
        stride: Int = 4,
    ): RawFrameStats {
        require(width > 8 && height > 8)
        require(whiteLevel > 0)
        require(blackLevels.size >= 4)

        val left = width * 2 / 5
        val right = width * 3 / 5
        val top = height * 2 / 5
        val bottom = height * 3 / 5
        val sums = DoubleArray(3)
        val counts = IntArray(3)
        var clipped = 0
        var total = 0

        fun channel(x: Int, y: Int): Int = when (pattern) {
            BayerPattern.RGGB -> when ((y and 1) * 2 + (x and 1)) { 0 -> 0; 3 -> 2; else -> 1 }
            BayerPattern.GRBG -> when ((y and 1) * 2 + (x and 1)) { 1 -> 0; 2 -> 2; else -> 1 }
            BayerPattern.GBRG -> when ((y and 1) * 2 + (x and 1)) { 2 -> 0; 1 -> 2; else -> 1 }
            BayerPattern.BGGR -> when ((y and 1) * 2 + (x and 1)) { 3 -> 0; 0 -> 2; else -> 1 }
        }

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
                    if (raw >= black + denom * 0.985) clipped++
                    total++
                }
                x += stride
            }
            y += stride
        }

        require(counts.all { it > 0 }) { "RAW Bayer sample did not contain all color channels" }
        return RawFrameStats(
            Rgb(sums[0] / counts[0], sums[1] / counts[1], sums[2] / counts[2]),
            if (total == 0) 0.0 else clipped.toDouble() / total,
        )
    }
}
