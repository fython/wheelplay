package moe.feng.wheelplay.plugin.carlink

import moe.feng.wheelplay.plugin.PluginContact
import org.junit.Assert.*
import org.junit.Test

class TouchTranslatorTest {
    @Test fun singleTouchMovesAndReleasesAtFinalCoordinates() {
        val t = TouchTranslator(1280, 720)
        val down = t.translate(listOf(PluginContact(17, 0.0, 0.0, true))).single()
        assertEquals(0, down.action); assertArrayEquals(intArrayOf(17), down.ids)
        val move = t.translate(listOf(PluginContact(17, .5, .5, true))).single()
        assertEquals(2, move.action); assertArrayEquals(intArrayOf(639), move.xs)
        val up = t.translate(listOf(PluginContact(17, 1.0, 1.0, false))).single()
        assertEquals(1, up.action); assertArrayEquals(intArrayOf(1279), up.xs); assertArrayEquals(intArrayOf(719), up.ys)
        assertTrue(t.translate(emptyList()).isEmpty())
    }
    @Test fun pointerIndexDoesNotUseTrackIdAndReorderingDoesNotSwapPointers() {
        val t = TouchTranslator(100, 100)
        val a = PluginContact(20, .1, .2, true); val b = PluginContact(3, .8, .9, true)
        val down = t.translate(listOf(a, b))
        assertEquals(listOf(0, 5 or (1 shl 8)), down.map { it.action })
        val move = t.translate(listOf(b, a)).single()
        assertArrayEquals(intArrayOf(20, 3), move.ids)
        val up = t.translate(listOf(b)).single()
        assertEquals(6, up.action); assertArrayEquals(intArrayOf(20, 3), up.ids)
        assertEquals(1, t.translate(emptyList()).single().action)
    }
    @Test fun replacementReleasesOldBeforePressingNew() {
        val t = TouchTranslator(10, 10)
        t.translate(listOf(PluginContact(0, 0.0, 0.0, true)))
        assertEquals(listOf(1, 0), t.translate(listOf(PluginContact(1, 0.0, 0.0, true))).map { it.action })
    }
    @Test fun malformedSnapshotDoesNotCorruptHeldPointers() {
        val t = TouchTranslator(10, 10)
        t.translate(listOf(PluginContact(0, 0.0, 0.0, true)))
        assertThrows(IllegalArgumentException::class.java) { t.translate(listOf(PluginContact(0, Double.NaN, 0.0, true))) }
        assertEquals(1, t.translate(emptyList()).single().action)
    }
    @Test fun emptySnapshotReleasesEveryPointer() {
        val t = TouchTranslator(10, 10)
        t.translate((0..9).map { PluginContact(it, .1, .1, true) })
        val releases = t.translate(emptyList())
        assertEquals(10, releases.size)
        assertEquals(1, releases.last().action)
        assertEquals((10 downTo 1).toList(), releases.map { it.ids.size })
    }
    @Test fun deviceAndCanvasGates() {
        assertNull(CarLinkEnvironment.unsupported(listOf("arm64-v8a"), 4096))
        assertNotNull(CarLinkEnvironment.unsupported(listOf("arm64-v8a"), 16384))
        assertNotNull(CarLinkEnvironment.unsupported(listOf("x86_64"), 4096))
        assertThrows(IllegalArgumentException::class.java) { CarLinkConfig(0, 720) }
        assertThrows(IllegalArgumentException::class.java) { CarLinkConfig(4096, 4096) }
    }
}
