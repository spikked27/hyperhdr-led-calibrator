package com.spikked27.hyperhdrcalibrator

import kotlin.math.sqrt

object CalibrationEngine {
    private fun subtract(sample: Rgb, black: Rgb): Rgb = Rgb(
        (sample.r - black.r).coerceAtLeast(0.0),
        (sample.g - black.g).coerceAtLeast(0.0),
        (sample.b - black.b).coerceAtLeast(0.0),
    )

    /**
     * Express a measured color relative to the WHITE measurement from the same optical path.
     *
     * This is intentionally channel-wise, not a simple brightness normalization. It removes the
     * different exposure used for the TV and wall phases and greatly reduces phone sensor channel
     * gain, wall reflectance, and lens-shading effects. WHITE therefore becomes [1,1,1].
     */
    private fun whiteReferenced(sample: Rgb, black: Rgb, white: Rgb): Rgb {
        val s = subtract(sample, black)
        val w = subtract(white, black)
        require(w.r > 1e-6 && w.g > 1e-6 && w.b > 1e-6) { "White reference is too dark in one or more camera channels" }
        return Rgb(s.r / w.r, s.g / w.g, s.b / w.b)
    }

    private fun chromaticity(v: Rgb): Rgb {
        val sum = (v.r + v.g + v.b).coerceAtLeast(1e-12)
        return Rgb(v.r / sum, v.g / sum, v.b / sum)
    }

    private fun chromaError(a: Rgb, b: Rgb): Double {
        val x = chromaticity(a)
        val y = chromaticity(b)
        return 100.0 * sqrt(
            (x.r - y.r) * (x.r - y.r) +
            (x.g - y.g) * (x.g - y.g) +
            (x.b - y.b) * (x.b - y.b)
        )
    }

    fun solve(tv: Map<Patch, Rgb>, led: Map<Patch, Rgb>): CalibrationResult {
        require(tv.keys.containsAll(Patch.entries) && led.keys.containsAll(Patch.entries)) { "Missing calibration measurements" }
        val tvBlack = tv.getValue(Patch.BLACK)
        val ledBlack = led.getValue(Patch.BLACK)
        val tvWhite = tv.getValue(Patch.WHITE)
        val ledWhite = led.getValue(Patch.WHITE)

        fun t(p: Patch) = whiteReferenced(tv.getValue(p), tvBlack, tvWhite)
        fun l(p: Patch) = whiteReferenced(led.getValue(p), ledBlack, ledWhite)

        // Do NOT normalize each LED primary independently here. Their relative strength at the fixed
        // LED exposure is useful: it tells us how much of each command is needed to reach a target hue.
        val ledBasis = Matrix3.columns(l(Patch.RED), l(Patch.GREEN), l(Patch.BLUE))
        val invLed = ledBasis.inverse()

        val targets = linkedMapOf<Patch, IntArray>()
        for (patch in Patch.entries) {
            when (patch) {
                Patch.BLACK -> {
                    targets[patch] = intArrayOf(0, 0, 0)
                    continue
                }
                Patch.WHITE -> {
                    // With the RGBW mixer threshold at 1.0, equal RGB reaches the dedicated W diode.
                    // Keep neutral white W-only instead of secretly adding RGB just to correct its tint.
                    targets[patch] = intArrayOf(255, 255, 255)
                    continue
                }
                else -> Unit
            }

            val rawCommand = invLed * t(patch)
            val nonNegative = Rgb(
                rawCommand.r.coerceAtLeast(0.0),
                rawCommand.g.coerceAtLeast(0.0),
                rawCommand.b.coerceAtLeast(0.0),
            )
            require(nonNegative.max() > 1e-6) { "${patch.label} is outside the measured LED gamut" }
            targets[patch] = nonNegative.normalized().to255()
        }

        val validationPatches = listOf(Patch.RED, Patch.GREEN, Patch.BLUE, Patch.CYAN, Patch.MAGENTA, Patch.YELLOW, Patch.WHITE)
        val before = validationPatches.map { chromaError(t(it), l(it)) }.average()
        val after = validationPatches.map { patch ->
            if (patch == Patch.WHITE) {
                chromaError(t(Patch.WHITE), l(Patch.WHITE))
            } else {
                val q = targets.getValue(patch)
                val predicted = ledBasis * Rgb(q[0] / 255.0, q[1] / 255.0, q[2] / 255.0)
                chromaError(t(patch), predicted)
            }
        }.average()

        val whiteMismatch = chromaError(t(Patch.WHITE), l(Patch.WHITE))
        val warnings = mutableListOf<String>()
        if (after > before * 0.90) {
            warnings += "The measured RGB/CMY correction provides little chromatic improvement; repeat the run and verify the TV rectangle and camera lens."
        }
        if (whiteMismatch > 2.5) {
            warnings += "Dedicated W does not closely match TV white (camera chroma error ${"%.2f".format(whiteMismatch)}). White was intentionally kept [255,255,255] so neutral white remains W-only; tune the physical W white point separately if desired."
        }

        return CalibrationResult(
            targets = targets,
            estimatedErrorBefore = before,
            estimatedErrorAfter = after,
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }
}
