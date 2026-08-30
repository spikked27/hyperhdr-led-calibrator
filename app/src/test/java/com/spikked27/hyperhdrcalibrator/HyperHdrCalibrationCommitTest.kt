package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HyperHdrCalibrationCommitTest {
    @Test
    fun `full ICE adjustment request contains all anchors`() {
        val targets = Patch.entries.associateWith { it.rgb }
        val request = HyperHdrClient.adjustmentRequest(targets)
        assertEquals("adjustment", request.getString("command"))
        val adjustment = request.getJSONObject("adjustment")
        assertFalse(adjustment.getBoolean("classic_config"))
        for ((patch, key) in listOf(
            Patch.RED to "red",
            Patch.GREEN to "green",
            Patch.BLUE to "blue",
            Patch.CYAN to "cyan",
            Patch.MAGENTA to "magenta",
            Patch.YELLOW to "yellow",
            Patch.WHITE to "white",
            Patch.BLACK to "black",
        )) {
            val value = adjustment.getJSONArray(key)
            assertEquals(patch.rgb[0], value.getInt(0))
            assertEquals(patch.rgb[1], value.getInt(1))
            assertEquals(patch.rgb[2], value.getInt(2))
        }
    }
}