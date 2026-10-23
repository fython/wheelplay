package moe.feng.wheelplay.plugin.sample

import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import moe.feng.wheelplay.plugin.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Runs in a separate APK/UID. It has no dependency on WheelPlay's host or browser modules. */
class SampleProjectionService : ProjectionPluginService() {
    override val terminateProcessAfterStop = true
    private var worker: ScheduledExecutorService? = null
    @Volatile private var contacts = emptyList<PluginContact>()
    @Volatile private var night = false
    override fun videoInfo() = PluginVideoInfo(640, 360, 10, nightMode = true)
    override fun onStartProjection(surface: Surface, events: Events) {
        var frame = 0L
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        worker = Executors.newSingleThreadScheduledExecutor().also { executor ->
            executor.scheduleWithFixedDelay({
                try {
                    val canvas = surface.lockCanvas(null)
                    try {
                        canvas.drawColor(if (night) Color.rgb(16, 20, 32) else Color.rgb(25, 70, 100))
                        paint.color = Color.WHITE; paint.textSize = 28f
                        canvas.drawText("Independent APK · Surface plugin", 24f, 48f, paint)
                        paint.textSize = 20f
                        canvas.drawText("Frame $frame · ${contacts.count { it.down }} contacts", 24f, 80f, paint)
                        canvas.drawText("Touch the car browser to draw circles", 24f, 330f, paint)
                        paint.color = Color.CYAN
                        canvas.drawRect((frame % 580).toFloat(), 120f, (frame % 580 + 50).toFloat(), 150f, paint)
                        contacts.filter { it.down }.forEach {
                            canvas.drawCircle((it.x * 639).toFloat(), (it.y * 359).toFloat(), 20f, paint)
                        }
                    } finally { surface.unlockCanvasAndPost(canvas) }
                    if (frame++ == 0L) events.state(ProjectionPlugin.READY, "Separate APK connected")
                } catch (failure: Exception) { events.ended(failure.message ?: "Surface failed") }
            }, 0, 66, TimeUnit.MILLISECONDS)
        }
    }
    override fun onTouch(contacts: List<PluginContact>) { this.contacts = contacts }
    override fun onNightMode(night: Boolean) { this.night = night }
    override fun onStopProjection(): CompletableFuture<Unit> {
        val old = worker
        worker = null
        old?.shutdownNow()
        return CompletableFuture.supplyAsync {
            check(old == null || old.awaitTermination(3, TimeUnit.SECONDS)) { "Surface producer did not stop" }
            contacts = emptyList()
            Unit
        }
    }
}
