package com.shilapi.xcertplay.media

import java.util.concurrent.CompletableFuture

interface CarPlayMediaSource {
    fun mediaSnapshot(): NowPlayingState
    fun subscribeMediaState(listener: (NowPlayingState) -> Unit): AutoCloseable
    fun sendMediaCommand(command: MediaCommand, connectionGeneration: Long? = null): CompletableFuture<MediaCommandResult>
    fun seekTo(positionMs: Long): CompletableFuture<MediaCommandResult>
}

interface MediaOutputControl {
    fun isRemoteMediaAvailable(): Boolean = false
    fun setLocalMediaAllowed(allowed: Boolean)
    fun setLocalMediaRequestedListener(listener: (() -> Unit)?)
    fun subscribeMediaRoute(listener: (Boolean) -> Unit): AutoCloseable
}

fun com.shilapi.xcertplay.airplay.AudioFormat.isMediaPlayback(): Boolean =
    audioType.equals("media", ignoreCase = true) ||
        (audioType.lowercase() !in listOf("default", "alert", "compatibility", "telephony", "speechrecognition") && payloadType == 102)
