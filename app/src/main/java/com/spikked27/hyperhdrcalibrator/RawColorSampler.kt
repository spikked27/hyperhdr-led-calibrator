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
        val grid = sampleGrid(width, height, pattern, whiteLevel, blackLevels, valueAt, columns = 9, rows = 9)
        val center = mutableListOf<Rgb>()
        for (y in 3..5) for (x in 3..5) center += grid[x, y]
        fun median(v: List<Double>): Double = v.sorted()[v.size / 2]
        return RawFrameStats(
            rgb = Rgb(median(center.map { it.r }), median(center.map { it.g }), median(center.map { it.b })),
            clippedFraction = grid.clippedFraction,
        )
    }

    fun sampleGrid(
        width: Int,
        height: Int,
        pattern: BayerPattern,
        whiteLevel: Int,
        blackLevels: DoubleArray,
        valueAt: (x: Int, y: Int) -> Int,
        columns: Int = 36,
        rows: Int = 24,
    ): SpatialFrame {
        require(width > 16 && height > 16)
        require(columns >= 8 && rows >= 6)
        require(whiteLevel > 0)
        require(blackLevels.size >= 4)

        fun channel(x: Int, y: Int): Int = when (pattern) {
            BayerPattern.RGGB -> when ((y and 1) * 2 + (x and 1)) { 0 -> 0; 3 -> 2; else -> 1 }
            BayerPattern.GRBG -> when ((y and 1) * 2 + (x and 1)) { 1 -> 0; 2 -> 2; else -> 1 }
            BayerPattern.GBRG -> when ((y and 1) * 2 + (x and 1)) { 2 -> 0; 1 -> 2; else -> 1 }
            BayerPattern.BGGR -> when ((y and 1) * 2 + (x and 1)) { 3 -> 0; 0 -> 2; else -> 1 }
        }

        val tiles = ArrayList<Rgb>(columns * rows)
        var clipped = 0
        var total = 0

        for (ty in 0 until rows) {
            val cy = (((ty + 0.5) * height / rows).toInt()).coerceIn(2, height - 3)
            for (tx in 0 until columns) {
                val cx = (((tx + 0.5) * width / columns).toInt()).coerceIn(2, width - 3)
                val sums = DoubleArray(3)
                val counts = IntArray(3)

                // A small 10x10 Bayer neighborhood per tile is enough to characterize local color while
                // keeping the full RAW frame cheap to process on a phone.
                var y = cy - 4
                while (y <= cy + 4) {
                    var x = cx - 4
                    while (x <= cx + 4) {
                        val parity = (y and 1) * 2 + (x and 1)
                        val black = blackLevels[parity]
                        val raw = valueAt(x, y).toDouble()
                        val denom = (whiteLevel - black).coerceAtLeast(1.0)
                        val normalized = ((raw - black) / denom).coerceIn(0.0, 1.0)
                        val c = channel(x, y)
                        sums[c] += normalized
                        counts[c]++
                        if (raw >= black + denom * 0.985) clipped++
                        total++
                        x += 2
                    }
                    y += 2
                }
                require(counts.all { it > 0 }) { "RAW tile did not contain all Bayer channels" }
                tiles += Rgb(sums[0] / counts[0], sums[1] / counts[1], sums[2] / counts[2])
            }
        }

        return SpatialFrame(
            columns = columns,
            rows = rows,
            tiles = tiles,
            clippedFraction = if (total == 0) 0.0 else clipped.toDouble() / total,
        )
    }
}
