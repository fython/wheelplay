package moe.feng.wheelplay.projection.desktop

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Bounded helper-side work queue: the oneway Binder thread never waits for Android input injection. */
internal class DesktopTouchQueue(
    private val process: (DesktopTouchFrame) -> Unit,
    private val cancel: () -> Unit,
    private val diagnose: (DesktopException) -> Unit,
    private val capacity: Int = 64,
    private val operationLock: Any = Any(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "desktop-touch").apply { isDaemon = true } },
) : AutoCloseable {
    private val lock = Any()
    // Cancellation barriers also carry an epoch, including after they have been dequeued.
    private data class Entry(val epoch: Int, val frame: DesktopTouchFrame?)
    private val pending = ArrayDeque<Entry>()
    private var epoch = 0
    val inputEpoch: Int get() = synchronized(lock) { epoch }
    private var draining = false
    private var closed = false
    private var failures = 0
    @Volatile var fault: DesktopException? = null
        private set

    init { require(capacity >= 2) }

    fun offer(frame: DesktopTouchFrame) = synchronized(lock) {
        if (closed || fault != null || frame.inputEpoch != epoch) return@synchronized
        if (pending.size >= capacity) {
            // Dropping a queued UP alone could leave a held pointer. Reset before accepting a fresh snapshot.
            pending.clear()
            pending.addLast(Entry(epoch, null))
        }
        pending.addLast(Entry(epoch, frame))
        if (!draining) {
            draining = true
            worker.execute(::drain)
        }
    }

    /** Cancel old-page input before a synchronous navigation command. Late Binder calls are fenced too. */
    fun barrier(nextEpoch: Int, action: () -> Unit) = synchronized(operationLock) {
        synchronized(lock) {
            desktopCheck(!closed, DesktopErrorCode.INVALID_STATE) { "Desktop input is closed" }
            fault?.let { throw it }
            desktopCheck(nextEpoch > 0 && nextEpoch.toLong() == epoch.toLong() + 1,
                DesktopErrorCode.STALE_SESSION) { "Unexpected input epoch $nextEpoch after $epoch" }
            epoch = nextEpoch
            pending.clear()
            failures = 0
        }
        if (!cancelGesture()) throw checkNotNull(fault)
        action()
    }

    private fun drain() {
        while (true) {
            val frame = synchronized(lock) {
                if (closed || fault != null || pending.isEmpty()) {
                    pending.clear(); draining = false
                    return
                }
                pending.removeFirst()
            }
            consume(frame)
        }
    }

    private fun consume(entry: Entry) = synchronized(operationLock) {
        if (closed || fault != null || entry.epoch != epoch) return@synchronized
        val frame = entry.frame
        if (frame == null) {
            cancelGesture()
            return@synchronized
        }
        try {
            process(frame)
            failures = 0
        } catch (_: IllegalArgumentException) {
            // Validation precedes mutation in DesktopTouch. Malformed frames cannot change a gesture.
        } catch (error: Exception) {
            val failure = desktopFailure(error)
            when (failure.category) {
                DesktopErrorCode.STALE_SESSION, DesktopErrorCode.UNSUPPORTED_CAPABILITY -> Unit
                DesktopErrorCode.INPUT_FAILED -> if (cancelGesture()) {
                    if (++failures >= 3) fail(failure) else diagnose(failure)
                }
                else -> if (cancelGesture()) fail(failure)
            }
        }
    }

    private fun cancelGesture(): Boolean = try { cancel(); true } catch (error: Exception) {
        val code = (error as? DesktopException)?.code ?: DesktopErrorCode.INPUT_FAILED.wire
        fail(DesktopException(code, "Unable to cancel desktop gesture: $error", error))
        false
    }

    private fun fail(error: DesktopException) {
        synchronized(lock) {
            if (closed || fault != null) return
            fault = error
            pending.clear()
        }
        diagnose(error)
    }

    /** The owner serializes close/release with process/cancel, so a popped frame cannot use a released display. */
    override fun close() = synchronized(operationLock) {
        synchronized(lock) {
            closed = true
            pending.clear()
            worker.shutdown()
        }
    }
}
