package com.shilapi.xcertplay.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RtcNativeTest {
    @Test fun nativeMediaBuildGathersBothCodecOffersAndReleasesPeers() {
        for (hevc in listOf(false, true, false, true)) {
            val events = LinkedBlockingQueue<Pair<String, String>>()
            val native = RtcNative { kind, value -> events.offer(kind to value) }
            val handle = native.create(if (hevc) "profile-id=1;tier-flag=0;level-id=120;tx-mode=SRST"
                else "profile-level-id=42e020;packetization-mode=1;level-asymmetry-allowed=1", hevc)
            assertNotEquals(0L, handle)
            try {
                native.begin(handle)
                val offer = events.poll(8, TimeUnit.SECONDS)
                assertNotNull("ICE must complete on a real Android network", offer)
                assertEquals("offer", offer!!.first)
                assertTrue(offer.second.contains(if (hevc) "H265/90000" else "H264/90000"))
                assertTrue(offer.second.contains("a=sendonly"))
                assertTrue(offer.second.contains("a=fingerprint:sha-256"))
                assertTrue(offer.second.contains("a=candidate:"))
                assertFalse(native.send(handle, byteArrayOf(0, 0, 0, 1, 0x65), 0))
            } finally { native.destroy(handle) }
        }
    }
}
