package moe.feng.wheelplay.plugin

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class PluginAudioWireTest {
    private val pcm = PluginAudioWire.Format(PluginAudioWire.PCM16, 48000, 2)
    @Test fun bidirectionalRecordsRetainFormatsEpochsAndPcmBytes() {
        val output = ByteArrayOutputStream(); val writer = PluginAudioWire.Writer(DataOutputStream(output))
        val messages = listOf(PluginAudioWire.Start(1, 16, pcm), PluginAudioWire.Data(1, byteArrayOf(1,2,3,4)),
            PluginAudioWire.Stop(1), PluginAudioWire.Microphone(2, pcm, true),
            PluginAudioWire.MicrophoneData(2, byteArrayOf(5,6,7,8)), PluginAudioWire.MicrophoneStop(2))
        messages.forEach(writer::write)
        val reader = PluginAudioWire.Reader(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        assertEquals(messages[0], reader.read())
        assertArrayEquals(byteArrayOf(1,2,3,4), (reader.read() as PluginAudioWire.Data).bytes)
        assertEquals(messages[2], reader.read()); assertEquals(messages[3], reader.read())
        assertArrayEquals(byteArrayOf(5,6,7,8), (reader.read() as PluginAudioWire.MicrophoneData).bytes)
        assertEquals(messages[5], reader.read())
    }
    @Test fun rejectsBadLengthsBeforeAllocatingAndRejectsTruncatedPackets() {
        for (size in listOf(-1, 0, PluginAudioWire.MAX_PACKET + 1, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream(); val out = DataOutputStream(bytes)
            out.writeInt(0x57504131); out.writeLong(0); out.writeByte(2); out.writeLong(1); out.writeInt(size)
            assertThrows(IllegalArgumentException::class.java) {
                PluginAudioWire.Reader(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))).read()
            }
        }
        val bytes = ByteArrayOutputStream(); val writer = PluginAudioWire.Writer(DataOutputStream(bytes))
        writer.write(PluginAudioWire.Data(1, ByteArray(20)))
        assertThrows(EOFException::class.java) {
            PluginAudioWire.Reader(DataInputStream(ByteArrayInputStream(bytes.toByteArray().dropLast(1).toByteArray()))).read()
        }
    }
    @Test fun rejectsSequenceReplayInvalidFormatsAndEncodedMicrophone() {
        val bytes = ByteArrayOutputStream(); val out = DataOutputStream(bytes)
        out.writeInt(0x57504131); out.writeLong(1); out.writeByte(3); out.writeLong(1)
        assertThrows(IllegalArgumentException::class.java) {
            PluginAudioWire.Reader(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))).read()
        }
        for ((rate, channels) in listOf(0 to 1, 48000 to 8, Int.MAX_VALUE to 2)) {
            assertThrows(IllegalArgumentException::class.java) { PluginAudioWire.Format(1, rate, channels) }
        }
        assertFalse(pcm.validPcm(ByteArray(3)))
        assertThrows(IllegalArgumentException::class.java) {
            PluginAudioWire.Writer(DataOutputStream(ByteArrayOutputStream())).write(
                PluginAudioWire.Microphone(1, PluginAudioWire.Format(2, 48000, 2), false))
        }
    }
}
