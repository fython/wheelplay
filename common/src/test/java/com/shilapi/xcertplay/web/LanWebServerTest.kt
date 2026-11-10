package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.projection.*
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
@org.robolectric.annotation.Config(qualifiers = "zh-rCN", sdk = [29])
class LanWebServerTest {
    private fun startHttpsSession(context: android.content.Context) {
        // These tests exercise real TLS HTTP/WebSocket traffic, not async RSA startup latency.
        // The synchronous settings path returns only after the secure listener is bound.
        WebListenSettings.saveHttpsEnabled(context, false)
        WebSession.start(context)
        val failure = WebSession.setHttpsEnabled(context, true)
        if (failure != null) {
            WebSession.stop()
            fail("HTTPS setup failed: $failure")
        }
    }

    @Test fun pairedNowPlayingStreamsStateCommandsAndRevocableArtworkOverRealSockets() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3)
        val state = com.shilapi.xcertplay.media.NowPlayingState(connected = true, connectionGeneration = 7,
            title = "Phone song", status = com.shilapi.xcertplay.media.PhonePlaybackStatus.PLAYING,
            durationMs = 100000, positionMs = 1000, setElapsedTimeAvailable = true,
            artwork = com.shilapi.xcertplay.media.MediaArtwork(bytes))
        val subscribed = CountDownLatch(1)
        val commands = java.util.concurrent.CopyOnWriteArrayList<com.shilapi.xcertplay.media.MediaCommand>()
        val seeks = java.util.concurrent.CopyOnWriteArrayList<Triple<Long, Long?, Long?>>()
        val source = object : com.shilapi.xcertplay.media.CarPlayMediaSource {
            override fun mediaSnapshot() = state
            override fun subscribeMediaState(listener: (com.shilapi.xcertplay.media.NowPlayingState) -> Unit): AutoCloseable {
                listener(state); subscribed.countDown(); return AutoCloseable {}
            }
            override fun sendMediaCommand(command: com.shilapi.xcertplay.media.MediaCommand, connectionGeneration: Long?): java.util.concurrent.CompletableFuture<com.shilapi.xcertplay.media.MediaCommandResult> {
                commands.add(command)
                return java.util.concurrent.CompletableFuture.completedFuture(com.shilapi.xcertplay.media.MediaCommandResult.SENT)
            }
            override fun seekTo(positionMs: Long, connectionGeneration: Long?, trackRevision: Long?): java.util.concurrent.CompletableFuture<com.shilapi.xcertplay.media.MediaCommandResult> {
                seeks.add(Triple(positionMs, connectionGeneration, trackRevision))
                return java.util.concurrent.CompletableFuture.completedFuture(com.shilapi.xcertplay.media.MediaCommandResult.SENT)
            }
        }
        val server = LanWebServer(context, "123456", port = 0, mediaBridgeFactory = { send ->
            BrowserMediaBridge(send, currentSource = { source }, observeSource = { it(); AutoCloseable {} },
                dispatch = { src, gen, cmd -> src.sendMediaCommand(cmd, gen) },
                dispatchSeek = { src, gen, track, pos -> src.seekTo(pos, gen, track) })
        })
        server.start()
        val port = server.listeningPort
        val host = "127.0.0.1:$port"
        val token = server.pairing.issueToken()!!
        fun pumpUntil(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < until) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                Thread.sleep(5)
            }
            assertTrue(condition())
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        try {
            val missing = URL("http://$host/now-playing/artwork?id=unknown").openConnection() as java.net.HttpURLConnection
            assertEquals(404, missing.responseCode); missing.disconnect()
            assertTrue(URL("http://$host/browser-media-session.js").readText().contains("setPositionState"))
            handshake("", "http://$host", token, host, port, nowPlaying = true).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                pumpUntil { subscribed.count == 0L }
                var snapshot: org.json.JSONObject
                do { snapshot = org.json.JSONObject(frame(client).toString(Charsets.UTF_8)) }
                    while (snapshot.optString("type") != "now-playing" || !snapshot.optBoolean("available"))
                assertEquals("Phone song", snapshot.getString("title"))
                val coverUrl = URL("http://$host${snapshot.getString("artworkUrl")}")
                val cover = coverUrl.openConnection() as java.net.HttpURLConnection
                assertEquals("image/jpeg", cover.contentType)
                assertEquals("private, no-store", cover.getHeaderField("Cache-Control"))
                assertArrayEquals(bytes, cover.inputStream.use { it.readBytes() }); cover.disconnect()
                text(client, org.json.JSONObject().put("type", "media-command").put("requestId", 1)
                    .put("sessionId", snapshot.getString("sessionId")).put("connectionGeneration", 7)
                    .put("action", "pause").toString())
                pumpUntil { commands.isNotEmpty() }
                var result: org.json.JSONObject
                do { result = org.json.JSONObject(frame(client).toString(Charsets.UTF_8)) }
                    while (result.optString("type") != "media-command-result")
                assertEquals("sent", result.getString("result"))
                assertEquals(listOf(com.shilapi.xcertplay.media.MediaCommand.PAUSE), commands)
                assertTrue(snapshot.getBoolean("canSeek"))
                text(client, org.json.JSONObject().put("type", "media-command").put("requestId", 2)
                    .put("sessionId", snapshot.getString("sessionId")).put("connectionGeneration", 7)
                    .put("trackRevision", snapshot.getLong("trackRevision"))
                    .put("action", "seekto").put("positionMs", 42000).toString())
                pumpUntil { seeks.isNotEmpty() }
                do { result = org.json.JSONObject(frame(client).toString(Charsets.UTF_8)) }
                    while (result.optString("type") != "media-command-result" || result.optLong("requestId") != 2L)
                assertEquals("sent", result.getString("result"))
                assertEquals(listOf(Triple(42000L, 7L, 0L)), seeks)
                server.pairing.revoke(token)
                val revoked = coverUrl.openConnection() as java.net.HttpURLConnection
                assertEquals(404, revoked.responseCode); revoked.disconnect()
                server.disconnectUnauthorized()
            }
        } finally {
            server.stop()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    @org.junit.Before fun provisionAuthentication() {
        val identity = moe.feng.wheelplay.SyntheticMfiIdentity.create()
        moe.feng.wheelplay.MfiAssetStore(org.robolectric.RuntimeEnvironment.getApplication().noBackupFilesDir)
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

    @Test fun versionedJpegStatusAndTouchShareTheSameMediaGeneration() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake(WebSession.code, media = 2).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                val owner = WebSession.beginVideo(320, 180)
                WebSession.videoActive = true
                WebSession.publish(owner, byteArrayOf(1, 2, 3))
                var first = frame(client)
                while (first.firstOrNull() == '{'.code.toByte()) first = frame(client)
                assertArrayEquals(VideoPacket.encode(WebSession.frame!!), first)
                text(client, """{"type":"ping"}""")
                var status = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                while (status.optString("type") != "status")
                    status = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                val before = WebSession.mediaSnapshot()
                assertEquals(2, status.getInt("mediaProtocol"))
                assertEquals(before.sessionId, status.getLong("sessionId"))
                assertEquals(owner, status.getLong("videoEpoch"))
                assertEquals("ready", status.getString("sourceState"))
                assertFalse("No phone controller means input is not ready", status.getBoolean("inputReady"))
                assertTrue(WebSession.mediaMatches(before.sessionId, owner))
                val next = WebSession.beginVideo(320, 180)
                assertTrue(next > owner)
                WebSession.videoActive = true
                assertEquals("ready", WebSession.mediaSnapshot().sourceState)
                assertFalse(WebSession.mediaMatches(before.sessionId, owner))
                assertTrue(WebSession.mediaMatches(before.sessionId, next))
                val oldTouch = """{"type":"touch","sessionId":${before.sessionId},"videoEpoch":$owner,"contacts":[]}"""
                text(client, oldTouch)
                // A real down from an old generation is rejected even if dimensions are unchanged.
                assertFalse(WebSession.mediaTouch(client, before.sessionId, owner,
                    listOf(com.shilapi.xcertplay.airplay.AirPlayContact(0, .5, .5, true)), 100))
                WebSession.endVideo(owner)
                assertEquals(next, WebSession.mediaSnapshot().videoEpoch)
                WebSession.endVideo(next)
                val ended = WebSession.mediaSnapshot()
                assertEquals("stopped", ended.sourceState)
                assertFalse(ended.inputReady)
                val fresh = WebSession.beginVideo(320, 180)
                assertTrue(fresh > ended.videoEpoch)
                assertNotEquals(before.sessionId, WebSession.mediaSnapshot().sessionId)
                WebSession.publish(owner, byteArrayOf(9))
                assertNull("An old producer cannot republish into the new session", WebSession.frame)
            }
        } finally { WebSession.stop() }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun staticFramebufferReencodesForJpegFallbackAndQualityChanges() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        WebListenSettings.saveHttpsEnabled(context, false)
        moe.feng.wheelplay.AirPlayPersistence.saveImageFormat(context, ImageFormat.WEBP)
        moe.feng.wheelplay.AirPlayPersistence.saveImageQuality(context, 75)
        WebSession.start(context)
        fun waitFor(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(message, condition())
        }
        try {
            BrowserProjectionVideo(context, 16, 16, VideoIngressKind.FRAMEBUFFER).use { output ->
                handshake(WebSession.code, media = 2, images = 3).use { client ->
                    assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                    fun receiveAndAck(expected: WebSession.Frame) {
                        var data = frame(client)
                        while (data.firstOrNull() == '{'.code.toByte()) data = frame(client)
                        assertArrayEquals(VideoPacket.encode(expected, 3), data)
                        val bitmap = BitmapFactory.decodeByteArray(data, 40, data.size - 40)
                        assertNotNull(bitmap); assertEquals(16, bitmap.width); bitmap.recycle()
                        text(client, """{"type":"ack","sessionId":${expected.sessionId},"videoEpoch":${expected.videoEpoch},"sequence":${expected.sequence}}""")
                    }
                    text(client, """{"type":"image-format","format":"webp"}""")
                    waitFor("Browser negotiated WebP") { WebSession.imageFormat == ImageFormat.WEBP }
                    output.framebuffer(ProjectionFramebuffer.Snapshot(16, 16, 1, IntArray(256) { -1 }))
                    waitFor("Initial WebP encoded") { WebSession.frame?.format == ImageFormat.WEBP }
                    val first = WebSession.frame!!
                    receiveAndAck(first)

                    // No further source snapshots: VNC may leave an incremental request pending indefinitely.
                    text(client, """{"type":"image-format","format":"jpeg"}""")
                    waitFor("Static desktop re-encoded as JPEG") { WebSession.frame?.format == ImageFormat.JPEG }
                    val fallback = WebSession.frame!!
                    assertTrue(fallback.sequence > first.sequence)
                    assertEquals(first.videoEpoch, fallback.videoEpoch)
                    receiveAndAck(fallback)

                    moe.feng.wheelplay.AirPlayPersistence.saveImageQuality(context, 60)
                    waitFor("Static desktop picks up changed quality") { WebSession.frame?.quality == 60 }
                    val quality = WebSession.frame!!
                    assertEquals(ImageFormat.JPEG, quality.format)
                    assertTrue(quality.sequence > fallback.sequence)
                    receiveAndAck(quality)
                    Thread.sleep(300) // Several image scheduler ticks with unchanged options.
                    assertEquals("Static frames must not be re-encoded continuously", quality.sequence, WebSession.frame!!.sequence)
                }
            }
        } finally { WebSession.stop() }
    }

    @Test fun v3NegotiatesWebpPipelinesFramesAndRequiresExactAcksBeforeFallingBackToJpeg() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake(WebSession.code, media = 2, images = 3).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                fun nextImage(): ByteArray {
                    var data = frame(client)
                    while (data.firstOrNull() == '{'.code.toByte()) data = frame(client)
                    return data
                }
                val owner = WebSession.beginVideo(320, 180)
                WebSession.publish(owner, byteArrayOf(1))
                val first = WebSession.frame!!
                assertArrayEquals(VideoPacket.encode(first, 3), nextImage())
                assertEquals(ImageFormat.JPEG, WebSession.imageFormat)
                text(client, """{"type":"image-format","format":"webp"}""")
                text(client, """{"type":"ping"}""")
                var status = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                while (status.optString("type") != "status") status = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                assertEquals(3, status.getInt("mediaProtocol")); assertEquals(ImageFormat.WEBP, WebSession.imageFormat)
                WebSession.publish(owner, byteArrayOf(2), ImageFormat.WEBP)
                val second = WebSession.frame!!
                assertArrayEquals(VideoPacket.encode(second, 3), nextImage()) // Before first ACK.
                WebSession.publish(owner, byteArrayOf(3), ImageFormat.WEBP)
                client.soTimeout = 100
                try { frame(client); fail("Two frames already occupy the window") }
                catch (_: java.net.SocketTimeoutException) { }
                text(client, """{"type":"ack","sessionId":${first.sessionId},"videoEpoch":${first.videoEpoch + 1},"sequence":${first.sequence}}""")
                try { frame(client); fail("A stale ACK cannot free a slot") }
                catch (_: java.net.SocketTimeoutException) { }
                client.soTimeout = 3000
                text(client, """{"type":"ack","sessionId":${first.sessionId},"videoEpoch":${first.videoEpoch},"sequence":${first.sequence}}""")
                assertArrayEquals(VideoPacket.encode(WebSession.frame!!, 3), nextImage())
                text(client, """{"type":"image-format","format":"jpeg"}""")
                text(client, """{"type":"image-format","format":"webp"}""")
                text(client, """{"type":"ping"}""")
                do { status = org.json.JSONObject(frame(client).toString(Charsets.UTF_8)) }
                while (status.optString("type") != "status")
                assertEquals("A failed browser stays on JPEG for this connection", ImageFormat.JPEG, WebSession.imageFormat)
            }
        } finally { WebSession.stop() }
    }

    @Test fun aLegacyViewerCannotReceiveCachedWebpFromThePreviousViewer() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            val owner = WebSession.beginVideo(320, 180)
            WebSession.publish(owner, byteArrayOf(1), ImageFormat.WEBP)
            handshake(WebSession.code, media = 2).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                assertEquals(ImageFormat.JPEG, WebSession.imageFormat)
                client.soTimeout = 100
                try { frame(client); fail("A cached WebP must wait for JPEG re-encoding") }
                catch (_: java.net.SocketTimeoutException) { }
                client.soTimeout = 3000
                WebSession.publish(owner, byteArrayOf(2))
                assertArrayEquals(VideoPacket.encode(WebSession.frame!!), frame(client))
            }
        } finally { WebSession.stop() }
    }

    @Test fun userJpegSettingOverridesWebpCapabilityAndReportsTheEncodedQuality() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        moe.feng.wheelplay.AirPlayPersistence.saveImageFormat(context, ImageFormat.JPEG)
        WebSession.start(context)
        try {
            val owner = WebSession.beginVideo(320, 180)
            WebSession.publish(owner, byteArrayOf(1), ImageFormat.WEBP)
            handshake(WebSession.code, media = 2, images = 3).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                text(client, """{"type":"image-format","format":"webp"}""")
                text(client, """{"type":"ping"}""")
                var status = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                while (status.optString("type") != "status") status = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                client.soTimeout = 100
                try { frame(client); fail("A browser cannot override the user's JPEG setting") }
                catch (_: java.net.SocketTimeoutException) { }
                client.soTimeout = 3000
                WebSession.publish(owner, byteArrayOf(2), ImageFormat.JPEG, 37)
                assertArrayEquals(VideoPacket.encode(WebSession.frame!!, 3), frame(client))
                text(client, """{"type":"ping"}""")
                do { status = org.json.JSONObject(frame(client).toString(Charsets.UTF_8)) }
                while (status.optString("type") != "status")
                val image = status.getJSONObject("performance").getJSONObject("image")
                assertEquals("image/jpeg", image.getString("format")); assertEquals(37, image.getInt("quality"))
            }
        } finally { WebSession.stop() }
    }

    @Test fun retryAvailabilityAndExplicitStartsHonorTheSameCooldownAndConfigurationReset() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake(WebSession.code, media = 2).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                val source = WebVideoSource(StreamTechnology.WEBRTC)
                val config = byteArrayOf(1, 0x64, 0, 0x20, -1, -31, 0, 4, 0x67, 0x64, 0, 0x20, 1, 0, 2, 0x68, 1)
                source.configure(110, com.shilapi.xcertplay.airplay.VideoCodec.H264, config)
                WebSession.attachVideoSource(WebSession.beginVideo(320, 180), source)
                WebSession.videoActive = true
                fun status(): org.json.JSONObject {
                    text(client, """{"type":"ping"}""")
                    var value = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                    while (value.optString("type") != "status")
                        value = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                    return value
                }
                assertTrue(status().getBoolean("rtcAvailable"))
                val server = WebSession.javaClass.getDeclaredField("server").apply { isAccessible = true }.get(WebSession)
                val viewer = server.javaClass.getDeclaredField("viewer").apply { isAccessible = true }.get(server)
                val lock = requireNotNull(viewer.javaClass.getDeclaredField("rtcLock").apply { isAccessible = true }.get(viewer))
                val retry = viewer.javaClass.getDeclaredField("rtcRetry").apply { isAccessible = true }.get(viewer) as RtcRetryPolicy
                synchronized(lock) { assertTrue(retry.start()); retry.finish() }
                val gap = status()
                assertFalse(gap.getBoolean("rtcAvailable"))
                assertTrue(gap.getLong("rtcRetryAfterMs") in 1..5000L)
                text(client, """{"type":"rtc-start"}""")
                assertFalse(status().getBoolean("rtcAvailable"))
                assertEquals(0L, viewer.javaClass.getDeclaredField("rtcId").apply { isAccessible = true }.getLong(viewer))
                text(client, """{"type":"rtc-fallback","id":0,"retryable":false}""")
                assertTrue(status().getBoolean("rtcRetryBlocked"))
                source.configure(110, com.shilapi.xcertplay.airplay.VideoCodec.H264,
                    config.copyOf().apply { this[11] = 0x21 })
                val changed = status()
                assertTrue(changed.getBoolean("rtcAvailable")); assertFalse(changed.getBoolean("rtcRetryBlocked"))
                assertEquals(0L, changed.getLong("rtcRetryAfterMs"))
                text(client, """{"type":"rtc-fallback","id":0,"retryable":false}""")
                assertTrue("A retired peer rejection cannot block a fresh configuration", status().getBoolean("rtcAvailable"))
            }
        } finally { WebSession.stop() }
    }

    @Test fun rtcStartDoesNotHoldTheClientLockWhileWaitingForTheSessionSnapshot() {
        WebSession.start(org.robolectric.RuntimeEnvironment.getApplication())
        try {
            handshake(WebSession.code, media = 2).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                val source = WebVideoSource(StreamTechnology.WEBRTC)
                source.configure(110, com.shilapi.xcertplay.airplay.VideoCodec.H264,
                    byteArrayOf(1, 0x64, 0, 0x20, -1, -31, 0, 4, 0x67, 0x64, 0, 0x20, 1, 0, 2, 0x68, 1))
                val owner = WebSession.beginVideo(320, 180)
                WebSession.attachVideoSource(owner, source)
                WebSession.videoActive = true
                val server = WebSession.javaClass.getDeclaredField("server").apply { isAccessible = true }.get(WebSession)
                val viewer = server.javaClass.getDeclaredField("viewer").apply { isAccessible = true }.get(server)
                val rtcLock = requireNotNull(viewer.javaClass.getDeclaredField("rtcLock").apply { isAccessible = true }.get(viewer))
                synchronized(WebSession) {
                    text(client, """{"type":"rtc-start"}""")
                    var waiting = false
                    repeat(100) {
                        if (Thread.getAllStackTraces().any { (thread, trace) ->
                            thread.state == Thread.State.BLOCKED && trace.any {
                                it.className == WebSession.javaClass.name && it.methodName == "mediaSnapshot"
                            }
                        }) waiting = true
                        if (!waiting) Thread.sleep(10)
                    }
                    val acquired = CountDownLatch(1)
                    Thread { synchronized(rtcLock) { acquired.countDown() } }.apply { isDaemon = true; start() }
                    try {
                        assertTrue("Reader must be waiting for the coherent snapshot", waiting)
                        assertTrue("Service cleanup must still be able to acquire rtcLock", acquired.await(1, TimeUnit.SECONDS))
                    } finally { WebSession.videoActive = false }
                }
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

    @Test fun browserGpsRequiresHttpsAndExplicitOptInAndClearsWhenTheViewerDisconnects() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val provider = WebSession.location.createProvider()
        provider.start(); startHttpsSession(context)
        val fix = """{"type":"location","latitude":25,"longitude":121,"accuracy":5,"timestamp":1700000000000,"ageMillis":0}"""
        fun settle(client: Socket) {
            text(client, """{"type":"ping"}""")
            while (org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).optString("type") != "status") { }
        }
        try {
            handshake(WebSession.code).use { client ->
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                text(client, """{"type":"location-start"}"""); text(client, fix); settle(client)
                assertNull(provider.latestNmea())
            }
            val detachedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (WebSession.hasViewer && System.nanoTime() < detachedBy) Thread.sleep(10)
            assertFalse(WebSession.hasViewer)
            val endpoint = requireNotNull(WebSession.tls) { "HTTPS failed: ${WebSession.tlsError}" }
            val root = CertificateFactory.getInstance("X.509").generateCertificate(endpoint.certificate.inputStream())
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
                text(client, fix); settle(client); assertNull(provider.latestNmea())
                text(client, """{"type":"location-start"}"""); text(client, fix); settle(client)
                assertTrue(provider.latestNmea()!!.contains("2500.0000,N,12100.0000,E"))
                text(client, """{"type":"location-stop"}"""); settle(client); assertNull(provider.latestNmea())
                text(client, """{"type":"location-start"}"""); text(client, fix); settle(client)
                assertNotNull(provider.latestNmea())
            }
            val clearedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (provider.latestNmea() != null && System.nanoTime() < clearedBy) Thread.sleep(10)
            assertNull(provider.latestNmea())
        } finally { WebSession.stop(); provider.close() }
    }

    @Test fun trustedHttpsViewerCanSupplyNegotiatedMicrophonePcm() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        startHttpsSession(context)
        try {
            val endpoint = requireNotNull(WebSession.tls) { "HTTPS failed: ${WebSession.tlsError}" }
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
                    assertEquals(BrowserAudioBridge.AUDIO_FLAG_MEDIA, received.short.toInt())
                    val sequence = received.int.toLong() and 0xffff_ffffL
                    val samples = ByteArray(4); received.get(samples)
                    assertArrayEquals(byteArrayOf(1,2,3,4), samples)
                    text(client, """{"type":"audio-ack","sequence":$sequence}""")
                } finally { route.close() }
            }
        } finally { WebSession.stop() }
    }

    @Test fun browserMediaBufferFollowsSavedSettingsLiveAndAfterServiceRestart() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        moe.feng.wheelplay.AirPlayPersistence.saveMediaBufferMillis(context, 1000)
        moe.feng.wheelplay.AirPlayPersistence.saveBrowserAudioPlayback(context, true)
        val audioSource = object : ProjectionSession {
            override val descriptor = SourceDescriptor("audio-test", "Audio test")
            override val capabilities = ProjectionCapabilities(emptySet(), audioPlayback = true)
            override val inputReady = false
            override fun touch(contacts: List<ProjectionContact>) = false
            override fun stop() = java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }
        assertTrue(projectionSessions.adopt(audioSource, emptySet()))
        WebSession.start(context)
        try {
            handshake(WebSession.code).use { client ->
                client.soTimeout = 3000
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                val initial = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                assertEquals(1000, initial.getInt("mediaBufferMillis"))
                assertTrue(initial.getBoolean("playback"))
                WebSession.setMediaBufferMillis(context, 500)
                assertEquals(500, org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getInt("mediaBufferMillis"))
                assertEquals(500, moe.feng.wheelplay.AirPlayPersistence.loadMediaBufferMillis(context))
                WebSession.setBrowserAudioPlayback(context, false)
                val disabled = org.json.JSONObject(frame(client).toString(Charsets.UTF_8))
                assertFalse(disabled.getBoolean("playback"))
                assertEquals(500, disabled.getInt("mediaBufferMillis"))
                WebSession.setBrowserMicrophone(context, false)
                assertEquals(500, org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getInt("mediaBufferMillis"))
            }
            WebSession.stop(); WebSession.start(context)
            handshake(WebSession.code).use { client ->
                client.soTimeout = 3000
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals(500, org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getInt("mediaBufferMillis"))
            }
        } finally { projectionSessions.stop().join(); WebSession.stop() }
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
        startHttpsSession(context)
        try {
            val endpoint = requireNotNull(WebSession.tls) { "HTTPS failed: ${WebSession.tlsError}" }
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
        val service = org.robolectric.Robolectric.buildService(moe.feng.wheelplay.WheelPlaySessionService::class.java)
            .create().startCommand(0, 1)
        try {
            assertTrue(WebSession.running)
            val code = WebSession.code
            service.get().onTaskRemoved(android.content.Intent())
            assertTrue(WebSession.running)
            assertEquals(code, WebSession.code)
            service.get().onStartCommand(android.content.Intent().setAction(
                moe.feng.wheelplay.WheelPlaySessionService.ACTION_STOP), 0, 2)
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

    @Test fun emptyCloseFrameRepliesProtocolCorrectlyReleasesViewerAndAllowsReconnect() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        // This case binds a free port of its own: the default 8080 may already be owned by
        // another local service, and that must not decide this protocol boundary's verdict.
        val previousPort = WebListenSettings.httpPort(context)
        val port = java.net.ServerSocket(0).use { it.localPort }
        WebListenSettings.saveHttpPort(context, port)
        WebSession.start(context)
        try {
            val origin = "http://127.0.0.1:$port"
            handshake(WebSession.code, origin, host = "127.0.0.1:$port", port = port).use { client ->
                client.soTimeout = 3000
                assertTrue(readHeaders(client).startsWith("HTTP/1.1 101"))
                assertEquals("audio-route", org.json.JSONObject(frame(client).toString(Charsets.UTF_8)).getString("type"))
                maskedFrame(client, 8, ByteArray(0))
                val close = fi.iki.elonen.NanoWSD.WebSocketFrame.read(client.getInputStream())
                assertEquals(fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Close, close.opCode)
                assertArrayEquals("A status code must never be synthesized on the wire",
                    ByteArray(0), close.binaryPayload)
                assertEquals(-1, client.getInputStream().read())
            }
            assertFalse(WebSession.hasViewer)
            handshake(WebSession.code, origin, host = "127.0.0.1:$port", port = port).use { next ->
                assertTrue(readHeaders(next).startsWith("HTTP/1.1 101"))
                next.soTimeout = 3000
                // A rejected (busy/expired) viewer also completes the handshake, so only the
                // first control message proves the reclaimed lease actually carries this session.
                assertEquals("audio-route", org.json.JSONObject(frame(next).toString(Charsets.UTF_8)).getString("type"))
            }
        } finally {
            WebSession.stop()
            WebListenSettings.saveHttpPort(context, previousPort)
        }
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

    private fun handshake(code: String, origin: String = "http://127.0.0.1:8080", token: String = "", host: String = "127.0.0.1:8080", port: Int = 8080, media: Int = 1, images: Int = 0, nowPlaying: Boolean = false): Socket = Socket("127.0.0.1", port).apply {
        soTimeout = 3000
        getOutputStream().write(("GET /stream?code=$code&token=$token&media=$media&images=$images&nowPlaying=${if (nowPlaying) 1 else 0} HTTP/1.1\r\nHost: $host\r\n" +
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
        check(bytes.size <= 65535)
        // RFC6455 client frames are masked; zero mask is sufficient for this protocol fixture.
        socket.getOutputStream().apply {
            write(0x80 or opcode)
            if (bytes.size < 126) write(0x80 or bytes.size)
            else { write(0x80 or 126); write(bytes.size ushr 8); write(bytes.size and 255) }
            write(byteArrayOf(0,0,0,0)); write(bytes); flush()
        }
    }
}
