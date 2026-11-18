package moe.feng.wheelplay.carlife;

/** Per-channel monotonic timing. Reports metadata only, never audio contents. */
final class AudioArrivalStats {
    private final int port;
    private long lastArrival, windowStart, packets, maxGap, maxRead, maxHandle;
    private int burst, maxBurst;
    AudioArrivalStats(int port) { this.port = port; }
    void received(long beforeRead, long arrival) {
        if (windowStart == 0) windowStart = arrival;
        long gap = lastArrival == 0 ? 0 : arrival - lastArrival;
        burst = lastArrival != 0 && gap <= 5_000_000L ? burst + 1 : 1;
        maxBurst = Math.max(maxBurst, burst);
        maxGap = Math.max(maxGap, gap);
        maxRead = Math.max(maxRead, arrival - beforeRead);
        lastArrival = arrival;
        packets++;
    }
    String handled(long arrival, long done) {
        maxHandle = Math.max(maxHandle, done - arrival);
        if (done - windowStart < 5_000_000_000L) return null;
        String report = "AUDIO_ARRIVAL port=" + port + " packets=" + packets +
            " maxGapMs=" + maxGap / 1_000_000 + " maxReadMs=" + maxRead / 1_000_000 +
            " maxHandleMs=" + maxHandle / 1_000_000 + " maxBurst=" + maxBurst;
        windowStart = done;
        packets = maxGap = maxRead = maxHandle = 0;
        maxBurst = burst = 0;
        return report;
    }
}
