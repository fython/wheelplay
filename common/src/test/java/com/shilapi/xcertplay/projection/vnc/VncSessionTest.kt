package com.shilapi.xcertplay.projection.vnc

import android.view.Surface
import com.shilapi.xcertplay.media.CompressedVideoFrame
import com.shilapi.xcertplay.media.VideoCodec
import com.shilapi.xcertplay.projection.*
import com.shilapi.xcertplay.vnc.RfbClient
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VncSessionTest {
    private class Output : ProjectionVideoOutput {
        val frames = LinkedBlockingQueue<ProjectionFramebuffer.Snapshot>()
        var closed = false
        override val surface: Surface get() = error("Not used")
        override fun configure(codec: VideoCodec, codecData: ByteArray, requestKeyframe: () -> Unit): Long = error("Not used")
        override fun frame(formatRevision: Long, frame: CompressedVideoFrame, ptsUs: Long) = false
        override fun framebuffer(snapshot: ProjectionFramebuffer.Snapshot): Boolean { frames.add(snapshot); return true }
        override fun close() { closed = true }
    }
    @Test fun connectsReceivesPixelsSendsInputAndReleasesBeforeDisconnect() {
        ServerSocket(0).use { server ->
            val packets = LinkedBlockingQueue<ByteArray>()
            val finished = CompletableFuture<Unit>()
            val peer = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val out = DataOutputStream(socket.getOutputStream())
                        out.writeBytes("RFB 003.008\n")
                        assertEquals("RFB 003.008\n", ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII))
                        out.write(byteArrayOf(1,1)); assertEquals(1,input.readUnsignedByte()); out.writeInt(0)
                        assertEquals(1,input.readUnsignedByte())
                        out.writeShort(2); out.writeShort(1); out.write(RfbClient.PIXEL_FORMAT); out.writeInt(0)
                        val format = ByteArray(20).also(input::readFully); assertEquals(0,format[0].toInt())
                        assertEquals(2,input.readUnsignedByte()); input.readByte()
                        repeat(input.readUnsignedShort()) { input.readInt() }
                        assertEquals(3,input.readUnsignedByte()); input.readFully(ByteArray(9))
                        out.write(byteArrayOf(0,0,0,1))
                        out.writeShort(0); out.writeShort(0); out.writeShort(2); out.writeShort(1); out.writeInt(0)
                        out.write(byteArrayOf(0,0,-1,0,0,-1,0,0))
                        while (true) {
                            val type = input.read(); if (type < 0) break
                            val length = when(type) { 3 -> 10; 4 -> 8; 5 -> 6; else -> error("Unexpected packet $type") }
                            val packet = ByteArray(length); packet[0]=type.toByte(); input.readFully(packet,1,length-1)
                            if (type != 3) packets.add(packet)
                        }
                    }; finished.complete(Unit)
                } catch(error: Throwable) { finished.completeExceptionally(error) }
            }.apply { start() }
            val output = Output()
            val session = VncSession(RuntimeEnvironment.getApplication(),VncEndpoint("127.0.0.1",server.localPort,false),charArrayOf()) { _, _ -> output }
            try {
                session.activate()
                val frame = output.frames.poll(5,TimeUnit.SECONDS)!!
                assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt()),frame.argb)
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
                while (!session.inputReady && System.nanoTime()<deadline) Thread.sleep(5)
                assertTrue(session.inputReady)
                assertTrue(session.input(ProjectionInput.Key("a",true)))
                assertEquals(1,packets.poll(2,TimeUnit.SECONDS)!![1].toInt())
                session.stop().get(5,TimeUnit.SECONDS)
                assertEquals(0,packets.poll(2,TimeUnit.SECONDS)!![1].toInt())
                finished.get(5,TimeUnit.SECONDS)
                assertTrue(output.closed); assertFalse(session.inputReady)
            } finally { session.stop().get(5,TimeUnit.SECONDS); peer.join(5000) }
        }
    }
    @Test fun stopInterruptsStalledHandshakeAndStopBeforeActivateErasesSecret() {
        ServerSocket(0).use { server ->
            val password = "secret".toCharArray()
            val session = VncSession(RuntimeEnvironment.getApplication(),VncEndpoint("127.0.0.1",server.localPort,false),password) { _, _ -> error("No output before handshake") }
            session.activate()
            server.soTimeout = 5000
            server.accept().use {
                session.stop().get(3,TimeUnit.SECONDS)
                assertTrue(password.all { it == '\u0000' })
            }
            val unused = "unused".toCharArray()
            VncSession(RuntimeEnvironment.getApplication(),VncEndpoint("127.0.0.1",server.localPort,true),unused) { _, _ -> Output() }.apply {
                stop().get(1,TimeUnit.SECONDS); activate(); assertFalse(inputReady)
            }
            assertTrue(unused.all { it == '\u0000' })
        }
    }
    @Test fun configurationPersistsEndpointButNotPasswordAndRejectsHostnames() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("projection",0); prefs.edit().clear().commit()
        val source = VncSource(context) { _, _ -> Output() }
        assertTrue(source.probe() is SourceAvailability.Unavailable)
        assertFalse(VncEndpoint("example.com",5900,false).valid())
        assertFalse(VncEndpoint("127.0.0.1",65536,false).valid())
        assertTrue(VncEndpoint("::1",5900,false).valid())
        val secret = "secret".toCharArray()
        source.configure(VncEndpoint("127.0.0.1",5901,true),secret)
        secret.fill('\u0000')
        assertEquals(setOf("vnc_host","vnc_port","vnc_read_only"),prefs.all.keys)
        val session = source.open().get()
        assertFalse(session.capabilities.keyboardInput); assertEquals(0,session.capabilities.maxTouchContacts)
        session.stop().get()
    }
}
