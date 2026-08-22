package com.spikked27.hyperhdrcalibrator

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class HyperHdrClient(private val server: HyperHdrServer) : Closeable {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private fun newSocket(): Socket = Socket().apply {
        connect(InetSocketAddress(server.host, server.jsonPort), CONNECT_TIMEOUT_MS)
        soTimeout = READ_TIMEOUT_MS
        tcpNoDelay = true
    }

    private fun exchange(socket: Socket, obj: JSONObject): JSONObject {
        val w = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        w.write(obj.toString())
        w.write("\n")
        w.flush()
        return validateResponse(JSONObject(r.readLine() ?: error("HyperHDR closed the JSON connection without a response")))
    }

    private fun requestOnce(obj: JSONObject): JSONObject = newSocket().use { exchange(it, obj) }

    @Synchronized
    private fun request(obj: JSONObject): JSONObject {
        val w = writer ?: error("Not connected to HyperHDR")
        val r = reader ?: error("Not connected to HyperHDR")
        w.write(obj.toString())
        w.write("\n")
        w.flush()
        return validateResponse(JSONObject(r.readLine() ?: error("HyperHDR closed the JSON connection without a response")))
    }

    fun ping(): Boolean = runCatching {
        requestOnce(serverInfoRequest())
        true
    }.getOrDefault(false)

    fun discoverInstances(): List<HyperHdrInstance> = parseInstances(requestOnce(serverInfoRequest()), server.name)

    @Synchronized
    fun connectTo(instanceId: Int) {
        close()
        val s = newSocket()
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

        try {
            request(serverInfoRequest())
            request(instanceSwitchRequest(instanceId))
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun setColor(rgb: IntArray, priority: Int = TEST_PRIORITY) {
        request(colorRequest(rgb, priority))
    }

    fun clear(priority: Int = TEST_PRIORITY) {
        request(clearRequest(priority))
    }

    @Synchronized
    override fun close() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }

    companion object {
        const val TEST_PRIORITY = 40
        private const val ORIGIN = "LED Calibrator" // HyperHDR schema maxLength is 20.
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val READ_TIMEOUT_MS = 3500

        internal fun serverInfoRequest(): JSONObject = JSONObject()
            .put("command", "serverinfo")
            .put("tan", 1)

        internal fun instanceSwitchRequest(instanceId: Int): JSONObject = JSONObject()
            .put("command", "instance")
            .put("subcommand", "switchTo")
            .put("instance", instanceId)

        internal fun colorRequest(rgb: IntArray, priority: Int = TEST_PRIORITY): JSONObject {
            require(rgb.size == 3) { "RGB color must contain exactly 3 channels" }
            require(priority in 1..253) { "Priority must be 1..253" }
            val color = JSONArray().put(rgb[0]).put(rgb[1]).put(rgb[2])
            return JSONObject()
                .put("command", "color")
                .put("color", color)
                .put("duration", 0)
                .put("priority", priority)
                .put("origin", ORIGIN)
        }

        internal fun clearRequest(priority: Int = TEST_PRIORITY): JSONObject = JSONObject()
            .put("command", "clear")
            .put("priority", priority)

        internal fun parseInstances(response: JSONObject, fallbackName: String): List<HyperHdrInstance> {
            val info = response.optJSONObject("info") ?: response
            val arr = info.optJSONArray("instance") ?: return listOf(HyperHdrInstance(0, fallbackName, true))
            val result = ArrayList<HyperHdrInstance>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optInt("instance", i)
                val name = item.optString("friendly_name").ifBlank {
                    item.optString("name").ifBlank { if (id == 0) fallbackName else "Instance $id" }
                }
                result += HyperHdrInstance(id, name, item.optBoolean("running", true))
            }
            return result.ifEmpty { listOf(HyperHdrInstance(0, fallbackName, true)) }
        }

        internal fun validateResponse(response: JSONObject): JSONObject {
            if (response.has("success") && !response.optBoolean("success")) {
                val reason = response.optString("error").ifBlank { response.optString("info") }
                error(if (reason.isBlank()) "HyperHDR rejected the request" else "HyperHDR: $reason")
            }
            return response
        }
    }
}
