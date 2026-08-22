package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertEquals

class PatchOrderTest {
    @Test fun calibrationOrderMatchesCompanionVideo() {
        assertEquals(
            listOf("White", "Red", "Green", "Blue", "Cyan", "Magenta", "Yellow", "Black"),
            Patch.entries.map { it.label }
        )
    }
}
