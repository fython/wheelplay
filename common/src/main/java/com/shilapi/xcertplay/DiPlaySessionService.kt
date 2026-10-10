package com.shilapi.xcertplay

import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.WebListenSettings
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.media.CarPlayMediaSessionBridge
import com.shilapi.xcertplay.media.MediaCommand
import com.shilapi.xcertplay.media.NowPlayingState
import android.annotation.SuppressLint
import android.os.PowerManager
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.shilapi.xcertplay.host.R

/** Keeps the connection and its media session alive while no dashboard is visible. */
class DiPlaySessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var media: CarPlayMediaSessionBridge
    private var sessionSubscription: AutoCloseable? = null
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var activationBlocked = false
    private var currentState = NowPlayingState()
    private var currentSession: MediaSession? = null
    private var currentArtwork: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        RootAccess.checkOnStartup()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "CarPlay 服务", NotificationManager.IMPORTANCE_LOW))
        media = CarPlayMediaSessionBridge(this) { state, session, artwork ->
            currentState = state; currentSession = session; currentArtwork = artwork
            if (!destroyed) updateNotification()
        }
        sessionSubscription = CarPlayBackgroundSession.subscribe { _ -> main.post {
            if (!destroyed) {
                val current = CarPlayBackgroundSession.snapshot()
                media.bind(current?.controller, current?.sink)
            }
        } }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    @SuppressLint("WakelockTimeout") // Owned by this foreground service, released in onDestroy.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            media.close()
            runWebAction { WebSession.stop() }
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MEDIA && !currentState.available && !CarPlayBackgroundSession.hasSession()) {
            stopSelf()
            return START_NOT_STICKY
        }
        activationBlocked = false
        updateNotification()
        // Foreground startup must be acknowledged even when an external start is rejected.
        // Never open listeners or acquire a wake lock without a validated identity.
        if (!(DiPlayBootstrap.importing && WebSession.running) &&
            runCatching { DiPlayBootstrap.ensure(this) }.isFailure) {
            activationBlocked = true
            WebSession.stop()
            CarPlayBackgroundSession.stop()
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MEDIA) {
            if (intent.getLongExtra(EXTRA_GENERATION, -1) == media.commandGeneration) {
                intent.getStringExtra(EXTRA_COMMAND)?.let { name ->
                    MediaCommand.entries.firstOrNull { it.name == name }?.let(media::command)
                }
            }
            return START_NOT_STICKY
        }
        runWebAction {
            if (!destroyed) WebSession.start(this)
            updateNotification()
            val acquireWakeLock = {
                if (!destroyed && WebSession.running && wakeLock == null) {
                    wakeLock = getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WheelPlay:server").apply { acquire() }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) acquireWakeLock() else main.post { acquireWakeLock() }
        }
        return START_NOT_STICKY
    }

    private fun runWebAction(action: () -> Unit) {
        // Ordinary Web listeners are unprivileged; Tesla routing has its own worker.
        action()
    }

    private fun mediaAction(command: MediaCommand, icon: Int, title: Int): Notification.Action {
        val intent = Intent(this, DiPlaySessionService::class.java).setAction(ACTION_MEDIA)
            .setData(Uri.parse("wheelplay://media/${media.commandGeneration}/${command.name}"))
            .putExtra(EXTRA_COMMAND, command.name).putExtra(EXTRA_GENERATION, media.commandGeneration)
        val pending = PendingIntent.getService(this, command.ordinal + 10, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(title), pending).build()
    }

    private fun updateNotification() {
        if (destroyed || activationBlocked) return
        val open = PendingIntent.getActivity(this, 0, Intent(this, DiPlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentIntent(open).setOngoing(true)
        val session = currentSession
        if (session != null && currentState.available) {
            builder.setContentTitle(currentState.title?.takeIf { it.isNotBlank() } ?: currentState.appName?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name))
                .setContentText(listOfNotNull(currentState.artist, currentState.album).filter { it.isNotBlank() }.joinToString(" · "))
                .setLargeIcon(currentArtwork).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(mediaAction(MediaCommand.PREVIOUS, R.drawable.ic_media_skip_previous, R.string.notification_action_previous))
                .addAction(mediaAction(if (currentState.playing) MediaCommand.PAUSE else MediaCommand.PLAY,
                    if (currentState.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                    if (currentState.playing) R.string.notification_action_pause else R.string.notification_action_play))
                .addAction(mediaAction(MediaCommand.NEXT, R.drawable.ic_media_skip_next, R.string.notification_action_next))
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        } else {
            val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java)
                .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val port = if (WebSession.running) WebSession.httpPort else WebListenSettings.httpPort(this)
            builder.setContentTitle(getString(R.string.app_name)).setContentText(
                if (WebSession.running) "局域网串流服务运行中 · $port" else "正在准备串流服务…")
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_media_stop),
                    "停止服务", stop).build())
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (currentState.available && currentState.playing) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        destroyed = true
        sessionSubscription?.close()
        media.close()
        runWebAction { WebSession.stop() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        CarPlayBackgroundSession.stop()
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val ACTION_MEDIA = "com.shilapi.xcertplay.MEDIA_COMMAND"
        private const val EXTRA_COMMAND = "command"
        private const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "diplay_connection"
        internal const val NOTIFICATION_ID = 1
    }
}
