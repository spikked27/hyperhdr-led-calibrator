package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class CalibrationEngineTest {
    private fun allIdeal(black: Rgb = Rgb(0.0,0.0,0.0)): Map<Patch,Rgb> = Patch.entries.associateWith { p ->
        if (p == Patch.BLACK) black else Rgb(p.rgb[0]/255.0,p.rgb[1]/255.0,p.rgb[2]/255.0)
    }

    @Test fun identityCalibrationIsIdentity() {
        val m=allIdeal(); val r=CalibrationEngine.solve(m,m)
        Patch.entries.forEach { assertContentEquals(it.rgb,r.targets.getValue(it),it.label) }
        assertTrue(r.estimatedErrorAfter < 1e-6)
        assertTrue(CalibrationEngine.measuredChromaticError(m,m) < 1e-6)
    }

    @Test fun ambientBlackOffsetIsRemoved() {
        val black=Rgb(0.04,0.03,0.02)
        fun withOffset(p:Patch):Rgb {
            if (p==Patch.BLACK) return black
            return Rgb(p.rgb[0]/255.0+black.r,p.rgb[1]/255.0+black.g,p.rgb[2]/255.0+black.b)
        }
        val m=Patch.entries.associateWith(::withOffset)
        val r=CalibrationEngine.solve(m,m)
        Patch.entries.forEach { assertContentEquals(it.rgb,r.targets.getValue(it),it.label) }
    }

    @Test fun compensatesGreenLeakInRedLed() {
        val tv=allIdeal().toMutableMap()
        val led=allIdeal().toMutableMap()
        led[Patch.RED]=Rgb(1.0,0.20,0.0)
        val r=CalibrationEngine.solve(tv,led)
        val red=r.targets.getValue(Patch.RED)
        assertTrue(red[0] >= 250)
        assertTrue(red[1] < 30)
    }

    @Test fun singularLedPrimariesAreRejected() {
        val tv=allIdeal()
        val led=allIdeal().toMutableMap()
        led[Patch.GREEN]=led.getValue(Patch.RED)
        val failed=runCatching { CalibrationEngine.solve(tv,led) }.isFailure
        assertTrue(failed)
    }

    @Test fun channelGainIsRemovedByWhiteReference() {
        val tv=allIdeal()
        val gains=Rgb(1.0,0.72,0.58)
        val led=allIdeal().mapValues { (patch,c) ->
            if (patch == Patch.BLACK) c else Rgb(c.r*gains.r,c.g*gains.g,c.b*gains.b)
        }
        assertTrue(CalibrationEngine.measuredChromaticError(tv,led) < 1e-6)
    }
}
