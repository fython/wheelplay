package com.shilapi.xcertplay.projection.desktop

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.Surface
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shizuku constructs this Binder in its shell/root app_process, not an Android Service. */
@Keep
@RequiresApi(29)
class ShizukuDesktopUserService(context: Context) : IDesktopService.Stub() {
    private val hostUid = context.applicationInfo.uid
    private val shellContext = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    private val displays = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply {
        isAccessible = true
    }.newInstance(shellContext)
    private val lock = Any()
    private var token: String? = null
    private var owner: IBinder? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    @Volatile private var lastHeartbeat = SystemClock.elapsedRealtime()
    @Volatile private var used = false
    private val taskDisplayId = Class.forName("android.app.TaskInfo").getField("displayId")
    private val inputClass = Class.forName("android.hardware.input.InputManager")
    private val inputManager = inputClass.getDeclaredMethod("getInstance").invoke(null)
    private val injectMethod = inputClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    private var config: DesktopDisplayConfig? = null
    private var touches: DesktopTouch? = null
    private val death = IBinder.DeathRecipient { terminate() }
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    init {
        check(Process.myUid() == 2000 || Process.myUid() == 0)
        watchdog.scheduleWithFixedDelay({
            val expired = SystemClock.elapsedRealtime() - lastHeartbeat > if (used) 10_000 else 30_000
            // Do not acquire the IPC lock here: a stuck system call must not block the
            // watchdog too. Kernel Binder death destroys the display and its input target.
            if (expired) Process.killProcess(Process.myPid())
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun <T> hostCall(block: () -> T): T {
        check(Binder.getCallingUid() == hostUid) { "Caller does not own this helper" }
        val identity = Binder.clearCallingIdentity()
        try { return synchronized(lock, block) } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun hello(version: Int): Int = hostCall {
        require(version == DesktopProtocol.VERSION && Build.VERSION.SDK_INT >= 29) { "Unsupported desktop protocol or Android version" }
        Process.myUid()
    }

    override fun start(session: String, client: IBinder, target: Surface, component: String,
        width: Int, height: Int, dpi: Int): Int = hostCall {
        check(!used && token == null) { "Helper already used" }
        val requested = DesktopDisplayConfig(width, height, dpi)
        require(session.length in 16..128 && target.isValid)
        val activity = requireNotNull(ComponentName.unflattenFromString(component))
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val info = shellContext.packageManager.resolveActivity(intent, 0)?.activityInfo
        require(info != null && info.exported && info.enabled && info.applicationInfo.enabled) { "Application cannot be launched" }
        used = true; token = session; owner = client; surface = target
        config = requested
        touches = DesktopTouch(requested, ::inject)
        try {
            client.linkToDeath(death, 0)
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                (1 shl 6) or (1 shl 8) // SUPPORTS_TOUCH, DESTROY_CONTENT_ON_REMOVAL; never AUTO_MIRROR or SECURE.
            if (Build.VERSION.SDK_INT >= 33) flags = flags or (1 shl 10) or (1 shl 11) // TRUSTED, OWN_DISPLAY_GROUP
            if (Build.VERSION.SDK_INT >= 34) flags = flags or (1 shl 14) // OWN_FOCUS
            display = requireNotNull(displays.createVirtualDisplay("WheelPlay Android desktop", requested.width,
                requested.height, requested.dpi, target, flags)) { "Virtual displays are unsupported" }
            check(display!!.display.displayId > 0) { "Refusing the default display" }
            check(layoutValid()) { "Unsupported display layout" }
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = display!!.display.displayId }
            startActivity(intent, options.toBundle())
            // Some single-task apps ignore the requested display. Do not report their blank
            // virtual display as a successful launch or send input to the main-screen task.
            val activities = shellContext.getSystemService(ActivityManager::class.java)
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (activities.getRunningTasks(100).none { task ->
                taskDisplayId.getInt(task) == display!!.display.displayId && task.baseActivity?.packageName == activity.packageName
            }) {
                check(SystemClock.elapsedRealtime() < deadline) { "Application did not start on the virtual display" }
                Thread.sleep(50)
            }
            lastHeartbeat = SystemClock.elapsedRealtime()
            display!!.display.displayId
        } catch (failure: Throwable) {
            try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw IllegalStateException("Virtual desktop startup failed: ${failure.message}", failure)
        }
    }

    private fun startActivity(intent: Intent, options: Bundle) {
        // A UserService has no registered application thread. Context.startActivity supplies
        // an invalid caller; call the shell API with a null caller and an explicit host user.
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val method = Class.forName("android.app.IActivityManager").getMethod("startActivityAsUser",
            Class.forName("android.app.IApplicationThread"), String::class.java, Intent::class.java,
            String::class.java, IBinder::class.java, String::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Class.forName("android.app.ProfilerInfo"), Bundle::class.java, Int::class.javaPrimitiveType)
        val result = method.invoke(manager, null, "com.android.shell", intent, null, null, null, 0, 0, null, options, hostUid / 100000) as Int
        check(result in 0..3) { "Application launch rejected ($result)" }
    }

    private fun owned(session: String, layout: Int) {
        check(token == session && layout == DesktopProtocol.LAYOUT && display != null) { "Stale desktop session/layout" }
        if (!layoutValid()) {
            touches?.cancel()
            error("Display layout changed; reconnect the virtual desktop")
        }
    }

    private fun layoutValid(): Boolean {
        val expected = config ?: return false
        val screen = display?.display ?: return false
        if (screen.displayId <= 0 || !screen.isValid || screen.rotation != Surface.ROTATION_0) return false
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") screen.getRealMetrics(metrics)
        return metrics.widthPixels == expected.width && metrics.heightPixels == expected.height &&
            metrics.densityDpi == expected.dpi
    }

    override fun touch(session: String, layout: Int, ids: IntArray, xs: FloatArray, ys: FloatArray, down: BooleanArray) = hostCall {
        owned(session, layout)
        try { checkNotNull(touches).update(ids, xs, ys, down) } catch (failure: Throwable) { runCatching { touches?.cancel() }; throw failure }
    }

    override fun back(session: String, layout: Int) = hostCall {
        owned(session, layout)
        val now = SystemClock.uptimeMillis()
        try { inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)) }
        finally { inject(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)) }
    }

    private fun inject(event: InputEvent) {
        val id = display?.display?.displayId ?: error("No owned display")
        check(id > 0) { "Refusing input to the default display" }
        setDisplay.invoke(event, id) // Failure is fatal: never fall back to display 0.
        check(injectMethod.invoke(inputManager, event, 0) == true) { "Display input injection rejected; check manufacturer permissions" }
    }

    override fun healthy(session: String, layout: Int): Boolean = hostCall {
        owned(session, layout)
        lastHeartbeat = SystemClock.elapsedRealtime()
        true
    }

    override fun stop(session: String) = hostCall {
        check(token == null || token == session) { "Stale desktop session" }
        release()
    }

    private fun release() {
        // Even rejected input must not prevent the display and its tasks from being destroyed.
        runCatching { touches?.cancel() }
        display?.release()
        display = null
        touches = null; config = null
        surface?.release(); surface = null
        owner?.let { runCatching { it.unlinkToDeath(death, 0) } }; owner = null
        token = null
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(caller == hostUid || caller == 0 || caller == 2000)
        Handler(Looper.getMainLooper()).postDelayed({ terminate() }, 100)
    }

    private fun terminate() {
        try { synchronized(lock) { release() } } finally {
            watchdog.shutdownNow()
            // Required by the Shizuku UserService contract. Process death also releases system-owned display tokens.
            Process.killProcess(Process.myPid())
        }
    }
}
