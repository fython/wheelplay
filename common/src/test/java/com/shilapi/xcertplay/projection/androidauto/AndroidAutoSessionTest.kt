package com.shilapi.xcertplay.projection.androidauto

import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.androidauto.*
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.projection.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.*
import javax.net.ssl.SSLEngineResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidAutoSessionTest {
    @Test fun realTlsPeerDiscoversChannelsStreamsFragmentedVideoAndReceivesTouch() {
        val server = ServerSocket(0)
        val control = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        val frame = CountDownLatch(1)
        val outputClosed = CountDownLatch(1)
        val output = object : ProjectionVideoOutput {
            override val surface: Surface get() = error("encoded")
            override fun configure(codec: VideoCodec, codecData: ByteArray, requestKeyframe: () -> Unit): Long = 1
            override fun frame(formatRevision: Long, frameData: CompressedVideoFrame, ptsUs: Long): Boolean {
                assertEquals(42L, ptsUs); assertEquals(1L, formatRevision); frame.countDown(); return true
            }
            override fun framebuffer(snapshot: ProjectionFramebuffer.Snapshot) = false
            override fun close() { outputClosed.countDown() }
        }
        val peer = control.submit {
            server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = DataInputStream(socket.getInputStream()); val out = socket.getOutputStream()
                assertEquals(1, AapFraming.type(AapFraming.read(input).payload))
                AapFraming.write(out, 0, 3, AapFraming.message(2, byteArrayOf(0, 1, 0, 2, 0, 0)))
                val tls = AapTls(false); val engine = tls.engine
                engine.needClientAuth = true
                engine.beginHandshake()
                while (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    when (engine.handshakeStatus) {
                        SSLEngineResult.HandshakeStatus.NEED_TASK -> while (true) (engine.delegatedTask ?: break).run()
                        SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                            val dst = ByteBuffer.allocate(65535)
                            val result = engine.wrap(ByteBuffer.allocate(0), dst)
                            assertEquals(SSLEngineResult.Status.OK, result.status)
                            if (dst.position() > 0) AapFraming.write(out, 0, 3, AapFraming.message(3, dst.array().copyOf(dst.position())))
                        }
                        SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                            val packet = AapFraming.read(input)
                            assertEquals(3, AapFraming.type(packet.payload))
                            val src = ByteBuffer.wrap(packet.payload, 2, packet.payload.size - 2)
                            while (src.hasRemaining()) assertEquals(SSLEngineResult.Status.OK, engine.unwrap(src, ByteBuffer.allocate(65535)).status)
                        }
                        else -> error("Unexpected handshake state")
                    }
                }
                assertEquals(4, AapFraming.type(AapFraming.read(input).payload))
                fun send(channel: Int, type: Int, body: ByteArray) = AapFraming.write(out, channel, if (channel > 0 && type in 2..31) 15 else 11, tls.encrypt(AapFraming.message(type, body)))
                fun receive(): Pair<Int, ByteArray> {
                    val p = AapFraming.read(input); return p.channel to tls.decrypt(p.payload)
                }
                send(0, 5, AapProto.build { text(4, "Phone"); text(5, "Test") })
                val discovery = receive(); assertEquals(6, AapFraming.type(discovery.second))
                assertEquals(6, AapProto.parse(discovery.second.copyOfRange(2, discovery.second.size)).count { it.number == 1 })
                for (id in listOf(2, 3)) {
                    send(id, 7, AapProto.build { number(1, 0); number(2, id.toLong()) })
                    assertEquals(8, AapFraming.type(receive().second))
                }
                send(2, 0x8000, AapProto.build { number(1, 3) })
                assertEquals(0x8003, AapFraming.type(receive().second))
                assertEquals(0x8008, AapFraming.type(receive().second))
                send(2, 0x8001, AapProto.build { number(1, 9); number(2, 0) })
                send(2, 1, byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, 1, 0, 0, 0, 1, 0x68, 2))
                assertEquals(0x8004, AapFraming.type(receive().second))
                val first = tls.encrypt(ByteBuffer.allocate(14).putShort(0).putLong(42).put(byteArrayOf(0, 0, 0, 1)).array())
                out.write(byteArrayOf(2, 9, (first.size ushr 8).toByte(), first.size.toByte()))
                out.write(ByteBuffer.allocate(4).putInt(100).array()); out.write(first); out.flush()
                // This ACK must arrive before the final fragment; waiting for a complete frame deadlocks the phone window.
                assertEquals(0x8004, AapFraming.type(receive().second))
                val last = tls.encrypt(byteArrayOf(0x65, 0x12, 0x34))
                out.write(byteArrayOf(2, 10, (last.size ushr 8).toByte(), last.size.toByte())); out.write(last); out.flush()
                assertEquals(0x8004, AapFraming.type(receive().second))
                send(6, 7, AapProto.build { number(1, 0); number(2, 6) })
                assertEquals(8, AapFraming.type(receive().second))
                send(6, 0x8000, AapProto.build { number(1, 1) })
                assertEquals(0x8003, AapFraming.type(receive().second))
                assertEquals(19, AapFraming.type(receive().second))
                send(6, 0x8001, AapProto.build { number(1, 12); number(2, 0) })
                send(6, 0, ByteBuffer.allocate(648).putLong(42).put(ByteArray(640)).array())
                assertEquals(0x8004, AapFraming.type(receive().second))
                send(6, 0x8002, byteArrayOf())
                send(3, 0x8002, byteArrayOf())
                assertEquals(0x8003, AapFraming.type(receive().second)); ready.countDown()
                val touch = receive()
                assertEquals(3, touch.first); assertEquals(0x8001, AapFraming.type(touch.second))
                val report = AapProto.parse(touch.second.copyOfRange(2, touch.second.size))
                assertEquals(0L, AapProto.number(report.first { it.number == 3 }.bytes!!, 3))
                // Transient native focus releases held fingers but must not disconnect the protocol.
                send(2, 0x8007, AapProto.build { number(2, 3) })
                assertEquals(0x8001, AapFraming.type(receive().second))
                val lostFocus = receive().second
                assertEquals(0x8008, AapFraming.type(lostFocus))
                assertEquals(3L, AapProto.number(lostFocus.copyOfRange(2, lostFocus.size), 1))
                send(2, 0x8007, AapProto.build { number(2, 1) })
                assertEquals(0x8008, AapFraming.type(receive().second))
                send(0, 15, AapProto.build { number(1, 1) })
                assertEquals(16, AapFraming.type(receive().second))
            }
        }
        val context: Context = RuntimeEnvironment.getApplication()
        val session = AndroidAutoSession(context, SourceDescriptor("test-aa", "Test AA"), AapTcpConnection("127.0.0.1", server.localPort), false) { output }
        assertTrue(projectionSessions.adopt(session, emptySet()))
        try {
            session.start()
            val bound = ready.await(15, TimeUnit.SECONDS)
            if (!bound && peer.isDone) peer.get()
            assertTrue("Peer did not bind input: ${session.statusText}", bound)
            assertTrue("Frame did not reach generic output", frame.await(5, TimeUnit.SECONDS))
            assertTrue(session.touch(listOf(ProjectionContact(0, .5, .5, true))))
            peer.get(10, TimeUnit.SECONDS)
            session.stop().get(5, TimeUnit.SECONDS)
            assertTrue(outputClosed.await(5, TimeUnit.SECONDS))
            assertFalse(session.inputReady)
        } finally { session.stop().get(10, TimeUnit.SECONDS); projectionSessions.ended(session); server.close(); control.shutdownNow() }
    }

    @Test fun stopBeforeStartupNeverOpensTransportOrVideo() {
        val connection = object : AapConnection {
            override fun connect() { error("must not connect") }
            override val input get() = error("must not read")
            override val output get() = error("must not write")
            override fun close() = Unit
        }
        val session = AndroidAutoSession(RuntimeEnvironment.getApplication(), SourceDescriptor("test", "Test"), connection, false) { error("must not create output") }
        session.stop().get(1, TimeUnit.SECONDS); session.start()
        assertFalse(session.inputReady)
    }
}
