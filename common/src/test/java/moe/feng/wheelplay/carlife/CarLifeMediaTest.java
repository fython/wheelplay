package moe.feng.wheelplay.carlife;

import org.junit.Test;
import static org.junit.Assert.*;

public class CarLifeMediaTest {
    private byte[] song(String id, String title) {
        return Wire.join(Wire.string(8, id), Wire.string(2, title), Wire.string(3, "Artist"),
            Wire.string(4, "Album"), Wire.number(6, 180), Wire.bytes(5, new byte[]{1, 2, 3}));
    }
    private byte[] status(int module, int value) {
        return Wire.join(Wire.number(1, 1), Wire.bytes(2, Wire.join(Wire.number(1, module), Wire.number(2, value))));
    }
    @Test public void metadataProgressAndMusicStateHaveCorrectUnits() throws Exception {
        CarLifeMedia media = new CarLifeMedia();
        assertFalse(media.accept(65574, status(3, 1)).available);
        CarLifeMedia.Snapshot state = media.accept(65589, song("1", "歌曲"));
        assertTrue(state.available); assertTrue(state.playing);
        assertEquals("歌曲", state.title); assertEquals("Artist", state.artist); assertEquals("Album", state.album);
        assertEquals(180000, state.durationMs); assertEquals(-1, state.positionMs);
        assertEquals(12400, media.accept(65590, Wire.number(1, 12400)).positionMs);
        assertFalse(media.accept(65574, status(3, 0)).playing);
        assertNull(media.accept(65574, status(2, 1)));
        assertEquals(12400, media.accept(65589, song("1", "歌曲")).positionMs);
        assertEquals(-1, media.accept(65589, song("2", "Next")).positionMs);
    }
    @Test public void emptyMetadataClearsTrackAndArtworkIsOwned() throws Exception {
        CarLifeMedia media = new CarLifeMedia();
        CarLifeMedia.Snapshot first = media.accept(65589, song("1", "Title"));
        first.artwork()[0] = 99;
        assertEquals(1, first.artwork()[0]);
        CarLifeMedia.Snapshot cleared = media.accept(65589, Wire.EMPTY);
        assertFalse(cleared.available); assertEquals(-1, cleared.durationMs); assertEquals(0, cleared.artwork().length);
    }
    @Test public void invalidAndOversizedValuesStayBounded() throws Exception {
        CarLifeMedia media = new CarLifeMedia();
        CarLifeMedia.Snapshot state = media.accept(65589, Wire.join(Wire.string(2, new String(new char[2000]).replace('\0', 'a')),
            Wire.number(6, Long.MAX_VALUE), Wire.bytes(5, new byte[50000])));
        assertEquals(1024, state.title.length()); assertEquals(-1, state.durationMs); assertEquals(0, state.artwork().length);
        assertEquals(-1, media.accept(65590, Wire.number(1, Long.MAX_VALUE)).positionMs);
        try { media.accept(65589, new byte[]{18, 5, 1}); fail("truncated protobuf accepted"); }
        catch (java.io.IOException expected) {}
    }
}
