package moe.feng.wheelplay.plugin.carlink

import moe.feng.wheelplay.plugin.PluginAudioWire
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CarLinkAudioStateTest {
    private val messages = mutableListOf<PluginAudioWire.Message>()
    private val state = CarLinkAudioState(messages::add)
    private val format = PluginAudioWire.Format(1, 16000, 1)
    @Test fun pauseAndResumeUseFreshEpochAndNeverReuseSdkBuffers() {
        state.start(16, format)
        val first = messages.single() as PluginAudioWire.Start
        val bytes = byteArrayOf(1,2,3,4,5,6); val buffer = ByteBuffer.wrap(bytes).apply { position(2); limit(4) }
        state.data(16, buffer); bytes[2] = 99
        assertEquals(2, buffer.position())
        assertArrayEquals(byteArrayOf(3,4), (messages.last() as PluginAudioWire.Data).bytes)
        state.pause(16); assertEquals(PluginAudioWire.Stop(first.id), messages.last())
        val count = messages.size; state.data(16, ByteBuffer.wrap(ByteArray(4))); assertEquals(count, messages.size)
        state.resume(16); assertTrue((messages.last() as PluginAudioWire.Start).id > first.id)
        state.stopAll(); assertTrue(messages.last() is PluginAudioWire.Stop)
    }
    @Test fun microphoneStopsRejectLateOrMisalignedSamples() {
        state.microphone(format, false)
        val first = messages.last() as PluginAudioWire.Microphone
        assertTrue(state.acceptMicrophone(PluginAudioWire.MicrophoneData(first.id, ByteArray(640))))
        state.microphone(format, true)
        assertTrue(messages[messages.lastIndex - 1] is PluginAudioWire.MicrophoneStop)
        val second = messages.last() as PluginAudioWire.Microphone
        assertFalse(state.acceptMicrophone(PluginAudioWire.MicrophoneData(first.id, ByteArray(640))))
        assertThrows(IllegalArgumentException::class.java) { state.acceptMicrophone(PluginAudioWire.MicrophoneData(second.id, ByteArray(3))) }
        assertThrows(IllegalArgumentException::class.java) { state.acceptMicrophone(PluginAudioWire.MicrophoneData(second.id, ByteArray(16000))) }
        state.stopMicrophone(); assertFalse(state.acceptMicrophone(PluginAudioWire.MicrophoneData(second.id, ByteArray(640))))
    }
    @Test fun inputAndOutputMonoMasksAreDifferent() {
        assertEquals(1, CarLinkAudioFormats.channels(16, true))
        assertEquals(1, CarLinkAudioFormats.channels(4, false))
        assertEquals(2, CarLinkAudioFormats.channels(12, true))
        assertThrows(IllegalStateException::class.java) { CarLinkAudioFormats.channels(4, true) }
    }
    @Test fun replacingFormatStopsPreviousStreamAndUnknownDataIsIgnored() {
        state.data(16, ByteBuffer.wrap(ByteArray(4))); assertTrue(messages.isEmpty())
        state.start(16, format); state.start(16, PluginAudioWire.Format(2, 48000, 2))
        assertTrue(messages[1] is PluginAudioWire.Stop)
        assertTrue((messages[2] as PluginAudioWire.Start).id > (messages[0] as PluginAudioWire.Start).id)
    }
}
