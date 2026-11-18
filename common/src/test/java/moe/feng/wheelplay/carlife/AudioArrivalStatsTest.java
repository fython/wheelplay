package moe.feng.wheelplay.carlife;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioArrivalStatsTest {
    @Test public void distinguishesReadWaitFromHandlerDelayAndBurst() {
        AudioArrivalStats stats = new AudioArrivalStats(9240);
        stats.received(1_000_000_000L, 1_020_000_000L);
        assertNull(stats.handled(1_020_000_000L, 1_021_000_000L));
        stats.received(1_021_000_000L, 2_021_000_000L);
        assertNull(stats.handled(2_021_000_000L, 2_022_000_000L));
        stats.received(2_022_000_000L, 2_023_000_000L);
        assertNull(stats.handled(2_023_000_000L, 2_323_000_000L));
        stats.received(2_323_000_000L, 6_023_000_000L);
        String report = stats.handled(6_023_000_000L, 6_024_000_000L);
        assertTrue(report.contains("packets=4"));
        assertTrue(report.contains("maxGapMs=4000"));
        assertTrue(report.contains("maxReadMs=3700"));
        assertTrue(report.contains("maxHandleMs=300"));
        assertTrue(report.contains("maxBurst=2"));
    }
}
