package moe.feng.wheelplay.plugin.carlink

import android.app.*
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import moe.feng.wheelplay.plugin.*
import java.util.concurrent.CompletableFuture

class CarLinkService : ProjectionPluginService() {
    override val terminateProcessAfterStop = true
    private var session: CarLinkSession? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var encoded = false
    private var connection = CarLinkConnection.P2P
    private var hotspotCredentials: CarLinkSoftAp.Credentials? = null
    private var config = CarLinkConfig()
    override fun videoInfo(): PluginVideoInfo {
        val prefs = settingsCall("config")
        connection = CarLinkConnection.fromKey(prefs.getString("connection"))
        hotspotCredentials = CarLinkSoftAp.credentials(prefs)
        encoded = prefs.getString("videoOutput", "surface") == "webrtc"
        check(!encoded || hostEncodedVideoVersion == ProjectionPlugin.ENCODED_VIDEO_VERSION) {
            "当前 WheelPlay 不支持 WebRTC 插件输出，请更新宿主或选择 Surface。"
        }
        config = CarLinkConfig.forDisplay(requestedDisplaySize, prefs.getString("mode", "balanced")!!,
            prefs.getFloat("scale", 1f), prefs.getInt("dpi", 160))
        settingsCall("display", Bundle().apply { putInt("width", config.width); putInt("height", config.height)
            putInt("requestWidth", requestedDisplaySize?.first ?: 0)
            putInt("requestHeight", requestedDisplaySize?.second ?: 0)
            putInt("activeDpi", config.dpi)
            putString("connection", connection.key)
            putString("videoOutput", if (encoded) "webrtc" else "surface")
            putString("activeMode", prefs.getString("mode", "balanced"))
            putFloat("activeScale", prefs.getFloat("scale", 1f))
        })
        // Single touch until the real multi-touch path has been measured on devices.
        return PluginVideoInfo(config.width, config.height, maxTouchContacts = 1,
            encodedVideoVersion = if (encoded) ProjectionPlugin.ENCODED_VIDEO_VERSION else 0,
            displaySizeVersion = ProjectionPlugin.DISPLAY_SIZE_VERSION, interactionVersion = ProjectionPlugin.INTERACTION_VERSION)
    }
    override fun onDisplaySizeChanged(width: Int, height: Int): Boolean {
        val prefs = settingsCall("config")
        val next = CarLinkConfig.forDisplay(width to height, prefs.getString("mode", "balanced")!!,
            prefs.getFloat("scale", 1f), prefs.getInt("dpi", 160))
        // This SDK negotiates screenInfo on connection, not in startCast. Recreate via the host's
        // shutdown barrier so decoder output, encoded transport and touch mapping change together.
        val restart = next != config
        android.util.Log.i("CarLinkDisplay", "Viewport ${width}x${height}; active ${config.width}x${config.height}; target ${next.width}x${next.height}; restart=$restart")
        return restart
    }
    override fun onStartProjection(surface: Surface, events: Events) = startSession(surface, null, events)
    override fun onStartEncodedProjection(output: ParcelFileDescriptor, events: Events) = startSession(null, output, events)
    private fun startSession(surface: Surface?, output: ParcelFileDescriptor?, events: Events) {
        val problems = CarLinkEnvironment.problems(this, connection)
        check(problems.isEmpty()) { problems.joinToString("; ") }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("carlink", "CarLink projection", NotificationManager.IMPORTANCE_LOW))
        val openSettings = PendingIntent.getActivity(this, 0, Intent(this, CarLinkSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "carlink").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("WheelPlay CarLink").setContentText("配对与投影服务正在运行")
            .setContentIntent(openSettings).setOngoing(true).build())
        worker = HandlerThread("carlink-session").also { it.start() }
        val queue = Handler(worker!!.looper)
        handler = queue
        val scheduler = object : SessionScheduler {
            override fun execute(action: () -> Unit) { queue.post { runCatching(action).onFailure { events.ended(it.message ?: "CarLink callback failed") } } }
            override fun delay(ms: Long, action: () -> Unit): Cancellation {
                val task = Runnable { runCatching(action).onFailure { events.ended(it.message ?: "CarLink timer failed") } }
                queue.postDelayed(task, ms)
                return Cancellation { queue.removeCallbacks(task) }
            }
        }
        val engine = SdkCarLinkEngine(this, surface, output, connection, hotspotCredentials)
        session = CarLinkSession(engine, scheduler, config, { message, pin, ready ->
            settingsCall("update", Bundle().apply {
                putString("message", message); putString("pin", pin)
                putString("phase", session?.phase?.name)
                session?.errorCode?.let { putInt("errorCode", it) }
                putBoolean("active", session?.phase != CarLinkPhase.FAILED); putBoolean("ready", ready)
            })
            val phase = when (session?.phase) {
                CarLinkPhase.PHONE_CONFIRMATION -> "pairing"
                CarLinkPhase.READY -> "ready"
                CarLinkPhase.FAILED -> "failed"
                CarLinkPhase.WAITING_VIDEO -> "waiting-video"
                CarLinkPhase.INITIALIZING -> "preparing"
                else -> "waiting-device"
            }
            events.interaction(moe.feng.wheelplay.plugin.PluginInteraction(phase,
                pin?.takeIf { phase == "pairing" && it.matches(Regex("[0-9]{6}")) }, session?.pairingDevice.orEmpty()))
            events.state(if (ready) ProjectionPlugin.READY else ProjectionPlugin.CONNECTING, message)
        }, { events.ended(it) }, connection)
        queue.post { session?.start() }
    }
    override fun onTouch(contacts: List<PluginContact>) { handler?.post { session?.contacts(contacts) } }
    override fun onStopProjection(): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        val queue = handler
        if (queue == null) return CompletableFuture.completedFuture(Unit)
        queue.post {
            val current = session
            session = null
            (current?.stop() ?: CompletableFuture.completedFuture(Unit)).whenComplete { _, failure ->
                runCatching {
                    settingsCall("update", Bundle().apply {
                        if (failure == null && current?.phase == CarLinkPhase.FAILED) {
                            putAll(settingsCall("status"))
                        } else putString("message", if (failure == null) "投影已停止，连接资源已释放" else "连接清理失败，请按故障恢复说明断开旧连接并重启 WheelPlay：${failure.message}")
                        putString("phase", if (failure != null || current?.phase == CarLinkPhase.FAILED) CarLinkPhase.FAILED.name else CarLinkPhase.STOPPED.name)
                        putBoolean("ready", false)
                        putBoolean("active", false)
                    })
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
                worker?.quitSafely()
            }
        }
        return result
    }
}
