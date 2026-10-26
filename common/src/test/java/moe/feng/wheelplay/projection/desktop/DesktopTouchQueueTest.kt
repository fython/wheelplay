package moe.feng.wheelplay.projection.desktop

import android.view.MotionEvent
import com.shilapi.xcertplay.projection.ProjectionContact
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DesktopTouchQueueTest {
    private fun frame(down: Boolean = true, epoch: Int = 0) =
        DesktopTouchFrame.from(listOf(ProjectionContact(0, .5, .5, down)), inputEpoch = epoch)
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))

    @Test fun backCancelsGestureAndDropsQueuedAndLateOldTouches() {
        val operationLock = Any()
        val first = CountDownLatch(1); val next = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val queue = DesktopTouchQueue(
            process = { events += "touch:${it.inputEpoch}"; if (it.inputEpoch == 0) first.countDown() else next.countDown() },
            cancel = { events += "cancel" }, diagnose = {}, operationLock = operationLock, capacity = 2,
        )
        queue.use {
            queue.offer(frame()); await(first)
            synchronized(operationLock) {
                repeat(10) { queue.offer(frame()) } // Includes an overflow cancellation barrier.
                queue.barrier(1) { events += "back" }
                queue.offer(frame(false)) // Binder can deliver this old release after synchronous Back.
                queue.offer(frame(epoch = 2)) // A future epoch is not authorized either.
                queue.offer(frame(epoch = 1))
            }
            await(next); queue.close()
            assertEquals(listOf("touch:0", "cancel", "back", "touch:1"), events)
            assertNull(queue.fault)
        }
    }

    @Test fun backInvalidatesDequeuedFramesAndCancellationBarriers() {
        for (overflow in listOf(false, true)) {
            val operationLock = Any(); val next = CountDownLatch(1); val startWorker = CountDownLatch(1)
            val events = CopyOnWriteArrayList<String>()
            lateinit var workerThread: Thread
            val worker = Executors.newSingleThreadExecutor { task ->
                Thread { startWorker.await(); task.run() }.also { workerThread = it }
            }
            val queue = DesktopTouchQueue(
                process = { events += "touch:${it.inputEpoch}"; next.countDown() },
                cancel = { events += "cancel" }, diagnose = {}, capacity = 2,
                operationLock = operationLock, worker = worker,
            )
            queue.use {
                try {
                    synchronized(operationLock) {
                        // Three offers overflow to [cancel, frame] before the worker starts.
                        repeat(if (overflow) 3 else 1) { queue.offer(frame()) }
                        startWorker.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (workerThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                        assertEquals(Thread.State.BLOCKED, workerThread.state)
                        queue.barrier(1) { events += "back" }
                        queue.offer(frame(epoch = 1))
                    }
                    await(next); queue.close()
                    assertEquals(listOf("cancel", "back", "touch:1"), events)
                } finally { startWorker.countDown() }
            }
        }
    }

    @Test fun backRejectsRepeatedEpochAndDoesNotNavigateIfCancellationFails() {
        var navigated = 0
        DesktopTouchQueue({}, {}, {}).use { queue ->
            queue.barrier(1) { navigated++ }
            for (epoch in listOf(0, 1, 3)) {
                assertEquals(DesktopErrorCode.STALE_SESSION, assertThrows(DesktopException::class.java) {
                    queue.barrier(epoch) { navigated++ }
                }.category)
            }
            queue.barrier(2) { navigated++ }
            assertEquals(2, navigated)
        }
        DesktopTouchQueue({}, { error("cancel failed") }, {}).use { queue ->
            val error = assertThrows(DesktopException::class.java) { queue.barrier(1) { navigated++ } }
            assertSame(queue.fault, error)
            assertEquals(2, navigated)
        }
    }

    @Test fun failedFinalUpCancelsWithoutWaitingForAnotherFrameAndNextGestureWorks() {
        val actions = CopyOnWriteArrayList<Int>()
        val cancel = CountDownLatch(1); val nextUp = CountDownLatch(1)
        val rejectUp = AtomicBoolean(true)
        val touch = DesktopTouch(DesktopDisplayConfig()) {
            if (it.actionMasked == MotionEvent.ACTION_UP && rejectUp.compareAndSet(true, false))
                throw DesktopException(DesktopErrorCode.INPUT_FAILED, "transient UP rejection")
            actions += it.actionMasked
            if (it.actionMasked == MotionEvent.ACTION_CANCEL) cancel.countDown()
            if (it.actionMasked == MotionEvent.ACTION_UP) nextUp.countDown()
        }
        val queue = DesktopTouchQueue({ touch.update(it.ids, it.xs, it.ys, it.down) }, touch::cancel, {})
        queue.use {
            queue.offer(frame()); queue.offer(frame(false))
            await(cancel)
            assertNull(queue.fault)
            assertEquals(MotionEvent.ACTION_CANCEL, actions.last())
            queue.offer(frame()); queue.offer(frame(false)); await(nextUp)
            assertEquals(2, actions.count { it == MotionEvent.ACTION_DOWN })
            assertNull(queue.fault)
        }
    }

    @Test fun permissionDeniedDuringCancellationRetainsItsCodeAndPreventsBack() {
        val denied = DesktopException(DesktopErrorCode.PERMISSION_DENIED, "INJECT_EVENTS denied")
        DesktopTouchQueue({}, { throw denied }, {}).use { queue ->
            val status = desktopServiceStatus { queue.barrier(1) { fail("Back must not run") } }
            assertEquals(DesktopErrorCode.PERMISSION_DENIED.wire, status.code)
            assertTrue(status.diagnostic!!.contains("INJECT_EVENTS denied"))
            assertSame(denied, queue.fault!!.cause)
        }
    }

    @Test fun cancellationFailureLatchesFaultAndDiscardsSubsequentFrames() {
        val reported = CountDownLatch(1); val calls = AtomicInteger()
        val queue = DesktopTouchQueue(
            process = { calls.incrementAndGet(); throw DesktopException(DesktopErrorCode.INPUT_FAILED, "input rejected") },
            cancel = { error("cancel rejected") }, diagnose = { reported.countDown() },
        )
        queue.use {
            queue.offer(frame()); await(reported)
            assertEquals(DesktopErrorCode.INPUT_FAILED, queue.fault!!.category)
            assertTrue(queue.fault!!.diagnostic.contains("cancel rejected"))
            queue.offer(frame()); queue.close()
            assertEquals(1, calls.get())
        }
    }

    @Test fun repeatedInputFailuresAndFatalLayoutErrorsAreAvailableToHealthChecks() {
        for ((code, expectedCalls) in listOf(DesktopErrorCode.INPUT_FAILED to 3, DesktopErrorCode.LAYOUT_CHANGED to 1,
            DesktopErrorCode.PERMISSION_DENIED to 1)) {
            val done = CountDownLatch(1); val calls = AtomicInteger(); val canceled = AtomicInteger()
            lateinit var queue: DesktopTouchQueue
            queue = DesktopTouchQueue(
                process = { calls.incrementAndGet(); throw DesktopException(code, "rejected") },
                cancel = { canceled.incrementAndGet() }, diagnose = { if (queue.fault != null) done.countDown() },
            )
            queue.use {
                repeat(4) { queue.offer(frame()) }
                await(done); queue.close()
                assertEquals(code, queue.fault!!.category)
                assertEquals(expectedCalls, calls.get()); assertEquals(expectedCalls, canceled.get())
            }
        }
    }

    @Test fun overloadedQueueAdmitsWithoutWaitingAndCancelsBeforeTheLatestRelease() {
        val entered = CountDownLatch(1); val gate = CountDownLatch(1); val ended = CountDownLatch(1)
        val actions = CopyOnWriteArrayList<Int>()
        val first = AtomicBoolean(true)
        val touch = DesktopTouch(DesktopDisplayConfig()) { actions += it.actionMasked }
        val queue = DesktopTouchQueue(
            process = {
                touch.update(it.ids, it.xs, it.ys, it.down)
                if (first.compareAndSet(true, false)) { entered.countDown(); gate.await(3, TimeUnit.SECONDS) }
                if (it.ids.isEmpty()) ended.countDown()
            }, cancel = touch::cancel, diagnose = {}, capacity = 2,
        )
        queue.use {
            try {
                queue.offer(frame()); await(entered)
                CompletableFuture.runAsync {
                    repeat(100) { queue.offer(frame()) }
                    queue.offer(DesktopTouchFrame.from(emptyList()))
                }.get(1, TimeUnit.SECONDS)
                gate.countDown(); await(ended); queue.close()
                assertTrue(actions.contains(MotionEvent.ACTION_CANCEL))
                assertTrue(actions.last() in listOf(MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP))
                assertNull(queue.fault)
            } finally { gate.countDown() }
        }
    }

    @Test fun stopDiscardsWorkWaitingForTheDisplayLockAndLateFrames() {
        val operationLock = Any(); val calls = AtomicInteger()
        val queue = DesktopTouchQueue({ calls.incrementAndGet() }, {}, {}, operationLock = operationLock)
        synchronized(operationLock) {
            queue.offer(frame())
            // Simulate display release under the same lock; even an already popped frame must not inject.
            queue.close()
        }
        queue.offer(frame(false)); queue.close()
        assertEquals(0, calls.get())
    }

    @Test fun invalidAndStaleFramesDoNotPoisonTheNextValidFrame() {
        val done = CountDownLatch(1); val canceled = AtomicInteger(); val calls = AtomicInteger()
        val queue = DesktopTouchQueue(
            process = {
                when (calls.incrementAndGet()) {
                    1 -> throw IllegalArgumentException("invalid coordinates")
                    2 -> throw DesktopException(DesktopErrorCode.STALE_SESSION, "stale layout")
                    else -> done.countDown()
                }
            }, cancel = { canceled.incrementAndGet() }, diagnose = {},
        )
        queue.use {
            repeat(3) { queue.offer(frame()) }; await(done); queue.close()
            assertNull(queue.fault); assertEquals(0, canceled.get())
        }
    }
}
