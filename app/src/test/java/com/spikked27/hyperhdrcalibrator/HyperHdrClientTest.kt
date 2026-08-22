package com.spikked27.hyperhdrcalibrator

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class HyperHdrClientTest {
    @Test
    fun colorCommandMatchesHyperHdrSchemaAndOriginFitsLimit() {
        val request = HyperHdrClient.colorRequest(intArrayOf(255, 64, 0))
        assertEquals("color", request.getString("command"))
        assertEquals(40, request.getInt("priority"))
        assertEquals(0, request.getInt("duration"))
        assertTrue(request.getString("origin").length <= 20)
        val color = request.getJSONArray("color")
        assertEquals(255, color.getInt(0))
        assertEquals(64, color.getInt(1))
        assertEquals(0, color.getInt(2))
    }

    @Test
    fun blackoutCommandForTvCalibrationIsTrueBlack() {
        val request = HyperHdrClient.colorRequest(Patch.BLACK.rgb)
        val color = request.getJSONArray("color")
        assertEquals(0, color.getInt(0))
        assertEquals(0, color.getInt(1))
        assertEquals(0, color.getInt(2))
        assertEquals(HyperHdrClient.TEST_PRIORITY, request.getInt("priority"))
        assertEquals(0, request.getInt("duration"))
    }

    @Test
    fun instanceSwitchTargetsChosenInstance() {
        val request = HyperHdrClient.instanceSwitchRequest(3)
        assertEquals("instance", request.getString("command"))
        assertEquals("switchTo", request.getString("subcommand"))
        assertEquals(3, request.getInt("instance"))
    }

    @Test
    fun parsesMultipleServerInfoInstances() {
        val response = JSONObject()
            .put("success", true)
            .put("info", JSONObject().put("instance", JSONArray()
                .put(JSONObject().put("instance", 0).put("friendly_name", "Living Room").put("running", true))
                .put(JSONObject().put("instance", 2).put("friendly_name", "Office").put("running", false))))

        val instances = HyperHdrClient.parseInstances(response, "Fallback")
        assertEquals(2, instances.size)
        assertEquals("Living Room", instances[0].friendlyName)
        assertTrue(instances[0].running)
        assertEquals(2, instances[1].instanceId)
        assertEquals("Office", instances[1].friendlyName)
        assertFalse(instances[1].running)
    }

    @Test
    fun rejectsExplicitHyperHdrFailure() {
        val response = JSONObject().put("success", false).put("error", "bad request")
        val error = assertFailsWith<IllegalStateException> { HyperHdrClient.validateResponse(response) }
        assertTrue(error.message.orEmpty().contains("bad request"))
    }

    @Test
    fun rejectsInvalidPriority() {
        assertFailsWith<IllegalArgumentException> { HyperHdrClient.colorRequest(intArrayOf(1, 2, 3), 0) }
        assertFailsWith<IllegalArgumentException> { HyperHdrClient.colorRequest(intArrayOf(1, 2, 3), 254) }
    }
}
