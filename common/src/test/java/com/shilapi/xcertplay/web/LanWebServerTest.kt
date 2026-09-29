package com.shilapi.xcertplay.web

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class LanWebServerTest {
    @Test fun foregroundServiceKeepsEndpointWhenDashboardTaskIsRemoved() {
        val service = org.robolectric.Robolectric.buildService(com.shilapi.xcertplay.DiPlaySessionService::class.java)
            .create().startCommand(0, 1)
        try {
            assertTrue(WebSession.running)
            val code = WebSession.code
            service.get().onTaskRemoved(android.content.Intent())
            assertTrue(WebSession.running)
            assertEquals(code, WebSession.code)
            service.get().onStartCommand(android.content.Intent().setAction(
                com.shilapi.xcertplay.DiPlaySessionService.ACTION_STOP), 0, 2)
            assertFalse(WebSession.running)
        } finally { service.destroy() }
    }

    @Test fun retiredMediaSinkCannotOverwriteOrClearNewSession() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            val old = WebSession.beginVideo(320,180)
            val current = WebSession.beginVideo(1280,720)
            WebSession.publish(old, byteArrayOf(1))
            assertNull(WebSession.frame)
            WebSession.publish(current, byteArrayOf(2))
            WebSession.endVideo(old)
            assertArrayEquals(byteArrayOf(2), WebSession.frame!!.jpeg)
            WebSession.endVideo(current)
            WebSession.publish(current, byteArrayOf(3))
            assertNull(WebSession.frame)
        } finally { WebSession.stop() }
    }

    @Test fun actualHttpWebSocketPairingBackpressureAndRestart() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
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
                WebSession.publish(generation, byteArrayOf(7,8,9))
                client.soTimeout = 250
                try { frame(client); fail("A second frame must wait for the browser ACK") }
                catch (_: java.net.SocketTimeoutException) { /* bounded in-flight video */ }
                client.soTimeout = 3000
                text(client, "{\"type\":\"ack\"}")
                assertArrayEquals(byteArrayOf(7,8,9), frame(client))
            }
        } finally { WebSession.stop() }
        assertFalse(WebSession.running)
        WebSession.start(context)
        try { assertTrue("Port must be reusable after stop", WebSession.running) }
        finally { WebSession.stop() }
    }

    @Test fun cachedFrameBusyReplyHeartbeatAndProtocolControlsUseTheAsyncWriter() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            val generation = WebSession.beginVideo(320, 180)
            WebSession.publish(generation, byteArrayOf(42))
            handshake(WebSession.code).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertArrayEquals("Static image must be sent on connect", byteArrayOf(42), frame(client))
                // Control replies must work even while the JPEG is waiting for an ACK.
                text(client, "{\"type\":\"ping\"}")
                assertEquals("status", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                handshake(WebSession.code).use { other ->
                    assertTrue(readHeaders(other).startsWith("HTTP/1.1 101"))
                    assertEquals("busy", org.json.JSONObject(frame(other).toString(Charsets.UTF_8)).getString("type"))
                    assertEquals(-1, other.getInputStream().read())
                }
                assertTrue("Rejecting another browser must keep the viewer", WebSession.hasViewer)
                maskedFrame(client, 9, byteArrayOf(1, 2, 3))
                val pong = fi.iki.elonen.NanoWSD.WebSocketFrame.read(client.getInputStream())
                assertEquals(fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Pong, pong.opCode)
                assertArrayEquals(byteArrayOf(1, 2, 3), pong.binaryPayload)
                maskedFrame(client, 8, byteArrayOf(3, 0xe8.toByte()))
                val close = fi.iki.elonen.NanoWSD.WebSocketFrame.read(client.getInputStream())
                assertEquals(fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Close, close.opCode)
                assertEquals(-1, client.getInputStream().read())
            }
        } finally { WebSession.stop() }
    }

    @Test fun qrHttpFlowRequiresOriginAndLocalApprovalBeforeWebSocket() {
        val server = LanWebServer(org.robolectric.RuntimeEnvironment.getApplication(), "123456")
        server.start(5000, true)
        try {
            fun post(path: String, id: String = "", secret: String = "", origin: String = "http://127.0.0.1:8080"): Pair<Int, String> {
                // Raw socket preserves Origin; HttpURLConnection may strip restricted headers.
                return Socket("127.0.0.1", 8080).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write(("POST /pair/$path HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n" +
                        "Origin: $origin\r\nX-Pair-Id: $id\r\nX-Pair-Secret: $secret\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    val headers = readHeaders(socket)
                    headers.split(" ")[1].toInt() to socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                }
            }
            assertEquals(403, post("request", origin = "http://evil.invalid").first)
            assertEquals(403, post("request", origin = "").first)
            val created = post("request")
            assertEquals(200, created.first)
            val data = org.json.JSONObject(created.second)
            val id = data.getString("id"); val secret = data.getString("secret")
            assertEquals(404, post("status", id, "wrong").first)
            assertFalse(post("status", id, secret).second.contains("token"))
            val svg = URL("http://127.0.0.1:8080/pair/qr?id=$id&secret=$secret").readText()
            assertTrue(svg.contains("<svg"))
            assertEquals(404, post("approve", id, secret).first)
            val request = server.pairing.get(id, secret)!!
            assertTrue(server.pairing.approve(request.payload))
            val approved = org.json.JSONObject(post("status", id, secret).second)
            val token = approved.getString("token")
            handshake("", token = "invalid").use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 401")) }
            handshake("", token = token).use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 101")) }
            post("cancel", id, secret)
            handshake("", token = token).use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 401")) }
        } finally { server.stop() }
    }

    private fun handshake(code: String, origin: String = "http://127.0.0.1:8080", token: String = ""): Socket = Socket("127.0.0.1", 8080).apply {
        soTimeout = 3000
        getOutputStream().write(("GET /stream?code=$code&token=$token HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n" +
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
        return fi.iki.elonen.NanoWSD.WebSocketFrame.read(socket.getInputStream()).binaryPayload
    }
    private fun text(socket: Socket, message: String) {
        maskedFrame(socket, 1, message.toByteArray())
    }
    private fun maskedFrame(socket: Socket, opcode: Int, bytes: ByteArray) {
        check(bytes.size < 126)
        // RFC6455 client frames are masked; zero mask is sufficient for this protocol fixture.
        socket.getOutputStream().apply {
            write(byteArrayOf((0x80 or opcode).toByte(), (0x80 or bytes.size).toByte(), 0,0,0,0)); write(bytes); flush()
        }
    }
}
