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
import java.util.Base64

/**
 * Small JSON/TCP client for HyperHDR.
 *
 * Normal control operations use short-lived sockets because HyperHDR may close an otherwise-idle
 * session. Admin configuration operations are different: HyperHDR authorization is connection
 * scoped, so Beta 9.4 authenticates and performs getconfig/setconfig/read-back on the SAME socket.
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

    private fun requestForInstance(instanceId: Int, obj: JSONObject): JSONObject = newSocket().use { socket ->
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        sendAndRead(writer, reader, serverInfoRequest())
        sendAndRead(writer, reader, instanceSwitchRequest(instanceId))
        sendAndRead(writer, reader, obj)
    }

    /**
     * Authenticate this exact TCP connection as HyperHDR admin.
     *
     * If the authorization gate supplied a password, exchange it once for HyperHDR's long-lived
     * user token, then immediately drop the password from process state. Later sockets authenticate
     * with that user token. HyperHDR treats user tokens (>36 chars) as admin authorization.
     */
    private fun authenticateAdmin(writer: BufferedWriter, reader: BufferedReader) {
        val existingToken = HyperHdrAdminCredentialStore.token()
        if (!existingToken.isNullOrBlank()) {
            sendAndRead(writer, reader, authorizeTokenRequest(existingToken))
            return
        }

        val password = HyperHdrAdminCredentialStore.passwordForExchange()
            ?: error("HyperHDR admin authorization is required. Restart Beta 9.4 and enter the HyperHDR admin password.")

        val response = sendAndRead(writer, reader, authorizePasswordRequest(password))
        val returnedToken = response.optJSONObject("info")?.optString("token").orEmpty()
        require(returnedToken.length > 36) {
            "HyperHDR accepted the login but did not return a valid user token"
        }
        HyperHdrAdminCredentialStore.acceptUserToken(returnedToken)
    }

    private fun <T> adminSession(
        instanceId: Int,
        block: (BufferedWriter, BufferedReader) -> T,
    ): T = newSocket().use { socket ->
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        authenticateAdmin(writer, reader)
        sendAndRead(writer, reader, serverInfoRequest())
        sendAndRead(writer, reader, instanceSwitchRequest(instanceId))
        block(writer, reader)
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

    @Synchronized
    fun connectTo(instanceId: Int) {
        require(instanceId >= 0) { "Invalid HyperHDR instance" }
        retryOnce {
            newSocket().use { socket ->
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                // Beta 9.4 verifies the supplied admin credential as soon as the user chooses the
                // HyperHDR instance instead of waiting until the end of a six-minute calibration.
                if (HyperHdrAdminCredentialStore.hasCredential()) {
                    authenticateAdmin(writer, reader)
                }
                sendAndRead(writer, reader, serverInfoRequest())
                sendAndRead(writer, reader, instanceSwitchRequest(instanceId))
                if (HyperHdrAdminCredentialStore.hasCredential()) {
                    // Prove that this login really has admin config access now, while failure is cheap.
                    sendAndRead(writer, reader, configGetRequest())
                }
            }
        }
        selectedInstanceId = instanceId
    }

    /**
     * Send a static HyperHDR COLOR source. Current HyperHDR intentionally disables Infinite Color
     * Engine processing while COMP_COLOR is the visible component. This is used for raw hardware
     * characterization, not closed-loop validation.
     */
    @Synchronized
    fun setColor(rgb: IntArray, priority: Int = TEST_PRIORITY) {
        val instanceId = selectedInstanceId ?: error("Not connected to a HyperHDR instance")
        retryOnce { requestForInstance(instanceId, colorRequest(rgb, priority)) }
    }

    /** Send a tiny solid IMAGE source so HyperHDR's normal ICE processing remains active. */
    @Synchronized
    fun setProcessedColor(rgb: IntArray, priority: Int = TEST_PRIORITY) {
        val instanceId = selectedInstanceId ?: error("Not connected to a HyperHDR instance")
        retryOnce { requestForInstance(instanceId, processedImageRequest(rgb, priority)) }
    }

    @Synchronized
    fun clear(priority: Int = TEST_PRIORITY) {
        val instanceId = selectedInstanceId ?: return
        retryOnce { requestForInstance(instanceId, clearRequest(priority)) }
    }

    /** Apply ICE anchors to the running instance without persisting them. */
    @Synchronized
    fun applyCalibration(targets: Map<Patch, IntArray>) {
        val instanceId = selectedInstanceId ?: error("Not connected to a HyperHDR instance")
        retryOnce { requestForInstance(instanceId, adjustmentRequest(targets)) }
    }

    /** Read currently persisted ICE anchors through an authenticated admin session. */
    @Synchronized
    fun readCalibrationTargets(): Map<Patch, IntArray> {
        val instanceId = selectedInstanceId ?: error("Not connected to a HyperHDR instance")
        val response = retryOnce {
            adminSession(instanceId) { writer, reader ->
                sendAndRead(writer, reader, configGetRequest())
            }
        }
        return calibrationTargetsFromConfigResponse(response)
    }

    /**
     * Persist the solved ICE anchors while preserving unrelated HyperHDR configuration.
     *
     * Beta 9.4 does the entire getconfig -> patch -> setconfig -> getconfig verification sequence on
     * one authenticated TCP connection. Only after read-back succeeds are the same anchors applied
     * live to guarantee the running instance matches the persisted configuration.
     */
    @Synchronized
    fun commitCalibration(targets: Map<Patch, IntArray>) {
        val instanceId = selectedInstanceId ?: error("Not connected to a HyperHDR instance")

        retryOnce {
            adminSession(instanceId) { writer, reader ->
                val response = sendAndRead(writer, reader, configGetRequest())
                val info = response.optJSONObject("info")
                    ?: response.optJSONObject("config")
                    ?: error("HyperHDR returned no configuration object")
                val config = JSONObject(info.toString())
                patchCalibration(config, targets)

                sendAndRead(writer, reader, configSetRequest(config))
                val readBack = calibrationTargetsFromConfigResponse(
                    sendAndRead(writer, reader, configGetRequest())
                )
                verifyTargets(targets, readBack)
            }
        }

        // Make the verified persisted result visible immediately as well.
        retryOnce { requestForInstance(instanceId, adjustmentRequest(targets)) }
    }

    @Synchronized
    override fun close() {
        selectedInstanceId = null
    }

    companion object {
        const val TEST_PRIORITY = 40
        private const val ORIGIN = "LED Calibrator"
        private const val IMAGE_NAME = "Calibrator"
        private const val CONNECT_TIMEOUT_MS = 1800
        private const val READ_TIMEOUT_MS = 3000
        private const val RETRY_DELAY_MS = 180L

        internal fun serverInfoRequest(): JSONObject = JSONObject()
            .put("command", "serverinfo")
            .put("tan", 1)

        internal fun instanceSwitchRequest(instanceId: Int): JSONObject = JSONObject()
            .put("command", "instance")
            .put("subcommand", "switchTo")
            .put("instance", instanceId)

        internal fun authorizePasswordRequest(password: String): JSONObject {
            require(password.length >= 8) { "HyperHDR admin password must be at least 8 characters" }
            return JSONObject()
                .put("command", "authorize")
                .put("subcommand", "login")
                .put("password", password)
                .put("tan", 1)
        }

        internal fun authorizeTokenRequest(userToken: String): JSONObject {
            require(userToken.length > 36) { "HyperHDR user token is invalid" }
            return JSONObject()
                .put("command", "authorize")
                .put("subcommand", "login")
                .put("token", userToken)
                .put("tan", 1)
        }

        internal fun colorRequest(rgb: IntArray, priority: Int = TEST_PRIORITY): JSONObject {
            require(rgb.size == 3) { "RGB color must contain exactly 3 channels" }
            require(priority in 1..253) { "Priority must be 1..253" }
            return JSONObject()
                .put("command", "color")
                .put("color", jsonRgb(rgb))
                .put("duration", 0)
                .put("priority", priority)
                .put("origin", ORIGIN)
        }

        internal fun processedImageRequest(rgb: IntArray, priority: Int = TEST_PRIORITY): JSONObject {
            require(rgb.size == 3) { "RGB color must contain exactly 3 channels" }
            require(priority in 1..253) { "Priority must be 1..253" }
            val pixel = byteArrayOf(
                rgb[0].coerceIn(0, 255).toByte(),
                rgb[1].coerceIn(0, 255).toByte(),
                rgb[2].coerceIn(0, 255).toByte(),
            )
            val image = ByteArray(2 * 2 * 3)
            for (i in 0 until 4) pixel.copyInto(image, i * 3)
            return JSONObject()
                .put("command", "image")
                .put("imagedata", Base64.getEncoder().encodeToString(image))
                .put("imagewidth", 2)
                .put("imageheight", 2)
                .put("format", "rgb")
                .put("duration", 0)
                .put("priority", priority)
                .put("origin", ORIGIN)
                .put("name", IMAGE_NAME)
        }

        internal fun clearRequest(priority: Int = TEST_PRIORITY): JSONObject = JSONObject()
            .put("command", "clear")
            .put("priority", priority)

        internal fun adjustmentRequest(targets: Map<Patch, IntArray>): JSONObject {
            val adjustment = JSONObject().put("classic_config", false)
            putTarget(adjustment, "red", targets.getValue(Patch.RED))
            putTarget(adjustment, "green", targets.getValue(Patch.GREEN))
            putTarget(adjustment, "blue", targets.getValue(Patch.BLUE))
            putTarget(adjustment, "cyan", targets.getValue(Patch.CYAN))
            putTarget(adjustment, "magenta", targets.getValue(Patch.MAGENTA))
            putTarget(adjustment, "yellow", targets.getValue(Patch.YELLOW))
            putTarget(adjustment, "white", targets.getValue(Patch.WHITE))
            putTarget(adjustment, "black", targets.getValue(Patch.BLACK))
            return JSONObject()
                .put("command", "adjustment")
                .put("adjustment", adjustment)
                .put("tan", 1)
        }

        internal fun configGetRequest(): JSONObject = JSONObject()
            .put("command", "config")
            .put("subcommand", "getconfig")
            .put("tan", 1)

        internal fun configSetRequest(config: JSONObject): JSONObject = JSONObject()
            .put("command", "config")
            .put("subcommand", "setconfig")
            .put("config", config)
            .put("tan", 1)

        internal fun calibrationTargetsFromConfigResponse(response: JSONObject): Map<Patch, IntArray> {
            val config = response.optJSONObject("info")
                ?: response.optJSONObject("config")
                ?: response
            val adjustment = config.optJSONObject("color")
                ?.optJSONArray("channelAdjustment")
                ?.optJSONObject(0)
                ?: error("HyperHDR configuration has no color.channelAdjustment[0]")

            fun read(key: String): IntArray {
                val a = adjustment.optJSONArray(key)
                    ?: error("HyperHDR calibration is missing '$key'")
                require(a.length() >= 3) { "HyperHDR calibration '$key' is not RGB" }
                return intArrayOf(a.optInt(0), a.optInt(1), a.optInt(2))
            }

            return linkedMapOf(
                Patch.WHITE to read("white"),
                Patch.RED to read("red"),
                Patch.GREEN to read("green"),
                Patch.BLUE to read("blue"),
                Patch.CYAN to read("cyan"),
                Patch.MAGENTA to read("magenta"),
                Patch.YELLOW to read("yellow"),
                Patch.BLACK to read("black"),
            )
        }

        internal fun patchCalibration(config: JSONObject, targets: Map<Patch, IntArray>) {
            val color = config.optJSONObject("color") ?: JSONObject().also { config.put("color", it) }
            val adjustments = color.optJSONArray("channelAdjustment")
                ?: JSONArray().also { color.put("channelAdjustment", it) }
            val adjustment = adjustments.optJSONObject(0) ?: JSONObject()
            adjustment.put("classic_config", false)
            putTarget(adjustment, "red", targets.getValue(Patch.RED))
            putTarget(adjustment, "green", targets.getValue(Patch.GREEN))
            putTarget(adjustment, "blue", targets.getValue(Patch.BLUE))
            putTarget(adjustment, "cyan", targets.getValue(Patch.CYAN))
            putTarget(adjustment, "magenta", targets.getValue(Patch.MAGENTA))
            putTarget(adjustment, "yellow", targets.getValue(Patch.YELLOW))
            putTarget(adjustment, "white", targets.getValue(Patch.WHITE))
            putTarget(adjustment, "black", targets.getValue(Patch.BLACK))
            adjustments.put(0, adjustment)
            color.put("channelAdjustment", adjustments)
            config.put("color", color)
        }

        internal fun verifyTargets(expectedTargets: Map<Patch, IntArray>, actualTargets: Map<Patch, IntArray>) {
            for (patch in Patch.entries) {
                val expected = expectedTargets.getValue(patch)
                val actual = actualTargets.getValue(patch)
                require(expected.contentEquals(actual)) {
                    "HyperHDR read-back mismatch for ${patch.label}: expected ${expected.contentToString()}, got ${actual.contentToString()}"
                }
            }
        }

        private fun putTarget(obj: JSONObject, key: String, rgb: IntArray) {
            obj.put(key, jsonRgb(rgb))
        }

        private fun jsonRgb(rgb: IntArray): JSONArray = JSONArray()
            .put(rgb[0].coerceIn(0, 255))
            .put(rgb[1].coerceIn(0, 255))
            .put(rgb[2].coerceIn(0, 255))

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
