package moe.feng.wheelplay.carlife;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class CarLifeAudioChannelTest {
    private static byte[] format(int rate, int channels, int bits) {
        return Wire.join(Wire.number(1, rate), Wire.number(2, channels), Wire.number(3, bits));
    }
    private static final class Sink implements CarLifeSession.AudioListener {
        final List<String> events = new ArrayList<>();
        @Override public void started(int port, int rate, int channels) { events.add("start:" + port + ":" + rate + ":" + channels); }
        @Override public void pcm(int port, byte[] bytes) { events.add("pcm:" + port + ":" + bytes.length); }
        @Override public void stopped(int port) { events.add("stop:" + port); }
        CarLifeAudioChannel channel(int port) { return new CarLifeAudioChannel(port, this, message -> {}); }
    }
    @Test public void mediaPauseAndResumeRetainFormatButDiscardPausedTail() throws Exception {
        Sink sink = new Sink(); CarLifeAudioChannel media = sink.channel(9240);
        media.accept(0x30001, format(44100, 2, 16));
        assertTrue(sink.events.isEmpty()); // Format alone must not take audio focus.
        media.accept(0x30006, new byte[8]);
        media.accept(0x30003, Wire.EMPTY);
        media.accept(0x30006, new byte[8]);
        media.accept(0x30004, Wire.EMPTY);
        media.accept(0x30006, new byte[4]);
        media.accept(0x30002, Wire.EMPTY);
        media.close();
        assertEquals(List.of("start:9240:44100:2", "pcm:9240:8", "stop:9240",
            "start:9240:44100:2", "pcm:9240:4", "stop:9240"), sink.events);
    }
    @Test public void ttsStaysIndependentWhileMediaIsPaused() throws Exception {
        Sink sink = new Sink(); CarLifeAudioChannel media = sink.channel(9240), tts = sink.channel(9241);
        media.accept(0x30001, format(48000, 2, 16)); media.accept(0x30006, new byte[4]);
        tts.accept(0x40001, format(16000, 1, 16)); tts.accept(0x40003, new byte[2]);
        media.accept(0x30003, Wire.EMPTY); tts.accept(0x40003, new byte[4]); tts.accept(0x40002, Wire.EMPTY);
        assertEquals(List.of("start:9240:48000:2", "pcm:9240:4", "start:9241:16000:1", "pcm:9241:2",
            "stop:9240", "pcm:9241:4", "stop:9241"), sink.events);
    }
    @Test public void newInitRetiresOldFormatBeforeNextSamples() throws Exception {
        Sink sink = new Sink(); CarLifeAudioChannel media = sink.channel(9240);
        media.accept(0x30001, format(48000, 2, 16)); media.accept(0x30006, new byte[4]);
        media.accept(0x30001, format(16000, 1, 16)); media.accept(0x30006, new byte[2]);
        media.close(); media.close();
        assertEquals(List.of("start:9240:48000:2", "pcm:9240:4", "stop:9240", "start:9240:16000:1",
            "pcm:9240:2", "stop:9240"), sink.events);
    }
    @Test public void pcmBeforeInitAndUnknownPacketsNeverBecomeSound() throws Exception {
        Sink sink = new Sink(); CarLifeAudioChannel media = sink.channel(9240);
        media.accept(0x30006, new byte[4]); media.accept(0x30004, Wire.EMPTY);
        media.accept(0x30006, new byte[4]); media.accept(0x30001, format(48000, 2, 16));
        media.accept(0x30099, new byte[4]); media.accept(0x30006, Wire.EMPTY);
        assertTrue(sink.events.isEmpty());
    }
    @Test public void malformedAndUnsupportedFormatsAreRejected() {
        CarLifeAudioChannel media = new Sink().channel(9240);
        for (byte[] value : List.of(format(48000, 2, 8), format(48000, 12, 16), format(1, 2, 16),
            Wire.number(1, 48000), new byte[] {8, (byte)128})) {
            assertThrows(IOException.class, () -> media.accept(0x30001, value));
        }
    }
    @Test public void invalidPcmNeverReachesSink() throws Exception {
        Sink sink = new Sink(); CarLifeAudioChannel media = sink.channel(9240);
        media.accept(0x30001, format(48000, 2, 16));
        assertThrows(IOException.class, () -> media.accept(0x30006, new byte[3]));
        assertThrows(IOException.class, () -> media.accept(0x30006, new byte[256 * 1024 + 4]));
        assertTrue(sink.events.isEmpty());
    }
}
