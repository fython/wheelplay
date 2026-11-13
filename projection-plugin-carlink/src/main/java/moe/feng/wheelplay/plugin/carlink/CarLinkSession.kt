package moe.feng.wheelplay.plugin.carlink

import moe.feng.wheelplay.plugin.PluginContact
import java.util.concurrent.CompletableFuture

internal data class CarLinkConfig(val width: Int = 1280, val height: Int = 720, val dpi: Int = 160) {
    init {
        require(width in 320..4096 && height in 240..4096 && width.toLong() * height <= 8_388_608)
        require(dpi in 120..480)
    }
    companion object {
        fun forDisplay(size: Pair<Int, Int>?, mode: String = "balanced", scale: Float = 1f, dpi: Int = 160): CarLinkConfig {
            val (w, h) = size?.takeIf { it.first > 0 && it.second > 0 } ?: (1280 to 720)
            val maxW = if (mode == "balanced") 1920 else 4096
            val maxH = if (mode == "balanced") 1080 else 4096
            val ratio = (w.toDouble() / h).coerceIn(320.0 / maxH, maxW / 240.0)
            val multiplier = if (mode == "custom") scale.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f else 1f
            val limitH = minOf(maxH.toDouble(), maxW / ratio, kotlin.math.sqrt(8_388_608.0 / ratio))
            val targetHeight = (h * multiplier.toDouble()).coerceIn(maxOf(240.0, 320 / ratio), limitH)
            fun even(value: Double) = (value.toInt() / 2) * 2
            return CarLinkConfig(even(targetHeight * ratio).coerceIn(320, maxW), even(targetHeight).coerceIn(240, maxH), dpi.coerceIn(120, 480))
        }
    }
}

internal interface CarLinkEngine {
    interface Listener {
        fun initialized()
        fun failed(message: String)
        fun connection(state: Int, parameter: Int)
        fun pairing(pin: String, car: String)
        fun surfaceReady()
        fun progress(percent: Int) = Unit
        fun usbPermissionWaiting(waiting: Boolean) = Unit
    }
    fun initialize(config: CarLinkConfig, listener: Listener)
    fun advertise(): Boolean
    fun refreshAdvertise(): Boolean
    fun startCast(): Boolean
    fun touch(event: TouchEvent): Boolean
    fun stop(): CompletableFuture<Unit>
}

internal fun interface Cancellation { fun cancel() }
internal interface SessionScheduler {
    fun execute(action: () -> Unit)
    fun delay(ms: Long, action: () -> Unit): Cancellation
}

/** All state is confined to the supplied scheduler. SDK callbacks only enqueue work. */
internal class CarLinkSession(
    private val engine: CarLinkEngine,
    private val scheduler: SessionScheduler,
    private val config: CarLinkConfig,
    private val publishStatus: (String, String?, Boolean) -> Unit,
    private val ended: (String) -> Unit,
    private val connection: CarLinkConnection = CarLinkConnection.P2P,
) {
    var pairingDevice = ""
        private set
    var phase = CarLinkPhase.IDLE
        private set
    var errorCode: Int? = null
        private set
    private fun status(message: String, pin: String?, ready: Boolean, next: CarLinkPhase = phase) {
        phase = next
        publishStatus(message, pin, ready)
    }
    private var active = false
    private var started = false
    private var connected = false
    private var channelReady = false
    private var rendered = false
    private var castBound = false
    private var castAttempts = 0
    private var failures = 0
    private var refresh: Cancellation? = null
    private var retry: Cancellation? = null
    private var deadline: Cancellation? = null
    private var stopping: CompletableFuture<Unit>? = null
    private val touch = TouchTranslator(config.width, config.height)

    fun start() {
        check(!started) { "A session cannot be restarted" }
        started = true
        active = true
        status("正在初始化 CarLink", null, false, CarLinkPhase.INITIALIZING)
        deadline = scheduler.delay(55_000) { fail(if (connection == CarLinkConnection.USB)
            "USB 连接超时。请断开 USB 线后重新插入，解锁发送手机并确认车联提示，再从 WheelPlay 连接。"
            else "配对超时。请检查蓝牙、Wi-Fi、定位和权限，在发送手机的车联功能中重新选择接收设备，再从 WheelPlay 连接。") }
        try {
            engine.initialize(config, object : CarLinkEngine.Listener {
                private fun post(action: () -> Unit) = scheduler.execute { if (active) action() }
                override fun initialized() = post {
                    if (!engine.advertise()) fail(if (connection.wireless) "蓝牙广播失败，请检查蓝牙和附近设备权限后重新连接" else "USB 检测失败，请检查 OTG 和数据线后重新连接")
                    else {
                        status(if (connection.wireless) "等待发送手机连接，请打开手机的车联功能" else "请通过 USB / OTG 连接手机，并允许 USB 访问", null, false, CarLinkPhase.WAITING_DEVICE)
                        scheduleRefresh()
                    }
                }
                override fun failed(message: String) = post { fail(message) }
                override fun usbPermissionWaiting(waiting: Boolean) = post {
                    if (!connected) status(if (waiting) "请在接收手机的系统对话框中允许 USB 访问" else "USB 已授权，请在发送手机确认车联提示", null, false,
                        if (waiting) CarLinkPhase.USB_PERMISSION else CarLinkPhase.PHONE_CONFIRMATION)
                }
                override fun progress(percent: Int) = post {
                    if (connection == CarLinkConnection.USB && !connected && percent in 20..39) {
                        status("USB 配件已连接，等待发送手机响应；请在发送手机上确认车联提示。", null, false, CarLinkPhase.PHONE_CONFIRMATION)
                    }
                }
                override fun pairing(pin: String, car: String) = post {
                    pairingDevice = car.filterNot { it.isISOControl() }.take(120)
                    if (!connected) { status("请在发送手机上选择 $car 并确认配对码", pin.take(32), false, CarLinkPhase.PHONE_CONFIRMATION); scheduleRefresh() }
                }
                override fun surfaceReady() = post { rendered = true; reportReady() }
                override fun connection(state: Int, parameter: Int) = post { onConnection(state, parameter) }
            })
        } catch (failure: Throwable) { fail(failure.message ?: "CarLink 初始化失败，请停止投影后重新连接") }
    }

    private fun onConnection(state: Int, parameter: Int) {
        when (state) {
            1 -> fail("发送手机已断开，请从 WheelPlay 重新连接")
            2 -> {
                connected = true; failures = 0; errorCode = null; refresh?.cancel()
                // The SDK can deliver CONNECTED after WFD/frame readiness on reconnect.
                // Do not revoke host input for an already rendered session.
                if (channelReady && rendered) reportReady()
                else status("手机已连接，正在等待画面", null, false, CarLinkPhase.WAITING_VIDEO)
                tryCast()
            }
            3 -> {
                errorCode = parameter
                if (connection.wireless && !connected && parameter in setOf(10003, 10006, 10008) && failures < 3) {
                    failures++
                    retry?.cancel()
                    status("无线连接失败（$parameter），正在重试", null, false)
                    refresh?.cancel()
                    retry = scheduler.delay(retryDelay(failures)) {
                        if (active && !connected) {
                            if (!engine.refreshAdvertise()) fail("无法重新启动蓝牙广播，请检查蓝牙后重新连接") else scheduleRefresh()
                        }
                    }
                } else fail(if (connection == CarLinkConnection.USB && parameter == 10007)
                    "USB 发送端响应超时（10007）。请解锁发送手机、确认车联提示，并重新插拔数据线后连接。"
                    else "CarLink 连接失败（$parameter），请检查连接方式和手机端提示后重试")
            }
            4 -> { connected = true; channelReady = true; errorCode = null; refresh?.cancel();
                if (!rendered) status("视频通道已连接，正在等待首帧", null, false, CarLinkPhase.WAITING_VIDEO)
                tryCast(); reportReady() }
            5 -> fail("视频通道已停止，请重新连接")
            // Phone desktop/foreground notifications do not terminate a projection.
            6, 7 -> Unit
        }
    }

    private fun tryCast() {
        if (!active || castBound) return
        retry?.cancel()
        castAttempts++
        try { castBound = engine.startCast() } catch (failure: Throwable) { fail(failure.message ?: "无法连接视频输出 Surface，请重新连接"); return }
        if (!castBound) {
            if (castAttempts >= 20) fail("SDK 未接受视频输出 Surface，请停止投影后重试")
            else retry = scheduler.delay(500) { tryCast() }
        }
    }

    private fun reportReady() {
        if (active && channelReady && rendered) {
            deadline?.cancel()
            status("CarLink 画面已就绪", null, true, CarLinkPhase.READY)
        }
    }

    private fun scheduleRefresh() {
        refresh?.cancel()
        if (!connection.wireless) return
        refresh = scheduler.delay(30_000) {
            if (active && !connected) {
                status("正在刷新设备发现，请在发送手机上重新选择接收设备", null, false)
                if (!engine.refreshAdvertise()) fail("无法重新启动蓝牙广播，请检查蓝牙后重新连接") else scheduleRefresh()
            }
        }
    }

    fun contacts(contacts: List<PluginContact>) {
        if (!active || !rendered || !channelReady) return
        try {
            touch.translate(contacts).forEach { check(engine.touch(it)) { "CarLink rejected touch input" } }
        } catch (failure: Exception) { fail(failure.message ?: "触控发送失败，请重新连接") }
    }

    private fun fail(message: String) {
        if (!active) return
        active = false
        cancelTimers()
        status(message, null, false, CarLinkPhase.FAILED)
        ended(message)
    }

    private fun cancelTimers() { deadline?.cancel(); refresh?.cancel(); retry?.cancel() }

    fun stop(): CompletableFuture<Unit> {
        stopping?.let { return it }
        active = false
        cancelTimers()
        // Empty snapshots are real UP transitions. Never claim native CANCEL support.
        runCatching { touch.translate(emptyList()).forEach { engine.touch(it) } }
        return try { engine.stop() } catch (failure: Throwable) {
            CompletableFuture<Unit>().also { it.completeExceptionally(failure) }
        }.also { stopping = it }
    }

    companion object {
        fun retryDelay(attempt: Int): Long = (1_200L shl (attempt.coerceIn(1, 4) - 1)).coerceAtMost(8_000)
    }
}

internal data class TouchEvent(val action: Int, val ids: IntArray, val xs: IntArray, val ys: IntArray)

/** Stable IDs, indexed Android pointer actions, and final coordinates on UP. */
internal class TouchTranslator(private val width: Int, private val height: Int) {
    private val current = linkedMapOf<Int, PluginContact>()
    fun translate(snapshot: List<PluginContact>): List<TouchEvent> {
        require(moe.feng.wheelplay.plugin.validPluginContacts(snapshot, 10))
        val incoming = snapshot.associateBy { it.id }
        val target = snapshot.filter { it.down }.associateBy { it.id }
        val events = mutableListOf<TouchEvent>()
        // Update coordinates before releasing: an UP may carry a final location.
        current.keys.toList().forEach { id -> incoming[id]?.let { current[id] = it } }
        current.keys.toList().filter { it !in target }.forEach { id ->
            val index = current.keys.indexOf(id)
            events += event(if (current.size == 1) 1 else 6 or (index shl 8))
            current.remove(id)
        }
        target.forEach { (id, contact) ->
            if (id !in current) {
                current[id] = contact
                events += event(if (current.size == 1) 0 else 5 or (current.keys.indexOf(id) shl 8))
            }
        }
        if (current.isNotEmpty() && events.isEmpty()) events += event(2)
        return events
    }
    private fun event(action: Int): TouchEvent = TouchEvent(action, current.keys.toIntArray(),
        current.values.map { (it.x * (width - 1)).toInt() }.toIntArray(),
        current.values.map { (it.y * (height - 1)).toInt() }.toIntArray())
}
