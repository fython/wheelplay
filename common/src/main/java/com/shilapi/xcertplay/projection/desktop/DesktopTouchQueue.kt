package com.shilapi.xcertplay.projection.desktop

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
    // null is a cancellation barrier, not a touch frame.
    private val pending = ArrayDeque<DesktopTouchFrame?>()
    private var draining = false
    private var closed = false
    private var failures = 0
    @Volatile var fault: DesktopException? = null
        private set

    init { require(capacity >= 2) }

    fun offer(frame: DesktopTouchFrame) = synchronized(lock) {
        if (closed || fault != null) return@synchronized
        if (pending.size >= capacity) {
            // Dropping a queued UP alone could leave a held pointer. Reset before accepting a fresh snapshot.
            pending.clear()
            pending.addLast(null)
        }
        pending.addLast(frame)
        if (!draining) {
            draining = true
            worker.execute(::drain)
        }
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

    private fun consume(frame: DesktopTouchFrame?) = synchronized(operationLock) {
        if (closed || fault != null) return@synchronized
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
            if (failure.category == DesktopErrorCode.STALE_SESSION || failure.category == DesktopErrorCode.UNSUPPORTED_CAPABILITY) return@synchronized
            if (!cancelGesture()) return@synchronized
            if (failure.category != DesktopErrorCode.INPUT_FAILED || ++failures >= 3) fail(failure)
            else diagnose(failure)
        }
    }

    private fun cancelGesture(): Boolean = try { cancel(); true } catch (error: Exception) {
        fail(DesktopException(DesktopErrorCode.INPUT_FAILED, "Unable to cancel desktop gesture: $error", error))
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
