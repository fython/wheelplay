package com.shilapi.xcertplay.airplay

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BplistCodecTest {
    @Test fun roundTripsProtocolMetadataAndAllSupportedLeafTypes() {
        val value = linkedMapOf("name" to "车机", "ascii" to "test", "enabled" to true,
            "disabled" to false, "number" to 0xffffffffL, "real" to 1.25,
            "streams" to listOf(mapOf("type" to 110L)), "data" to byteArrayOf(1, 2, 3))
        val result = BplistCodec.decode(BplistCodec.encode(value)) as Map<*, *>
        value.filterKeys { it != "data" }.forEach { (key, expected) -> assertEquals(expected, result[key]) }
        assertArrayEquals(value["data"] as ByteArray, result["data"] as ByteArray)
        val float = plist(listOf(byteArrayOf(0x22) + ByteBuffer.allocate(4).putFloat(2.5f).array()))
        assertEquals(2.5f, BplistCodec.decode(float))
        assertEquals(-1L, BplistCodec.decode(plist(listOf(byteArrayOf(0x13) + ByteArray(8) { -1 }))))
    }

    @Test fun rejectsSelfAndMutualCycles() {
        reject(plist(listOf(byteArrayOf(0xa1.toByte(), 0))))
        reject(plist(listOf(byteArrayOf(0xa1.toByte(), 1), byteArrayOf(0xa1.toByte(), 0))))
    }

    @Test fun acceptsSharedReferencesButBoundsExponentialExpansion() {
        assertEquals(listOf(true, true), BplistCodec.decode(plist(listOf(byteArrayOf(0xa2.toByte(), 1, 1), byteArrayOf(9)))))
        val nodes = (0 until 20).map { byteArrayOf(0xa2.toByte(), (it + 1).toByte(), (it + 1).toByte()) } + listOf(byteArrayOf(9))
        reject(plist(nodes))
    }

    @Test fun boundsDepthBeforeStackExhaustion() {
        fun chain(depth: Int) = plist((0 until depth).map { byteArrayOf(0xa1.toByte(), (it + 1).toByte()) } + listOf(byteArrayOf(9)))
        assertNotNull(BplistCodec.decode(chain(BplistCodec.MAX_DEPTH)))
        reject(chain(BplistCodec.MAX_DEPTH + 1))
    }

    @Test fun boundsRepeatedLargeLeafCopies() {
        val data = byteArrayOf(0x4f, 0x12, 0, 0x10, 0, 0) + ByteArray(1024 * 1024)
        val array = byteArrayOf(0xaf.toByte(), 0x10, 20) + ByteArray(20) { 1 }
        reject(plist(listOf(array, data)))
    }

    @Test fun rejectsUntrustedTrailerCountsWidthsOffsetsAndRootBeforeAllocation() {
        val valid = plist(listOf(byteArrayOf(9)))
        for (field in listOf(8, 16, 24)) {
            for (value in listOf(-1L, Long.MAX_VALUE, 0x100000001L)) {
                reject(valid.copyOf().also { ByteBuffer.wrap(it).putLong(it.size - 32 + field, value) })
            }
        }
        for (field in listOf(6, 7)) for (width in listOf(0, 9, 255)) {
            reject(valid.copyOf().also { it[it.size - 32 + field] = width.toByte() })
        }
        reject(valid.copyOf().also { ByteBuffer.wrap(it).putInt(9, 0) })
        reject(valid.copyOf().also { ByteBuffer.wrap(it).putInt(9, it.size - 32) })
        reject(ByteArray(BplistCodec.MAX_INPUT_BYTES + 1))
    }

    @Test fun rejectsTruncatedObjectsAndInvalidLengthsReferencesAndKeys() {
        for (node in listOf(
            byteArrayOf(0x13, 1), byteArrayOf(0x14), byteArrayOf(0x22, 1),
            byteArrayOf(0x4f, 0x13, 0x7f, -1, -1, -1, -1, -1, -1, -1),
            byteArrayOf(0x4f, 0x20, 1), byteArrayOf(0x4f), byteArrayOf(0x52, 65),
            byteArrayOf(0x61, 1), byteArrayOf(0xa1.toByte(), -1), byteArrayOf(0xa2.toByte(), 0),
        )) reject(plist(listOf(node)))
        reject(plist(listOf(byteArrayOf(0xd1.toByte(), 1, 1), byteArrayOf(9))))
        val valid = BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 110))))
        for (size in 0 until valid.size) reject(valid.copyOf(size))
    }

    @Test fun boundedRandomInputsNeverEscapeAsVmErrorsOrIndexErrors() {
        val random = java.util.Random(42)
        repeat(2000) {
            val node = ByteArray(1 + random.nextInt(128)).also(random::nextBytes)
            try { BplistCodec.decode(plist(listOf(node))) } catch (_: IllegalArgumentException) { }
        }
    }

    private fun reject(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
    }

    private fun plist(nodes: List<ByteArray>): ByteArray {
        val table = 8 + nodes.sumOf { it.size }
        val output = ByteBuffer.allocate(table + nodes.size * 4 + 32)
        output.put("bplist00".toByteArray())
        nodes.forEach(output::put)
        var offset = 8
        nodes.forEach { output.putInt(offset); offset += it.size }
        output.put(ByteArray(6)).put(4).put(1).putLong(nodes.size.toLong()).putLong(0).putLong(table.toLong())
        return output.array()
    }
}
