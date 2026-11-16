package com.shilapi.xcertplay.projection.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginAdtsTest {
    private fun frame() = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x4c, 0x80.toByte(), 0x01, 0x7f, 0xfc.toByte(), 1,2,3,4)
    @Test fun separatesAccessUnitsAndRejectsPartialOrChangedFormat() {
        val bytes = frame()
        val frames = PluginAdts.frames(bytes + bytes, 48000, 2)
        assertEquals(2, frames.size); assertArrayEquals(bytes, frames[0])
        assertThrows(IllegalArgumentException::class.java) { PluginAdts.frames(bytes.dropLast(1).toByteArray(), 48000, 2) }
        assertThrows(IllegalArgumentException::class.java) { PluginAdts.frames(bytes, 44100, 2) }
        assertThrows(IllegalArgumentException::class.java) { PluginAdts.frames(bytes, 48000, 1) }
        assertThrows(IllegalArgumentException::class.java) { PluginAdts.frames(bytes.apply { this[0] = 0 }, 48000, 2) }
    }
}
