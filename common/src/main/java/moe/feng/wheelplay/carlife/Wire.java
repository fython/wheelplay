package moe.feng.wheelplay.carlife;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Minimal CarLife framing/protobuf subset; see NOTICE.md for protocol references. */
final class Wire {
    static final int[] PORTS = {7240, 8240, 9240, 9241, 9242, 9340, 9440};
    static final int MAX_VIDEO = 4 * 1024 * 1024;
    static final byte[] EMPTY = new byte[0];
    static final class Packet {
        final int type;
        final long sequence;
        final byte[] body;
        Packet(int type, long sequence, byte[] body) { this.type = type; this.sequence = sequence; this.body = body; }
    }
    static Packet read(InputStream input, boolean command, int max) throws IOException {
        DataInputStream in = new DataInputStream(input);
        long length = command ? in.readUnsignedShort() : Integer.toUnsignedLong(in.readInt());
        long sequence = command ? in.readUnsignedShort() : Integer.toUnsignedLong(in.readInt());
        int type = in.readInt();
        if (length > max) throw new IOException("packet exceeds limit: " + length);
        byte[] body = new byte[(int) length];
        in.readFully(body);
        return new Packet(type, sequence, body);
    }
    static byte[] command(int type, byte[] body) throws IOException {
        if (body.length > 65535) throw new IOException("command too large");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(body.length); out.writeShort(0); out.writeInt(type); out.write(body);
        return bytes.toByteArray();
    }
    static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
    static byte[] varint(long n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int b = (int)(n & 127); n >>>= 7; out.write(b | (n == 0 ? 0 : 128)); } while (n != 0);
        return out.toByteArray();
    }
    static byte[] number(int field, long n) { return join(varint((long)field << 3), varint(n)); }
    static byte[] bytes(int field, byte[] b) { return join(varint(((long)field << 3) | 2), varint(b.length), b); }
    static byte[] string(int field, String s) { return bytes(field, s.getBytes(StandardCharsets.UTF_8)); }
    static final class Field {
        final int id; final long value; final byte[] data;
        Field(int id, long value, byte[] data) { this.id = id; this.value = value; this.data = data; }
    }
    static long readVarint(ByteArrayInputStream in) throws IOException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.read();
            if (b < 0 || (shift == 63 && (b & 254) != 0)) throw new IOException("invalid varint");
            result |= (long)(b & 127) << shift;
            if ((b & 128) == 0) return result;
        }
        throw new IOException("varint overflow");
    }
    static List<Field> fields(byte[] data) throws IOException {
        List<Field> out = new ArrayList<>();
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        while (in.available() > 0) {
            long tag = readVarint(in); int id = (int)(tag >>> 3); int kind = (int)(tag & 7);
            if (id <= 0 || tag >>> 3 > 536870911L) throw new IOException("invalid field");
            if (kind == 0) out.add(new Field(id, readVarint(in), null));
            else if (kind == 2) {
                long n = readVarint(in);
                if (n < 0 || n > in.available()) throw new IOException("truncated protobuf");
                byte[] b = new byte[(int)n]; new DataInputStream(in).readFully(b);
                out.add(new Field(id, 0, b));
            } else if (kind == 1 || kind == 5) {
                int n = kind == 1 ? 8 : 4;
                if (in.available() < n) throw new IOException("truncated fixed field");
                in.skip(n);
            } else throw new IOException("unsupported protobuf wire type");
        }
        return out;
    }
    static long integer(byte[] data, int field, long fallback) throws IOException {
        for (Field f : fields(data)) if (f.id == field && f.data == null) return f.value;
        return fallback;
    }
    static byte[] version() { return join(number(1, 3), number(2, 0)); }
    static byte[] statistics() { return join(string(1, "wheelplay"), string(2, "4.0.0"), number(3, 16), string(4, "20029999"), number(5, 1), number(6, 0), number(7, 0)); }
    static byte[] device() { return device(VideoSize.DEFAULT); }
    static byte[] device(VideoSize size) {
        return join(string(1, "Android"), string(2, "unknown"), string(3, "unknown"), string(4, "WheelPlay"),
            string(5, "arm64-v8a"), string(6, "arm64-v8a"), string(7, "CarLifeVehicle"), string(8, size.toString()));
    }
    static byte[] features() { return features(false); }
    static byte[] features(boolean microphone) {
        String[] keys = {"CONNECT_TYPE", "I_FRAME_INTERVAL", "DISABLE_RESOLUTION_OPTION", "CONTENT_ENCRYPTION"};
        // Bypass CarLife resolution presets; the vendor MediaFormat callback may still override this.
        int[] values = {5, 20, 1, 0};
        byte[] result = number(1, keys.length + (microphone ? 1 : 0));
        for (int i = 0; i < keys.length; i++) result = join(result, bytes(2, join(string(1, keys[i]), number(2, values[i]))));
        if (microphone) result = join(result, bytes(2, join(string(1, "VOICE_MIC"), number(2, 0))));
        return join(result, number(3, 0), string(4, "WheelPlay"));
    }
    static byte[] video() { return video(VideoSize.DEFAULT); }
    static byte[] video(VideoSize size) { return join(number(1, size.width), number(2, size.height), number(3, 30), number(9, 1)); }
}
