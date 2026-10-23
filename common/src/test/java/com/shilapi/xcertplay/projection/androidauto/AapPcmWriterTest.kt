package com.shilapi.xcertplay.projection.androidauto

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class AapPcmWriterTest {
    @Test fun partialWritesAndFullBufferPreserveEverySampleInOrder() {
        val pcm = ByteArray(640) { it.toByte() }
        val played = ByteArrayOutputStream()
        val results = ArrayDeque(listOf(64, 0, 0, 128, 0, 448))
        var pauses = 0
        AapPcmWriter.write(pcm.size, { true }, { offset, length ->
            val count = results.removeFirst()
            assertTrue(count <= length)
            played.write(pcm, offset, count)
            count
        }, { pauses++ })
        assertArrayEquals(pcm, played.toByteArray())
        assertEquals(3, pauses)
        assertTrue(results.isEmpty())
    }

    @Test fun stopOrFocusLossCancelsAFullBufferWithoutAnotherWrite() {
        var active = true
        var writes = 0
        AapPcmWriter.write(640, { active }, { _, _ -> writes++; 0 }, { active = false })
        assertEquals(1, writes)
    }

    @Test fun deadAudioDeviceIsReportedInsteadOfSilentlyDroppingPcm() {
        val error = assertThrows(IOException::class.java) {
            AapPcmWriter.write(640, { true }, { _, _ -> -6 }, { fail("must not retry an error") })
        }
        assertTrue(error.message!!.contains("-6"))
    }

    @Test fun permanentlyFullDeviceHasABoundedRetryBudget() {
        var writes = 0
        assertThrows(IOException::class.java) {
            AapPcmWriter.write(640, { true }, { _, _ -> writes++; 0 }, {})
        }
        assertEquals(400, writes)
    }
}
