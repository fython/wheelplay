package moe.feng.wheelplay.plugin.carlink

import android.os.Handler
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.feng.wheelplay.plugin.EncodedVideoWire
import java.io.DataOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Adapter for the pinned SDK only. Its callback supplies complete Annex-B access units.
 * Retains connection/foreground/UIBC behavior, but never calls the SDK video decoder.
 */
internal class EncodedCarLinkVideo(
    descriptor: ParcelFileDescriptor,
    private val ready: () -> Unit,
    private val format: (Int, Int) -> Unit,
    private val fail: (String) -> Unit,
) : AutoCloseable {
    private data class Packet(val frame: EncodedVideoWire.Frame?, val width: Int = 0, val height: Int = 0)
    private val queue = ArrayBlockingQueue<Packet>(128)
    private val queuedBytes = AtomicLong()
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val output = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(descriptor))
    private var cast: Any? = null
    private val callbackLock = Any()
    private val timeline = CarLinkVideoTimeline()
    private var sequence = 0L
    private var dimensions: Pair<Int, Int>? = null
    private fun failure(message: String) { if (!closed.get() && failed.compareAndSet(false, true)) fail(message) }
    private val writer = Thread({
        try {
            while (!closed.get()) {
                val packet = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val frame = packet.frame
                    if (frame == null) EncodedVideoWire.writeFormat(output, packet.width, packet.height)
                    else EncodedVideoWire.writeFrame(output, frame)
                    output.flush()
                } finally { queuedBytes.addAndGet(-(packet.frame?.bytes?.size ?: 0).toLong()) }
            }
        } catch (error: Exception) { failure("WebRTC 视频通道中断：${error.message}") }
    }, "carlink-encoded-writer").apply { isDaemon = true; start() }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun offer(packet: Packet) {
        if (closed.get() || failed.get()) return
        val bytes = packet.frame?.bytes?.size ?: 0
        val total = queuedBytes.addAndGet(bytes.toLong())
        if (total > 8 * 1024 * 1024 || !queue.offer(packet)) {
            queuedBytes.addAndGet(-bytes.toLong())
            failure("WebRTC 视频队列已满，请重新连接或选择 Surface。")
        }
    }

    fun install(adapter: Any) {
        val manager = checkNotNull(field(adapter, "mCastManager"))
        cast = manager
        val member = manager.javaClass.getDeclaredField("mWfdListener").apply { isAccessible = true }
        val original = member.get(manager)
        val type = Class.forName("com.ucarsink.sink.natives.SinkNative\$WfdListener")
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            when (method.name) {
                "onVideoDataReceived" -> {
                    if (!closed.get() && !failed.get()) try {
                        val size = args!![2] as Int; val bytes = args[3] as ByteArray; val pts = args[4] as Long
                        val number = synchronized(callbackLock) {
                            check(dimensions != null) { "Video arrived before format" }
                            require(size in 1..EncodedVideoWire.MAX_ACCESS_UNIT && size <= bytes.size) { "Invalid access unit size: $size / ${bytes.size}" }
                            val normalizedPts = timeline.normalize(pts)
                            val next = sequence++
                            offer(Packet(EncodedVideoWire.Frame(next, normalizedPts, bytes.copyOf(size))))
                            next
                        }
                        if (number == 0L) {
                            check(field(manager, "mVideoPlayer") == null) { "SDK decoder unexpectedly active" }
                            Log.i("CarLinkEncoded", "Compressed video started; SDK decoder absent")
                            ready()
                        }
                    } catch (error: Exception) { failure("不支持的 CarLink 视频数据：${error.message}") }
                    null
                }
                "onCastVideoInitialized" -> {
                    if (!closed.get()) try {
                        require(args!![1] == "video/avc") { "Only H.264 is supported" }
                        val next = (args[2] as Int) to (args[3] as Int)
                        moe.feng.wheelplay.plugin.PluginVideoInfo(next.first, next.second)
                        synchronized(callbackLock) {
                            check(dimensions == null || dimensions == next) { "Video format changed; reconnect required" }
                            if (dimensions == null) {
                                dimensions = next
                                offer(Packet(null, next.first, next.second))
                                format(next.first, next.second)
                            }
                        }
                    } catch (error: Exception) { failure("CarLink 视频格式不兼容：${error.message}") }
                    method.invoke(original, *(args ?: emptyArray()))
                }
                else -> method.invoke(original, *(args ?: emptyArray()))
            }
        }
        member.set(manager, proxy)
    }

    fun startWithoutDecoder(adapter: Any, width: Int, height: Int): Boolean {
        val handler = field(adapter, "mWorkerHandler") as Handler
        return handler.post {
            if (!closed.get()) try {
                val uibc = checkNotNull(field(adapter, "mUibcManager"))
                uibc.javaClass.getMethod("setDisplayWidth", Int::class.javaPrimitiveType).invoke(uibc, width)
                uibc.javaClass.getMethod("setDisplayHeight", Int::class.javaPrimitiveType).invoke(uibc, height)
                val control = checkNotNull(field(adapter, "mControlManager"))
                check(control.javaClass.getMethod("sendGotoForeground", Boolean::class.javaPrimitiveType).invoke(control, true) == true)
                check(field(checkNotNull(cast), "mVideoPlayer") == null)
            } catch (error: Exception) { failure("CarLink 前台初始化失败：${error.message}") }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        writer.interrupt()
        queue.clear()
    }
}
