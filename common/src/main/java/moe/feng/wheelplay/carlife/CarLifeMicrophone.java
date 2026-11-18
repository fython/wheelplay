package moe.feng.wheelplay.carlife;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Demand-driven vivo voice uplink: PCM16 LE, 16 kHz mono on the VR socket. */
public final class CarLifeMicrophone implements AutoCloseable {
    public interface Listener {
        void started(Capture capture);
        void stopped(Capture capture);
    }
    public interface Capture { void pcm(byte[] bytes); }
    interface Transport { void write(byte[] packet) throws IOException; }
    private final Listener listener;
    private final Transport transport;
    private final Consumer<String> log, fail;
    private final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(8);
    private volatile Lease active;
    private volatile boolean closed;
    private Thread worker;
    private final class Lease implements Capture {
        volatile boolean enabled = true;
        long samples, packets, nonSilent, lastLog;
        public void pcm(byte[] bytes) {
            synchronized (CarLifeMicrophone.this) {
            if (closed || !enabled || active != this) return;
            if (bytes.length == 0 || bytes.length > 3200 || bytes.length % 2 != 0)
                throw new IllegalArgumentException("Invalid microphone PCM frame");
            if (!queue.offer(new Chunk(this, bytes.clone()))) {
                if (!closed && enabled) fail.accept("MICROPHONE_QUEUE_OVERFLOW");
            }
            }
        }
    }
    private static final class Chunk {
        final Lease lease; final byte[] bytes;
        Chunk(Lease lease, byte[] bytes) { this.lease = lease; this.bytes = bytes; }
    }
    CarLifeMicrophone(Listener listener, Transport transport, Consumer<String> log, Consumer<String> fail) {
        this.listener = listener; this.transport = transport; this.log = log; this.fail = fail;
    }
    /** The three core protocol commands carry no payload. Duplicate starts retain the lease. */
    synchronized void command(int type, byte[] payload) throws IOException {
        if (type != 65570 && type != 65572 && type != 65571) return;
        if (payload.length != 0) throw new IOException("Unexpected microphone command payload");
        if (closed) return;
        if (type == 65571) { end(); return; }
        if (active != null) return;
        active = new Lease();
        if (worker == null) { worker = new Thread(this::run, "carlife-microphone-tx"); worker.start(); }
        log.accept("MICROPHONE_START rate=16000 channels=1 bits=16 reason=" + type);
        listener.started(active);
    }
    private void run() {
        try {
            while (!closed) {
                Chunk chunk = queue.poll(200, TimeUnit.MILLISECONDS);
                if (chunk == null || !chunk.lease.enabled || active != chunk.lease) continue;
                Lease lease = chunk.lease;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(chunk.bytes.length + 12);
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(chunk.bytes.length); out.writeInt((int)(lease.samples / 16));
                out.writeInt(0x58001); out.write(chunk.bytes);
                // At most one in-flight packet can precede a stop; queued old leases are discarded.
                if (closed || !lease.enabled || active != lease) continue;
                transport.write(bytes.toByteArray());
                lease.samples += chunk.bytes.length / 2; lease.packets++;
                for (byte value : chunk.bytes) if (value != 0) { lease.nonSilent++; break; }
                long now = System.nanoTime();
                if (lease.packets == 1 || now - lease.lastLog >= 5_000_000_000L) {
                    lease.lastLog = now;
                    log.accept("MICROPHONE_SENT packets=" + lease.packets + " samples=" + lease.samples + " nonSilent=" + lease.nonSilent);
                }
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (Exception error) { if (!closed) fail.accept("MICROPHONE_FAILED " + error.getClass().getSimpleName()); }
    }
    private void end() {
        Lease old = active; active = null;
        if (old != null) {
            old.enabled = false; queue.clear();
            listener.stopped(old); log.accept("MICROPHONE_STOP");
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; end(); queue.clear();
        if (worker != null) worker.interrupt();
    }
    /** Caller closes the socket before joining to unblock a stalled transport write. */
    void awaitStopped() throws InterruptedException, IOException {
        Thread thread;
        synchronized (this) { thread = worker; }
        if (thread != null && thread != Thread.currentThread()) {
            thread.join(3000); if (thread.isAlive()) throw new IOException("Microphone writer did not stop");
        }
    }
}
