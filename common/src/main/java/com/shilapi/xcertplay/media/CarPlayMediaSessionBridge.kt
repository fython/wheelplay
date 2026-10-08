package com.shilapi.xcertplay.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One service-owned session, independent of the dashboard and its surfaces. */
internal class CarPlayMediaSessionBridge(
    context: Context,
    private val changed: (NowPlayingState, MediaSession?, Bitmap?) -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    internal val session = MediaSession(context, "WheelPlay iPhone")
    private val artworkWorker = Executors.newSingleThreadExecutor { Thread(it, "carplay-artwork-preview").apply { isDaemon = true } }
    private val defaultArtwork = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { bitmap ->
        context.applicationInfo.loadIcon(context.packageManager).apply {
            setBounds(0, 0, 128, 128); draw(Canvas(bitmap))
        }
    }
    private var source: CarPlayMediaSource? = null
    private var output: MediaOutputControl? = null
    private var subscription: AutoCloseable? = null
    private var routeSubscription: AutoCloseable? = null
    internal var commandGeneration = 0L
        private set
    private var epoch = 0L
    private var state = NowPlayingState()
    private var remote = false
    private var focused = false
    private var focusBlocked = false
    private var pausePending = false
    private var pauseRequest = 0L
    private var artwork: MediaArtwork? = null
    private var cover: Bitmap? = null
    private var closed = false
    private val localRequestQueued = AtomicBoolean(false)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val fixedVolume = object : VolumeProvider(VOLUME_CONTROL_FIXED, 1, 1) {}
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0 && !remote && !closed) {
                focusBlocked = true
                output?.setLocalMediaAllowed(false)
                abandonFocus()
                source?.sendMediaCommand(MediaCommand.PAUSE, state.connectionGeneration)
            }
            // Gaining focus never restarts the phone. Resumption requires a user command.
        }, main).build()

    init {
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setPlaybackToLocal(attributes)
        installCallbacks()
    }

    private fun installCallbacks() {
        val generation = commandGeneration
        session.setCallback(object : MediaSession.Callback() {
            private fun dispatch(operation: MediaCommand) {
                if (!closed && generation == commandGeneration) command(operation)
            }
            override fun onPlay() = dispatch(MediaCommand.PLAY)
            override fun onPause() = dispatch(MediaCommand.PAUSE)
            override fun onStop() = dispatch(MediaCommand.PAUSE)
            override fun onSkipToNext() = dispatch(MediaCommand.NEXT)
            override fun onSkipToPrevious() = dispatch(MediaCommand.PREVIOUS)
            override fun onSeekTo(pos: Long) {
                if (!closed && generation == commandGeneration && state.canSeek) source?.seekTo(pos)
            }
        }, main)
    }

    fun bind(next: CarPlayMediaSource?, nextOutput: MediaOutputControl?) {
        if (closed || source === next && output === nextOutput) return
        val generation = ++epoch
        ++commandGeneration
        installCallbacks()
        subscription?.close(); routeSubscription?.close()
        output?.setLocalMediaRequestedListener(null)
        output?.setLocalMediaAllowed(false)
        abandonFocus()
        source = next; output = nextOutput
        focusBlocked = false; pausePending = false; ++pauseRequest; remote = false; artwork = null; cover = null
        session.setPlaybackToLocal(attributes)
        apply(NowPlayingState())
        if (next == null) return
        nextOutput?.setLocalMediaRequestedListener {
            if (localRequestQueued.compareAndSet(false, true)) main.post {
                localRequestQueued.set(false)
                if (!closed && epoch == generation && !remote && !pausePending && state.connected &&
                    state.status !in listOf(PhonePlaybackStatus.PAUSED, PhonePlaybackStatus.STOPPED)) requestFocus()
            }
        }
        routeSubscription = nextOutput?.subscribeMediaRoute { isRemote -> main.post {
            if (!closed && epoch == generation && remote != isRemote) {
                remote = isRemote
                if (remote) { output?.setLocalMediaAllowed(false); abandonFocus(); session.setPlaybackToRemote(fixedVolume) }
                else { session.setPlaybackToLocal(attributes); if (state.playing && !pausePending) requestFocus() }
                render()
            }
        } }
        subscription = next.subscribeMediaState { snapshot -> main.post {
            // The source serializes its initial snapshot with subsequent updates.
            if (!closed && epoch == generation) apply(snapshot)
        } }
    }

    fun command(command: MediaCommand) {
        if (closed || !state.available) return
        val current = source?.mediaSnapshot() ?: return
        if (!current.available || current.connectionGeneration != state.connectionGeneration) return
        if (command == MediaCommand.PLAY || command == MediaCommand.TOGGLE && !state.playing) {
            focusBlocked = false; pausePending = false; ++pauseRequest
            if (!remote && !requestFocus()) return
        }
        if (command == MediaCommand.PAUSE || command == MediaCommand.TOGGLE && state.playing) {
            pausePending = true
            val request = ++pauseRequest
            output?.setLocalMediaAllowed(false)
            main.postDelayed({
                if (!closed && request == pauseRequest && pausePending) {
                    pausePending = false
                    if (!remote && state.playing) requestFocus()
                }
            }, 3000)
        }
        source?.sendMediaCommand(command, state.connectionGeneration)
    }

    private fun apply(next: NowPlayingState) {
        val wasPaused = state.status == PhonePlaybackStatus.PAUSED || state.status == PhonePlaybackStatus.STOPPED
        if (state.connectionGeneration != next.connectionGeneration || state.connected && !next.connected) {
            ++commandGeneration
            installCallbacks()
        }
        state = next
        if (!state.connected) {
            focusBlocked = false; pausePending = false; ++pauseRequest; remote = false
            session.setPlaybackToLocal(attributes)
            abandonFocus(); output?.setLocalMediaAllowed(false)
        }
        if (state.artwork !== artwork) {
            artwork = state.artwork; cover = null
            val request = artwork
            val generation = epoch
            if (request != null) artworkWorker.execute {
                val bytes = request.bytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                main.post {
                    if (!closed && generation == epoch && artwork === request) { cover = bitmap; render() }
                }
            }
        }
        render() // Foreground mediaPlayback type precedes a focus request on Android 15+.
        if (!remote) {
            if (state.playing) { if (wasPaused) focusBlocked = false; if (!pausePending) requestFocus() }
            else if (state.status != PhonePlaybackStatus.UNKNOWN) {
                pausePending = false; ++pauseRequest
                output?.setLocalMediaAllowed(false); abandonFocus()
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (remote || output?.isRemoteMediaAvailable() == true) {
            output?.setLocalMediaAllowed(false)
            return true
        }
        if (focusBlocked) return false
        if (!focused) focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        output?.setLocalMediaAllowed(focused)
        if (!focused) { focusBlocked = true; source?.sendMediaCommand(MediaCommand.PAUSE, state.connectionGeneration) }
        return focused
    }
    private fun abandonFocus() {
        if (focused) audio.abandonAudioFocusRequest(focusRequest)
        focused = false
    }

    private fun render() {
        session.isActive = state.available
        if (!state.available) {
            session.setMetadata(null)
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0f).build())
            changed(state, null, null)
            return
        }
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "${epoch}:${state.trackRevision}:${state.persistentId}")
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title?.takeIf { it.isNotBlank() } ?: state.appName)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, state.album)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover ?: defaultArtwork)
            .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, cover ?: defaultArtwork)
        state.durationMs?.takeIf { it > 0 }?.let { metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
        session.setMetadata(metadata.build())
        val playback = when (state.status) {
            PhonePlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            PhonePlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            PhonePlaybackStatus.STOPPED -> PlaybackState.STATE_STOPPED
            PhonePlaybackStatus.SEEK_FORWARD -> PlaybackState.STATE_FAST_FORWARDING
            PhonePlaybackStatus.SEEK_BACKWARD -> PlaybackState.STATE_REWINDING
            PhonePlaybackStatus.UNKNOWN -> PlaybackState.STATE_NONE
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (state.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(playback, state.positionMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN, state.speed, state.positionAtMs).build())
        changed(state, session, cover ?: defaultArtwork)
    }

    override fun close() {
        if (closed) return
        closed = true; ++epoch
        subscription?.close(); routeSubscription?.close()
        output?.setLocalMediaRequestedListener(null); output?.setLocalMediaAllowed(false)
        abandonFocus()
        session.isActive = false; session.setCallback(null); session.release()
        artworkWorker.shutdownNow()
        source = null; output = null
    }
}
