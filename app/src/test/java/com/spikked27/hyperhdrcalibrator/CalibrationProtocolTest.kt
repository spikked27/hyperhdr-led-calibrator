package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationProtocolTest {
    @Test fun tvAndLedSequencesAreLockedToExpectedOrder() {
        val expected = listOf(
            Patch.WHITE,
            Patch.RED,
            Patch.GREEN,
            Patch.BLUE,
            Patch.CYAN,
            Patch.MAGENTA,
            Patch.YELLOW,
            Patch.BLACK,
        )
        assertEquals(expected, CalibrationProtocol.tvSequence)
        assertEquals(expected, CalibrationProtocol.ledSequence)
    }

    @Test fun videoLeavesLargeFinalBlackWindowForAutomaticLedPass() {
        assertTrue(CalibrationProtocol.FINAL_BLACK_SECONDS >= 90)
        assertTrue(CalibrationProtocol.TV_PATCH_SECONDS >= 8)
        assertTrue(CalibrationProtocol.VIDEO_LEAD_IN_SECONDS >= 5)
    }
}
