package moe.feng.wheelplay.carlife;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** USB outer framing is plaintext even when the inner payload uses CarLife 4.1 encryption. */
public final class CarLifeUsbTransport implements CarLifeTransport {
    /** Android AOA endpoints, or a test link. close must unblock input/output and connect. */
    public interface Link extends AutoCloseable {
        void connect() throws Exception;
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        @Override void close() throws IOException;
    }
    private final Link link;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writer = new Object();
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    /** All logical streams share this terminal read event; the command reader owns its reporting. */
    static final class ReceiveEndedException extends IOException {
        ReceiveEndedException(IOException cause) {
            super("USB receive ended: " + cause.getClass().getSimpleName() + ":" + cause.getMessage(), cause);
        }
    }
    boolean receiveEnded() { return failure.get() instanceof ReceiveEndedException; }
    private final Map<Integer, Channel> channels = new LinkedHashMap<>();
    private volatile Thread reader;
    public CarLifeUsbTransport(Link link) {
        this.link = Objects.requireNonNull(link);
        for (int port : Wire.PORTS) channels.put(port, new Channel(port == 8240 ? 8 * 1024 * 1024 : 512 * 1024));
    }
    @Override public boolean usb() { return true; }
    @Override public int connectionType() { return 2; }
    @Override public void connect() throws Exception {
        if (closed.get()) throw new IOException("USB transport closed");
        link.connect();
        synchronized (this) {
            if (closed.get()) { link.close(); throw new IOException("USB transport closed"); }
            if (reader != null) throw new IOException("USB already connected");
            reader = new Thread(this::receive, "carlife-usb-rx");
            reader.start();
        }
    }
    private static int channel(int port) throws IOException {
        for (int i = 0; i < Wire.PORTS.length; i++) if (Wire.PORTS[i] == port) return i + 1;
        throw new IOException("Unknown CarLife channel");
    }
    private static void validate(int port, byte[] inner) throws IOException {
        boolean command = port == 7240 || port == 9340 || port == 9440;
        int header = command ? 8 : 12;
        if (inner.length < header) throw new IOException("Truncated USB inner header");
        ByteBuffer bytes = ByteBuffer.wrap(inner);
        long length = command ? Short.toUnsignedInt(bytes.getShort()) : Integer.toUnsignedLong(bytes.getInt());
        if (length != inner.length - header) throw new IOException("USB inner length mismatch");
    }
    private void receive() {
        try {
            DataInputStream in = new DataInputStream(link.input());
            while (!closed.get()) {
                int channel = in.readInt(); long length = Integer.toUnsignedLong(in.readInt());
                if (channel < 1 || channel > Wire.PORTS.length) throw new IOException("Invalid USB channel: " + channel);
                int port = Wire.PORTS[channel - 1];
                int max = port == 8240 ? Wire.MAX_VIDEO + 12 : 256 * 1024 + 12;
                if (length < 8 || length > max) throw new IOException("Invalid USB frame length: " + length);
                byte[] inner = new byte[(int) length]; in.readFully(inner);
                validate(port, inner);
                // Input is an uplink channel. A few phones send UI sounds/acks here; drain them.
                if (port != 9340) channels.get(port).offer(inner);
            }
        } catch (Exception error) {
            IOException cause = error instanceof IOException ? (IOException) error : new IOException("USB receive failed", error);
            ReceiveEndedException ended = new ReceiveEndedException(cause);
            if (failure.compareAndSet(null, ended)) channels.values().forEach(c -> c.finish(ended));
        }
    }
    @Override public InputStream input(int port) throws IOException {
        Channel result = channels.get(port);
        if (result == null) throw new IOException("Unknown CarLife channel");
        return result;
    }
    @Override public void write(int port, byte[] message) throws IOException {
        validate(port, message);
        synchronized (writer) {
            if (closed.get()) throw new IOException("USB transport closed");
            IOException prior = failure.get();
            if (prior != null) throw prior;
            int id = channel(port);
            try {
                OutputStream out = link.output();
                // Never merge these writes: accessory fd readers can discard the remainder of a transfer.
                out.write(ByteBuffer.allocate(8).putInt(id).putInt(message.length).array());
                out.write(message);
            } catch (IOException error) {
                // A partially written frame cannot be repaired by sending another frame on this byte stream.
                // Publish the failure before releasing the writer lock so queued writers cannot overtake it.
                if (failure.compareAndSet(null, error)) {
                    channels.values().forEach(c -> c.abort(error));
                    try { link.close(); } catch (IOException cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
                }
                throw failure.get();
            }
        }
    }
    @Override public void close() throws IOException {
        Thread worker;
        synchronized (this) { closed.set(true); worker = reader; }
        channels.values().forEach(c -> c.abort(new EOFException("USB transport closed")));
        IOException failure = null;
        try { link.close(); } catch (IOException error) { failure = error; }
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(3000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("USB close interrupted", error); }
            if (worker.isAlive()) throw new IOException("USB reader did not stop");
        }
        if (failure != null) throw failure;
    }
    /** Never block the single USB pump behind a slow video/audio consumer. Fail on bounded overflow. */
    private static final class Channel extends InputStream {
        private final int limit;
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private int bytes, offset;
        private IOException ended;
        Channel(int limit) { this.limit = limit; }
        synchronized void offer(byte[] frame) throws IOException {
            if (ended != null) throw ended;
            if (bytes + frame.length > limit || queue.size() >= 256) throw new IOException("USB channel queue overflow");
            queue.add(frame); bytes += frame.length; notifyAll();
        }
        // Remote termination preserves complete frames, including a partially consumed queued frame.
        synchronized void finish(IOException failure) { if (ended == null) ended = failure; notifyAll(); }
        // Explicit cancellation and failed writes invalidate the session immediately.
        synchronized void abort(IOException failure) { ended = failure; queue.clear(); bytes = 0; offset = 0; notifyAll(); }
        @Override public int read() throws IOException { byte[] one = new byte[1]; read(one, 0, 1); return one[0] & 255; }
        @Override public synchronized int read(byte[] target, int start, int length) throws IOException {
            if (start < 0 || length < 0 || start > target.length - length) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            while (queue.isEmpty() && ended == null) try { wait(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("USB channel interrupted"); }
            if (queue.isEmpty()) throw ended;
            byte[] first = queue.peek(); int n = Math.min(length, first.length - offset);
            System.arraycopy(first, offset, target, start, n); offset += n; bytes -= n;
            if (offset == first.length) { queue.remove(); offset = 0; }
            return n;
        }
    }
}
