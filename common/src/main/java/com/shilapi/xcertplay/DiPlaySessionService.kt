package com.shilapi.xcertplay

import com.shilapi.xcertplay.web.WebSession
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
import android.os.Build
import android.os.IBinder
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    @SuppressLint("WakelockTimeout") // Owned by this foreground service, released in onDestroy.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WebSession.stop()
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay 服务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, DiPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("局域网串流服务运行中 · 8080")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止服务", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
        WebSession.start(this)
        if (WebSession.running && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WheelPlay:server").apply { acquire() }
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // A server continues when its dashboard is removed from recents.
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        WebSession.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        CarPlayBackgroundSession.stop()
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        private const val CHANNEL = "diplay_connection"
    }
}
