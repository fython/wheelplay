package moe.feng.wheelplay.carlife;

import org.junit.Test;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CarLifeSessionTest {
    @Test public void captureThreadCanRequestSessionStopWithoutWaitingForItself() throws Exception {
        CountDownLatch requestStop = new CountDownLatch(1);
        CarLifeSession[] owner = new CarLifeSession[1];
        Thread capture = new Thread(() -> {
            try { requestStop.await(); owner[0].stop(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        owner[0] = new CarLifeSession(new CarLifeSession.Listener() {
            public void log(String message) {}
            public void video(byte[] data, long time) {}
            public void ended() {}
        }, null, null, VideoSize.DEFAULT, CarLifeProtocolMode.LEGACY_3_0,
            new CarLifeMicrophone.Listener() {
                public void started(CarLifeMicrophone.Capture lease) { capture.start(); }
                public void stopped(CarLifeMicrophone.Capture lease) {
                    try { capture.join(1000); }
                    catch (InterruptedException error) { throw new IllegalStateException(error); }
                    if (capture.isAlive()) throw new IllegalStateException("capture did not stop");
                }
            });
        java.lang.reflect.Field field = CarLifeSession.class.getDeclaredField("microphone");
        field.setAccessible(true);
        try {
            ((CarLifeMicrophone) field.get(owner[0])).command(65570, Wire.EMPTY);
            requestStop.countDown();
            capture.join(2000);
            assertFalse(capture.isAlive());
            owner[0].stop().get(5, TimeUnit.SECONDS);
        } finally {
            requestStop.countDown();
            owner[0].stop().get(5, TimeUnit.SECONDS);
            capture.join(2000);
        }
    }
    @Test public void microphoneStopFailureStillClosesTransportAndExecutors() throws Exception {
        AtomicInteger transportCloses = new AtomicInteger(), ends = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("capture cleanup failed");
        CarLifeSession session = new CarLifeSession(new CarLifeSession.Listener() {
            public void log(String message) {}
            public void video(byte[] data, long time) {}
            public void ended() { ends.incrementAndGet(); }
        }, null, null, VideoSize.DEFAULT, CarLifeProtocolMode.LEGACY_3_0,
            new CarLifeMicrophone.Listener() {
                public void started(CarLifeMicrophone.Capture capture) {}
                public void stopped(CarLifeMicrophone.Capture capture) { throw failure; }
            }, new CarLifeTransport() {
                public void connect() {}
                public java.io.InputStream input(int port) { return new java.io.ByteArrayInputStream(Wire.EMPTY); }
                public void write(int port, byte[] packet) {}
                public void close() { transportCloses.incrementAndGet(); }
            });
        java.lang.reflect.Field micField = CarLifeSession.class.getDeclaredField("microphone");
        micField.setAccessible(true);
        CarLifeMicrophone mic = (CarLifeMicrophone) micField.get(session);
        mic.command(65570, Wire.EMPTY);
        CompletableFuture<Void> stopped = session.stop();
        ExecutionException error = assertThrows(ExecutionException.class, () -> stopped.get(5, TimeUnit.SECONDS));
        assertSame(failure, error.getCause());
        assertSame(stopped, session.stop());
        assertEquals(1, transportCloses.get());
        assertEquals(1, ends.get());
        for (String name : new String[] { "control", "readers" }) {
            java.lang.reflect.Field field = CarLifeSession.class.getDeclaredField(name);
            field.setAccessible(true);
            assertTrue(((ExecutorService) field.get(session)).isTerminated());
        }
        mic.awaitStopped();
    }
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
        java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
        CarLifeTransport transport = new CarLifeTransport() {
            public void connect() {}
            public java.io.InputStream input(int port) { return new java.io.ByteArrayInputStream(new byte[0]); }
            public void write(int port, byte[] packet) throws java.io.IOException { written.write(packet); }
            public void close() {}
        };
        CarLifeSession session = new CarLifeSession(new CarLifeSession.Listener() {
            public void log(String message) {}
            public void video(byte[] data, long time) {}
            public void ended() {}
        }, null, null, VideoSize.DEFAULT, CarLifeProtocolMode.LEGACY_3_0, null, transport);
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
    @Test public void desktopCommandOnlyNotifiesWhileStreaming() throws Exception {
        java.lang.reflect.Method message = CarLifeSession.class.getDeclaredMethod("message", Wire.Packet.class);
        message.setAccessible(true);
        java.lang.reflect.Field streaming = CarLifeSession.class.getDeclaredField("streaming");
        streaming.setAccessible(true);
        Wire.Packet desktop = new Wire.Packet(0x10021, 0, Wire.EMPTY);
        for (CarLifeProtocolMode mode : new CarLifeProtocolMode[] {
                CarLifeProtocolMode.LEGACY_3_0, CarLifeProtocolMode.XIAOMI_4_1 }) {
            AtomicInteger desktopRequests = new AtomicInteger();
            CarLifeSession session = new CarLifeSession(new CarLifeSession.Listener() {
                public void log(String message) {}
                public void video(byte[] bytes, long ptsUs) {}
                public void ended() {}
                public void goToDesktop() { desktopRequests.incrementAndGet(); }
            }, null, null, VideoSize.DEFAULT, mode);
            try {
                message.invoke(session, desktop);
                assertEquals(0, desktopRequests.get());
                streaming.setBoolean(session, true);
                message.invoke(session, desktop);
                assertEquals(1, desktopRequests.get());
            } finally { session.stop().get(2, TimeUnit.SECONDS); }
        }
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
