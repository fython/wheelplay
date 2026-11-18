package moe.feng.wheelplay.carlife;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoTimelineTest {
    @Test public void thirtyFramesKeepOneSecondOfMediaTimeAndDistinctRtpTimestamps() {
        VideoTimeline timeline = new VideoTimeline();
        long previousRtp = -1;
        for (int i = 0; i <= 30; i++) {
            long pts = timeline.accept(-7_000_000_000L + i * 1_000_000_000L / 30);
            assertEquals(i * 1_000_000L / 30, pts);
            long rtp = pts * 90 / 1000;
            assertTrue(rtp > previousRtp);
            previousRtp = rtp;
        }
        assertEquals(90000, previousRtp);
    }
    @Test public void burstFramesNeverCollapseToTheSameRtpTimestamp() {
        VideoTimeline timeline = new VideoTimeline();
        long previousRtp = -1;
        for (int i = 0; i < 100; i++) {
            long rtp = timeline.accept(1234) * 90 / 1000;
            assertTrue(rtp > previousRtp);
            previousRtp = rtp;
        }
    }
    @Test public void idleGapAndNewSessionArePreserved() {
        VideoTimeline timeline = new VideoTimeline();
        assertEquals(0, timeline.accept(5_000_000_000L));
        assertEquals(75_000, timeline.accept(5_075_000_000L));
        assertEquals(10_000_000, timeline.accept(15_000_000_000L));
        assertEquals(0, new VideoTimeline().accept(15_000_000_000L));
    }
}
