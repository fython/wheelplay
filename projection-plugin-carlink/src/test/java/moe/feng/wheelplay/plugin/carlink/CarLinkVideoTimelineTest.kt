package moe.feng.wheelplay.plugin.carlink

import org.junit.Assert.*
import org.junit.Test

class CarLinkVideoTimelineTest {
    @Test fun negativeUsbOriginPreservesFrameIntervals() {
        val timeline = CarLinkVideoTimeline()
        assertEquals(0L, timeline.normalize(-14_686_710_400L))
        assertEquals(33_333L, timeline.normalize(-14_686_677_067L))
        assertEquals(66_667L, timeline.normalize(-14_686_643_733L))
    }
    @Test fun positiveOriginAndConfigurationPacketsAlsoStartAtZero() {
        val timeline = CarLinkVideoTimeline()
        assertEquals(0L, timeline.normalize(9_000_000L))
        assertEquals(0L, timeline.normalize(9_000_000L))
        assertEquals(33_333L, timeline.normalize(9_033_333L))
    }
    @Test fun rejectsValuesBeforeOriginAndOverflow() {
        val timeline = CarLinkVideoTimeline()
        timeline.normalize(-100)
        assertThrows(IllegalArgumentException::class.java) { timeline.normalize(-101) }
        assertThrows(IllegalArgumentException::class.java) { timeline.normalize(Long.MAX_VALUE) }
    }
}
