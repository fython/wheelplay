package moe.feng.wheelplay.carlife;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class WireTest {
    @Test public void commandUsesNetworkByteOrderAndHandlesFragmentedInput() throws Exception {
        byte[] bytes = {0,2,0,0,0,1,0,2,8,1};
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(1, len));
            }
        };
        Wire.Packet packet = Wire.read(fragmented, true, 100);
        assertEquals(65538, packet.type);
        assertEquals(1, Wire.integer(packet.body, 1, -1));
        assertArrayEquals(bytes, Wire.command(65538, new byte[]{8,1}));
    }
    @Test public void streamHeaderKeepsUnsignedTimestamp() throws Exception {
        byte[] bytes = {0,0,0,1, (byte)255,(byte)255,(byte)255,(byte)254, 0,2,0,1, 7};
        Wire.Packet packet = Wire.read(new ByteArrayInputStream(bytes), false, 100);
        assertEquals(4294967294L, packet.sequence);
        assertEquals(131073, packet.type);
        assertArrayEquals(new byte[]{7}, packet.body);
    }
    @Test public void rejectsOversizedStreamBeforeReadingPayload() {
        assertThrows(IOException.class, () -> Wire.read(new ByteArrayInputStream(
            new byte[]{(byte)255,(byte)255,(byte)255,(byte)255,0,0,0,0,0,0,0,0}), false, Wire.MAX_VIDEO));
    }
    @Test public void rejectsTruncatedPacket() {
        assertThrows(EOFException.class, () -> Wire.read(new ByteArrayInputStream(
            new byte[]{0,2,0,0,0,1,0,2,8}), true, 100));
    }
    @Test public void rejectsMalformedProtobuf() {
        assertThrows(IOException.class, () -> Wire.fields(new byte[]{10,4,1}));
        assertThrows(IOException.class, () -> Wire.fields(new byte[]{0}));
        assertThrows(IOException.class, () -> Wire.fields(new byte[]{8,(byte)128}));
        assertThrows(IOException.class, () -> Wire.fields(new byte[]{9,1,2}));
        assertThrows(IOException.class, () -> Wire.fields(new byte[]{8,
            (byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,2}));
    }
    @Test public void skipsUnknownFixedFields() throws Exception {
        assertEquals(3, Wire.integer(new byte[]{21,0,0,0,0,8,3}, 1, -1));
    }
    @Test public void statisticsHaveAllSevenRequiredFields() throws Exception {
        assertEquals(7, Wire.fields(Wire.statistics()).size());
        assertEquals(16, Wire.integer(Wire.statistics(), 3, -1));
        assertEquals(0, Wire.integer(Wire.statistics(), 7, -1));
    }
    @Test public void featureConfigEnablesVehicleResolution() throws Exception {
        byte[] body = Wire.features();
        int count = 0;
        boolean found = false;
        for (Wire.Field entry : Wire.fields(body)) {
            if (entry.id != 2) continue;
            count++;
            for (Wire.Field field : Wire.fields(entry.data)) {
                if (field.id == 1 && "DISABLE_RESOLUTION_OPTION".equals(
                        new String(field.data, java.nio.charset.StandardCharsets.UTF_8))) {
                    assertEquals(1, Wire.integer(entry.data, 2, -1));
                    found = true;
                }
            }
        }
        assertTrue(found);
        assertEquals(count, Wire.integer(body, 1, -1));
    }
    @Test public void videoRequestDefaultsTo720p() throws Exception {
        assertEquals(1280, Wire.integer(Wire.video(), 1, -1));
        assertEquals(720, Wire.integer(Wire.video(), 2, -1));
        assertEquals(30, Wire.integer(Wire.video(), 3, -1));
    }
    @Test public void videoRequestAndDeviceInfoUseRequestedCanvas() throws Exception {
        VideoSize size = new VideoSize(1920, 1080);
        assertEquals(1920, Wire.integer(Wire.video(size), 1, -1));
        assertEquals(1080, Wire.integer(Wire.video(size), 2, -1));
        assertTrue(Wire.fields(Wire.device(size)).stream().anyMatch(field -> field.id == 8 &&
            new String(field.data, java.nio.charset.StandardCharsets.UTF_8).equals("1920x1080")));
    }
    @Test public void negotiatedCanvasValidatesLimitsAndClampsTouchToActualSize() {
        VideoSize size = new VideoSize(1920, 1080);
        assertEquals(1919, size.clampX(2000));
        assertEquals(1079, size.clampY(2000));
        assertEquals(0, size.clampX(-1));
        assertEquals(0, size.clampY(-1));
        assertThrows(IllegalArgumentException.class, () -> new VideoSize(0, 720));
        assertThrows(IllegalArgumentException.class, () -> new VideoSize(4097, 720));
        assertThrows(IllegalArgumentException.class, () -> new VideoSize(4096, 4096));
    }
}
