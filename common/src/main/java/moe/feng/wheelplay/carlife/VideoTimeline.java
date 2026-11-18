package moe.feng.wheelplay.carlife;

/** Single reader ownership. The sender's wire timestamp has only whole-second precision. */
final class VideoTimeline {
    private boolean initialized;
    private long originNs, previousUs;

    long accept(long receivedAtNs) {
        if (!initialized) {
            initialized = true;
            originNs = receivedAtNs;
            return previousUs = 0;
        }
        // Preserve actual arrival spacing and idle gaps. A 12us minimum also keeps
        // distinct pictures distinct after integer conversion to the 90kHz RTP clock.
        previousUs = Math.max((receivedAtNs - originNs) / 1000, previousUs + 12);
        return previousUs;
    }
}
