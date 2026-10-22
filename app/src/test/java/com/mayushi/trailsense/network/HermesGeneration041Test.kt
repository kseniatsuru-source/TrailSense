package com.mayushi.trailsense.network


import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HermesGeneration041Test {
    @Test fun actualHttpIdempotencyIncludesInstructionsAttachmentAndExplicitGeneration() {
        val keys = mutableListOf<String>()
        val instructions = mutableListOf<String>()
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit {
            repeat(6) {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val stream = socket.getInputStream()
                    val header = java.io.ByteArrayOutputStream()
                    while(!header.toString("UTF-8").endsWith("\r\n\r\n")) { val byte = stream.read(); check(byte >= 0); header.write(byte) }
                    val lines = header.toString("UTF-8").split("\r\n")
                    val length = lines.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    val bytes = ByteArray(length); var offset = 0
                    while(offset < length) { val read = stream.read(bytes, offset, length - offset); check(read > 0); offset += read }
                    val body = JSONObject(String(bytes, Charsets.UTF_8))
                    keys += lines.first { it.startsWith("Idempotency-Key:", true) }.substringAfter(':').trim()
                    instructions += body.getJSONArray("messages").getJSONObject(0).getString("content")
                    val content = JSONObject().put("summary", "Synthetic response").put("route_facts", JSONObject.NULL).put("activity_facts", JSONObject.NULL)
                    val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content.toString())))).toString().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response); flush()
                    }
                }
            }
        }
        val image = File(RuntimeEnvironment.getApplication().cacheDir, "synthetic-generation.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val client = HermesClient()
            val endpoint = "http://127.0.0.1:${server.localPort}"
            fun send(style: String, generation: String = "generation-1", path: String? = null) {
                assertTrue(client.analyze(endpoint, "synthetic-local-test-token", style, "{\"synthetic\":true}", generation, path, path?.let { "image/png" }).text.contains("Synthetic response"))
            }
            send("Old instructions"); send("Old instructions"); send(AnalysisPrompt.INSTRUCTIONS)
            send(AnalysisPrompt.INSTRUCTIONS, "generation-2")
            send(AnalysisPrompt.INSTRUCTIONS, "generation-2", image.absolutePath)
            image.writeBytes(byteArrayOf(4, 5, 6))
            send(AnalysisPrompt.INSTRUCTIONS, "generation-2", image.absolutePath)
            received.get(10, TimeUnit.SECONDS)
            assertEquals(keys[0], keys[1])
            assertEquals(5, keys.distinct().size)
            assertEquals(AnalysisPrompt.INSTRUCTIONS, instructions[2])
        } finally { server.close(); executor.shutdownNow(); image.delete() }
    }
}
