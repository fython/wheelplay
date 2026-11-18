package moe.feng.wheelplay.plugin

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class EncodedVideoWireTest {
    @Test fun formatAndFramesRoundTripWithoutTimestampConversion() {
        val bytes = ByteArrayOutputStream(); val out = DataOutputStream(bytes)
        EncodedVideoWire.writeFormat(out, 1456, 1544)
        EncodedVideoWire.writeFrame(out, EncodedVideoWire.Frame(0, 4_341_746_144L, byteArrayOf(1, 2, 3)))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1456 to 1544, EncodedVideoWire.readFormat(input))
        val frame = EncodedVideoWire.readFrame(input)
        assertEquals(0L, frame.sequence); assertEquals(4_341_746_144L, frame.ptsUs)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.bytes)
    }
    @Test fun invalidLengthsAreRejectedBeforeAllocationAndTruncationFails() {
        for (length in listOf(-1, 0, EncodedVideoWire.MAX_ACCESS_UNIT + 1, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream(); val out = DataOutputStream(bytes)
            out.writeLong(0); out.writeLong(1); out.writeInt(length)
            assertThrows(IllegalArgumentException::class.java) {
                EncodedVideoWire.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            }
        }
        val bytes = ByteArrayOutputStream(); val out = DataOutputStream(bytes)
        out.writeLong(0); out.writeLong(1); out.writeInt(10); out.writeByte(1)
        assertThrows(EOFException::class.java) {
            EncodedVideoWire.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }
}
