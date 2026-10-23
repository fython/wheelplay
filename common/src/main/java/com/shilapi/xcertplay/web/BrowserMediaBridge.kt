package com.shilapi.xcertplay.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.DiPlaySessionService
import com.shilapi.xcertplay.media.CarPlayMediaSource
import com.shilapi.xcertplay.media.MediaArtwork
import com.shilapi.xcertplay.media.MediaCommand
import com.shilapi.xcertplay.media.MediaCommandResult
import com.shilapi.xcertplay.media.NowPlayingState
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** One paired viewer. All binding/state/command work is serialized on main, never on iAP2. */
internal class BrowserMediaBridge(
    private val send: (String) -> Unit,
    private val currentSource: () -> CarPlayMediaSource? = { CarPlayBackgroundSession.snapshot()?.controller },
    private val observeSource: (() -> Unit) -> AutoCloseable = { listener -> CarPlayBackgroundSession.subscribe { listener() } },
    private val dispatch: (CarPlayMediaSource, Long, MediaCommand) -> CompletableFuture<MediaCommandResult> = DiPlaySessionService::browserMediaCommand,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var sourceSubscription: AutoCloseable? = null
    private var mediaSubscription: AutoCloseable? = null
    private var source: CarPlayMediaSource? = null
    private var binding = ""
    private var revision = 0L
    private var state = NowPlayingState()
    private var artwork: MediaArtwork? = null
    private data class Cover(val id: String, val bytes: ByteArray, val source: CarPlayMediaSource?,
        val generation: Long, val track: Long, val artwork: MediaArtwork)
    @Volatile private var cover: Cover? = null
    private var pendingCommand: Long? = null
    private var lastRequest = 0L
    private var lastCommandAt: Long? = null

    fun start() { main.post {
        if (!closed.get() && sourceSubscription == null) {
            sourceSubscription = observeSource { main.post { bind() } }
            bind()
        }
    } }

    private fun bind() {
        if (closed.get()) return
        val next = currentSource()
        if (binding.isNotEmpty() && source === next) return
        mediaSubscription?.close()
        source = next
        binding = UUID.randomUUID().toString()
        val expected = binding
        pendingCommand = null
        publish(NowPlayingState())
        mediaSubscription = next?.subscribeMediaState { snapshot -> main.post {
            if (!closed.get() && binding == expected && currentSource() === next) publish(snapshot)
        } }
    }

    private fun publish(next: NowPlayingState) {
        val boundary = next.connectionGeneration != state.connectionGeneration || next.trackRevision != state.trackRevision
        if (boundary || next.artwork !== artwork || !next.available) {
            artwork = next.artwork.takeIf { next.available }
            cover = artwork?.let { Cover(UUID.randomUUID().toString(), it.bytes(), source,
                next.connectionGeneration, next.trackRevision, it) }
        }
        state = next
        send(JSONObject().put("type", "now-playing").put("sessionId", binding)
            .put("revision", ++revision).put("connectionGeneration", next.connectionGeneration)
            .put("trackRevision", next.trackRevision).put("connected", next.connected)
            .put("available", next.available).put("title", next.title ?: JSONObject.NULL)
            .put("artist", next.artist ?: JSONObject.NULL).put("album", next.album ?: JSONObject.NULL)
            .put("appName", next.appName ?: JSONObject.NULL)
            .put("status", next.status.name.lowercase()).put("durationMs", next.durationMs ?: JSONObject.NULL)
            .put("positionMs", next.positionAt(clock()) ?: JSONObject.NULL).put("speed", next.speed)
            .put("canSeek", false)
            .put("artworkUrl", cover?.let { "/now-playing/artwork?id=${it.id}" } ?: JSONObject.NULL)
            .toString())
    }

    /** Random capability is scoped to this viewer and the currently published image; never cached. */
    fun artwork(id: String?): ByteArray? {
        if (closed.get() || id == null) return null
        val image = cover?.takeIf { it.id == id } ?: return null
        if (currentSource() !== image.source) return null
        val current = image.source?.mediaSnapshot() ?: return null
        return image.bytes.takeIf { current.available && current.connectionGeneration == image.generation &&
            current.trackRevision == image.track && current.artwork === image.artwork }
    }

    fun command(data: JSONObject, authorized: () -> Boolean = { true }) {
        val requestId = data.getLong("requestId")
        require(requestId in 1..9_007_199_254_740_991L)
        val sessionId = data.getString("sessionId")
        val generation = data.getLong("connectionGeneration")
        val command = when (data.getString("action")) {
            "play" -> MediaCommand.PLAY
            "pause", "stop" -> MediaCommand.PAUSE
            "nexttrack" -> MediaCommand.NEXT
            "previoustrack" -> MediaCommand.PREVIOUS
            else -> error("Unsupported media action")
        }
        main.post {
            // Pairing can be revoked after the socket reader posts the command.
            if (closed.get() || !authorized()) return@post
            val current = source
            val now = clock()
            if (requestId <= lastRequest || sessionId != binding || generation != state.connectionGeneration ||
                current == null || currentSource() !== current || !state.available || pendingCommand != null ||
                lastCommandAt?.let { now - it < 150 } == true) {
                result(requestId, MediaCommandResult.UNAVAILABLE)
                return@post
            }
            lastRequest = requestId
            lastCommandAt = now
            pendingCommand = requestId
            val future = try { dispatch(current, generation, command) }
                catch (_: Exception) { CompletableFuture.completedFuture(MediaCommandResult.FAILED) }
            future.whenComplete { outcome, error -> main.post {
                if (!closed.get() && binding == sessionId && pendingCommand == requestId) {
                    pendingCommand = null
                    result(requestId, if (currentSource() !== current || current.mediaSnapshot().connectionGeneration != generation)
                        MediaCommandResult.UNAVAILABLE else if (error != null) MediaCommandResult.FAILED else outcome)
                }
            } }
        }
    }

    private fun result(id: Long, result: MediaCommandResult) {
        send(JSONObject().put("type", "media-command-result").put("requestId", id)
            .put("result", result.name.lowercase()).toString())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cover = null
        main.post {
            mediaSubscription?.close(); mediaSubscription = null
            sourceSubscription?.close(); sourceSubscription = null
            source = null; artwork = null; state = NowPlayingState(); pendingCommand = null
        }
    }
}
