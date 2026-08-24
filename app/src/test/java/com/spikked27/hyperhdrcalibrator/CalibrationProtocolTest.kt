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

    @Test fun videoLeavesTimeForReadyCountdownAndBorderAcquisition() {
        assertTrue(CalibrationProtocol.BORDER_COUNTDOWN_SECONDS >= 3)
        assertTrue(
            CalibrationProtocol.VIDEO_LEAD_IN_SECONDS >= CalibrationProtocol.BORDER_COUNTDOWN_SECONDS + 7,
            "Black lead-in should leave several seconds for border detection after the framing countdown",
        )
        assertTrue(CalibrationProtocol.TV_PATCH_SECONDS >= 10)
        assertTrue(CalibrationProtocol.FINAL_BLACK_SECONDS >= 90)
    }
}
