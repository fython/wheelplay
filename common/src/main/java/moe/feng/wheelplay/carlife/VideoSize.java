package moe.feng.wheelplay.carlife;

/** Requested and negotiated video canvas. Matches the host's bounded video dimensions. */
public final class VideoSize {
    public static final VideoSize DEFAULT = new VideoSize(1280, 720);
    public final int width, height;

    public VideoSize(int width, int height) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096 ||
            (long) width * height > 8_388_608L)
            throw new IllegalArgumentException("Unsupported CarLife video size: " + width + "x" + height);
        this.width = width;
        this.height = height;
    }

    public int clampX(int x) { return Math.max(0, Math.min(width - 1, x)); }
    public int clampY(int y) { return Math.max(0, Math.min(height - 1, y)); }
    @Override public boolean equals(Object other) {
        return other instanceof VideoSize && width == ((VideoSize) other).width && height == ((VideoSize) other).height;
    }
    @Override public int hashCode() { return 31 * width + height; }
    @Override public String toString() { return width + "x" + height; }
}
