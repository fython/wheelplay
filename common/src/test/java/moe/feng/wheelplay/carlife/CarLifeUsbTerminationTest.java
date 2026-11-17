package moe.feng.wheelplay.carlife;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CarLifeUsbTerminationTest {
    private static final class TerminalLink implements CarLifeUsbTransport.Link {
        private final InputStream incoming;
        volatile boolean closed;
        TerminalLink(byte[] bytes, boolean ioFailure) {
            incoming = new InputStream() {
                final ByteArrayInputStream data = new ByteArrayInputStream(bytes);
                @Override public int read() throws IOException {
                    int value = data.read();
                    if (value < 0 && ioFailure) throw new IOException("injected USB disconnect");
                    return value;
                }
            };
        }
        public void connect() {}
        public InputStream input() { return incoming; }
        public OutputStream output() { return new ByteArrayOutputStream(); }
        public void close() { closed = true; }
    }
    private static byte[] rejection() throws IOException {
        byte[] inner = Wire.command(0x10002,Wire.number(1,0));
        return ByteBuffer.allocate(inner.length+8).putInt(1).putInt(inner.length).put(inner).array();
    }
    @Test public void autoFallbackProcessesFinalRejectionBeforeSharedEof() throws Exception { fallback(false); }
    @Test public void autoFallbackProcessesFinalRejectionBeforeSharedReadError() throws Exception { fallback(true); }
    private void fallback(boolean ioFailure) throws Exception {
        TerminalLink first = new TerminalLink(rejection(),ioFailure);
        PipedInputStream incoming = new PipedInputStream(); PipedOutputStream source = new PipedOutputStream(incoming);
        AtomicInteger attempts = new AtomicInteger(); AtomicReference<CarLifeUsbTransport> initial = new AtomicReference<>();
        CountDownLatch plaintextVersion = new CountDownLatch(1);
        BlockingQueue<String> failures = new LinkedBlockingQueue<>();
        CarLifeRecovery recovery = new CarLifeRecovery(new CarLifeRecovery.Listener() {
            public void waiting(String reason) {}
            public void log(String message) {
                if (message.equals("USB_CHANNELS_CONNECTED") && attempts.get() == 1) {
                    // Force an idle channel to see terminal input before any session reader is scheduled.
                    assertThrows(CarLifeUsbTransport.ReceiveEndedException.class, () -> initial.get().input(8240).read());
                }
                if (message.equals("VERSION_SENT 3.0")) plaintextVersion.countDown();
            }
            public void video(byte[] data,long time) {}
            public void failed(String message) { failures.add(message); }
            public void ended() {}
        },null,VideoSize.DEFAULT,CarLifeProtocolMode.AUTO,null,() -> {
            if (attempts.incrementAndGet() == 1) {
                CarLifeUsbTransport transport = new CarLifeUsbTransport(first); initial.set(transport); return transport;
            }
            assertTrue("old USB link must be closed before fallback",first.closed);
            return new CarLifeUsbTransport(new CarLifeUsbTransport.Link() {
                public void connect() {}
                public InputStream input() { return incoming; }
                public OutputStream output() { return new ByteArrayOutputStream(); }
                public void close() throws IOException { source.close(); incoming.close(); }
            });
        });
        try {
            recovery.start(); assertTrue("fallback missing: " + failures,plaintextVersion.await(5,TimeUnit.SECONDS));
            assertEquals(2,attempts.get()); assertTrue(failures.toString(),failures.isEmpty());
        } finally { recovery.stop().get(5,TimeUnit.SECONDS); source.close(); incoming.close(); }
    }
    @Test public void eofWithoutACommandStillEndsTheSession() throws Exception {
        CountDownLatch ended = new CountDownLatch(1); BlockingQueue<String> failures = new LinkedBlockingQueue<>();
        CarLifeSession session = new CarLifeSession(new CarLifeSession.Listener() {
            public void log(String message) {}
            public void video(byte[] data,long time) {}
            public void failed(String message) { failures.add(message); }
            public void ended() { ended.countDown(); }
        },null,null,VideoSize.DEFAULT,CarLifeProtocolMode.XIAOMI_4_1,null,
            new CarLifeUsbTransport(new TerminalLink(new byte[0],false)));
        try {
            session.start(); assertTrue(ended.await(3,TimeUnit.SECONDS));
            assertEquals(1,failures.size()); assertTrue(failures.peek(),failures.peek().startsWith("USB_CHANNEL_FAILED port=7240"));
        } finally { session.stop().get(3,TimeUnit.SECONDS); }
    }
}
