package com.shilapi.xcertplay.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [RecordingMediaSession::class, LinkedMediaController::class, LinkedTransportControls::class])
class CarPlayMediaSessionBridgeTest {
    private class Source : CarPlayMediaSource {
        var state = NowPlayingState()
        val listeners = mutableListOf<(NowPlayingState) -> Unit>()
        val sent = mutableListOf<MediaCommand>()
        override fun mediaSnapshot() = state
        override fun subscribeMediaState(listener: (NowPlayingState) -> Unit): AutoCloseable {
            listeners.add(listener); listener(state)
            return AutoCloseable { listeners.remove(listener) }
        }
        override fun sendMediaCommand(command: MediaCommand, connectionGeneration: Long?): CompletableFuture<MediaCommandResult> {
            sent.add(command); return CompletableFuture.completedFuture(MediaCommandResult.SENT)
        }
        override fun seekTo(positionMs: Long) = CompletableFuture.completedFuture(MediaCommandResult.UNSUPPORTED)
        fun update(next: NowPlayingState) { state = next; listeners.toList().forEach { it(next) } }
    }
    private class Output : MediaOutputControl {
        var allowed = false
        var remoteReady = false
        override fun isRemoteMediaAvailable() = remoteReady
        var request: (() -> Unit)? = null
        var route: ((Boolean) -> Unit)? = null
        override fun setLocalMediaAllowed(allowed: Boolean) { this.allowed = allowed }
        override fun setLocalMediaRequestedListener(listener: (() -> Unit)?) { request = listener }
        override fun subscribeMediaRoute(listener: (Boolean) -> Unit): AutoCloseable {
            route = listener; listener(false)
            return AutoCloseable { route = null }
        }
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun playing() = NowPlayingState(connected = true, title = "Song", artist = "Artist", album = "Album",
        durationMs = 100000, positionMs = 2000, positionAtMs = 1234, status = PhonePlaybackStatus.PLAYING,
        setElapsedTimeAvailable = true)

    @Test fun nativeControllerReceivesMetadataStateAndOnlyVerifiedActions() {
        val context: Context = RuntimeEnvironment.getApplication()
        val source = Source(); val output = Output()
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        bridge.bind(source, output); source.update(playing()); idle()
        val controller = MediaController(context, bridge.session.sessionToken)
        assertEquals("Song", controller.metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Artist", controller.metadata!!.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals(100000L, controller.metadata!!.getLong(MediaMetadata.METADATA_KEY_DURATION))
        assertEquals(PlaybackState.STATE_PLAYING, controller.playbackState!!.state)
        assertEquals(2000L, controller.playbackState!!.position)
        assertEquals(1234L, controller.playbackState!!.lastPositionUpdateTime)
        assertEquals(0L, controller.playbackState!!.actions and PlaybackState.ACTION_SEEK_TO)
        controller.transportControls.pause(); idle()
        assertEquals(listOf(MediaCommand.PAUSE), source.sent)
        assertEquals("Sending pause is not acknowledgement", PlaybackState.STATE_PLAYING, controller.playbackState!!.state)
        source.update(playing().copy(status = PhonePlaybackStatus.PAUSED, positionMs = 2100)); idle()
        assertEquals(0f, controller.playbackState!!.playbackSpeed, 0f)
        bridge.close()
        assertTrue(source.listeners.isEmpty()); assertNull(output.request)
    }

    @Test fun remotePlaybackRetainsControlsAndReturnsToLocalAfterDetach() {
        val context: Context = RuntimeEnvironment.getApplication()
        val source = Source(); val output = Output()
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        bridge.bind(source, output); source.update(playing()); idle()
        val controller = MediaController(context, bridge.session.sessionToken)
        output.route!!(true); idle()
        assertTrue(bridge.session.isActive)
        assertEquals(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE, controller.playbackInfo.playbackType)
        assertEquals(android.media.VolumeProvider.VOLUME_CONTROL_FIXED, controller.playbackInfo.volumeControl)
        controller.transportControls.skipToNext(); idle()
        assertEquals(MediaCommand.NEXT, source.sent.last())
        output.route!!(false); idle()
        assertEquals(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL, controller.playbackInfo.playbackType)
        bridge.close()
    }

    @Test fun disconnectAndSourceReplacementIgnoreAlreadyQueuedCallbacks() {
        val context: Context = RuntimeEnvironment.getApplication()
        val old = Source(); val next = Source(); val output = Output()
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        bridge.bind(old, output); old.update(playing()) // Still queued on main.
        bridge.bind(next, output); idle()
        assertFalse(bridge.session.isActive)
        assertTrue(old.listeners.isEmpty())
        next.update(playing().copy(title = "New phone")); idle()
        val controller = MediaController(context, bridge.session.sessionToken)
        assertEquals("New phone", controller.metadata!!.getString(MediaMetadata.METADATA_KEY_TITLE))
        val generation = bridge.commandGeneration
        next.update(NowPlayingState()); idle()
        assertFalse(bridge.session.isActive)
        assertTrue(bridge.commandGeneration > generation)
        assertNull(controller.metadata)
        assertEquals(PlaybackState.STATE_NONE, controller.playbackState!!.state)
        bridge.command(MediaCommand.PLAY)
        assertTrue(next.sent.isEmpty())
        bridge.close()
    }
    @Test fun focusLossPausesThePhoneAndNeverAutoResumesOnGain() {
        val context: Context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val shadow = shadowOf(audio)
        val source = Source(); val output = Output()
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        bridge.bind(source, output); source.update(playing()); idle()
        assertTrue(output.allowed)
        val focus = shadow.lastAudioFocusRequest.listener
        focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(output.allowed); assertEquals(listOf(MediaCommand.PAUSE), source.sent)
        focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        output.request!!(); idle()
        assertFalse(output.allowed); assertEquals(1, source.sent.size)
        bridge.command(MediaCommand.PLAY)
        assertTrue(output.allowed); assertEquals(MediaCommand.PLAY, source.sent.last())
        bridge.close()
        assertNotNull(shadow.lastAbandonedAudioFocusRequest)
    }

    @Test fun browserPlaybackDoesNotRequestLocalFocus() {
        val context: Context = RuntimeEnvironment.getApplication()
        val source = Source(); val output = Output()
        val audio = shadowOf(context.getSystemService(android.media.AudioManager::class.java))
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        bridge.bind(source, output); idle()
        output.route!!(true); idle()
        source.update(playing()); idle()
        assertNull(audio.lastAudioFocusRequest)
        assertFalse(output.allowed)
        bridge.command(MediaCommand.PLAY)
        assertEquals(listOf(MediaCommand.PLAY), source.sent)
        assertNull(audio.lastAudioFocusRequest)
        bridge.close()
    }

    @Test fun serviceUsesMediaStyleAndRestoresConnectionNotificationOnDisconnect() {
        val identity = com.shilapi.xcertplay.SyntheticMfiIdentity.create()
        com.shilapi.xcertplay.MfiAssetStore(RuntimeEnvironment.getApplication().noBackupFilesDir)
            .installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        val serviceController = org.robolectric.Robolectric.buildService(com.shilapi.xcertplay.DiPlaySessionService::class.java).create()
        val service = serviceController.get(); idle()
        val bridge = org.robolectric.util.ReflectionHelpers.getField<CarPlayMediaSessionBridge>(service, "media")
        val source = Source(); val output = Output()
        bridge.bind(source, output); source.update(playing()); idle()
        val notification = shadowOf(service).lastForegroundNotification
        assertEquals("android.app.Notification\$MediaStyle", notification.extras.getString(android.app.Notification.EXTRA_TEMPLATE))
        assertNotNull(notification.extras.getParcelable<android.os.Parcelable>(android.app.Notification.EXTRA_MEDIA_SESSION))
        assertEquals("Song", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(3, notification.actions.size)
        assertEquals(listOf("Previous track", "Pause", "Next track"), notification.actions.map { it.title.toString() })
        assertTrue(service.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
        service.onStartCommand(shadowOf(notification.actions[1].actionIntent).savedIntent, 0, 1)
        assertEquals(listOf(MediaCommand.PAUSE), source.sent)
        assertFalse(output.allowed)
        output.request!!(); idle()
        assertFalse("Pause request must not reopen local output before its response", output.allowed)
        source.update(playing().copy(status = PhonePlaybackStatus.PAUSED)); idle()
        assertEquals(0, service.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        assertEquals(listOf("Previous track", "Play", "Next track"),
            shadowOf(service).lastForegroundNotification.actions.map { it.title.toString() })
        source.update(NowPlayingState()); idle()
        val connection = shadowOf(service).lastForegroundNotification
        assertNull(connection.extras.getString(android.app.Notification.EXTRA_TEMPLATE))
        assertEquals(1, connection.actions.size)
        assertEquals("停止服务", connection.actions.single().title.toString())
        assertEquals(com.shilapi.xcertplay.DiPlaySessionService.ACTION_STOP,
            shadowOf(connection.actions.single().actionIntent).savedIntent.action)
        serviceController.destroy()
        assertFalse(bridge.session.isActive)
        assertTrue(org.robolectric.shadow.api.Shadow.extract<RecordingMediaSession>(bridge.session).released)
        assertTrue(source.listeners.isEmpty())
    }

    @Test fun browserReadinessPreventsFocusEvenBeforeFirstRemotePcmArrives() {
        val context: Context = RuntimeEnvironment.getApplication()
        val source = Source(); val output = Output().apply { remoteReady = true }
        val audio = shadowOf(context.getSystemService(android.media.AudioManager::class.java))
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        bridge.bind(source, output); source.update(playing()); idle()
        assertNull(audio.lastAudioFocusRequest)
        assertFalse(output.allowed)
        output.route!!(true); idle()
        val controller = MediaController(context, bridge.session.sessionToken)
        assertEquals(MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE, controller.playbackInfo.playbackType)
        bridge.close()
    }

    @Test fun callbacksFromAnOldConnectionCannotControlTheReconnectedPhone() {
        val context: Context = RuntimeEnvironment.getApplication()
        val source = Source(); val output = Output()
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        bridge.bind(source, output); source.update(playing().copy(connectionGeneration = 1)); idle()
        val oldCallback = org.robolectric.shadow.api.Shadow.extract<RecordingMediaSession>(bridge.session).callback!!
        source.update(NowPlayingState(connectionGeneration = 2))
        source.update(playing().copy(connectionGeneration = 3)); idle()
        oldCallback.onSkipToNext()
        assertTrue(source.sent.isEmpty())
        MediaController(context, bridge.session.sessionToken).transportControls.skipToNext(); idle()
        assertEquals(listOf(MediaCommand.NEXT), source.sent)
        bridge.close()
    }

}
