package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class Iap2FileTransferBackpressureTest {
    /** Hold real link acknowledgements after negotiation, without disconnecting the peer. */
    private class Phone : BlockingDuplexByteStream {
        private val lock = Object()
        private val engine = Iap2LinkEngine(Iap2LinkConfig(maxOutgoing = 1))
        private val incoming = ArrayDeque<ByteArray>()
        val controls = LinkedBlockingQueue<ByteArray>()
        val files = LinkedBlockingQueue<ByteArray>()
        private var hold = false
        private var closed = false
        private var now = 0L
        init { engine.start(false, 0); drain() }
        private fun drain() {
            engine.takeOutput().takeIf { it.isNotEmpty() }?.let(incoming::add)
            while (true) {
                when (val event = engine.pollEvent() ?: break) {
                    is Iap2LinkEngine.Event.Control -> controls.add(event.bytes)
                    is Iap2LinkEngine.Event.FileTransfer -> files.add(event.bytes)
                    else -> Unit
                }
            }
        }
        fun holdAcknowledgements(value: Boolean) = synchronized(lock) { hold = value; lock.notifyAll() }
        override fun send(data: ByteArray) = synchronized(lock) {
            engine.feed(data, now)
            now += 500
            engine.advanceTime(now) // Also generate the peer's delayed acknowledgements.
            drain(); lock.notifyAll()
        }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(lock) {
            if (!closed && (hold || incoming.isEmpty()) && timeoutMillis > 0) lock.wait(timeoutMillis)
            if (closed) byteArrayOf() else if (hold) null else incoming.pollFirst()
        }
        override fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
    }

    @Test fun fileReplyWaitsBehindControlBackpressureAndIsDeliveredWhenAcknowledged() {
        val phone = Phone()
        val link = Iap2LinkChannel.openWireless(phone)
        try {
            assertTrue(link.awaitReady(2000))
            phone.holdAcknowledgements(true)
            repeat(4) { assertTrue(link.sendControl(byteArrayOf(it.toByte()))) }
            assertNotNull(phone.controls.poll(2, TimeUnit.SECONDS))
            assertNotNull(phone.controls.poll(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (link.awaitReady(0) && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse("Link must actually enter transient backpressure", link.awaitReady(0))
            val cancel = byteArrayOf(128.toByte(), 2)
            assertTrue("Negotiated file session remains usable during backpressure", link.sendFileTransfer(cancel, 1000))
            phone.holdAcknowledgements(false)
            assertArrayEquals(cancel, phone.files.poll(2, TimeUnit.SECONDS))
            assertNotNull(phone.controls.poll(2, TimeUnit.SECONDS))
            assertNotNull(phone.controls.poll(2, TimeUnit.SECONDS))
        } finally { link.close() }
    }

    @Test fun queuedArtworkReplyIsDiscardedAfterItsConnectionRetires() {
        val phone = Phone()
        val link = Iap2LinkChannel.openWireless(phone)
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            assertTrue(link.awaitReady(2000))
            phone.holdAcknowledgements(true)
            repeat(4) { assertTrue(link.sendControl(byteArrayOf(it.toByte()))) }
            repeat(2) { assertNotNull(phone.controls.poll(2, TimeUnit.SECONDS)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (link.awaitReady(0) && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(link.awaitReady(0))
            assertTrue(link.sendFileTransfer(byteArrayOf(128.toByte(), 6), 1000, current::get))
            current.set(false)
            val replacement = byteArrayOf(128.toByte(), 1)
            assertTrue(link.sendFileTransfer(replacement, 1000))
            phone.holdAcknowledgements(false)
            assertArrayEquals("Only the current connection's START may reach the phone",
                replacement, phone.files.poll(2, TimeUnit.SECONDS))
            assertNull(phone.files.poll(150, TimeUnit.MILLISECONDS))
        } finally { link.close() }
    }

    @Test fun artworkReplyWaitingForQueueCapacityCannotEnterAfterRetirement() {
        val phone = Phone()
        val link = Iap2LinkChannel.openWireless(phone)
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = java.util.concurrent.CountDownLatch(1)
        try {
            assertTrue(link.awaitReady(2000))
            phone.holdAcknowledgements(true)
            repeat(4) { assertTrue(link.sendControl(byteArrayOf(it.toByte()))) }
            repeat(2) { assertNotNull(phone.controls.poll(2, TimeUnit.SECONDS)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (link.awaitReady(0) && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(link.awaitReady(0))
            var queued = 0
            while (link.sendControl(byteArrayOf(7))) { queued++; check(queued <= 64) }
            val waiting = java.util.concurrent.CompletableFuture.supplyAsync {
                link.sendFileTransfer(byteArrayOf(128.toByte(), 6), 2000) {
                    entered.countDown(); current.get()
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(waiting.isDone)
            current.set(false)
            phone.holdAcknowledgements(false)
            assertFalse(waiting.get(2, TimeUnit.SECONDS))
            val replacement = byteArrayOf(128.toByte(), 1)
            assertTrue(link.sendFileTransfer(replacement, 1000))
            assertArrayEquals(replacement, phone.files.poll(2, TimeUnit.SECONDS))
            assertNull(phone.files.poll(150, TimeUnit.MILLISECONDS))
        } finally { current.set(false); link.close() }
    }
}
