package moe.feng.wheelplay.carlife;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Test phone: forwards the USB frames to the independent seven-channel fake phone. */
final class UsbLoopbackLink implements CarLifeUsbTransport.Link {
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final ExecutorService pumps = Executors.newFixedThreadPool(7);
    private volatile boolean closed;
    private int channel, expected;
    volatile boolean failNextInputBody;
    @Override public void connect() throws Exception {
        for (int port : Wire.PORTS) {
            Socket socket = new Socket("127.0.0.1", port); sockets.put(port, socket);
            if (closed) { socket.close(); throw new IOException("closed"); }
            pumps.execute(() -> {
                try {
                    while (!closed) {
                        Wire.Packet packet = Wire.read(socket.getInputStream(), port == 7240 || port == 9340 || port == 9440, Wire.MAX_VIDEO);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
                        int header = port == 7240 || port == 9340 || port == 9440 ? 8 : 12;
                        int id = 1; while (Wire.PORTS[id - 1] != port) id++;
                        out.writeInt(id); out.writeInt(header + packet.body.length);
                        if (header == 8) { out.writeShort(packet.body.length); out.writeShort((int) packet.sequence); }
                        else { out.writeInt(packet.body.length); out.writeInt((int) packet.sequence); }
                        out.writeInt(packet.type); out.write(packet.body); incoming.add(bytes.toByteArray());
                    }
                } catch (IOException error) { if (!closed) incoming.add(new byte[0]); }
            });
        }
    }
    private final InputStream input = new InputStream() {
        private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);
        @Override public int read() throws IOException { byte[] one = new byte[1]; int n = read(one,0,1); return n < 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] b,int off,int len) throws IOException {
            if (len == 0) return 0;
            while (current.available() == 0) {
                if (closed) throw new EOFException();
                try {
                    byte[] frame = incoming.take(); if (frame.length == 0) throw new EOFException();
                    current = new ByteArrayInputStream(frame);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            }
            return current.read(b,off,Math.min(3,len)); // Deliberately fragment every outer/inner header.
        }
    };
    private final OutputStream output = new OutputStream() {
        @Override public void write(int value) throws IOException { throw new IOException("Expected whole transfer"); }
        @Override public void write(byte[] b,int off,int len) throws IOException {
            if (expected == 0) {
                if (len != 8) throw new IOException("USB header must be a separate transfer");
                ByteBuffer header = ByteBuffer.wrap(b,off,len); channel = header.getInt(); expected = header.getInt();
                if (channel < 1 || channel > 7 || expected < 8) throw new IOException("Bad outer frame");
            } else {
                if (len != expected) throw new IOException("Bad inner length");
                if (channel == 6 && failNextInputBody) {
                    failNextInputBody = false;
                    throw new IOException("injected USB input body failure");
                }
                sockets.get(Wire.PORTS[channel - 1]).getOutputStream().write(b,off,len); expected = 0;
            }
        }
    };
    @Override public InputStream input() { return input; }
    @Override public OutputStream output() { return output; }
    @Override public void close() throws IOException {
        closed = true; incoming.add(new byte[0]);
        for (Socket socket : sockets.values()) socket.close(); pumps.shutdownNow();
        try { if (!pumps.awaitTermination(2,TimeUnit.SECONDS)) throw new IOException("test USB pumps did not stop"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
    }
}
