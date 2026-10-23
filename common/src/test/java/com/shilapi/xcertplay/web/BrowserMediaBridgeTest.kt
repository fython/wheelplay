package com.shilapi.xcertplay.web

import android.os.Looper
import com.shilapi.xcertplay.media.*
import java.util.concurrent.CompletableFuture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrowserMediaBridgeTest {
    private class Source : CarPlayMediaSource {
        var state = NowPlayingState(connected = true, connectionGeneration = 7, trackRevision = 2,
            title = "Song", artist = "Artist", album = "Album", status = PhonePlaybackStatus.PLAYING,
            durationMs = 100000, positionMs = 2000, positionAtMs = 1000)
        val listeners = mutableListOf<(NowPlayingState) -> Unit>()
        val commands = mutableListOf<MediaCommand>()
        val seeks = mutableListOf<Triple<Long, Long?, Long?>>()
        override fun mediaSnapshot() = state
        override fun subscribeMediaState(listener: (NowPlayingState) -> Unit): AutoCloseable {
            listeners.add(listener); listener(state)
            return AutoCloseable { listeners.remove(listener) }
        }
        override fun sendMediaCommand(command: MediaCommand, connectionGeneration: Long?): CompletableFuture<MediaCommandResult> {
            commands.add(command); return CompletableFuture.completedFuture(MediaCommandResult.SENT)
        }
        override fun seekTo(positionMs: Long, connectionGeneration: Long?, trackRevision: Long?): CompletableFuture<MediaCommandResult> {
            seeks.add(Triple(positionMs, connectionGeneration, trackRevision))
            return CompletableFuture.completedFuture(MediaCommandResult.SENT)
        }
        fun update(next: NowPlayingState) { state = next; listeners.toList().forEach { it(next) } }
    }
    private class Fixture {
        val first = Source()
        var source: Source? = first
        var changed: (() -> Unit)? = null
        var time = 2000L
        var deferred: CompletableFuture<MediaCommandResult>? = null
        val sent = mutableListOf<JSONObject>()
        val bridge = BrowserMediaBridge(send = { sent.add(JSONObject(it)) }, currentSource = { source },
            observeSource = { changed = it; it(); AutoCloseable { changed = null } },
            dispatch = { src, gen, cmd ->
                val sent = src.sendMediaCommand(cmd, gen)
                deferred ?: sent
            }, dispatchSeek = { src, gen, track, pos -> deferred ?: src.seekTo(pos, gen, track) }, clock = { time })
        init { bridge.start(); idle() }
        fun state() = sent.last { it.getString("type") == "now-playing" }
        fun command(action: String = "pause", id: Long = 1, snapshot: JSONObject = state()) = JSONObject()
            .put("requestId", id).put("action", action).put("sessionId", snapshot.getString("sessionId"))
            .put("connectionGeneration", snapshot.getLong("connectionGeneration"))
        fun seek(id: Long, position: Long, snapshot: JSONObject = state()) = command("seekto", id, snapshot)
            .put("positionMs", position).put("trackRevision", snapshot.getLong("trackRevision"))
        fun tick() { time += 200; shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        fun enableSeek() { first.update(first.state.copy(setElapsedTimeAvailable = true)); idle() }
        fun close() { bridge.close(); idle() }
    }
    companion object { private fun idle() = shadowOf(Looper.getMainLooper()).idle() }

    @Test fun initialAndIncrementalStatesHaveAnchoredProgressAndExplicitNulls() {
        val f = Fixture()
        try {
            assertEquals("Song", f.state().getString("title"))
            assertEquals(3000L, f.state().getLong("positionMs"))
            assertFalse(f.state().getBoolean("canSeek"))
            assertTrue(f.state().isNull("artworkUrl"))
            val revision = f.state().getLong("revision")
            f.first.update(f.first.state.copy(status = PhonePlaybackStatus.PAUSED, positionMs = 3100, positionAtMs = f.time))
            f.time += 5000; idle()
            assertEquals("paused", f.state().getString("status"))
            assertEquals(3100L, f.state().getLong("positionMs"))
            assertTrue(f.state().getLong("revision") > revision)
            f.first.update(NowPlayingState(connectionGeneration = 8)); idle()
            assertFalse(f.state().getBoolean("available")); assertTrue(f.state().isNull("title"))
        } finally { f.close() }
    }

    @Test fun artworkCapabilityIsStableForLyricsButRetiredOnTrackSourceOrViewerChanges() {
        val f = Fixture()
        try {
            val bytes = byteArrayOf(1, 2, 3)
            f.first.update(f.first.state.copy(artwork = MediaArtwork(bytes))); idle()
            val url = f.state().getString("artworkUrl"); val id = url.substringAfter("id=")
            assertArrayEquals(bytes, f.bridge.artwork(id)); assertNull(f.bridge.artwork("guess"))
            f.first.update(f.first.state.copy(title = "Live lyrics")); idle()
            assertEquals(url, f.state().getString("artworkUrl"))
            f.first.update(f.first.state.copy(trackRevision = 3, artwork = null))
            assertNull("Old cover is denied even before main processes the new snapshot", f.bridge.artwork(id))
            idle(); assertTrue(f.state().isNull("artworkUrl"))
            f.first.update(f.first.state.copy(artwork = MediaArtwork(bytes))); idle()
            val nextId = f.state().getString("artworkUrl").substringAfter("id=")
            f.source = Source(); assertNull(f.bridge.artwork(nextId))
            f.changed!!(); idle()
            f.source!!.update(f.source!!.state.copy(artwork = MediaArtwork(bytes))); idle()
            val lastId = f.state().getString("artworkUrl").substringAfter("id=")
            f.bridge.close(); assertNull(f.bridge.artwork(lastId))
        } finally { f.close() }
    }

    @Test fun replacementControllerWithSameGenerationRejectsOldCallbacksAndCommands() {
        val f = Fixture()
        try {
            val old = f.state(); val callback = f.first.listeners.single()
            f.source = Source(); f.changed!!(); idle()
            assertTrue(f.first.listeners.isEmpty())
            assertNotEquals(old.getString("sessionId"), f.state().getString("sessionId"))
            callback(f.first.state.copy(title = "Retired")); idle()
            assertEquals("Song", f.state().getString("title"))
            f.bridge.command(f.command(snapshot = old)); idle()
            assertTrue(f.source!!.commands.isEmpty()); assertTrue(f.first.commands.isEmpty())
            assertEquals("unavailable", f.sent.last().getString("result"))
            f.bridge.command(f.command(id = 2)); idle()
            assertEquals(listOf(MediaCommand.PAUSE), f.source!!.commands)
            assertEquals("sent", f.sent.last().getString("result"))
            assertEquals("playing", f.state().getString("status"))
        } finally { f.close() }
        assertTrue(f.source!!.listeners.isEmpty()); assertNull(f.changed)
    }

    @Test fun commandsAreBoundedDeduplicatedAndRejectIncompleteSeek() {
        val f = Fixture()
        try {
            f.bridge.command(f.command()); idle()
            f.time += 200
            f.bridge.command(f.command()); idle()
            assertEquals(1, f.first.commands.size)
            f.bridge.command(f.command("nexttrack", 2)); idle()
            f.bridge.command(f.command("previoustrack", 3)); idle()
            assertEquals(listOf(MediaCommand.PAUSE, MediaCommand.NEXT), f.first.commands)
            assertThrows(org.json.JSONException::class.java) { f.bridge.command(f.command("seekto", 4)) }
            f.first.update(f.first.state.copy(connectionGeneration = 8));
            f.time += 200
            // Delivery is still guarded by the underlying source/native bridge while main catches up.
            idle(); f.bridge.close(); f.bridge.command(f.command(id = 5)); idle()
            assertEquals(2, f.first.commands.size)
        } finally { f.close() }
    }

    @Test fun closeBeforeStartCannotLeakSubscriptionsOrPublish() {
        var subscriptions = 0
        val bridge = BrowserMediaBridge(send = { fail("Closed bridge published") }, currentSource = { Source() },
            observeSource = { subscriptions++; AutoCloseable {} })
        bridge.start(); bridge.close(); idle()
        assertEquals(0, subscriptions)
    }

    @Test fun revokingPairingBeforeMainDispatchDropsTheQueuedControl() {
        val f = Fixture()
        try {
            var authorized = true
            f.bridge.command(f.command()) { authorized }
            authorized = false
            idle()
            assertTrue(f.first.commands.isEmpty())
        } finally { f.close() }
    }

    @Test fun pendingCommandsStayBoundedAndOldCompletionCannotAcknowledgeANewController() {
        val f = Fixture()
        try {
            val old = CompletableFuture<MediaCommandResult>(); f.deferred = old
            f.bridge.command(f.command()); idle()
            f.time += 200; f.bridge.command(f.command("nexttrack", 2)); idle()
            assertEquals(listOf(MediaCommand.PAUSE), f.first.commands)
            assertEquals("unavailable", f.sent.last().getString("result"))
            f.source = Source(); f.changed!!(); idle()
            val count = f.sent.size
            old.complete(MediaCommandResult.SENT); idle()
            assertEquals(count, f.sent.size)
            f.deferred = null; f.bridge.command(f.command("nexttrack", 3)); idle()
            assertEquals(listOf(MediaCommand.NEXT), f.source!!.commands)
            assertEquals(3L, f.sent.last().getLong("requestId"))
            assertEquals("sent", f.sent.last().getString("result"))
        } finally { f.close() }
    }
    @Test fun finalSeekSurvivesRapidDragAndInFlightControlWithoutChangingPhoneState() {
        val f = Fixture()
        try {
            f.enableSeek(); assertTrue(f.state().getBoolean("canSeek"))
            f.deferred = CompletableFuture()
            f.bridge.command(f.command()); idle()
            f.bridge.command(f.seek(2, 10000)); f.bridge.command(f.seek(3, 42000)); idle()
            assertEquals("superseded", f.sent.last { it.optLong("requestId") == 2L }.getString("result"))
            f.tick(); assertTrue(f.first.seeks.isEmpty())
            f.deferred!!.complete(MediaCommandResult.SENT); f.deferred = null; idle(); f.tick()
            assertEquals(listOf(Triple(42000L, 7L, 2L)), f.first.seeks)
            assertEquals("sent", f.sent.last().getString("result"))
            assertEquals(3L, f.sent.last().getLong("requestId"))
            assertEquals(2000L, f.first.state.positionMs)
            assertEquals(PhonePlaybackStatus.PLAYING, f.first.state.status)
        } finally { f.close() }
    }

    @Test fun queuedSeekRechecksTrackConnectionPairingAndCapabilityBeforeDispatch() {
        for (change in 0..4) {
            val f = Fixture()
            try {
                f.enableSeek()
                var authorized = true
                f.bridge.command(f.seek(1, 42000)) { authorized }; idle()
                when (change) {
                    0 -> f.first.update(f.first.state.copy(trackRevision = 3))
                    1 -> f.first.update(f.first.state.copy(connectionGeneration = 8))
                    2 -> f.first.update(f.first.state.copy(setElapsedTimeAvailable = false))
                    3 -> authorized = false
                    4 -> f.source = Source()
                }
                f.tick(); assertTrue(f.first.seeks.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun seeksRejectMalformedPositionsStaleTracksAndUnavailableTimelines() {
        val f = Fixture()
        try {
            f.bridge.command(f.seek(1, 42000)); idle()
            assertEquals("unsupported", f.sent.last().getString("result"))
            f.enableSeek()
            for (value in listOf<Any>(-1, 1.5, "42000", JSONObject.NULL, 9007199254740992L)) {
                assertThrows(IllegalArgumentException::class.java) { f.bridge.command(f.seek(2, 42000).put("positionMs", value)) }
            }
            f.bridge.command(f.seek(2, 42000).put("trackRevision", 1)); idle(); f.tick()
            assertTrue(f.first.seeks.isEmpty())
            assertEquals("unavailable", f.sent.last().getString("result"))
            f.bridge.command(f.seek(3, 42000)); idle(); f.bridge.close(); f.tick()
            assertTrue(f.first.seeks.isEmpty())
        } finally { f.close() }
    }

}
