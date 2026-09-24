package com.forgery.app.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Regression test for the live bug: kotlinx-serialization converter cannot
 * create a @Body converter for `Map<String, Any?>`
 * ("Unable to create @Body converter ... for method ForgeService.txt2img",
 * seen in queue_state.resultsJson on device).
 * All request bodies must be [JsonObject]; this test drives the full
 * Retrofit path against a loopback socket server.
 */
class ConverterRegressionTest {

    private class Loopback(val responseJson: String) {
        val received = AtomicReference("")
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val server = ServerSocket(0, 1)
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "loopback-http") {
                try {
                    ready.countDown()
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val raw = ByteArrayOutputStream()
                        // Read headers first (until blank line), then body per Content-Length.
                        val head = ByteArrayOutputStream()
                        var prev = intArrayOf(-1, -1, -1, -1)
                        while (true) {
                            val b = input.read()
                            if (b < 0) break
                            head.write(b)
                            prev = intArrayOf(prev[1], prev[2], prev[3], b)
                            if (prev.contentEquals(intArrayOf(13, 10, 13, 10))) break
                        }
                        val headerText = head.toString(Charsets.US_ASCII)
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                            .find(headerText)?.groupValues?.get(1)?.toInt() ?: 0
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                        received.set(body.toString(Charsets.UTF_8))
                        val payload = responseJson.toByteArray(Charsets.UTF_8)
                        val out = socket.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII),
                        )
                        out.write(payload)
                        out.flush()
                    }
                } finally {
                    done.countDown()
                    server.close()
                }
            }
            ready.await(5, TimeUnit.SECONDS)
        }

        fun stop() {
            done.await(10, TimeUnit.SECONDS)
        }
    }

    private fun retrofit(port: Int): Retrofit = Retrofit.Builder()
        .baseUrl("http://127.0.0.1:$port/")
        .addConverterFactory(ForgeJson.asConverterFactory("application/json".toMediaType()))
        .build()

    @Test
    fun `txt2img posts JsonObject body`() = runTest {
        val server = Loopback("""{"images":["AAA"]}""")
        try {
            val svc = retrofit(server.port).create(ForgeService::class.java)
            val result = svc.txt2img(
                buildJsonObject {
                    put("prompt", "x")
                    put("steps", 20)
                    put("cfg_scale", 7.0)
                },
            )
            server.stop()
            assertEquals(listOf("AAA"), result.images())
            assertTrue(server.received.get().contains("\"prompt\":\"x\""))
            assertTrue(server.received.get().contains("\"steps\":20"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `img2img posts JsonObject body`() = runTest {
        val server = Loopback("""{"images":["BBB"]}""")
        try {
            val svc = retrofit(server.port).create(ForgeService::class.java)
            val result = svc.img2img(
                buildJsonObject {
                    put("prompt", "y")
                    put("steps", 10)
                },
            )
            server.stop()
            assertEquals(listOf("BBB"), result.images())
            assertTrue(server.received.get().contains("\"prompt\":\"y\""))
            assertTrue(server.received.get().contains("\"steps\":10"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `service methods accept JsonObject bodies`() {
        val txt2img = ForgeService::class.java.methods.first { it.name == "txt2img" }
        assertEquals(JsonObject::class.java, txt2img.parameterTypes[0])
        val img2img = ForgeService::class.java.methods.first { it.name == "img2img" }
        assertEquals(JsonObject::class.java, img2img.parameterTypes[0])
    }

    @Test
    fun `setOptions tolerates null body like Neo`() = runTest {
        // Live bug: Neo/A1111 set_config returns None -> body `null`; decoding it
        // as JsonObject threw "Unexpected JSON token" on model+modules switch.
        // setOptions returns raw ResponseBody so the converter never touches it.
        val server = Loopback("null")
        try {
            val svc = retrofit(server.port).create(ForgeService::class.java)
            svc.setOptions(
                buildJsonObject { put("sd_model_checkpoint", "m.safetensors") },
            ).close()
            server.stop()
            assertTrue(server.received.get().contains("sd_model_checkpoint"))
        } finally {
            server.stop()
        }
    }
}
