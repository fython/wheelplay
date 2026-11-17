package moe.feng.wheelplay.carlife;

import org.junit.Test;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CarLifeSessionTest {
    private CarLifeSession session(AtomicInteger ends, boolean throwOnEnd) {
        return new CarLifeSession(new CarLifeSession.Listener() {
            @Override public void log(String message) {}
            @Override public void video(byte[] data, long time) {}
            @Override public void ended() {
                ends.incrementAndGet();
                if (throwOnEnd) throw new IllegalStateException("decoder already failed");
            }
        }, new File("unused-capture"));
    }
    @Test public void earlyEncoderRequestWaitsForWrittenFeatures() throws Exception {
        CarLifeSession session = session(new AtomicInteger(), false);
        java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
        java.lang.reflect.Field sockets = CarLifeSession.class.getDeclaredField("sockets");
        sockets.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Integer, java.net.Socket> channels =
            (java.util.Map<Integer, java.net.Socket>) sockets.get(session);
        channels.put(7240, new java.net.Socket() {
            @Override public java.io.OutputStream getOutputStream() { return written; }
        });
        java.lang.reflect.Field matched = CarLifeSession.class.getDeclaredField("matched");
        matched.setAccessible(true);
        matched.setBoolean(session, true);
        java.lang.reflect.Method request = CarLifeSession.class.getDeclaredMethod("requestVideo");
        java.lang.reflect.Method features = CarLifeSession.class.getDeclaredMethod("sendFeatures");
        request.setAccessible(true);
        features.setAccessible(true);
        try {
            request.invoke(session);
            assertEquals(0, written.size());
            features.invoke(session);
            request.invoke(session);
            java.io.InputStream input = new java.io.ByteArrayInputStream(written.toByteArray());
            assertEquals(98386, Wire.read(input, true, 4096).type);
            assertEquals(98311, Wire.read(input, true, 4096).type);
            assertEquals(0, input.available());
        } finally { session.stop().get(2, TimeUnit.SECONDS); }
    }
    @Test public void concurrentStopsShareOneCompletionAndNotifyOnce() throws Exception {
        AtomicInteger ends = new AtomicInteger();
        CarLifeSession session = session(ends, false);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            Future<CompletableFuture<Void>> first = callers.submit(session::stop);
            Future<CompletableFuture<Void>> second = callers.submit(session::stop);
            assertSame(first.get(), second.get());
            first.get().get(2, TimeUnit.SECONDS);
            assertEquals(1, ends.get());
        } finally { callers.shutdownNow(); }
    }
    @Test public void cleanupFailureCompletesFutureRatherThanHanging() throws Exception {
        CarLifeSession session = session(new AtomicInteger(), true);
        ExecutionException error = assertThrows(ExecutionException.class,
            () -> session.stop().get(2, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof IllegalStateException);
    }
    @Test public void cancelledSessionCannotRestart() throws Exception {
        AtomicInteger ends = new AtomicInteger();
        CarLifeSession session = session(ends, false);
        session.stop().get(2, TimeUnit.SECONDS);
        session.start();
        session.touch(0, 30, 40);
        session.key(30);
        assertEquals(1, ends.get());
    }
}
