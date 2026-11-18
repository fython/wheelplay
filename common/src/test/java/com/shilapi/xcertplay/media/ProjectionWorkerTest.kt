package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProjectionWorkerTest {
    @Test fun interruptedProtocolReaderStillJoinsMediaWorkerAndRestoresInterrupt() {
        val release = CountDownLatch(1)
        val worker = Thread { release.await() }.apply { start() }
        val joined = CountDownLatch(1)
        var interruptRestored = false
        val reader = Thread {
            Thread.currentThread().interrupt()
            awaitProjectionWorker(worker, 2000)
            interruptRestored = Thread.currentThread().isInterrupted
            joined.countDown()
        }.apply { start() }
        release.countDown()
        assertTrue(joined.await(3, TimeUnit.SECONDS))
        reader.join(1000)
        assertFalse(worker.isAlive)
        assertTrue(interruptRestored)
    }
}
