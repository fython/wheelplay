package com.shilapi.xcertplay.vnc

import com.shilapi.xcertplay.projection.ProjectionInput
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class RfbClientTest {
    private fun packet(block: DataOutputStream.() -> Unit) = RfbClient.packet(block)
    private fun DataOutputStream.init(version: Int = 8, security: Int = 1, width: Int = 3, height: Int = 2) {
        writeBytes("RFB 003.00$version\n")
        if (version == 3) writeInt(security) else { writeByte(1); writeByte(security) }
        if (security == 2) write(ByteArray(16) { it.toByte() })
        if (version == 8 || security == 2) writeInt(0)
        writeShort(width); writeShort(height); write(RfbClient.PIXEL_FORMAT); writeInt(4); writeBytes("test")
    }
    private fun client(bytes: ByteArray, sent: MutableList<ByteArray> = mutableListOf()) =
        RfbClient(DataInputStream(bytes.inputStream())) { sent.add(it) }
    private fun DataOutputStream.rect(x: Int, y: Int, w: Int, h: Int, encoding: Int) {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(encoding)
    }
    private fun DataOutputStream.pixel(red: Int) { writeByte(0); writeByte(0); writeByte(red); writeByte(0) }

    @Test fun negotiatesAllVersionsAndRequestsExplicitFormatAndSharedDesktop() {
        for (version in listOf(3, 7, 8)) {
            val sent = mutableListOf<ByteArray>()
            val c = client(packet { init(version) }, sent)
            assertEquals("test", c.initialize(charArrayOf()))
            assertEquals("RFB 003.00$version\n", sent[0].toString(Charsets.US_ASCII))
            assertArrayEquals(byteArrayOf(1), sent[sent.size - 3])
            assertArrayEquals(RfbClient.PIXEL_FORMAT, sent[sent.size - 2].copyOfRange(4, 20))
            c.request(false)
            assertArrayEquals(byteArrayOf(3,0,0,0,0,0,0,3,0,2), sent.last())
        }
    }
    @Test fun authenticatesAndErasesPassword() {
        val sent = mutableListOf<ByteArray>()
        val secret = "password".toCharArray()
        client(packet { init(security = 2) }, sent).initialize(secret)
        // Independent DES known-answer vector (VNC reverses each password byte).
        assertEquals("b866924125c8eebb9debc1db61c538e2", sent[2].joinToString("") { "%02x".format(it.toInt() and 255) })
        assertTrue(secret.all { it == '\u0000' })
    }
    @Test fun rejectsSecurityDowngradeAndUnsupportedSecurity() {
        val secret = "secret".toCharArray()
        assertThrows(IllegalArgumentException::class.java) { client(packet { init(version = 3) }).initialize(secret) }
        assertTrue(secret.all { it == '\u0000' })
        assertThrows(IllegalArgumentException::class.java) { client(packet { writeBytes("RFB 003.008\n"); writeByte(1); writeByte(19) }).initialize(charArrayOf()) }
    }
    @Test fun rawAndOverlappingCopyRectApplyInOrder() {
        val c = client(packet {
            init(); writeByte(0); writeShort(2)
            rect(0,0,3,2,0); (1..6).forEach { pixel(it) }
            rect(1,0,2,2,1); writeShort(0); writeShort(0)
        })
        c.initialize(charArrayOf())
        assertArrayEquals(intArrayOf(1,1,2,4,4,5).map { 0xff000000.toInt() or (it shl 16) }.toIntArray(), c.message(0)!!.snapshot!!.argb)
    }
    @Test fun hextileCarriesBackgroundAndForegroundAcrossEdgeTiles() {
        val c = client(packet {
            init(width=17,height=1); writeByte(0); writeShort(1); rect(0,0,17,1,5)
            writeByte(14); pixel(1); pixel(2); writeByte(1); writeByte(0); writeByte(0)
            writeByte(8); writeByte(1); writeByte(0); writeByte(0)
        })
        c.initialize(charArrayOf())
        val pixels = c.message(0)!!.snapshot!!.argb
        assertEquals(0xff020000.toInt(), pixels[0]); assertEquals(0xff010000.toInt(), pixels[1])
        assertEquals(0xff020000.toInt(), pixels[16])
    }
    @Test fun resizeDiscardsOldPixelsAndRequestsNewBounds() {
        val sent = mutableListOf<ByteArray>()
        val c = client(packet { init(); writeByte(0); writeShort(1); rect(0,0,4,3,-223) }, sent)
        c.initialize(charArrayOf())
        val update = c.message(0)!!
        assertTrue(update.resized); assertNull(update.snapshot); c.request(false)
        assertArrayEquals(byteArrayOf(3,0,0,0,0,0,0,4,0,3), sent.last())
    }
    @Test fun rejectsOutOfBoundsRectanglesOversizedDesktopAndTruncatedPixels() {
        assertThrows(IllegalArgumentException::class.java) { client(packet { init(width=8192,height=8192) }).initialize(charArrayOf()) }
        val c = client(packet { init(); writeByte(0); writeShort(1); rect(2,0,2,1,0) }); c.initialize(charArrayOf())
        assertThrows(IllegalArgumentException::class.java) { c.message(0) }
        val truncated = client(packet { init(); writeByte(0); writeShort(1); rect(0,0,1,1,0); writeByte(0) })
        truncated.initialize(charArrayOf()); assertThrows(EOFException::class.java) { truncated.message(0) }
    }
    @Test fun rejectsUnadvertisedEncodingAndOversizedClipboard() {
        val c = client(packet { init(); writeByte(0); writeShort(1); rect(0,0,1,1,16) }); c.initialize(charArrayOf())
        assertThrows(IOException::class.java) { c.message(0) }
        val clip = client(packet { write(ByteArray(3)); writeInt(Int.MAX_VALUE) })
        assertThrows(IllegalArgumentException::class.java) { clip.message(3) }
    }
    @Test fun pointerWheelKeysAndReleaseHaveCorrectWireValues() {
        val sent = mutableListOf<ByteArray>()
        val input = RfbInput(101,51) { sent.add(it); true }
        input.accept(ProjectionInput.Pointer(1.0,1.0,2))
        assertArrayEquals(byteArrayOf(5,4,0,100,0,50),sent.last())
        input.accept(ProjectionInput.Scroll(0.5,0.5,0,-1))
        assertEquals(12,sent[sent.lastIndex-1][1].toInt()); assertEquals(4,sent.last()[1].toInt())
        input.accept(ProjectionInput.Key("ArrowLeft",true))
        assertArrayEquals(byteArrayOf(4,1,0,0,0,0,-1,0x51),sent.last())
        input.accept(ProjectionInput.Release)
        assertArrayEquals(byteArrayOf(5,0,0,50,0,25),sent[sent.lastIndex-1])
        assertEquals(0,sent.last()[1].toInt())
        assertEquals(0x01004e2d, RfbInput.keysym("中"))
        assertNull(RfbInput.keysym("Dead")); assertNull(RfbInput.keysym("\ud800"))
    }
}
