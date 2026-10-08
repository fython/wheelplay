package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.PlaybackInfoBuilder
import org.robolectric.shadows.ShadowMediaController
import org.robolectric.shadows.ShadowMediaSession
import org.robolectric.shadows.ShadowTransportControls

/** Robolectric's stock session/controller shadows have no shared Binder state. This test-only
 * adapter records native API calls and links controller queries/callbacks to that session.
 * The Android system service and AVRCP still require device verification. */
@Implements(MediaSession::class)
class RecordingMediaSession : ShadowMediaSession() {
    @RealObject lateinit var real: MediaSession
    var recordedMetadata: MediaMetadata? = null
    var playback: PlaybackState? = null
    var callback: MediaSession.Callback? = null
    var handler: Handler? = null
    var recordedActive = false
    var released = false
    var playbackType = MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
    var attributes: AudioAttributes? = null
    var volume: VolumeProvider? = null
    private fun register() { sessions[real.sessionToken] = this }

    @Implementation fun setMetadata(value: MediaMetadata?) { register(); recordedMetadata = value }
    @Implementation fun setPlaybackState(value: PlaybackState?) { register(); playback = value }
    @Implementation fun setCallback(value: MediaSession.Callback?, handler: Handler?) {
        register(); callback = value; this.handler = handler
    }
    @Implementation fun setActive(value: Boolean) { register(); recordedActive = value }
    @Implementation fun isActive() = recordedActive
    @Implementation fun release() { released = true; recordedActive = false; callback = null }
    @Implementation fun setPlaybackToLocal(value: AudioAttributes) {
        register(); playbackType = MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL; attributes = value; volume = null
    }
    @Implementation fun setPlaybackToRemote(value: VolumeProvider) {
        register(); playbackType = MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE; volume = value
    }
    companion object {
        val sessions = mutableMapOf<MediaSession.Token, RecordingMediaSession>()
        @JvmStatic @Resetter fun clear() { sessions.clear() }
    }
}

@Implements(MediaController::class)
class LinkedMediaController : ShadowMediaController() {
    @RealObject lateinit var real: MediaController
    private lateinit var token: MediaSession.Token
    private val session: RecordingMediaSession get() = RecordingMediaSession.sessions.getValue(token)
    @Implementation public override fun __constructor__(context: Context, token: MediaSession.Token) {
        super.__constructor__(context, token)
        this.token = token
        Shadow.extract<LinkedTransportControls>(real.transportControls).token = token
    }
    @Implementation public override fun getMetadata(): MediaMetadata? = session.recordedMetadata
    @Implementation public override fun getPlaybackState(): PlaybackState? = session.playback
    @Implementation public override fun getPlaybackInfo(): MediaController.PlaybackInfo = PlaybackInfoBuilder.newBuilder()
        .setVolumeType(session.playbackType)
        .setVolumeControl(session.volume?.volumeControl ?: VolumeProvider.VOLUME_CONTROL_ABSOLUTE)
        .setMaxVolume(session.volume?.maxVolume ?: 15).setCurrentVolume(session.volume?.currentVolume ?: 5)
        .setAudioAttributes(session.attributes ?: AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).build()
}

@Implements(MediaController.TransportControls::class)
class LinkedTransportControls : ShadowTransportControls() {
    lateinit var token: MediaSession.Token
    private val session: RecordingMediaSession get() = RecordingMediaSession.sessions.getValue(token)
    private fun dispatch(call: (MediaSession.Callback) -> Unit) {
        val callback = session.callback ?: return
        session.handler?.post { if (!session.released) call(callback) }
    }
    @Implementation public override fun play() = dispatch { it.onPlay() }
    @Implementation public override fun pause() = dispatch { it.onPause() }
    @Implementation public override fun skipToNext() = dispatch { it.onSkipToNext() }
    @Implementation public override fun skipToPrevious() = dispatch { it.onSkipToPrevious() }
    @Implementation public override fun seekTo(position: Long) = dispatch { it.onSeekTo(position) }
}
