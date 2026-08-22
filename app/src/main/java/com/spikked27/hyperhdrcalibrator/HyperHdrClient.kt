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

/**
 * Small JSON/TCP client for HyperHDR.
 *
 * HyperHDR can close an otherwise-idle JSON socket between the discovery/intro screen and
 * calibration. Beta 2/3 kept that socket open, which made the first command of a later phase
 * vulnerable to a stale connection. Control commands are intentionally short-lived now:
 * each operation opens a fresh socket, selects the locked instance, sends the command, and
 * closes the socket. A transient network failure is retried once.
 */
class HyperHdrClient(private val server: HyperHdrServer) : Closeable {
    @Volatile private var selectedInstanceId: Int? = null

    private fun newSocket(): Socket = Socket().apply {
        connect(InetSocketAddress(server.host, server.jsonPort), CONNECT_TIMEOUT_MS)
        soTimeout = READ_TIMEOUT_MS
        tcpNoDelay = true
        keepAlive = false
    }

    private fun sendAndRead(
        writer: BufferedWriter,
        reader: BufferedReader,
        obj: JSONObject,
    ): JSONObject {
        writer.write(obj.toString())
        writer.write("\n")
        writer.flush()
        val line = reader.readLine()
            ?: error("HyperHDR closed the JSON connection without a response")
        return validateResponse(JSONObject(line))
    }

    private fun requestOnce(obj: JSONObject): JSONObject = newSocket().use { socket ->
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        sendAndRead(writer, reader, obj)
    }

    /** Execute one command against a specific instance on one fresh socket. */
    private fun requestForInstance(instanceId: Int, obj: JSONObject): JSONObject = newSocket().use { socket ->
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

        // This is the same sequence that proved reliable during discovery/connection, but the
        // socket exists only for this operation so there is no idle/stale session to recover.
        sendAndRead(writer, reader, serverInfoRequest())
        sendAndRead(writer, reader, instanceSwitchRequest(instanceId))
        sendAndRead(writer, reader, obj)
    }

    private fun <T> retryOnce(block: () -> T): T {
        var first: Throwable? = null
        repeat(2) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                if (attempt == 0) {
                    first = t
                    Thread.sleep(RETRY_DELAY_MS)
                } else {
                    val firstMessage = first?.message?.takeIf { it.isNotBlank() }
                    val lastMessage = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    error(
                        if (firstMessage == null || firstMessage == lastMessage) lastMessage
                        else "$lastMessage (first attempt: $firstMessage)"
                    )
                }
            }
        }
        error("HyperHDR request failed")
    }

    fun ping(): Boolean = runCatching {
        requestOnce(serverInfoRequest())
        true
    }.getOrDefault(false)

    fun discoverInstances(): List<HyperHdrInstance> =
        parseInstances(requestOnce(serverInfoRequest()), server.name)

    /**
     * Lock this client to one HyperHDR instance. No long-lived network socket is retained.
     */
    @Synchronized
    fun connectTo(instanceId: Int) {
        require(instanceId >= 0) { "Invalid HyperHDR instance" }
        retryOnce {
            newSocket().use { socket ->
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                sendAndRead(writer, reader, serverInfoRequest())
                sendAndRead(writer, reader, instanceSwitchRequest(instanceId))
            }
        }
        selectedInstanceId = instanceId
    }

    @Synchronized
    fun setColor(rgb: IntArray, priority: Int = TEST_PRIORITY) {
        val instanceId = selectedInstanceId ?: error("Not connected to a HyperHDR instance")
        retryOnce { requestForInstance(instanceId, colorRequest(rgb, priority)) }
    }

    @Synchronized
    fun clear(priority: Int = TEST_PRIORITY) {
        val instanceId = selectedInstanceId ?: return
        retryOnce { requestForInstance(instanceId, clearRequest(priority)) }
    }

    @Synchronized
    override fun close() {
        selectedInstanceId = null
    }

    companion object {
        const val TEST_PRIORITY = 40
        private const val ORIGIN = "LED Calibrator" // HyperHDR schema maxLength is 20.
        private const val CONNECT_TIMEOUT_MS = 1800
        private const val READ_TIMEOUT_MS = 2200
        private const val RETRY_DELAY_MS = 180L

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
