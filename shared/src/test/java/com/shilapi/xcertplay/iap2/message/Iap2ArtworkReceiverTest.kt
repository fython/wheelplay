package com.shilapi.xcertplay.iap2.message

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class Iap2ArtworkReceiverTest {
    private val replies = mutableListOf<ByteArray>()
    private val completed = mutableListOf<Triple<Long,Int,ByteArray>>()
    private var now = 0L
    private fun receiver(valid: Boolean = true) = Iap2ArtworkReceiver(replies::add,
        { revision, id, bytes -> completed.add(Triple(revision,id,bytes)); valid }, { now })
    private fun setup(size: Long = 4, id: Int = 128, type: Int = 2, version: Int = 2) =
        ByteBuffer.allocate(if (version == 1) 10 else 12).put(id.toByte()).put(4).putLong(size)
            .apply { if (version == 2) putShort(type.toShort()) }.array()
    private fun data(kind: Int, vararg bytes: Int, id: Int = 128) = byteArrayOf(id.toByte(),kind.toByte(), *bytes.map(Int::toByte).toByteArray())
    private fun replyKinds() = replies.map { it[1].toInt() and 255 }

    @Test fun setupCanPrecedeMetadataAndFragmentsCompleteExactlyOnce() {
        val r = receiver(); r.receive(setup()); assertTrue(replies.isEmpty())
        r.expect(128, 7); assertEquals(listOf(1), replyKinds())
        r.receive(data(0x80,255,216)); r.receive(data(0x40,255,217))
        assertEquals(listOf(1,5), replyKinds()); assertEquals(7L, completed.single().first)
        assertArrayEquals(byteArrayOf(-1,-40,-1,-39), completed.single().third)
        r.receive(data(0x40,1)); assertEquals(1, completed.size)
    }

    @Test fun changingTrackCancelsOldTransferAndLateChunksCannotCompleteIt() {
        val r = receiver(); r.expect(128, 1); r.receive(setup()); r.receive(data(0x80,255,216))
        r.expect(129, 2); r.receive(data(0x40,255,217))
        assertTrue(completed.isEmpty()); assertTrue(replyKinds().contains(2))
    }

    @Test fun initialSetupSurvivesMetadataThatDoesNotYetContainArtworkId() {
        val r = receiver()
        r.receive(setup())
        r.expect(null, 1) // Playback app / title arrive in separate incremental messages.
        r.expect(null, 2)
        assertTrue(replies.isEmpty())
        r.expect(128, 2)
        assertEquals(listOf(1), replyKinds())
        r.receive(data(0xc0,255,216,255,217))
        assertEquals(2L, completed.single().first)
        assertEquals(listOf(1,5), replyKinds())
    }

    @Test fun nextSetupSurvivesTrackChangeWhileOldTransferIsCancelled() {
        val r = receiver()
        r.expect(128, 1); r.receive(setup()); r.receive(data(0x80,255,216))
        r.receive(setup(id = 129)) // New file queue wins the race against new metadata.
        r.expect(null, 2)
        assertEquals(listOf(1,2), replyKinds())
        r.expect(129, 2)
        r.receive(data(0xc0,255,216,255,217, id = 129))
        assertEquals(129, completed.single().second)
        assertEquals(listOf(1,2,1,5), replyKinds())
    }

    @Test fun overLimitWrongTypeWrongOrderTruncationAndDecodeFailureAreRejected() {
        val r = receiver(false); r.expect(128, 1)
        r.receive(setup(Iap2ArtworkReceiver.MAX_BYTES + 1L)); r.receive(setup(type = 3))
        assertEquals(listOf(2,2), replyKinds())
        r.receive(setup()); r.receive(data(0,255,216)) // Missing FIRST.
        assertTrue(completed.isEmpty())
        r.receive(setup()); r.receive(data(0xc0,255,216,255)) // Truncated size.
        assertEquals(6, replyKinds().last())
        r.receive(setup()); r.receive(data(0xc0,255,216,255,217))
        assertEquals(6, replyKinds().last()) // Decoder rejected JPEG contents.
    }

    @Test fun versionOneArtworkTimeoutAndSenderCancellationAreHandled() {
        val r = receiver(); r.expect(128, 1); r.receive(setup(version = 1))
        now = 15001; r.expire(); assertEquals(2, replyKinds().last())
        r.receive(setup()); r.receive(data(2)); r.receive(data(0xc0,255,216,255,217))
        assertTrue(completed.isEmpty())
    }

    @Test fun failedStartRetiresOnlyThatTransferAndLaterArtworkStillCompletes() {
        val diagnostics = mutableListOf<String>()
        var failReply = true
        val r = Iap2ArtworkReceiver({ bytes ->
            if (failReply) throw java.io.IOException("Synthetic transport backpressure")
            replies.add(bytes)
        }, { revision, id, bytes -> completed.add(Triple(revision, id, bytes)); true }, { now }, diagnostics::add)
        r.expect(128, 1)
        r.receive(setup()) // Rejected START retires this transfer.
        assertTrue(diagnostics.any { it.contains("START SEND FAILED") })
        assertFalse(diagnostics.any { it.contains("START queued") })
        r.receive(data(0xc0,255,216,255,217)) // Late data cannot complete the retired transfer.
        assertTrue(completed.isEmpty())
        failReply = false
        r.expect(129, 2)
        r.receive(setup(id = 129))
        r.receive(data(0xc0,255,216,255,217, id = 129))
        assertEquals(listOf(1,5), replyKinds())
        assertEquals(129, completed.single().second)
    }

    @Test fun failedCancellationStillAllowsTheNextAdvertisedArtworkToStart() {
        var failCancel = false
        val r = Iap2ArtworkReceiver({ bytes ->
            if (failCancel && bytes[1].toInt() == 2) throw java.io.IOException("Synthetic queue timeout")
            replies.add(bytes)
        }, { revision, id, bytes -> completed.add(Triple(revision, id, bytes)); true }, { now })
        r.expect(128, 1); r.receive(setup())
        r.receive(setup(id = 129))
        failCancel = true
        r.expect(129, 2) // Called by the control loop; must not fail its connection.
        r.receive(data(0xc0,255,216,255,217, id = 129))
        assertEquals(listOf(1,1,5), replyKinds())
        assertEquals(2L, completed.single().first)
    }
}
