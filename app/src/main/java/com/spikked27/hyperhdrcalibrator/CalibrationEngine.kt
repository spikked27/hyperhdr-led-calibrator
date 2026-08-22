package com.spikked27.hyperhdrcalibrator

object CalibrationEngine {
    private fun subtract(sample: Rgb, black: Rgb): Rgb = Rgb(
        (sample.r-black.r).coerceAtLeast(0.0),
        (sample.g-black.g).coerceAtLeast(0.0),
        (sample.b-black.b).coerceAtLeast(0.0),
    )

    fun solve(tv: Map<Patch, Rgb>, led: Map<Patch, Rgb>): CalibrationResult {
        require(tv.keys.containsAll(Patch.entries) && led.keys.containsAll(Patch.entries)) { "Missing calibration measurements" }
        val tvBlack=tv.getValue(Patch.BLACK)
        val ledBlack=led.getValue(Patch.BLACK)
        fun t(p:Patch)=subtract(tv.getValue(p),tvBlack)
        fun l(p:Patch)=subtract(led.getValue(p),ledBlack)

        val ledBasis = Matrix3.columns(
            l(Patch.RED).normalized(),
            l(Patch.GREEN).normalized(),
            l(Patch.BLUE).normalized(),
        )
        val invLed = ledBasis.inverse()

        val targets = linkedMapOf<Patch, IntArray>()
        for (patch in Patch.entries) {
            if (patch == Patch.BLACK) {
                targets[patch] = intArrayOf(0,0,0)
                continue
            }
            val command = (invLed * t(patch).normalized()).clamp01()
            require(command.max() > 1e-6) { "${patch.label} measurement is too dark after black-level subtraction" }
            targets[patch] = command.normalized().to255()
        }

        val validationPatches = listOf(Patch.CYAN, Patch.MAGENTA, Patch.YELLOW, Patch.WHITE)
        val before = validationPatches.map { ColorScience.deltaE76(t(it), l(it)) }.average()
        val after = validationPatches.map { p ->
            val q = targets.getValue(p)
            val predicted = ledBasis * Rgb(q[0]/255.0, q[1]/255.0, q[2]/255.0)
            ColorScience.deltaE76(t(p), predicted)
        }.average()

        val warning = if (after > before * 0.95)
            "Correction did not materially improve validation colors; repeat measurements with room lights off, fixed camera position, and a neutral wall."
        else null
        return CalibrationResult(targets, before, after, warning)
    }
}
