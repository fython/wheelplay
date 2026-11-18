package moe.feng.wheelplay.carlife;

import java.io.IOException;
import java.util.Set;
import java.util.function.Consumer;

/** One reader owns each channel. INIT describes PCM16 LE; media and TTS keep separate formats. */
final class CarLifeAudioChannel implements AutoCloseable {
    private static final Set<Integer> RATES = Set.of(8000, 11025, 12000, 16000, 22050, 24000,
        32000, 44100, 48000, 64000, 88200, 96000);
    private final int port, init, data, stop;
    private final CarLifeSession.AudioListener sink;
    private final Consumer<String> log;
    private int rate, channels;
    private boolean enabled, started, warnedMissingFormat, warnedUnknown;
    private long packets, bytes, nonSilentPackets, lastReport;

    CarLifeAudioChannel(int port, CarLifeSession.AudioListener sink, Consumer<String> log) {
        if (port != 9240 && port != 9241) throw new IllegalArgumentException("Not a playback channel");
        this.port = port; this.sink = sink; this.log = log;
        init = port == 9240 ? 0x30001 : 0x40001;
        data = port == 9240 ? 0x30006 : 0x40003;
        stop = port == 9240 ? 0x30002 : 0x40002;
    }

    void accept(int type, byte[] payload) throws Exception {
        if (type == init) {
            long nextRate = Wire.integer(payload, 1, -1);
            long nextChannels = Wire.integer(payload, 2, -1);
            long bits = Wire.integer(payload, 3, -1);
            if (nextRate < 8000 || nextRate > Integer.MAX_VALUE || !RATES.contains((int) nextRate) ||
                (nextChannels != 1 && nextChannels != 2) || bits != 16)
                throw new IOException("Unsupported CarLife PCM format: " + nextRate + "/" + nextChannels + "/" + bits);
            end();
            rate = (int) nextRate; channels = (int) nextChannels; enabled = true; warnedMissingFormat = false;
            log.accept("AUDIO_FORMAT port=" + port + " rate=" + rate + " channels=" + channels + " bits=16");
        } else if (type == stop || (port == 9240 && type == 0x30003)) {
            enabled = false;
            end();
            log.accept("AUDIO_" + (type == 0x30003 ? "PAUSE" : "STOP") + " port=" + port);
        } else if (port == 9240 && type == 0x30004) {
            enabled = rate != 0;
            log.accept("AUDIO_RESUME port=" + port);
        } else if (type == data && payload.length > 0) {
            if (rate == 0) {
                if (!warnedMissingFormat) { log.accept("AUDIO_WAIT_FORMAT port=" + port); warnedMissingFormat = true; }
                return; // Never guess a sample rate and play PCM at the wrong speed.
            }
            if (!enabled) return; // Discard any tail packets after PAUSE/STOP.
            if (payload.length > 256 * 1024 || payload.length % (channels * 2) != 0)
                throw new IOException("Invalid CarLife PCM frame alignment or length");
            if (!started) { sink.started(port, rate, channels); started = true; }
            sink.pcm(port, payload);
            packets++; bytes += payload.length;
            for (byte value : payload) if (value != 0) { nonSilentPackets++; break; }
            long now = System.nanoTime();
            if (packets == 1 || now - lastReport >= 5_000_000_000L) {
                lastReport = now;
                log.accept("AUDIO_COUNTERS port=" + port + " packets=" + packets + " bytes=" + bytes + " nonSilent=" + nonSilentPackets);
            }
        } else if (type != data && !warnedUnknown) {
            warnedUnknown = true;
            log.accept("AUDIO_UNKNOWN port=" + port + " type=" + type + " size=" + payload.length);
        }
    }

    private void end() throws Exception {
        if (started) { started = false; sink.stopped(port); }
    }
    @Override public void close() throws Exception { enabled = false; end(); }
}
