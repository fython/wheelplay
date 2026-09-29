package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrowserAudioBridgeTest {
    private val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media")
    private val mic = MicrophoneConfig("telephony", 16_000, 1, 97, 20,
        InetAddress.getLoopbackAddress(), 12345, ByteArray(32))

    @Test fun onlyTheAuthenticatedOwnerCanRouteAudioOrFeedTheNegotiatedMicrophone() {
        val bridge = BrowserAudioBridge()
        val owner = Any(); val stranger = Any()
        val packets = mutableListOf<ByteArray>(); val controls = mutableListOf<String>()
        val route = bridge.createRoute()
        assertFalse(route.output(1, format, byteArrayOf(1,2,3,4), 0, 4))
        bridge.attach(owner, true, { packet, _ -> packets.add(packet) }, controls::add)
        bridge.ready(stranger, true, true)
        assertFalse(route.output(1, format, byteArrayOf(1,2,3,4), 0, 4))
        bridge.ready(owner, true, true)
        assertFalse("Browser readiness cannot enable App-disabled forwarding", route.output(1, format, byteArrayOf(1,2,3,4), 0, 4))
        bridge.configure(true, true)
        assertEquals("audio-route", org.json.JSONObject(controls.last()).getString("type"))
        bridge.ready(owner, true, true)
        assertTrue(route.output(1, format, byteArrayOf(1,2,3,4), 0, 4))
        val header = ByteBuffer.wrap(packets.single()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(BrowserAudioBridge.AUDIO_MAGIC, header.int)
        assertEquals(1, header.int); assertEquals(48_000, header.int); assertEquals(2, header.short.toInt())
        assertEquals(0, header.short.toInt()); assertEquals(1, header.int)
        assertArrayEquals(byteArrayOf(1,2,3,4), packets.single().copyOfRange(20, 24))
        var routeChanges = 0
        route.onMicrophoneRouteChanged { routeChanges++ }
        val input = route.microphone(2, mic)!!
        val id = org.json.JSONObject(controls.last()).getInt("id")
        val body = byteArrayOf(9,8,7,6)
        val wire = ByteBuffer.allocate(12 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(BrowserAudioBridge.MIC_MAGIC).putInt(id).putInt(0).put(body).array()
        assertFalse(bridge.input(stranger, wire))
        assertTrue(bridge.input(owner, wire))
        assertFalse("Replay must be ignored", bridge.input(owner, wire))
        val captured = ByteArray(8)
        assertEquals(4, input.read(captured))
        assertArrayEquals(body, captured.copyOf(4))
        bridge.configure(false, false)
        assertFalse(route.output(1, format, body, 0, body.size))
        assertEquals(-1, input.read(captured))
        bridge.configure(true, true)
        assertFalse("Re-enabling requires fresh browser readiness", route.output(1, format, body, 0, body.size))
        bridge.ready(owner, true, false)
        assertTrue("Audio can forward while microphone stays local", route.output(1, format, body, 0, body.size))
        bridge.detach(owner)
        assertFalse(route.output(1, format, body, 0, body.size))
        assertEquals(1, routeChanges)
        route.close()
    }
}
