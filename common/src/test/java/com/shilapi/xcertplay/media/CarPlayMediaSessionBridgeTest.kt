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
    @Test fun browserControlsShareNativePauseGatingAndRejectRetiredSources() {
        val source = Source(); val output = Output()
        val bridge = CarPlayMediaSessionBridge(RuntimeEnvironment.getApplication()) { _, _, _ -> }
        try {
            bridge.bind(source, output); source.update(playing().copy(connectionGeneration = 7)); idle()
            assertEquals(MediaCommandResult.UNAVAILABLE, bridge.browserCommand(Source(), 7, MediaCommand.PAUSE).get())
            assertEquals(MediaCommandResult.UNAVAILABLE, bridge.browserCommand(source, 6, MediaCommand.PAUSE).get())
            assertTrue(source.sent.isEmpty())
            assertEquals(MediaCommandResult.SENT, bridge.browserCommand(source, 7, MediaCommand.PAUSE).get())
            assertFalse(output.allowed)
            assertEquals(listOf(MediaCommand.PAUSE), source.sent)
            assertEquals(PlaybackState.STATE_PLAYING, MediaController(RuntimeEnvironment.getApplication(), bridge.session.sessionToken).playbackState!!.state)
            bridge.close()
            assertEquals(MediaCommandResult.UNAVAILABLE, bridge.browserCommand(source, 7, MediaCommand.NEXT).get())
        } finally { bridge.close() }
    }

    @Test fun positionStatusAndOutputRouteChangesDoNotRepublishArtworkMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val source = Source(); val output = Output()
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        try {
            bridge.bind(source, output); source.update(playing()); idle()
            val recorded = org.robolectric.shadow.api.Shadow.extract<RecordingMediaSession>(bridge.session)
            val first = recorded.recordedMetadata
            val publications = recorded.metadataPublications
            repeat(20) {
                source.update(playing().copy(positionMs = 3000L + it, positionAtMs = 2000L + it)); idle()
            }
            source.update(source.state.copy(status = PhonePlaybackStatus.PAUSED)); idle()
            output.route!!(true); idle()
            assertEquals(publications, recorded.metadataPublications)
            assertSame(first, recorded.recordedMetadata)
            assertEquals(3019L, recorded.playback!!.position)
            assertEquals(PlaybackState.STATE_PAUSED, recorded.playback!!.state)
            source.update(source.state.copy(title = "New title")); idle()
            assertEquals(publications + 1, recorded.metadataPublications)
            assertEquals("New title", recorded.recordedMetadata!!.getString(MediaMetadata.METADATA_KEY_TITLE))
            source.update(source.state.copy(durationMs = 200000)); idle()
            assertEquals(publications + 2, recorded.metadataPublications)
        } finally { bridge.close() }
    }

    @Test fun disconnectAndIdenticalSongFromReplacementSourceRepublishMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val old = Source(); val next = Source()
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        try {
            bridge.bind(old, Output()); old.update(playing()); idle()
            val recorded = org.robolectric.shadow.api.Shadow.extract<RecordingMediaSession>(bridge.session)
            val firstId = recorded.recordedMetadata!!.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            old.update(NowPlayingState()); idle()
            assertNull(recorded.recordedMetadata)
            val count = recorded.metadataPublications
            old.update(NowPlayingState()); idle()
            assertEquals(count, recorded.metadataPublications)
            bridge.bind(next, Output()); next.update(playing()); idle()
            assertEquals(count + 1, recorded.metadataPublications)
            assertNotEquals(firstId, recorded.recordedMetadata!!.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
            val afterBind = recorded.metadataPublications
            next.update(playing().copy(connectionGeneration = 1)); idle()
            assertEquals(afterBind + 1, recorded.metadataPublications)
        } finally { bridge.close() }
    }

    @Test fun completedArtworkDecodePublishesOnceAndPositionUpdatesReuseTheBitmap() {
        val context = RuntimeEnvironment.getApplication()
        val source = Source()
        val bridge = CarPlayMediaSessionBridge(context) { _, _, _ -> }
        try {
            bridge.bind(source, Output()); source.update(playing()); idle()
            val recorded = org.robolectric.shadow.api.Shadow.extract<RecordingMediaSession>(bridge.session)
            val fallback = recorded.recordedMetadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
            }
            bitmap.recycle()
            source.update(playing().copy(artwork = MediaArtwork(bytes))); idle()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (recorded.recordedMetadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) === fallback &&
                System.nanoTime() < deadline) {
                Thread.sleep(10); idle()
            }
            val cover = recorded.recordedMetadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            assertNotSame(fallback, cover)
            val count = recorded.metadataPublications
            source.update(source.state.copy(positionMs = 4000)); idle()
            assertEquals(count, recorded.metadataPublications)
            assertSame(cover, recorded.recordedMetadata!!.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        } finally { bridge.close() }
    }

    private class Source : CarPlayMediaSource {
        var state = NowPlayingState()
        val listeners = mutableListOf<(NowPlayingState) -> Unit>()
        val sent = mutableListOf<MediaCommand>()
        val seeks = mutableListOf<Triple<Long, Long?, Long?>>()
        override fun mediaSnapshot() = state
        override fun subscribeMediaState(listener: (NowPlayingState) -> Unit): AutoCloseable {
            listeners.add(listener); listener(state)
            return AutoCloseable { listeners.remove(listener) }
        }
        override fun sendMediaCommand(command: MediaCommand, connectionGeneration: Long?): CompletableFuture<MediaCommandResult> {
            sent.add(command); return CompletableFuture.completedFuture(MediaCommandResult.SENT)
        }
        override fun seekTo(positionMs: Long, connectionGeneration: Long?, trackRevision: Long?): CompletableFuture<MediaCommandResult> {
            seeks.add(Triple(positionMs, connectionGeneration, trackRevision))
            return CompletableFuture.completedFuture(MediaCommandResult.SENT)
        }
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
        assertEquals(PlaybackState.ACTION_SEEK_TO, controller.playbackState!!.actions and PlaybackState.ACTION_SEEK_TO)
        controller.transportControls.pause(); idle()
        assertEquals(listOf(MediaCommand.PAUSE), source.sent)
        assertEquals("Sending pause is not acknowledgement", PlaybackState.STATE_PLAYING, controller.playbackState!!.state)
        source.update(playing().copy(status = PhonePlaybackStatus.PAUSED, positionMs = 2100)); idle()
        assertEquals(0f, controller.playbackState!!.playbackSpeed, 0f)
        controller.transportControls.seekTo(42000); idle()
        assertEquals(listOf(Triple(42000L, 0L, 0L)), source.seeks)
        assertEquals(PlaybackState.STATE_PAUSED, controller.playbackState!!.state)
        assertEquals(listOf(MediaCommand.PAUSE), source.sent)
        assertEquals(MediaCommandResult.UNAVAILABLE, bridge.browserSeek(source, 99, 0, 100).get())
        assertEquals(MediaCommandResult.UNAVAILABLE, bridge.browserSeek(source, 0, 99, 100).get())
        source.update(playing().copy(setElapsedTimeAvailable = false)); idle()
        assertEquals(0L, controller.playbackState!!.actions and PlaybackState.ACTION_SEEK_TO)
        assertEquals(MediaCommandResult.UNSUPPORTED, bridge.browserSeek(source, 0, 0, 100).get())
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
        val identity = moe.feng.wheelplay.SyntheticMfiIdentity.create()
        moe.feng.wheelplay.MfiAssetStore(RuntimeEnvironment.getApplication().noBackupFilesDir)
            .installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        val serviceController = org.robolectric.Robolectric.buildService(moe.feng.wheelplay.WheelPlaySessionService::class.java).create()
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
        assertEquals("Stop service", connection.actions.single().title.toString())
        assertEquals(moe.feng.wheelplay.WheelPlaySessionService.ACTION_STOP,
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
