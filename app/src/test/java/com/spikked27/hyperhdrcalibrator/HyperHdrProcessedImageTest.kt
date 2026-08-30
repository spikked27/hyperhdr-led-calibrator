package com.spikked27.hyperhdrcalibrator

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HyperHdrProcessedImageTest {
    @Test
    fun processedColorUsesTwoByTwoRgbImage() {
        val request = HyperHdrClient.processedImageRequest(intArrayOf(12, 34, 56), 40)
        assertEquals("image", request.getString("command"))
        assertEquals(2, request.getInt("imagewidth"))
        assertEquals(2, request.getInt("imageheight"))
        assertEquals("rgb", request.getString("format"))
        assertEquals(40, request.getInt("priority"))

        val decoded = Base64.getDecoder().decode(request.getString("imagedata"))
        assertContentEquals(
            byteArrayOf(
                12, 34, 56,
                12, 34, 56,
                12, 34, 56,
                12, 34, 56,
            ),
            decoded,
        )
    }
}
