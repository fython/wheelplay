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
import android.util.Log
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
    @Volatile private var token: String? = null
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
    private var negotiated: DesktopServiceInfo? = null
    private var config: DesktopDisplayConfig? = null
    private var touches: DesktopTouch? = null
    @Volatile private var touchQueue: DesktopTouchQueue? = null
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
        desktopCheck(Binder.getCallingUid() == hostUid, DesktopErrorCode.PERMISSION_DENIED) { "Caller does not own this helper" }
        val identity = Binder.clearCallingIdentity()
        try { return synchronized(lock, block) } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun statusCall(block: () -> Unit) = desktopServiceStatus { hostCall(block) }

    override fun hello(version: Int, hostFeatures: Long): DesktopHandshakeResult = DesktopHandshakeResult().apply {
        status = statusCall {
            desktopCheck(negotiated == null && !used, DesktopErrorCode.INVALID_STATE) { "Handshake already completed" }
            desktopCheck(Build.VERSION.SDK_INT >= 29, DesktopErrorCode.UNSUPPORTED_CAPABILITY) { "Android 10 or later required" }
            info = DesktopNegotiation.negotiate(version = version, requested = hostFeatures, helperUid = Process.myUid())
                .also { negotiated = it }
        }
    }

    private fun requireFeature(feature: Long) {
        desktopCheck(negotiated?.enabledFeatures?.let { it and feature == feature } == true,
            DesktopErrorCode.UNSUPPORTED_CAPABILITY) { "Operation was not negotiated: $feature" }
    }

    override fun start(session: String, client: IBinder, target: Surface, component: String,
        width: Int, height: Int, dpi: Int): DesktopStartResult = DesktopStartResult().apply {
        status = desktopServiceStatus {
            displayId = startDisplay(session, client, target, component, width, height, dpi)
        }
    }

    private fun startDisplay(session: String, client: IBinder, target: Surface, component: String,
        width: Int, height: Int, dpi: Int): Int = hostCall {
        desktopCheck(negotiated != null && !used && token == null, DesktopErrorCode.INVALID_STATE) { "Handshake required; helper may only start once" }
        val capabilities = checkNotNull(negotiated)
        val requested = DesktopDisplayConfig(width = width, height = height, dpi = dpi)
        require(session.length in 16..128 && target.isValid)
        val activity = requireNotNull(ComponentName.unflattenFromString(component))
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val info = shellContext.packageManager.resolveActivity(intent, 0)?.activityInfo
        desktopCheck(info?.isDesktopLaunchable == true, DesktopErrorCode.APP_UNAVAILABLE) { "Application cannot be launched" }
        used = true; token = session; owner = client; surface = target
        config = requested
        val touchInput = DesktopTouch(requested, ::inject).also { touches = it }
        try {
            client.linkToDeath(death, 0)
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                (1 shl 6) or (1 shl 8) // SUPPORTS_TOUCH, DESTROY_CONTENT_ON_REMOVAL; never AUTO_MIRROR or SECURE.
            if (Build.VERSION.SDK_INT >= 33) flags = flags or (1 shl 10) or (1 shl 11) // TRUSTED, OWN_DISPLAY_GROUP
            if (Build.VERSION.SDK_INT >= 34) flags = flags or (1 shl 14) // OWN_FOCUS
            val virtualDisplay = desktopOperation(DesktopErrorCode.DISPLAY_FAILED) {
                requireNotNull(displays.createVirtualDisplay("WheelPlay Android desktop", requested.width,
                    requested.height, requested.dpi, target, flags)) { "Virtual displays are unsupported" }
            }.also { display = it }
            val displayId = virtualDisplay.display.displayId
            desktopCheck(displayId > 0, DesktopErrorCode.DISPLAY_FAILED) { "Refusing the default display" }
            desktopCheck(layoutValid(), DesktopErrorCode.LAYOUT_CHANGED) { "Unsupported display layout" }
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
            desktopOperation(DesktopErrorCode.LAUNCH_FAILED) { startActivity(intent, options.toBundle()) }
            // Some single-task apps ignore the requested display. Do not report their blank
            // virtual display as a successful launch or send input to the main-screen task.
            val activities = shellContext.getSystemService(ActivityManager::class.java)
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (activities.getRunningTasks(100).none { task ->
                taskDisplayId.getInt(task) == displayId && task.baseActivity?.packageName == activity.packageName
            }) {
                desktopCheck(SystemClock.elapsedRealtime() < deadline, DesktopErrorCode.LAUNCH_FAILED) { "Application did not start on the virtual display" }
                Thread.sleep(50)
            }
            lastHeartbeat = SystemClock.elapsedRealtime()
            if (capabilities.enabledFeatures and DesktopFeatures.TOUCH != 0L) {
                touchQueue = DesktopTouchQueue(
                    process = { frame -> synchronized(lock) {
                        // A synchronous stop can overtake pending oneway calls. Never inject after release.
                        if (token == session && display != null) {
                            owned(session, frame.layout)
                            requireFeature(DesktopFeatures.TOUCH)
                            require(frame.ids.size <= capabilities.maxTouchContacts)
                            touchInput.update(frame.ids, frame.xs, frame.ys, frame.down)
                        }
                    } },
                    cancel = { synchronized(lock) { if (token == session && display != null) touchInput.cancel() } },
                    diagnose = { Log.w("ShizukuDesktop", "Touch error ${it.code}: ${it.diagnostic}", it) },
                    operationLock = lock,
                )
            }
            displayId
        } catch (failure: Throwable) {
            try { release() } catch (cleanup: Throwable) { cleanup.addSuppressed(failure); throw cleanup }
            throw failure
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
        desktopCheck(token == session && layout == DesktopProtocol.LAYOUT && display != null, DesktopErrorCode.STALE_SESSION) { "Stale desktop session/layout" }
        if (!layoutValid()) {
            runCatching { touches?.cancel() }
            throw DesktopException(DesktopErrorCode.LAYOUT_CHANGED, "Display layout changed; reconnect the virtual desktop")
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

    override fun touch(session: String, frame: DesktopTouchFrame) {
        // Only enqueue here: no privileged operation, display lock or synchronous reply on the Binder thread.
        if (Binder.getCallingUid() != hostUid || token != session || frame.layout != DesktopProtocol.LAYOUT) return
        touchQueue?.offer(frame)
    }

    override fun back(session: String, layout: Int) = statusCall {
        owned(session, layout)
        requireFeature(DesktopFeatures.BACK)
        val now = SystemClock.uptimeMillis()
        try { inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)) }
        finally { inject(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)) }
    }

    private fun inject(event: InputEvent) = desktopOperation(DesktopErrorCode.INPUT_FAILED) {
        val id = display?.display?.displayId ?: error("No owned display")
        check(id > 0) { "Refusing input to the default display" }
        setDisplay.invoke(event, id) // Failure is fatal: never fall back to display 0.
        check(injectMethod.invoke(inputManager, event, 0) == true) { "Display input injection rejected; check manufacturer permissions" }
    }

    override fun healthy(session: String, layout: Int) = statusCall {
        owned(session, layout)
        touchQueue?.fault?.let { throw it }
        lastHeartbeat = SystemClock.elapsedRealtime()
    }

    override fun stop(session: String) = statusCall {
        desktopCheck(token == null || token == session, DesktopErrorCode.STALE_SESSION) { "Stale desktop session" }
        release()
    }

    private fun release() = desktopOperation(DesktopErrorCode.CLEANUP_FAILED) {
        // Even rejected input must not prevent the display and its tasks from being destroyed.
        touchQueue?.close(); touchQueue = null
        runCatching { touches?.cancel() }
        display?.release()
        display = null
        touches = null; config = null
        surface?.release(); surface = null
        owner?.let { runCatching { it.unlinkToDeath(death, 0) } }; owner = null
        token = null
    }

    override fun destroy() {
        // Shizuku's reserved transaction has no application result payload.
        val caller = Binder.getCallingUid()
        if (caller != hostUid && caller != 0 && caller != 2000) throw SecurityException("Caller cannot destroy this helper")
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
