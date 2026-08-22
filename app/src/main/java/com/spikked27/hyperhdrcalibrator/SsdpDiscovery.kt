package com.spikked27.hyperhdrcalibrator

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class SsdpDiscovery(private val context: Context) {
    fun discover(timeoutMs: Int = 2500): List<HyperHdrServer> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("hyperhdr-calibrator-ssdp")?.apply { setReferenceCounted(false); acquire() }
        return try { discoverInternal(timeoutMs) } finally { if (lock?.isHeld == true) lock.release() }
    }

    internal fun discoverInternal(timeoutMs: Int): List<HyperHdrServer> {
        val found = ConcurrentHashMap<String, HyperHdrServer>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val request = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: ssdp:all\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val buf = ByteArray(8192)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    parseResponse(String(packet.data, 0, packet.length), packet.address.hostAddress ?: "")?.let {
                        found["${it.host}:${it.jsonPort}"] = it
                    }
                } catch (_: java.net.SocketTimeoutException) { }
            }
        }
        return found.values.sortedBy { it.name }
    }

    companion object {
        internal fun parseResponse(text: String, fallbackHost: String): HyperHdrServer? {
            val headers = text.lineSequence().mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }.toMap()
            val name = headers["hyperhdr-name"] ?: return null
            val port = headers["hyperhdr-jss-port"]?.toIntOrNull() ?: return null
            val location = headers["location"]
            val host = try { location?.let { URI(it).host } ?: fallbackHost } catch (_: Exception) { fallbackHost }
            if (host.isBlank()) return null
            return HyperHdrServer(name=name, host=host, jsonPort=port, location=location, uuid=headers["usn"])
        }
    }
}
