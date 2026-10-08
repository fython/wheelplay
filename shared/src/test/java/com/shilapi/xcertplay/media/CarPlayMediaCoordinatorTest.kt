package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2LinkEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CarPlayMediaCoordinatorTest {
    /** Real link/file queues with a synthetic phone peer; no Android image decoder is involved. */
    private class ArtworkPeer : BlockingDuplexByteStream {
        private val lock = Object()
        private val engine = Iap2LinkEngine()
        private val incoming = java.util.ArrayDeque<ByteArray>()
        val replies = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        private var closed = false
        init { engine.start(false, 0); drain() }
        private fun drain() {
            engine.takeOutput().takeIf { it.isNotEmpty() }?.let(incoming::add)
            while (true) {
                val event = engine.pollEvent() ?: break
                if (event is Iap2LinkEngine.Event.FileTransfer) replies.add(event.bytes)
            }
        }
        override fun send(data: ByteArray) = synchronized(lock) {
            engine.feed(data, 0); drain(); lock.notifyAll()
        }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(lock) {
            if (!closed && incoming.isEmpty() && timeoutMillis > 0) lock.wait(timeoutMillis)
            if (closed) byteArrayOf() else incoming.pollFirst()
        }
        fun file(bytes: ByteArray) = synchronized(lock) {
            engine.sendFileTransfer(bytes, 0); drain(); lock.notifyAll()
        }
        fun control(frame: com.shilapi.xcertplay.iap2.wire.Iap2Frame) = synchronized(lock) {
            engine.sendControl(frame.encodedFrame(), 0); drain(); lock.notifyAll()
        }
        override fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
    }
    // Metadata is supplied by the existing control-loop callback. This stream only owns the
    // coordinator's bounded file-queue worker; it deliberately performs no phone emulation.
    private fun channel() = Iap2Session.openTunnel(object : BlockingDuplexByteStream {
        private val lock = Object()
        private var closed = false
        override fun send(data: ByteArray) {}
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(lock) {
            if (!closed && timeoutMillis > 0) lock.wait(timeoutMillis)
            if (closed) byteArrayOf() else null
        }
        override fun close() = synchronized(lock) { closed = true; lock.notifyAll() }
    })
    private fun song(title: String = "Song") = Iap2Messages.buildRaw(0x5001) {
        group(0) { string(1, title) }
        group(1) { u8(0, 1); u32(1, 1000) }
    }
    private fun setup(id: Int) = java.nio.ByteBuffer.allocate(12)
        .put(id.toByte()).put(4).putLong(4).putShort(2).array()
    private fun completeCover(peer: ArtworkPeer, id: Int, thirdByte: Int = 255) {
        peer.file(setup(id))
        assertArrayEquals(byteArrayOf(id.toByte(),1), peer.replies.poll(2, TimeUnit.SECONDS))
        peer.file(byteArrayOf(id.toByte(),0xc0.toByte(),0xff.toByte(),0xd8.toByte(),thirdByte.toByte(),0xd9.toByte()))
        assertArrayEquals(byteArrayOf(id.toByte(),if (thirdByte == 0) 6 else 5), peer.replies.poll(2, TimeUnit.SECONDS))
    }

    @Test fun lyricRefreshesDuringAndAfterArtworkTransferPreserveCoverAndProgress() {
        var now = 1000L
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val coordinator = CarPlayMediaCoordinator({ { true } }, { now }, logs::add, { MediaArtwork(it) })
        val peer = ArtworkPeer()
        val source = Iap2Session.openTunnel(peer)
        try {
            assertTrue(source.awaitReady(2000))
            coordinator.bind(source); coordinator.receive(source, song())
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { u64(0, 1); u8(26, 128) } })
            val revision = coordinator.snapshot().trackRevision
            peer.file(setup(128))
            assertArrayEquals(byteArrayOf(128.toByte(),1), peer.replies.poll(2, TimeUnit.SECONDS))
            peer.file(byteArrayOf(128.toByte(),0x80.toByte(),0xff.toByte(),0xd8.toByte()))
            now = 4500
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { string(1, "Lyric during download") } })
            peer.file(byteArrayOf(128.toByte(),0x40.toByte(),0xff.toByte(),0xd9.toByte()))
            assertArrayEquals(byteArrayOf(128.toByte(),5), peer.replies.poll(2, TimeUnit.SECONDS))
            val cover = coordinator.snapshot().artwork
            assertNotNull(cover)
            repeat(20) {
                now += 3500
                coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { string(1, "Lyric $it") } })
                assertEquals(revision, coordinator.snapshot().trackRevision)
                assertSame(cover, coordinator.snapshot().artwork)
                assertEquals(128, coordinator.snapshot().artworkTransferId)
                assertEquals(now, coordinator.snapshot().positionAt(now))
            }
            assertNull(peer.replies.poll()) // No cancellation or re-request for each lyric line.
            assertTrue(logs.any { it.contains("metadata refreshed") && it.contains("fields=title") &&
                it.contains("pidPresent=false") && it.contains("artworkRetained=true") })
            assertFalse(logs.any { it.contains("track changed") })
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { u64(0, 2) } })
            assertNull(coordinator.snapshot().artwork)
            assertNull(coordinator.snapshot().positionMs)
            assertTrue(logs.any { it.contains("track changed") && it.contains("reason=persistent-id") })
        } finally { coordinator.close(); source.close() }
    }

    @Test fun sameSongCoverRefreshReplacesOnlyOnSuccessfulDecodeAndTrueTrackChangesClearIt() {
        val coordinator = CarPlayMediaCoordinator({ { true } }, { 1000L },
            decodeArtwork = { if (it[2] == 0.toByte()) null else MediaArtwork(it) })
        val peer = ArtworkPeer()
        val source = Iap2Session.openTunnel(peer)
        try {
            assertTrue(source.awaitReady(2000))
            coordinator.bind(source); coordinator.receive(source, song())
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) {
                group(0) { u64(0, 1); u8(26, 128) }; group(1) { u32(2, 0) }
            })
            completeCover(peer, 128)
            val oldCover = coordinator.snapshot().artwork
            assertNotNull(oldCover)
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { u8(26, 129) } })
            assertSame(oldCover, coordinator.snapshot().artwork)
            completeCover(peer, 129, 42)
            val newCover = coordinator.snapshot().artwork
            assertNotSame(oldCover, newCover)
            assertArrayEquals(byteArrayOf(-1,-40,42,-39), newCover!!.bytes())
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { u8(26, 130) } })
            completeCover(peer, 130, 0) // Damaged replacement cannot remove the known-good cover.
            assertSame(newCover, coordinator.snapshot().artwork)
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(1) { u32(2, 1) } })
            assertNull(coordinator.snapshot().artwork)
            val revision = coordinator.snapshot().trackRevision
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { u8(26, 131) } })
            completeCover(peer, 131)
            val nextCover = coordinator.snapshot().artwork
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { u64(0, 2); string(1, "Next song") } })
            assertEquals(revision, coordinator.snapshot().trackRevision)
            assertSame(nextCover, coordinator.snapshot().artwork)
            coordinator.disconnected(source)
            assertNull(coordinator.snapshot().artwork)
        } finally { coordinator.close(); source.close() }
    }

    @Test fun oldArtworkDecodedAfterARealTrackChangeCannotReappear() {
        val decoding = java.util.concurrent.CountDownLatch(1)
        val releaseDecode = java.util.concurrent.CountDownLatch(1)
        val trackChanged = java.util.concurrent.CountDownLatch(1)
        val coordinator = CarPlayMediaCoordinator({ { true } }, { 1000L }, decodeArtwork = {
            decoding.countDown()
            check(releaseDecode.await(2, TimeUnit.SECONDS))
            MediaArtwork(it)
        })
        val observer = coordinator.subscribe { if (it.persistentId == 2L) trackChanged.countDown() }
        val peer = ArtworkPeer()
        val source = Iap2Session.openTunnel(peer)
        try {
            assertTrue(source.awaitReady(2000))
            coordinator.bind(source)
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { u64(0, 1); u8(26, 128) } })
            peer.file(setup(128))
            assertArrayEquals(byteArrayOf(128.toByte(),1), peer.replies.poll(2, TimeUnit.SECONDS))
            peer.file(byteArrayOf(128.toByte(),0xc0.toByte(),-1,-40,-1,-39))
            assertTrue(decoding.await(2, TimeUnit.SECONDS))
            peer.control(Iap2Messages.buildRaw(0x5001) { group(0) { u64(0, 2) } })
            peer.control(Iap2Messages.buildRaw(0x5703) { string(0, "Turn right") })
            val change = java.util.concurrent.CompletableFuture.supplyAsync {
                coordinator.receive(source, source.recv(1000)!!)
                source.recv(1000)
            }
            assertTrue(trackChanged.await(2, TimeUnit.SECONDS))
            assertEquals("Navigation must be read while JPEG decoding is still blocked",
                0x5703, change.get(1, TimeUnit.SECONDS)!!.messageId)
            releaseDecode.countDown()
            assertArrayEquals(byteArrayOf(128.toByte(),6), peer.replies.poll(2, TimeUnit.SECONDS))
            assertNull(coordinator.snapshot().artwork)
            coordinator.receive(source, Iap2Messages.buildRaw(0x5001) { group(0) { u8(26, 129) } })
            completeCover(peer, 129)
            assertNotNull(coordinator.snapshot().artwork)
        } finally { releaseDecode.countDown(); observer.close(); coordinator.close(); source.close() }
    }

    @Test fun reconnectingOnTheSameLinkRetiresOldDecodeAndHasOnlyOneFileConsumer() {
        val decoding = java.util.concurrent.CountDownLatch(1)
        val releaseDecode = java.util.concurrent.CountDownLatch(1)
        val decodeCount = java.util.concurrent.atomic.AtomicInteger()
        val coordinator = CarPlayMediaCoordinator({ { true } }, { 1000L }, decodeArtwork = {
            if (decodeCount.incrementAndGet() == 1) {
                decoding.countDown()
                check(releaseDecode.await(3, TimeUnit.SECONDS))
            }
            MediaArtwork(it)
        })
        val peer = ArtworkPeer()
        val source = Iap2Session.openTunnel(peer)
        val metadata = Iap2Messages.buildRaw(0x5001) { group(0) { u64(0, 1); u8(26, 128) } }
        try {
            assertTrue(source.awaitReady(2000))
            coordinator.bind(source); coordinator.receive(source, metadata)
            peer.file(setup(128))
            assertArrayEquals(byteArrayOf(128.toByte(), 1), peer.replies.poll(2, TimeUnit.SECONDS))
            peer.file(byteArrayOf(128.toByte(), 0xc0.toByte(), -1, -40, -1, -39))
            assertTrue(decoding.await(2, TimeUnit.SECONDS))
            val oldGeneration = coordinator.snapshot().connectionGeneration
            java.util.concurrent.CompletableFuture.runAsync {
                coordinator.disconnected(source); coordinator.resume(); coordinator.receive(source, metadata)
            }.get(1, TimeUnit.SECONDS)
            assertTrue(coordinator.snapshot().connectionGeneration > oldGeneration)
            peer.file(setup(128))
            assertNull("A second worker must not consume the reused link", peer.replies.poll(150, TimeUnit.MILLISECONDS))
            releaseDecode.countDown()
            // No old FAILURE/SUCCESS may precede or follow the new START, even with ID reuse.
            assertArrayEquals(byteArrayOf(128.toByte(), 1), peer.replies.poll(2, TimeUnit.SECONDS))
            assertNull(coordinator.snapshot().artwork)
            peer.file(byteArrayOf(128.toByte(), 0xc0.toByte(), -1, -40, 42, -39))
            assertArrayEquals(byteArrayOf(128.toByte(), 5), peer.replies.poll(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(-1, -40, 42, -39), coordinator.snapshot().artwork!!.bytes())
            assertNull(peer.replies.poll(150, TimeUnit.MILLISECONDS))
        } finally { releaseDecode.countDown(); coordinator.close(); source.close() }
    }

    @Test fun commandsReturnSendResultsWithoutOptimisticallyChangingPhoneState() {
        val sent = mutableListOf<MediaCommand>()
        val coordinator = CarPlayMediaCoordinator({ { command -> sent.add(command); true } }, { 100L })
        val source = channel()
        try {
            assertEquals(MediaCommandResult.UNAVAILABLE, coordinator.send(MediaCommand.PLAY).get(1, TimeUnit.SECONDS))
            coordinator.bind(source); coordinator.receive(source, song())
            assertEquals(MediaCommandResult.SENT, coordinator.send(MediaCommand.PAUSE).get(1, TimeUnit.SECONDS))
            assertEquals(listOf(MediaCommand.PAUSE), sent)
            assertEquals(PhonePlaybackStatus.PLAYING, coordinator.snapshot().status)
            assertEquals(MediaCommandResult.UNSUPPORTED, coordinator.seekTo(999999).get())
            coordinator.disconnected(source)
            assertEquals(MediaCommandResult.UNAVAILABLE, coordinator.send(MediaCommand.NEXT).get(1, TimeUnit.SECONDS))
        } finally { coordinator.close(); source.close() }
    }

    @Test fun newConnectionsResetTracksAndIgnoreOldSourceCallbacks() {
        val coordinator = CarPlayMediaCoordinator({ { true } }, { 100L })
        val old = channel(); val next = channel()
        try {
            coordinator.bind(old); coordinator.receive(old, song("Old phone"))
            val generation = coordinator.snapshot().connectionGeneration
            coordinator.bind(next)
            assertNull(coordinator.snapshot().title)
            coordinator.receive(old, song("Late old callback"))
            assertNull(coordinator.snapshot().title)
            coordinator.receive(next, song("New phone"))
            coordinator.disconnected(old)
            assertEquals("New phone", coordinator.snapshot().title)
            assertEquals(MediaCommandResult.UNAVAILABLE, coordinator.send(MediaCommand.PLAY, generation).get(1, TimeUnit.SECONDS))
            assertEquals(MediaCommandResult.SENT, coordinator.send(MediaCommand.PLAY).get(1, TimeUnit.SECONDS))
        } finally { coordinator.close(); old.close(); next.close() }
    }

    @Test fun initialSnapshotInvalidUpdatesObserverReleaseAndTunnelHandoffAreHandled() {
        val coordinator = CarPlayMediaCoordinator({ { true } }, { 100L })
        val old = channel(); val tunnel = channel()
        val seen = mutableListOf<NowPlayingState>()
        val observer = coordinator.subscribe(seen::add)
        try {
            assertFalse(seen.single().connected)
            coordinator.bind(old); coordinator.receive(old, song())
            val revision = coordinator.snapshot().trackRevision
            coordinator.bind(tunnel, retainState = true)
            assertEquals("Song", coordinator.snapshot().title)
            assertTrue(coordinator.snapshot().trackRevision > revision)
            coordinator.receive(tunnel, Iap2Messages.buildRaw(0x5001) { group(1) { u16(0, 1) } })
            assertEquals(PhonePlaybackStatus.PLAYING, coordinator.snapshot().status)
            observer.close()
            val count = seen.size
            coordinator.disconnected()
            assertEquals(count, seen.size)
        } finally { observer.close(); coordinator.close(); old.close(); tunnel.close() }
    }

    @Test fun closeCompletesEveryOutstandingOperation() {
        val coordinator = CarPlayMediaCoordinator({ { true } }, { 100L })
        val source = channel()
        try {
            coordinator.bind(source); coordinator.receive(source, song())
            val futures = (0 until 100).map { coordinator.send(MediaCommand.NEXT) }
            coordinator.close()
            futures.forEach { assertNotNull(it.get(1, TimeUnit.SECONDS)) }
            assertFalse(coordinator.snapshot().available)
        } finally { coordinator.close(); source.close() }
    }

    @Test fun firstCoverArrivesAcrossIncrementalMetadataAndSurvivesTunnelHandoff() {
        val setupQueued = java.util.concurrent.CountDownLatch(1)
        val coordinator = CarPlayMediaCoordinator({ { true } }, { 100L },
            onDiagnostic = { if (it.contains("SETUP queued")) setupQueued.countDown() },
            decodeArtwork = { MediaArtwork(it) })
        val peer = ArtworkPeer()
        val bootstrap = Iap2Session.openTunnel(peer)
        val tunnel = channel()
        try {
            assertTrue(bootstrap.awaitReady(2000))
            coordinator.bind(bootstrap)
            peer.file(java.nio.ByteBuffer.allocate(12).put(128.toByte()).put(4).putLong(4).putShort(2).array())
            assertTrue(setupQueued.await(2, TimeUnit.SECONDS))
            coordinator.receive(bootstrap, song())
            coordinator.receive(bootstrap, Iap2Messages.buildRaw(0x5001) { group(0) { u8(26, 128) } })
            assertArrayEquals(byteArrayOf(128.toByte(),1), peer.replies.poll(2, TimeUnit.SECONDS))
            coordinator.receive(bootstrap, Iap2Messages.buildRaw(0x5001) { group(0) { u64(0, 1) } })
            peer.file(byteArrayOf(128.toByte(),0xc0.toByte(),0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte()))
            assertArrayEquals(byteArrayOf(128.toByte(),5), peer.replies.poll(2, TimeUnit.SECONDS))
            val cover = coordinator.snapshot().artwork
            assertNotNull(cover)
            coordinator.bind(tunnel, retainState = true)
            assertSame(cover, coordinator.snapshot().artwork)
            assertNull(coordinator.snapshot().artworkTransferId)
            coordinator.receive(tunnel, Iap2Messages.buildRaw(0x5001) { group(0) { u8(26, 129) } })
            assertSame(cover, coordinator.snapshot().artwork)
            coordinator.receive(bootstrap, song("Stale bootstrap"))
            assertSame(cover, coordinator.snapshot().artwork)
            coordinator.receive(tunnel, Iap2Messages.buildRaw(0x5001) { group(0) { u64(0, 2); string(1, "Next song") } })
            assertNull(coordinator.snapshot().artwork)
            coordinator.bind(bootstrap) // A fresh connection must never inherit a cached cover.
            assertNull(coordinator.snapshot().artwork)
        } finally { coordinator.close(); bootstrap.close(); tunnel.close() }
    }
    @Test fun disconnectCompletesPendingCommandsWithoutWaitingForTransportIo() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val coordinator = CarPlayMediaCoordinator({ { entered.countDown(); release.await(); true } }, { 100L })
        val source = channel()
        try {
            coordinator.bind(source); coordinator.receive(source, song())
            val inFlight = coordinator.send(MediaCommand.PAUSE)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val queued = coordinator.send(MediaCommand.NEXT)
            java.util.concurrent.CompletableFuture.runAsync { coordinator.disconnected() }.get(1, TimeUnit.SECONDS)
            assertEquals(MediaCommandResult.UNAVAILABLE, inFlight.get(1, TimeUnit.SECONDS))
            assertEquals(MediaCommandResult.UNAVAILABLE, queued.get(1, TimeUnit.SECONDS))
            assertFalse(coordinator.snapshot().available)
        } finally { release.countDown(); coordinator.close(); source.close() }
    }

}
