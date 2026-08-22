package com.spikked27.hyperhdrcalibrator

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class HyperHdrClient(private val server: HyperHdrServer) {
    private fun request(obj: JSONObject): JSONObject {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server.host, server.jsonPort), 2000)
            socket.soTimeout = 2500
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            writer.write(obj.toString()); writer.write("\n"); writer.flush()
            val line = reader.readLine() ?: error("HyperHDR closed the JSON connection without a response")
            return JSONObject(line)
        }
    }

    fun ping(): Boolean = runCatching {
        val r = request(JSONObject().put("command", "serverinfo").put("tan", 1))
        r.optBoolean("success", true)
    }.getOrDefault(false)

    fun setColor(rgb: IntArray, priority: Int = 40) {
        require(rgb.size == 3)
        val color = org.json.JSONArray().put(rgb[0]).put(rgb[1]).put(rgb[2])
        request(JSONObject()
            .put("command", "color")
            .put("color", color)
            .put("duration", 0)
            .put("priority", priority)
            .put("origin", "HyperHDR LED Calibrator"))
    }

    fun clear(priority: Int = 40) {
        request(JSONObject().put("command", "clear").put("priority", priority))
    }
}
