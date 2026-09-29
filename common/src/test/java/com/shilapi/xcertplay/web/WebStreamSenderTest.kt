package com.shilapi.xcertplay.web

import fi.iki.elonen.NanoWSD.WebSocketFrame
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WebStreamSenderTest {
    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks.addLast(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun runNext() { tasks.removeFirst().run() }
    }
    private fun frame(id: Long) = WebSession.Frame(byteArrayOf(id.toByte()), 0, id)
    private fun text(value: String) = WebSocketFrame(WebSocketFrame.OpCode.Text, true, value)

    @Test fun publicationAndAckWakeTheWriterAndSkipSupersededFrames() {
        val executor = ManualExecutor()
        var latest: WebSession.Frame? = null
        val sent = mutableListOf<WebSocketFrame>()
        val sender = WebStreamSender({ latest }, { sent.add(it) }, { fail() }, executor, { 100 })
        try {
            sender.frameAvailable(); assertTrue(executor.tasks.isEmpty())
            latest = frame(1); sender.frameAvailable(); executor.runNext()
            assertArrayEquals(byteArrayOf(1), sent.single().binaryPayload)
            for (id in 2L..100L) { latest = frame(id); sender.frameAvailable() }
            assertTrue("No polling or per-frame tasks while ACK is outstanding", executor.tasks.isEmpty())
            sender.acknowledge(); assertEquals(1, executor.tasks.size); executor.runNext()
            assertArrayEquals(byteArrayOf(100), sent.last().binaryPayload)
            sender.acknowledge(); assertTrue(executor.tasks.isEmpty())
            latest = frame(101); sender.frameAvailable(); executor.runNext()
            assertEquals(3, sent.size)
            assertFalse(sender.frameStalled(6100)); assertTrue(sender.frameStalled(6101))
        } finally { sender.close() }
    }

    @Test fun controlRepliesCoalesceAndPrecedeTheNextJpeg() {
        val executor = ManualExecutor()
        val sent = mutableListOf<WebSocketFrame>()
        val sender = WebStreamSender({ frame(1) }, { sent.add(it) }, { fail() }, executor, { 0 })
        try {
            sender.frameAvailable()
            repeat(100) { sender.control(WebStreamSender.Control.STATUS, text("status-$it")) }
            sender.control(WebStreamSender.Control.INPUT_UNAVAILABLE, text("input"))
            assertEquals(1, executor.tasks.size)
            executor.runNext()
            assertEquals(listOf("status-99", "input"), sent.take(2).map { it.textPayload })
            assertEquals(WebSocketFrame.OpCode.Binary, sent.last().opCode)
            assertTrue(executor.tasks.isEmpty())
        } finally { sender.close() }
    }

    @Test fun blockedWriteDoesNotBlockReaderEventsAndNoWakeupIsLost() {
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val eventsAccepted = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val receiver = Executors.newSingleThreadExecutor()
        val sent = java.util.Collections.synchronizedList(mutableListOf<WebSocketFrame>())
        val latest = java.util.concurrent.atomic.AtomicReference(frame(1))
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val sender = WebStreamSender(
            latestFrame = { latest.get() },
            write = {
                if (it.opCode == WebSocketFrame.OpCode.Binary && it.binaryPayload[0] == 1.toByte()) {
                    enteredWrite.countDown()
                    check(releaseWrite.await(3, TimeUnit.SECONDS))
                }
                sent.add(it); finished.countDown()
            },
            onFailure = { failures.incrementAndGet() }, now = { 0 },
        )
        try {
            sender.frameAvailable(); assertTrue(enteredWrite.await(2, TimeUnit.SECONDS))
            receiver.execute {
                repeat(100) { sender.control(WebStreamSender.Control.STATUS, text("status-$it")) }
                latest.set(frame(2)); sender.frameAvailable(); sender.acknowledge()
                eventsAccepted.countDown()
            }
            assertTrue("Reader must not wait for a socket write", eventsAccepted.await(1, TimeUnit.SECONDS))
            assertEquals(0, sent.size)
            releaseWrite.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, failures.get())
            assertEquals("status-99", sent[1].textPayload)
            assertArrayEquals(byteArrayOf(2), sent[2].binaryPayload)
        } finally { releaseWrite.countDown(); sender.close(); receiver.shutdownNow() }
    }

    @Test fun closeCancelsQueuedWorkAndIgnoresLateEvents() {
        val executor = ManualExecutor()
        val sender = WebStreamSender({ frame(1) }, { fail() }, { fail() }, executor, { 0 })
        sender.frameAvailable(); sender.close()
        sender.acknowledge(); sender.frameAvailable(); sender.control(WebStreamSender.Control.STATUS, text("late"))
        assertTrue(executor.tasks.isEmpty())
    }

    @Test fun writeFailureStopsTheWriterAndReportsOnce() {
        val executor = ManualExecutor()
        var failures = 0
        val sender = WebStreamSender({ frame(1) }, { throw java.io.IOException("closed") }, { failures++ }, executor, { 0 })
        sender.frameAvailable(); executor.runNext()
        sender.frameAvailable(); sender.acknowledge()
        assertEquals(1, failures); assertTrue(executor.tasks.isEmpty())
    }
}
