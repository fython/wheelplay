package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.NowPlayingUpdate

enum class MediaCommand(val hidIndex: Int) { PLAY(1), PAUSE(2), TOGGLE(3), NEXT(4), PREVIOUS(5) }
enum class MediaCommandResult { SENT, UNAVAILABLE, UNSUPPORTED, SUPERSEDED, FAILED }
enum class PhonePlaybackStatus { UNKNOWN, STOPPED, PLAYING, PAUSED, SEEK_FORWARD, SEEK_BACKWARD }

/** Owns its bytes so snapshots can safely cross transport, decoder and main threads. */
class MediaArtwork(bytes: ByteArray) {
    private val data = bytes.copyOf()
    fun bytes(): ByteArray = data.copyOf()
}

data class NowPlayingState(
    val connected: Boolean = false,
    val connectionGeneration: Long = 0,
    val trackRevision: Long = 0,
    val persistentId: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val appName: String? = null,
    val appBundleId: String? = null,
    val queueIndex: Long? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val positionAtMs: Long = 0,
    val status: PhonePlaybackStatus = PhonePlaybackStatus.UNKNOWN,
    val reportedSpeed: Float? = null,
    val setElapsedTimeAvailable: Boolean = false,
    val artworkTransferId: Int? = null,
    val artwork: MediaArtwork? = null,
) {
    val available: Boolean get() = connected &&
        (!title.isNullOrBlank() || !appName.isNullOrBlank() || !appBundleId.isNullOrBlank())
    val playing: Boolean get() = status == PhonePlaybackStatus.PLAYING
    val speed: Float get() = if (playing) reportedSpeed ?: 1f else 0f
    // Outbound units and the audio continuity boundary still require a verified capture.
    val canSeek: Boolean get() = false
    fun positionAt(nowMs: Long): Long? = positionMs?.let {
        val position = it + ((nowMs - positionAtMs).coerceAtLeast(0) * speed).toLong()
        if ((durationMs ?: 0) > 0) position.coerceIn(0, durationMs!!) else position.coerceAtLeast(0)
    }
}

/** Pure incremental reducer. A missing property keeps its last value; empty strings clear it. */
object NowPlayingReducer {
    /** Only explicit identity changes establish a boundary; display text may contain live lyrics. */
    fun trackChangeReason(old: NowPlayingState, delta: NowPlayingUpdate): String? {
        val appChanged = delta.appBundleId != null && old.appBundleId != null && delta.appBundleId != old.appBundleId ||
            delta.appBundleId == null && old.appBundleId == null && delta.appName != null && old.appName != null && delta.appName != old.appName
        return when {
            appChanged -> "player"
            delta.persistentId != null && old.persistentId != null ->
                if (delta.persistentId != old.persistentId) "persistent-id" else null
            delta.queueIndex != null && old.queueIndex != null ->
                if (delta.queueIndex != old.queueIndex) "queue-index" else null
            else -> null
        }
    }

    fun update(old: NowPlayingState, delta: NowPlayingUpdate, nowMs: Long): NowPlayingState {
        val change = trackChangeReason(old, delta)
        val base = if (change == "player") NowPlayingState(
            connected = old.connected, connectionGeneration = old.connectionGeneration, trackRevision = old.trackRevision + 1,
        ) else if (change != null) old.copy(
            // The next identity can be reported in several messages. Do not compare a late
            // new PID/queue index with the previous song and invent a second boundary.
            persistentId = null, queueIndex = null,
            trackRevision = old.trackRevision + 1, positionMs = null, positionAtMs = nowMs,
            artworkTransferId = null, artwork = null,
        ) else old
        // Anchor before changing rate/status, including a pause without a new elapsed-time field.
        val position = delta.positionMs ?: base.positionAt(nowMs)
        // A new transfer ID can refresh the same song's cover. Keep the completed image until
        // its replacement is decoded; confirmed boundaries above already retire old artwork.
        return base.copy(
            persistentId = delta.persistentId ?: base.persistentId,
            title = delta.title ?: base.title, artist = delta.artist ?: base.artist,
            album = delta.album ?: base.album, appName = delta.appName ?: base.appName,
            appBundleId = delta.appBundleId ?: base.appBundleId,
            queueIndex = delta.queueIndex ?: base.queueIndex,
            durationMs = delta.durationMs ?: base.durationMs,
            positionMs = position, positionAtMs = nowMs,
            status = delta.status ?: base.status,
            reportedSpeed = if (delta.speed != null) delta.speed.takeIf { it > 0 } else base.reportedSpeed,
            setElapsedTimeAvailable = delta.setElapsedTimeAvailable ?: base.setElapsedTimeAvailable,
            artworkTransferId = delta.artworkTransferId ?: base.artworkTransferId,
            artwork = base.artwork,
        )
    }
}
