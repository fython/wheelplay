package com.shilapi.xcertplay.media

import android.os.SystemClock
import com.shilapi.xcertplay.iap2.message.Iap2ArtworkReceiver
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingCodec
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Service-independent state owner. Only the existing control loop calls [receive]. */
class CarPlayMediaCoordinator(
    private val captureSender: () -> ((MediaCommand) -> Boolean)?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val onDiagnostic: (String) -> Unit = {},
    private val decodeArtwork: (ByteArray) -> MediaArtwork? = MediaArtworkDecoder::decode,
) : AutoCloseable {
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<CompletableFuture<MediaCommandResult>>()
    private val lock = Object()
    private val listeners = CopyOnWriteArrayList<(NowPlayingState) -> Unit>()
    private val commands = Executors.newSingleThreadExecutor { Thread(it, "carplay-media-controls").apply { isDaemon = true } }
    @Volatile private var state = NowPlayingState()
    private var channel: Iap2Session? = null
    private var lastReadyChannel: Iap2Session? = null
    private data class ArtworkExpectation(val id: Int?, val revision: Long)
    private class ArtworkBinding(val source: Iap2Session, val receiver: Iap2ArtworkReceiver,
        @Volatile var expectation: ArtworkExpectation)
    @Volatile private var artworkBinding: ArtworkBinding? = null
    private var artworkWorkerStarted = false
    private val artworkWorker = Thread(::receiveArtwork, "carplay-artwork").apply { isDaemon = true }
    private var generation = 0L
    @Volatile private var closed = false

    fun snapshot(): NowPlayingState = state
    fun subscribe(listener: (NowPlayingState) -> Unit): AutoCloseable {
        synchronized(lock) {
            listeners.add(listener)
            listener(state)
        }
        return AutoCloseable { listeners.remove(listener) }
    }
    private fun publish(next: NowPlayingState) {
        state = next
        listeners.forEach { try { it(next) } catch (_: Exception) { /* Isolate observers. */ } }
    }

    fun bind(next: Iap2Session, retainState: Boolean = false) {
        val epoch: Long
        synchronized(lock) {
            if (closed || channel === next) return
            channel = next
            lastReadyChannel = next
            epoch = ++generation
            // Completed artwork belongs to the current song; only its transfer belongs to the link.
            val revision = state.trackRevision + 1
            publish(if (retainState) state.copy(connected = true, connectionGeneration = epoch, trackRevision = revision,
                artworkTransferId = null)
                else NowPlayingState(connected = true, connectionGeneration = epoch, trackRevision = revision))
            diagnostic("media link bound generation=$epoch handoff=$retainState artworkRetained=${state.artwork != null}")
            lateinit var binding: ArtworkBinding
            val files = Iap2ArtworkReceiver({ bytes ->
                next.sendFileTransfer(bytes) { artworkBinding === binding }
            }, { revision, id, bytes ->
                val artwork = decodeArtwork(bytes)
                synchronized(lock) {
                    if (closed || generation != epoch || state.trackRevision != revision || state.artworkTransferId != id || artwork == null) false
                    else {
                        publish(state.copy(artwork = artwork))
                        true
                    }
                }
            }, clock, ::diagnostic)
            binding = ArtworkBinding(next, files, ArtworkExpectation(state.artworkTransferId, revision))
            artworkBinding = binding
            if (!artworkWorkerStarted) {
                artworkWorkerStarted = true
                artworkWorker.start()
            }
            lock.notifyAll()
        }
    }

    /** One consumer survives link reuse; metadata updates never enter the receiver's monitor. */
    private fun receiveArtwork() {
        while (!closed) {
            val binding = try {
                synchronized(lock) {
                    while (!closed && artworkBinding == null) lock.wait()
                    artworkBinding
                }
            } catch (_: InterruptedException) { return } ?: continue
            try {
                if (binding.source.isClosed) {
                    synchronized(lock) { if (artworkBinding === binding) artworkBinding = null }
                    continue
                }
                binding.expectation.let { binding.receiver.expect(it.id, it.revision) }
                if (artworkBinding !== binding) continue
                val bytes = binding.source.recvFileTransfer(250)
                // A reconnect can occur during the queue wait. A packet on the reused link
                // belongs to its current receiver; never let a retiring consumer steal it.
                val current = artworkBinding
                if (current != null && current.source === binding.source) {
                    current.expectation.let { current.receiver.expect(it.id, it.revision) }
                    if (bytes != null) current.receiver.receive(bytes)
                    current.receiver.expire()
                }
            } catch (failure: Exception) {
                if (closed) return
                diagnostic("artwork receiver stopped: ${failure.javaClass.simpleName}: ${failure.message}")
                synchronized(lock) { if (artworkBinding === binding) artworkBinding = null }
            }
        }
    }

    /** AirPlay may reconnect while the authenticated iAP2 control loop remains alive. */
    fun resume() {
        val ready = synchronized(lock) {
            if (closed || channel != null) return
            lastReadyChannel?.takeUnless { it.isClosed }
        } ?: return
        bind(ready)
        val epoch = synchronized(lock) { generation }
        try {
            commands.execute {
                if (synchronized(lock) { !closed && generation == epoch && channel === ready }) {
                    try { ready.send(Iap2NowPlayingCodec.subscription()) } catch (_: Exception) { disconnected(ready) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* Closing. */ }
    }

    fun receive(source: Iap2Session, frame: Iap2Frame) {
        if (frame.messageId != Iap2NowPlayingCodec.UPDATE) return
        val delta = try { Iap2NowPlayingCodec.decode(frame) } catch (_: com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException) { return }
        synchronized(lock) {
            if (closed || channel !== source) return
            val before = state
            val change = NowPlayingReducer.trackChangeReason(before, delta)
            publish(NowPlayingReducer.update(state.copy(connected = true), delta, clock()))
            val fields = buildList {
                if (delta.title != null && delta.title != before.title) add("title")
                if (delta.artist != null && delta.artist != before.artist) add("artist")
                if (delta.album != null && delta.album != before.album) add("album")
                if (delta.appName != null && delta.appName != before.appName) add("app-name")
                if (delta.appBundleId != null && delta.appBundleId != before.appBundleId) add("app-bundle")
                if (delta.persistentId != null && delta.persistentId != before.persistentId) add("persistent-id")
                if (delta.queueIndex != null && delta.queueIndex != before.queueIndex) add("queue-index")
                if (delta.artworkTransferId != null && delta.artworkTransferId != before.artworkTransferId) add("artwork-id")
            }
            if (change != null || fields.isNotEmpty()) {
                diagnostic("media ${if (change != null) "track changed" else "metadata refreshed"} " +
                    "generation=$generation revision=${state.trackRevision} reason=${change ?: "none"} " +
                    "fields=${fields.joinToString(",")} pidPresent=${delta.persistentId != null} " +
                    "queuePresent=${delta.queueIndex != null} artworkRetained=${state.artwork != null}")
            }
            if (before.trackRevision != state.trackRevision || before.artworkTransferId != state.artworkTransferId) {
                diagnostic("media artwork expectation generation=$generation revision=${state.trackRevision} id=${state.artworkTransferId ?: "none"}")
            }
            artworkBinding?.expectation = ArtworkExpectation(state.artworkTransferId, state.trackRevision)
        }
    }

    fun send(command: MediaCommand, connectionGeneration: Long? = null): CompletableFuture<MediaCommandResult> {
        val result = CompletableFuture<MediaCommandResult>()
        pending.add(result)
        result.whenComplete { _, _ -> pending.remove(result) }
        val epoch = synchronized(lock) { generation }
        try {
            commands.execute {
                if (result.isDone) return@execute
                val sender = synchronized(lock) {
                    if (closed || epoch != generation || !state.available ||
                        connectionGeneration != null && connectionGeneration != state.connectionGeneration) null
                    else captureSender()
                }
                // Capture the phone before leaving the lock. Transport I/O must never block a
                // disconnect, and an old command must never look up a new active phone afterwards.
                val outcome = if (sender == null) MediaCommandResult.UNAVAILABLE else try {
                    if (sender(command)) MediaCommandResult.SENT else MediaCommandResult.UNAVAILABLE
                } catch (_: Exception) { MediaCommandResult.FAILED }
                result.complete(synchronized(lock) {
                    if (closed || generation != epoch) MediaCommandResult.UNAVAILABLE else outcome
                })
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { result.complete(MediaCommandResult.UNAVAILABLE) }
        return result
    }

    fun seekTo(positionMs: Long): CompletableFuture<MediaCommandResult> {
        // R32 confirms the parameter width and capability, but omits its outbound time unit.
        // Never send a guessed encoding or expose ACTION_SEEK_TO until a capture verifies it.
        return CompletableFuture.completedFuture(if (!state.available) MediaCommandResult.UNAVAILABLE else MediaCommandResult.UNSUPPORTED)
    }

    fun disconnected(source: Iap2Session? = null) = synchronized(lock) {
        if (source != null && channel !== source) return@synchronized
        ++generation
        pending.forEach { it.complete(MediaCommandResult.UNAVAILABLE) }
        channel = null
        artworkBinding = null
        publish(NowPlayingState(connectionGeneration = generation, trackRevision = state.trackRevision + 1))
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; lastReadyChannel = null; disconnected() }
        commands.shutdownNow()
        artworkWorker.interrupt()
        listeners.clear()
    }

    private fun diagnostic(message: String) {
        try { onDiagnostic(message) } catch (_: Exception) { /* Logging must not affect state. */ }
    }


}
