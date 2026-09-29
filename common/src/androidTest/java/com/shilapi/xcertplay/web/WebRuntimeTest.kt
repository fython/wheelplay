package com.shilapi.xcertplay.web

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

@RunWith(AndroidJUnit4::class)
class WebRuntimeTest {
    @Test fun offscreenThrottlePublishesFinalFrameWithoutAnotherArrival() {
        val frames = LinkedBlockingQueue<ByteArray>()
        val output = OffscreenVideoOutput(320, 180, { true }) { frames.offer(it) }
        try {
            fun paint(color: Int) {
                val canvas = output.surface.lockCanvas(null)
                canvas.drawColor(color)
                output.surface.unlockCanvasAndPost(canvas)
            }
            paint(Color.RED)
            assertNotNull("First frame must arrive", frames.poll(5, TimeUnit.SECONDS))
            // Immediately change the last image, then leave the producer completely idle.
            paint(Color.BLUE)
            val bytes = frames.poll(2, TimeUnit.SECONDS)
            assertNotNull("The final image must not depend on another producer frame", bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes!!, 0, bytes.size)
            val color = bitmap.getPixel(160, 90)
            assertTrue("Final frame must be blue", Color.blue(color) > 200 && Color.red(color) < 50)
            bitmap.recycle()
            assertNull("A static image must not be recompressed repeatedly", frames.poll(300, TimeUnit.MILLISECONDS))
        } finally { output.close() }
    }

    @Test fun offscreenSurfaceProducesUprightJpegWithoutActivity() {
        val ready = CountDownLatch(1)
        var jpeg: ByteArray? = null
        val output = OffscreenVideoOutput(320, 180, { true }) { jpeg = it; ready.countDown() }
        try {
            val canvas = output.surface.lockCanvas(null)
            val paint = Paint()
            paint.color = Color.RED; canvas.drawRect(0f, 0f, 160f, 90f, paint)
            paint.color = Color.GREEN; canvas.drawRect(160f, 0f, 320f, 90f, paint)
            paint.color = Color.BLUE; canvas.drawRect(0f, 90f, 160f, 180f, paint)
            paint.color = Color.WHITE; canvas.drawRect(160f, 90f, 320f, 180f, paint)
            output.surface.unlockCanvasAndPost(canvas)
            assertTrue("A frame must arrive without any visible window", ready.await(5, TimeUnit.SECONDS))
            val bytes = requireNotNull(jpeg)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(320, bitmap.width); assertEquals(180, bitmap.height)
            val topLeft = bitmap.getPixel(40, 30); val bottomLeft = bitmap.getPixel(40, 140)
            assertTrue("Top left must be red", Color.red(topLeft) > 200 && Color.blue(topLeft) < 50)
            assertTrue("Bottom left must be blue", Color.blue(bottomLeft) > 200 && Color.red(bottomLeft) < 50)
            assertTrue(Color.green(bitmap.getPixel(240,30)) > 200)
            bitmap.recycle()
        } finally { output.close() }
    }

    @Test fun actualHttpWebSocketPairingBackpressureAndRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebSession.start(context)
        try {
            assertTrue(WebSession.error, WebSession.running)
            assertTrue(URL("http://127.0.0.1:8080/").readText().contains("设备上的六位配对码"))
            assertTrue(URL("http://127.0.0.1:8080/app.js").readText().contains("pointercancel"))
            handshake("000000").use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 401")) }
            handshake(WebSession.code, "http://evil.invalid").use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 403")) }
            handshake(WebSession.code).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                val generation = WebSession.beginVideo(320, 180)
                WebSession.publish(generation, byteArrayOf(1,2,3))
                assertArrayEquals(byteArrayOf(1,2,3), frame(client))
                WebSession.publish(generation, byteArrayOf(4,5,6))
                client.soTimeout = 250
                try { frame(client); fail("A second frame must wait for the browser ACK") }
                catch (_: java.net.SocketTimeoutException) { /* bounded in-flight video */ }
                client.soTimeout = 3000
                text(client, "{\"type\":\"ack\"}")
                assertArrayEquals(byteArrayOf(4,5,6), frame(client))
            }
        } finally { WebSession.stop() }
        assertFalse(WebSession.running)
        WebSession.start(context)
        try { assertTrue("Port must be reusable after stop", WebSession.running) }
        finally { WebSession.stop() }
    }

    private fun handshake(code: String, origin: String = "http://127.0.0.1:8080"): Socket = Socket("127.0.0.1", 8080).apply {
        soTimeout = 3000
        getOutputStream().write(("GET /stream?code=$code HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n" +
            "Origin: $origin\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
        getOutputStream().flush()
    }
    private fun readHeaders(socket: Socket): String {
        val result = StringBuilder()
        while (!result.endsWith("\r\n\r\n")) {
            val next = socket.getInputStream().read(); check(next >= 0); result.append(next.toChar())
        }
        return result.toString()
    }
    private fun frame(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        check(input.read() >= 0)
        val length = input.read() and 127
        check(length < 126)
        val result = ByteArray(length)
        for (index in result.indices) result[index] = input.read().also { check(it >= 0) }.toByte()
        return result
    }
    private fun text(socket: Socket, message: String) {
        val bytes = message.toByteArray(); check(bytes.size < 126)
        // RFC6455 client frames are masked; zero mask is sufficient for this protocol fixture.
        socket.getOutputStream().apply {
            write(byteArrayOf(0x81.toByte(), (0x80 or bytes.size).toByte(), 0,0,0,0)); write(bytes); flush()
        }
    }
}
