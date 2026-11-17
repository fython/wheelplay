package moe.feng.wheelplay.carlife;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** CarLife 3.x/4.x plaintext media protobufs, after command decryption. */
public final class CarLifeMedia {
    public static final class Snapshot {
        public final boolean available, playing;
        public final String title, artist, album;
        public final long durationMs, positionMs;
        private final byte[] cover;
        Snapshot(boolean available, boolean playing, String title, String artist, String album,
                long duration, long position, byte[] cover) {
            this.available = available; this.playing = playing;
            this.title = title; this.artist = artist; this.album = album;
            this.durationMs = duration; this.positionMs = position;
            this.cover = cover.clone();
        }
        public byte[] artwork() { return cover.clone(); }
    }
    private static final long MAX_TIME = 365L * 24 * 60 * 60 * 1000;
    private String title = "", artist = "", album = "", songId = "";
    private byte[] artwork = Wire.EMPTY;
    private long duration = -1, position = -1;
    private boolean available, playing;

    public static boolean supports(int type) { return type == 65589 || type == 65590 || type == 65574; }
    Snapshot accept(int type, byte[] body) throws IOException {
        if (!supports(type)) return null;
        List<Wire.Field> fields = Wire.fields(body);
        if (type == 65589) {
            String nextTitle = text(fields, 2), nextArtist = text(fields, 3), nextAlbum = text(fields, 4);
            String nextId = text(fields, 8);
            boolean changed = !nextId.equals(songId) || !nextTitle.equals(title) || !nextArtist.equals(artist) || !nextAlbum.equals(album);
            title = nextTitle; artist = nextArtist; album = nextAlbum; songId = nextId;
            long seconds = integer(fields, 6, -1);
            duration = seconds > 0 && seconds <= MAX_TIME / 1000 ? seconds * 1000 : -1;
            byte[] image = bytes(fields, 5);
            artwork = image.length <= 48 * 1024 ? image : Wire.EMPTY;
            available = !title.isEmpty() || !artist.isEmpty() || !album.isEmpty();
            if (changed || !available) position = -1;
        } else if (type == 65590) {
            // Standard CarLife uses seconds for duration and milliseconds for progress.
            long value = integer(fields, 1, -1);
            position = value >= 0 && value <= MAX_TIME ? value : -1;
        } else {
            boolean music = false;
            for (Wire.Field field : fields) if (field.id == 2 && field.data != null) {
                List<Wire.Field> status = Wire.fields(field.data);
                if (integer(status, 1, -1) == 3) {
                    long value = integer(status, 2, -1);
                    if (value == 0 || value == 1) { playing = value == 1; music = true; }
                }
            }
            if (!music) return null;
        }
        return new Snapshot(available, playing, title, artist, album, duration,
            duration > 0 && position >= 0 ? Math.min(position, duration) : position, artwork);
    }
    private static String text(List<Wire.Field> fields, int id) {
        String value = new String(bytes(fields, id), StandardCharsets.UTF_8);
        return value.substring(0, Math.min(value.length(), 1024));
    }
    private static byte[] bytes(List<Wire.Field> fields, int id) {
        for (Wire.Field f : fields) if (f.id == id && f.data != null) return f.data;
        return Wire.EMPTY;
    }
    private static long integer(List<Wire.Field> fields, int id, long fallback) {
        for (Wire.Field f : fields) if (f.id == id && f.data == null) return f.value;
        return fallback;
    }
}
