package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class Iap2FileTransferLinkTest {
    private fun pair(): Pair<Iap2LinkEngine, Iap2LinkEngine> {
        val a = Iap2LinkEngine(); val b = Iap2LinkEngine()
        a.start(false, 0); b.start(false, 0)
        repeat(6) {
            b.feed(a.takeOutput(), 0); a.feed(b.takeOutput(), 0)
        }
        assertEquals(Iap2LinkEngine.State.NORMAL, a.state())
        assertEquals(Iap2LinkEngine.State.NORMAL, b.state())
        while (a.pollEvent() != null) {}
        while (b.pollEvent() != null) {}
        return a to b
    }

    @Test fun fileAndNavigationPayloadsUseTheSameLinkWithSeparateEvents() {
        val (a,b) = pair()
        val navigation = byteArrayOf(0x52,1,0)
        val file = byteArrayOf(-128,4,0,0)
        a.sendControl(navigation, 1); a.sendFileTransfer(file, 1)
        b.feed(a.takeOutput(), 1)
        assertArrayEquals(navigation, (b.pollEvent() as Iap2LinkEngine.Event.Control).bytes)
        assertArrayEquals(file, (b.pollEvent() as Iap2LinkEngine.Event.FileTransfer).bytes)
        assertNull(b.pollEvent())
    }

    @Test fun missingFilePacketIsRetransmittedBeforeLaterControlPayloadIsDelivered() {
        val (a,b) = pair()
        val file = byteArrayOf(-128,-64,-1,-40,-1,-39)
        a.sendFileTransfer(file, 1)
        a.takeOutput() // Simulate a lost datagram.
        a.sendControl(byteArrayOf(0x52,1), 2)
        b.feed(a.takeOutput(), 2)
        assertNull(b.pollEvent()) // Control packet waits in the common sequence window.
        a.feed(b.takeOutput(), 3)
        a.advanceTime(20000)
        b.feed(a.takeOutput(), 20000)
        val received = mutableListOf<Iap2LinkEngine.Event>()
        while (true) received.add(b.pollEvent() ?: break)
        assertTrue(received.any { it is Iap2LinkEngine.Event.FileTransfer && it.bytes.contentEquals(file) })
        assertTrue(received.any { it is Iap2LinkEngine.Event.Control })
    }
}
