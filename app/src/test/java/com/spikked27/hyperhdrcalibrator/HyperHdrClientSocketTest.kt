package com.spikked27.hyperhdrcalibrator

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HyperHdrClientSocketTest {
    private class FakeHyperHdr(
        private val failFirstColorResponse: Boolean = false,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val connectionCount = AtomicInteger(0)
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val failedColor = AtomicInteger(0)
        @Volatile private var running = true

        private val acceptThread = thread(start = true, isDaemon = true, name = "fake-hyperhdr") {
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                connectionCount.incrementAndGet()
                handle(socket)
            }
        }

        private fun handle(socket: Socket) {
            socket.use { s ->
                s.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    val request = JSONObject(line)
                    val command = request.optString("command")
                    commands += command

                    if (command == "color" && failFirstColorResponse && failedColor.compareAndSet(0, 1)) {
                        // Simulate a socket that dies after HyperHDR receives the command but before
                        // the acknowledgement reaches the app. The client must reconnect and retry.
                        return
                    }

                    val response = JSONObject()
                        .put("success", true)
                        .put("command", command)
                    writer.write(response.toString())
                    writer.write("\n")
                    writer.flush()
                }
            }
        }

        override fun close() {
            running = false
            runCatching { server.close() }
            acceptThread.join(1000)
        }
    }

    @Test
    fun controlOperationsUseFreshConnections() {
        FakeHyperHdr().use { fake ->
            val client = HyperHdrClient(
                HyperHdrServer("Test", "127.0.0.1", fake.port)
            )

            client.connectTo(2)
            client.setColor(intArrayOf(0, 0, 0))
            client.clear()

            assertEquals(3, fake.connectionCount.get())
            assertEquals(3, fake.commands.count { it == "serverinfo" })
            assertEquals(3, fake.commands.count { it == "instance" })
            assertEquals(1, fake.commands.count { it == "color" })
            assertEquals(1, fake.commands.count { it == "clear" })
        }
    }

    @Test
    fun colorCommandRetriesAfterLostAcknowledgement() {
        FakeHyperHdr(failFirstColorResponse = true).use { fake ->
            val client = HyperHdrClient(
                HyperHdrServer("Test", "127.0.0.1", fake.port)
            )

            client.connectTo(0)
            client.setColor(intArrayOf(0, 0, 0))

            assertEquals(3, fake.connectionCount.get()) // connect + failed color + retry
            assertEquals(2, fake.commands.count { it == "color" })
            assertTrue(fake.commands.count { it == "serverinfo" } >= 3)
        }
    }
}
