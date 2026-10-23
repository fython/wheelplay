package com.shilapi.xcertplay.projection

import org.junit.Assert.*
import org.junit.Test

class ProjectionFramebufferTest {
    @Test fun allIncrementsSurviveCoalescingAndSnapshotsOwnTheirPixels() {
        val frame = ProjectionFramebuffer(3, 2)
        frame.update(0, 0, 3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val first = frame.snapshot()
        frame.update(2, 1, 1, 1, intArrayOf(9))
        frame.copy(0, 0, 1, 0, 2, 2)
        assertArrayEquals(intArrayOf(1, 1, 2, 4, 4, 5), frame.snapshot().argb)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), first.argb)
        first.argb.fill(0)
        assertEquals(1, frame.snapshot().argb[0])
        assertEquals(3L, frame.snapshot().revision)
    }
    @Test fun overlappingVerticalCopiesUseOriginalRows() {
        val frame = ProjectionFramebuffer(2, 3)
        frame.update(0, 0, 2, 3, intArrayOf(1, 2, 3, 4, 5, 6))
        frame.copy(0, 0, 0, 1, 2, 2)
        assertArrayEquals(intArrayOf(1, 2, 1, 2, 3, 4), frame.snapshot().argb)
        frame.copy(0, 1, 0, 0, 2, 2)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 3, 4), frame.snapshot().argb)
    }
    @Test fun invalidAndOversizedUpdatesAreRejectedBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) { ProjectionFramebuffer(Int.MAX_VALUE, 2) }
        val frame = ProjectionFramebuffer(2, 2)
        assertThrows(IllegalArgumentException::class.java) { frame.update(1, 1, 2, 2, IntArray(4)) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(Int.MAX_VALUE, 0, 0, 0, 1, 1) }
        assertEquals(0L, frame.snapshot().revision)
    }
}
