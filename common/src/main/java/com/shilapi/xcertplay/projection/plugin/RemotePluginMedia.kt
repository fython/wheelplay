package com.shilapi.xcertplay.projection.plugin

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.*
import moe.feng.wheelplay.plugin.PluginMediaState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Serialized on main; media state is phone-authoritative, a command receipt never changes playback. */
internal class RemotePluginMedia(
    private val active: () -> Boolean,
    private val dispatch: (Long, Int) -> Boolean,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val sourceName: String = "CarLink",
    override val publishNativeSession: Boolean = true,
) : MediaControlSource, AutoCloseable {
    // PluginAudioPlayback already owns local AudioTrack/focus and browser fallback.
    override val managesAudioFocus = true
    @Volatile private var state = NowPlayingState()
    private var closed = false
    private var artworkBytes: ByteArray? = null
    private var artworkEpoch = 0L
    private val artworkWorker = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(), { task -> Thread(task, "plugin-artwork").apply { isDaemon = true } })
    private val listeners = mutableSetOf<(NowPlayingState) -> Unit>()
    private var sequence = 0L
    private var pending: Pair<Long, CompletableFuture<MediaCommandResult>>? = null
    private var timeout: Runnable? = null

    override fun mediaSnapshot() = state
    override fun subscribeMediaState(listener: (NowPlayingState) -> Unit): AutoCloseable {
        val removed = AtomicBoolean()
        main.post { if (!removed.get()) { if (!closed) listeners += listener; listener(state) } }
        return AutoCloseable { removed.set(true); main.post { listeners -= listener } }
    }
    fun update(value: PluginMediaState) {
        if (closed) return
        val changed = listOf(state.title.orEmpty(), state.artist.orEmpty(), state.album.orEmpty(), state.appBundleId.orEmpty()) !=
            listOf(value.title, value.artist, value.album, value.player)
        val incomingArt = value.artwork?.takeIf { value.available && it.isNotEmpty() }
        val artworkChanged = !(incomingArt contentEquals artworkBytes)
        val previousArt = state.artwork.takeUnless { artworkChanged }
        if (artworkChanged) {
            artworkBytes = incomingArt?.copyOf()
            artworkEpoch++
            artworkWorker.queue.clear()
        }
        state = NowPlayingState(
            artwork = previousArt,
            connected = true, connectionGeneration = 1,
            trackRevision = state.trackRevision + if (changed || state.available != value.available) 1 else 0,
            title = value.title.takeIf { value.available }, artist = value.artist.takeIf { value.available },
            album = value.album.takeIf { value.available }, appName = sourceName.takeIf { value.available },
            appBundleId = value.player.takeIf { value.available },
            durationMs = value.durationMs.takeIf { value.available && it > 0 },
            positionMs = value.positionMs.takeIf { value.available && it >= 0 },
            positionAtMs = SystemClock.elapsedRealtime(),
            status = if (!value.available) PhonePlaybackStatus.STOPPED else if (value.playing) PhonePlaybackStatus.PLAYING else PhonePlaybackStatus.PAUSED,
        )
        listeners.toList().forEach { it(state) }
        if (artworkChanged && incomingArt != null) {
            val epoch = artworkEpoch
            val bytes = incomingArt.copyOf()
            artworkWorker.execute {
                val image = MediaArtworkDecoder.decode(bytes)
                main.post {
                    if (!closed && epoch == artworkEpoch && state.available) {
                        state = state.copy(artwork = image)
                        listeners.toList().forEach { it(state) }
                    }
                }
            }
        }
    }
    override fun sendMediaCommand(command: MediaCommand, connectionGeneration: Long?): CompletableFuture<MediaCommandResult> {
        val result = CompletableFuture<MediaCommandResult>()
        main.post {
            if (closed || !active() || !state.available || connectionGeneration != null && connectionGeneration != state.connectionGeneration) {
                result.complete(MediaCommandResult.UNAVAILABLE); return@post
            }
            if (pending != null) { result.complete(MediaCommandResult.SUPERSEDED); return@post }
            val operation = when (command) {
                MediaCommand.PLAY -> PluginMediaState.PLAY
                MediaCommand.PAUSE -> PluginMediaState.PAUSE
                MediaCommand.NEXT -> PluginMediaState.NEXT
                MediaCommand.PREVIOUS -> PluginMediaState.PREVIOUS
                MediaCommand.TOGGLE -> if (state.playing) PluginMediaState.PAUSE else PluginMediaState.PLAY
            }
            val id = ++sequence
            pending = id to result
            val expires = Runnable { if (pending?.first == id) finishCommand(MediaCommandResult.FAILED) }
            timeout = expires
            main.postDelayed(expires, 3000)
            if (!dispatch(id, operation)) finishCommand(MediaCommandResult.FAILED)
        }
        return result
    }
    fun result(id: Long, sent: Boolean) {
        if (!closed && pending?.first == id) finishCommand(if (sent) MediaCommandResult.SENT else MediaCommandResult.FAILED)
    }
    private fun finishCommand(value: MediaCommandResult) {
        timeout?.let(main::removeCallbacks); timeout = null
        val future = pending?.second; pending = null; future?.complete(value)
    }
    override fun seekTo(positionMs: Long, connectionGeneration: Long?, trackRevision: Long?) =
        CompletableFuture.completedFuture(MediaCommandResult.UNSUPPORTED)

    override fun close() {
        if (closed) return
        closed = true; artworkEpoch++; artworkWorker.shutdownNow(); artworkBytes = null; finishCommand(MediaCommandResult.UNAVAILABLE)
        state = NowPlayingState()
        listeners.toList().forEach { it(state) }; listeners.clear()
    }
}
