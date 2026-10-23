package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.projection.ProjectionContact
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer

class AapProtocolTest {
    @Test fun protoParsesUnknownFixedFieldsAndRejectsTruncationAndOverflow() {
        assertEquals(300L, AapProto.number(byteArrayOf(8, 0xac.toByte(), 2), 1))
        assertEquals(-4L, AapProto.number(AapProto.build { number(1, -4) }, 1))
        assertEquals(7L, AapProto.number(byteArrayOf(0x15, 0, 0, 0, 0, 8, 7), 1))
        listOf(byteArrayOf(8, 0x80.toByte()), byteArrayOf(10, 5, 1), byteArrayOf(0),
            byteArrayOf(8) + ByteArray(11) { 0xff.toByte() }).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { AapProto.parse(bytes) }
        }
    }
    @Test fun headerReadsSplitStreamAndFirstFragmentTotalWithoutConsumingNextPacket() {
        val wire = byteArrayOf(2, 9, 0, 3, 0, 0, 0, 20, 1, 2, 3, 0, 3, 0, 2, 0, 2)
        val input = DataInputStream(object : ByteArrayInputStream(wire) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        })
        val first = AapFraming.read(input)
        assertEquals(20, first.total); assertArrayEquals(byteArrayOf(1, 2, 3), first.payload)
        assertEquals(2, AapFraming.type(AapFraming.read(input).payload))
        assertThrows(EOFException::class.java) { AapFraming.read(input) }
        assertThrows(IllegalArgumentException::class.java) { AapFraming.read(DataInputStream(ByteArrayInputStream(byteArrayOf(2, 9, 0, 1, 0x7f, -1, -1, -1)))) }
    }
    @Test fun transportFramingHasWireCompatibleControlMessageBytes() {
        val out = ByteArrayOutputStream()
        AapFraming.write(out, 0, 3, AapFraming.message(1, byteArrayOf(0, 1, 0, 2)))
        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 2), out.toByteArray())
    }
    @Test fun reassemblesTimestampedVideoAndKeepsContinuationBytesIncludingApparentTypes() {
        val assembler = AapMediaAssembler()
        val first = ByteBuffer.allocate(14).putShort(0).putLong(123456).put(byteArrayOf(0, 0, 0, 1)).array()
        assertNull(assembler.accept(9, first))
        assertNull(assembler.accept(8, byteArrayOf(0x65, 9, 8)))
        val frame = assembler.accept(10, byteArrayOf(0, 1, 0x11))!!
        assertEquals(123456L, frame.ptsUs)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 9, 8, 0, 1, 0x11), frame.data)
        assertThrows(IllegalStateException::class.java) { assembler.accept(10, byteArrayOf(1)) }
        assembler.accept(9, first)
        assertThrows(IllegalStateException::class.java) { assembler.accept(11, first) }
    }
    @Test fun touchPreservesPointerIdsAndUpIncludesReleasedPointer() {
        val touch = AapTouch(1280, 720)
        val first = ProjectionContact(2, .1, .2, true)
        val second = ProjectionContact(8, .3, .4, true)
        assertEquals(0, touch.update(listOf(first)).single().action)
        assertTrue(touch.update(listOf(first)).isEmpty())
        val down = touch.update(listOf(second, first)).single()
        assertEquals(5, down.action); assertEquals(1, down.index)
        assertEquals(listOf(2, 8), down.pointers.map { it.id })
        val up = touch.update(listOf(second)).single()
        assertEquals(6, up.action); assertEquals(0, up.index)
        assertEquals(listOf(2, 8), up.pointers.map { it.id })
        val end = touch.update(emptyList()).single()
        assertEquals(1, end.action); assertEquals(listOf(8), end.pointers.map { it.id })
        assertTrue(touch.update(emptyList()).isEmpty())
    }
    @Test fun simultaneousChangesProduceOrderedTransitionsAndWireCoordinates() {
        val touch = AapTouch(1280, 720)
        val events = touch.update(listOf(ProjectionContact(0, 1.0, 1.0, true), ProjectionContact(1, 0.0, 0.0, true)))
        assertEquals(listOf(0, 5), events.map { it.action })
        val report = AapProto.parse(touch.encode(events.last(), 123))
        assertEquals(123L, report.first { it.number == 1 }.value)
        val event = AapProto.parse(report.first { it.number == 3 }.bytes!!)
        val pointer = event.first { it.number == 1 }.bytes!!
        assertEquals(1279L, AapProto.number(pointer, 1)); assertEquals(719L, AapProto.number(pointer, 2))
        assertEquals(listOf(6, 1), touch.update(emptyList()).map { it.action })
    }
    @Test fun discoveryOnlyAdvertisesImplementedChannelsAndOptionalMicrophone() {
        fun channels(mic: Boolean) = AapProto.parse(AapServices.discovery(mic)).filter { it.number == 1 }
            .map { AapProto.number(it.bytes!!, 1).toInt() }.toSet()
        assertEquals(AapServices.channels, channels(false))
        assertEquals(AapServices.channels + 7, channels(true))
    }
}
