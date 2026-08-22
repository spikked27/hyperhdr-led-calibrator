package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationFlowTest {
    @Test
    fun ledBlackIsAutomaticallyCapturedAfterLastVisiblePatch() {
        val blackIndex = Patch.entries.indexOf(Patch.BLACK)
        require(blackIndex > 0)
        assertTrue(CalibrationFlow.shouldAutoCaptureLedBlack(blackIndex - 1))
    }

    @Test
    fun earlierLedPatchesRemainManual() {
        val blackIndex = Patch.entries.indexOf(Patch.BLACK)
        for (i in 0 until blackIndex - 1) {
            assertFalse(CalibrationFlow.shouldAutoCaptureLedBlack(i))
        }
    }

    @Test
    fun finalPatchHasNoNextPatch() {
        assertNull(CalibrationFlow.nextPatchIndex(Patch.entries.lastIndex))
    }
}
