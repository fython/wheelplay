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
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.HttpsURLConnection

@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29])
class LanWebServerTest {
    @org.junit.Before fun provisionAuthentication() {
        val identity = com.shilapi.xcertplay.SyntheticMfiIdentity.create()
        com.shilapi.xcertplay.MfiAssetStore(org.robolectric.RuntimeEnvironment.getApplication().noBackupFilesDir)
            .installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
    }

    @Test fun changingHttpPortPreservesMediaPairingAndUsesSavedPortOnRestart() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val oldCode = WebSession.code
            val token = org.json.JSONObject(pairPost("code", mapOf("X-Pair-Code" to oldCode)).second).getString("token")
            val owner = WebSession.beginVideo(640, 480)
            WebSession.publish(owner, byteArrayOf(1, 2, 3))
            val oldFrame = WebSession.frame
            val port = java.net.ServerSocket(0).use { it.localPort }
            assertNull(WebSession.setHttpPort(context, port))
            assertEquals(port, WebSession.httpPort)
            assertEquals(port, WebListenSettings.httpPort(context))
            assertFalse(TeslaHttpCompatibility.config(context).enabled)
            assertEquals(oldCode, WebSession.code)
            assertSame(oldFrame, WebSession.frame)
            assertTrue(URL("http://127.0.0.1:$port/").readText().contains("设备上的六位配对码"))
            for (host in listOf("100.96.0.1:$port", "car.example.com:$port")) {
                handshake("", "http://$host", token, host, port).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 101"))
                }
                handshake("", "http://${host.substringBefore(':')}:8080", token, host, port).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 403"))
                }
            }
            WebSession.stop(); WebSession.start(context)
            assertEquals(port, WebSession.httpPort)
            assertTrue(URL("http://127.0.0.1:$port/app.js").readText().contains("pointercancel"))
            handshake("", "http://127.0.0.1:$port", token, "127.0.0.1:$port", port).use {
                assertTrue("A full service stop still revokes tokens", readHeaders(it).startsWith("HTTP/1.1 401"))
            }
        } finally { WebSession.stop() }
    }

    @Test fun occupiedPortKeepsTheOriginalListenerConfigAndPairingUsable() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val oldConfig = TeslaHttpCompatibility.config(context)
            val token = org.json.JSONObject(pairPost("code", mapOf("X-Pair-Code" to WebSession.code)).second).getString("token")
            java.net.ServerSocket(0).use { occupied ->
                val failure = WebSession.setHttpPort(context, occupied.localPort)
                assertNotNull(failure)
                assertTrue(failure!!.contains("原端口 8080 继续运行"))
            }
            assertTrue(WebSession.running)
            assertNull(WebSession.error)
            assertEquals(8080, WebSession.httpPort)
            assertEquals(oldConfig, TeslaHttpCompatibility.config(context))
            assertTrue(URL("http://127.0.0.1:8080/").readText().contains("设备上的六位配对码"))
            handshake("", token = token).use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 101")) }
        } finally { WebSession.stop() }
    }

    @Test fun httpVirtualIpAndDomainKeepPairingAndSameOriginChecks() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            for (host in listOf("100.96.0.1:8080", "car.example.com:8080")) {
                handshake("000000", "http://$host", host = host).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 401"))
                }
                handshake(WebSession.code, "http://evil.invalid", host = host).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 403"))
                }
                handshake(WebSession.code, "http://$host", host = host).use {
                    assertTrue(readHeaders(it).startsWith("HTTP/1.1 101"))
                    assertEquals("audio-route", org.json.JSONObject(frame(it).toString(Charsets.UTF_8)).getString("type"))
                }
            }
        } finally { WebSession.stop() }
    }

    @Test fun trustedHttpsViewerCanSupplyNegotiatedMicrophonePcm() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (WebSession.tls == null && WebSession.tlsError == null && System.nanoTime() < deadline) Thread.sleep(20)
            val endpoint = WebSession.tls ?: error("HTTPS failed: ${WebSession.tlsError}")
            val root = CertificateFactory.getInstance("X.509")
                .generateCertificate(endpoint.certificate.inputStream())
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("wheelplay", root) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
            (ssl.socketFactory.createSocket("127.0.0.1", 8443) as SSLSocket).use { client ->
                client.soTimeout = 3000
                client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                client.startHandshake()
                client.outputStream.write(("GET /stream?code=${WebSession.code} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:8443\r\nOrigin: https://127.0.0.1:8443\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                WebSession.audio.configure(false, true)
                assertTrue(org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getBoolean("microphone"))
                text(client, """{"type":"audio-ready","playback":false,"microphone":true}""")
                val route = WebSession.audio.createRoute()
                try {
                    val config = com.shilapi.xcertplay.airplay.MicrophoneConfig("telephony", 16_000, 1, 97, 20,
                        java.net.InetAddress.getLoopbackAddress(), 12345, ByteArray(32))
                    var input: com.shilapi.xcertplay.media.PcmInput? = null
                    val ready = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (input == null && System.nanoTime() < ready) {
                        input = route.microphone(1, config)
                        if (input == null) Thread.sleep(10)
                    }
                    assertNotNull(input)
                    val mic = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                    assertEquals("mic-config", mic.getString("type"))
                    val wire = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(BrowserAudioBridge.MIC_MAGIC).putInt(mic.getInt("id")).putInt(0)
                        .put(byteArrayOf(1,2,3,4)).array()
                    maskedFrame(client, 2, wire)
                    val pcm = ByteArray(8)
                    var count = 0
                    val receivedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (count == 0 && System.nanoTime() < receivedBy) count = input!!.read(pcm)
                    assertEquals(4, count)
                    assertArrayEquals(byteArrayOf(1,2,3,4), pcm.copyOf(4))
                } finally { route.close() }
            }
        } finally { WebSession.stop() }
    }
    @Test fun pairedViewerReceivesDecodedPcmOnTheExistingSocket() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake(WebSession.code).use { client ->
                client.soTimeout = 3000
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                WebSession.audio.configure(true, false)
                assertTrue(org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getBoolean("playback"))
                text(client, """{"type":"audio-ready","playback":true,"microphone":false}""")
                val route = WebSession.audio.createRoute()
                try {
                    val format = com.shilapi.xcertplay.airplay.AudioFormat(
                        com.shilapi.xcertplay.airplay.AudioCodecKind.LPCM, 48_000, 1, 96, "media")
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (!route.output(3, format, byteArrayOf(1,2,3,4), 0, 4) && System.nanoTime() < deadline) Thread.sleep(10)
                    val received = java.nio.ByteBuffer.wrap(frame(client)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    assertEquals(BrowserAudioBridge.AUDIO_MAGIC, received.int)
                    assertEquals(3, received.int)
                    assertEquals(48_000, received.int)
                    assertEquals(1, received.short.toInt())
                    received.short
                    val sequence = received.int.toLong() and 0xffff_ffffL
                    val samples = ByteArray(4); received.get(samples)
                    assertArrayEquals(byteArrayOf(1,2,3,4), samples)
                    text(client, """{"type":"audio-ack","sequence":$sequence}""")
                } finally { route.close() }
            }
        } finally { WebSession.stop() }
    }
    @Test fun plainHttpRejectsMicrophoneCaptureEvenForAPairedViewer() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake(WebSession.code).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                client.soTimeout = 3000
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                WebSession.audio.configure(false, true)
                assertFalse(org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getBoolean("microphone"))
                text(client, """{"type":"audio-ready","playback":false,"microphone":true}""")
                assertFalse(WebSession.audio.microphoneRequested)
                val route = WebSession.audio.createRoute()
                try {
                    val config = com.shilapi.xcertplay.airplay.MicrophoneConfig("telephony", 16_000, 1, 97, 20,
                        java.net.InetAddress.getLoopbackAddress(), 12345, ByteArray(32))
                    assertNull(route.microphone(1, config))
                } finally { route.close() }
            }
        } finally { WebSession.stop() }
    }
    @Test fun tlsEndpointUsesThePerInstallationCertificateAndRejectsCrossOriginWebSockets() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (WebSession.tls == null && WebSession.tlsError == null && System.nanoTime() < deadline) Thread.sleep(20)
            val endpoint = WebSession.tls ?: error("HTTPS failed: ${WebSession.tlsError}")
            val root = CertificateFactory.getInstance("X.509")
                .generateCertificate(endpoint.certificate.inputStream())
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("wheelplay", root) }
            val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
            val connection = java.net.URL("https://127.0.0.1:8443/").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = ssl.socketFactory
            assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("浏览器麦克风"))
            fun handshake(origin: String): String = (ssl.socketFactory.createSocket("127.0.0.1", 8443) as SSLSocket).use { socket ->
                socket.soTimeout = 3000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                socket.outputStream.write(("GET /stream?code=${WebSession.code} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:8443\r\nOrigin: $origin\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
                readHeaders(socket)
            }
            assertTrue(handshake("http://127.0.0.1:8443").startsWith("HTTP/1.1 403"))
            assertTrue(handshake("https://127.0.0.1:8443").startsWith("HTTP/1.1 101"))
        } finally { WebSession.stop() }
    }
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
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
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
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
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

    @Test fun rememberedBrowserResumesAfterServiceRestartAndAppRemovalClosesItsSocket() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            assertEquals(401, pairPost("remember").first)
            assertEquals(403, pairPost("code", mapOf("X-Pair-Code" to WebSession.code), "http://evil.invalid").first)
            assertEquals(401, pairPost("code", mapOf("X-Pair-Code" to "000000")).first)
            val token = org.json.JSONObject(pairPost("code", mapOf("X-Pair-Code" to WebSession.code)).second).getString("token")
            val credential = org.json.JSONObject(pairPost("remember", mapOf("X-Pair-Token" to token)).second)
            val id = credential.getString("id")
            val secret = credential.getString("secret")
            val repeated = org.json.JSONObject(pairPost("remember", mapOf("X-Pair-Token" to token)).second)
            assertEquals(id, repeated.getString("id"))
            assertEquals(secret, repeated.getString("secret"))
            assertEquals(1, WebSession.rememberedBrowsers(context).list().size)
            val headers = mapOf("X-Device-Id" to id, "X-Device-Secret" to secret)
            assertEquals(401, pairPost("resume", headers + ("X-Device-Secret" to "x".repeat(32))).first)
            assertEquals(403, pairPost("resume", headers, "http://evil.invalid").first)
            WebSession.stop(); WebSession.start(context)
            handshake("", token = token).use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 401")) }
            val resumed = org.json.JSONObject(pairPost("resume", headers).second).getString("token")
            assertFalse(WebSession.hasViewer) // Authentication does not start display/audio/control.
            handshake("", token = resumed).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                client.soTimeout = 3000
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                WebSession.forgetBrowser(context, id)
                try { assertEquals(-1, client.getInputStream().read()) }
                catch (_: java.net.SocketException) { /* Revocation can force-close with a TCP reset. */ }
            }
            assertEquals(401, pairPost("resume", headers).first)
            handshake("", token = resumed).use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 401")) }
            WebSession.stop(); WebSession.start(context)
            assertEquals(401, pairPost("resume", headers).first)
        } finally { WebSession.stop() }
    }

    @Test fun removingAllBrowsersRevokesSessionsAndPersistsAnEmptyRegistry() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            val credentials = (1..2).map {
                val token = org.json.JSONObject(pairPost("code", mapOf("X-Pair-Code" to WebSession.code)).second).getString("token")
                val device = org.json.JSONObject(pairPost("remember", mapOf("X-Pair-Token" to token)).second)
                Triple(token, device.getString("id"), device.getString("secret"))
            }
            handshake("", token = credentials.first().first).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                frame(client)
                WebSession.forgetAllBrowsers(context)
                client.soTimeout = 3000
                try { assertEquals(-1, client.getInputStream().read()) }
                catch (_: java.net.SocketException) { /* Forced TCP close is also valid. */ }
            }
            assertTrue(RememberedBrowsers(context).list().isEmpty())
            credentials.forEach { (token, id, secret) ->
                assertEquals(401, pairPost("resume", mapOf("X-Device-Id" to id, "X-Device-Secret" to secret)).first)
                handshake("", token = token).use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 401")) }
            }
        } finally { WebSession.stop() }
    }

    @Test fun codeAuthenticationUsesTheSameThrottleAsWebSocketPairing() {
        val server = LanWebServer(org.robolectric.RuntimeEnvironment.getApplication(), "123456")
        server.start(5000, true)
        try {
            repeat(5) { assertEquals(401, pairPost("code", mapOf("X-Pair-Code" to "000000")).first) }
            assertEquals(401, pairPost("code", mapOf("X-Pair-Code" to "123456")).first)
            handshake("123456").use { assertTrue(readHeaders(it).startsWith("HTTP/1.1 401")) }
        } finally { server.stop() }
    }

    private fun pairPost(path: String, extra: Map<String, String> = emptyMap(), origin: String = "http://127.0.0.1:8080"): Pair<Int, String> =
        Socket("127.0.0.1", 8080).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write(("POST /pair/$path HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nOrigin: $origin\r\n" +
                extra.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
                "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            val headers = readHeaders(socket)
            headers.split(" ")[1].toInt() to socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun handshake(code: String, origin: String = "http://127.0.0.1:8080", token: String = "", host: String = "127.0.0.1:8080", port: Int = 8080): Socket = Socket("127.0.0.1", port).apply {
        soTimeout = 3000
        getOutputStream().write(("GET /stream?code=$code&token=$token HTTP/1.1\r\nHost: $host\r\n" +
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
