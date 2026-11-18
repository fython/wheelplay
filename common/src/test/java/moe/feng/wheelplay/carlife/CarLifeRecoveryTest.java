package moe.feng.wheelplay.carlife;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class CarLifeRecoveryTest {
    static class Observer implements CarLifeRecovery.Listener {
        final BlockingQueue<Long> frames = new LinkedBlockingQueue<>();
        final BlockingQueue<String> failures = new LinkedBlockingQueue<>();
        final CountDownLatch ended = new CountDownLatch(1);
        public void log(String s) {}
        public void waiting(String s) {}
        public void failed(String s) { failures.add(s); }
        public void video(byte[] b, long pts) { frames.add(pts); }
        public void ended() { ended.countDown(); }
    }
    static class Fake implements CarLifeRecovery.Attempt {
        final CarLifeSession.Listener listener;
        final BlockingQueue<Fake> starts;
        CompletableFuture<Void> cleanup = CompletableFuture.completedFuture(null);
        Fake(CarLifeSession.Listener listener, BlockingQueue<Fake> starts) {
            this.listener = listener; this.starts = starts;
        }
        public void start() { starts.add(this); }
        public CompletableFuture<Void> stop() { listener.ended(); return cleanup; }
        public void touch(int a, int x, int y) {}
        void fail(String reason) { listener.failed(reason); listener.ended(); }
    }
    @Test public void recoveryWaitsForCleanupAndRebasesSourceTimestamps() throws Exception {
        Observer observer = new Observer();
        BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
        CarLifeRecovery recovery = new CarLifeRecovery(observer, l -> new Fake(l, starts), 1, 10_000);
        recovery.start();
        Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
        first.listener.video(new byte[]{1}, 1000);
        long firstPts = observer.frames.poll(1, TimeUnit.SECONDS);
        first.cleanup = new CompletableFuture<>();
        first.fail("CHANNEL_FAILED port=7240 reason=SocketException:reset");
        assertNull(starts.poll(100, TimeUnit.MILLISECONDS));
        first.cleanup.complete(null);
        Fake second = starts.poll(1, TimeUnit.SECONDS); assertNotNull(second);
        second.listener.video(new byte[]{1}, 0);
        assertTrue(observer.frames.poll(1, TimeUnit.SECONDS) > firstPts);
        recovery.stop().get(1, TimeUnit.SECONDS);
        assertTrue(observer.failures.isEmpty());
    }
    @Test public void stopDuringRecoveryPreventsAnotherAttempt() throws Exception {
        Observer observer = new Observer();
        BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
        CarLifeRecovery recovery = new CarLifeRecovery(observer, l -> new Fake(l, starts), 10_000, 30_000);
        recovery.start();
        Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
        first.fail("CONNECT_FAILED ConnectException");
        recovery.stop().get(1, TimeUnit.SECONDS);
        recovery.start();
        assertTrue(starts.isEmpty());
    }
    @Test public void waitingAttemptCannotOutliveRecoveryDeadline() throws Exception {
        Observer observer = new Observer();
        BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
        CarLifeRecovery recovery = new CarLifeRecovery(observer, l -> new Fake(l, starts), 1, 50);
        recovery.start();
        Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
        first.fail("CONNECT_FAILED ConnectException");
        assertNotNull(starts.poll(1, TimeUnit.SECONDS));
        assertTrue(observer.ended.await(1, TimeUnit.SECONDS));
        assertTrue(observer.failures.poll().contains("超时"));
        recovery.stop().get(1, TimeUnit.SECONDS);
    }
    @Test public void protocolRejectionIsNotRetried() throws Exception {
        Observer observer = new Observer();
        BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
        CarLifeRecovery recovery = new CarLifeRecovery(observer, l -> new Fake(l, starts), 1, 10_000);
        recovery.start();
        Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
        first.fail("AUTH_REJECTED");
        assertTrue(observer.ended.await(1, TimeUnit.SECONDS));
        assertEquals("AUTH_REJECTED", observer.failures.poll());
        assertTrue(starts.isEmpty());
        recovery.stop().get(1, TimeUnit.SECONDS);
    }

    @Test public void autoFallsBackOnceAfterExplicitRejectionAndCleanup() throws Exception {
        Observer observer = new Observer();
        BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
        BlockingQueue<CarLifeProtocolMode> modes = new LinkedBlockingQueue<>();
        CarLifeRecovery recovery = new CarLifeRecovery(observer, (l, mode) -> {
            modes.add(mode); return new Fake(l, starts);
        }, CarLifeProtocolMode.AUTO, 1, 10_000);
        try {
            recovery.start();
            Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
            assertEquals(CarLifeProtocolMode.XIAOMI_4_1, modes.poll());
            first.cleanup = new CompletableFuture<>();
            first.fail("VERSION_REJECTED");
            assertNull(starts.poll(100, TimeUnit.MILLISECONDS));
            first.cleanup.complete(null);
            Fake second = starts.poll(1, TimeUnit.SECONDS); assertNotNull(second);
            assertEquals(CarLifeProtocolMode.LEGACY_3_0, modes.poll());
            second.fail("VERSION_REJECTED");
            assertTrue(observer.ended.await(1, TimeUnit.SECONDS));
            assertEquals("VERSION_REJECTED", observer.failures.poll());
            assertTrue(modes.isEmpty());
        } finally { recovery.stop().get(1, TimeUnit.SECONDS); }
    }
    @Test public void autoDoesNotDowngradeOnNetworkOrTimeoutOrAfterAcceptedVersion() throws Exception {
        for (String reason : new String[]{"CONNECT_FAILED ConnectException", "VERSION_TIMEOUT (no automatic retry)", "FIRST_VIDEO_TIMEOUT"}) {
            Observer observer = new Observer();
            BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
            BlockingQueue<CarLifeProtocolMode> modes = new LinkedBlockingQueue<>();
            CarLifeRecovery recovery = new CarLifeRecovery(observer, (l, mode) -> {
                modes.add(mode); return new Fake(l, starts);
            }, CarLifeProtocolMode.AUTO, 1, 10_000);
            try {
                recovery.start();
                Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
                assertEquals(CarLifeProtocolMode.XIAOMI_4_1, modes.poll());
                first.listener.log("VERSION_MATCHED 4.1 XIAOMI");
                first.fail(reason);
                Fake second = starts.poll(1, TimeUnit.SECONDS); assertNotNull(second);
                assertEquals(CarLifeProtocolMode.XIAOMI_4_1, modes.poll());
                second.fail("VERSION_REJECTED");
                assertTrue(observer.ended.await(1, TimeUnit.SECONDS));
                assertTrue(modes.isEmpty());
            } finally { recovery.stop().get(1, TimeUnit.SECONDS); }
        }
    }
    @Test public void authenticationDecryptionAndManualRejectionNeverDowngrade() throws Exception {
        for (String reason : new String[]{"AUTH_REJECTED", "PROTOCOL_FAILED IOException:XIAOMI authentication rejected", "CHANNEL_FAILED port=8240 reason=BadPaddingException:invalid", "PROTOCOL_FAILED IOException:XIAOMI invalid version result"}) {
            Observer observer = new Observer();
            BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
            CarLifeRecovery recovery = new CarLifeRecovery(observer, (l, mode) -> new Fake(l, starts), CarLifeProtocolMode.AUTO, 1, 10_000);
            try {
                recovery.start(); Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
                first.fail(reason);
                assertTrue(observer.ended.await(1, TimeUnit.SECONDS));
                assertEquals(reason, observer.failures.poll()); assertTrue(starts.isEmpty());
            } finally { recovery.stop().get(1, TimeUnit.SECONDS); }
        }
        Observer observer = new Observer();
        BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
        CarLifeRecovery recovery = new CarLifeRecovery(observer, (l, mode) -> new Fake(l, starts), CarLifeProtocolMode.XIAOMI_4_1, 1, 10_000);
        recovery.start(); Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
        first.fail("VERSION_REJECTED");
        assertTrue(observer.ended.await(1, TimeUnit.SECONDS)); assertTrue(starts.isEmpty());
        recovery.stop().get(1, TimeUnit.SECONDS);
    }
    @Test public void stopDuringFallbackCleanupPreventsPlaintextStart() throws Exception {
        Observer observer = new Observer();
        BlockingQueue<Fake> starts = new LinkedBlockingQueue<>();
        CarLifeRecovery recovery = new CarLifeRecovery(observer, (l, mode) -> new Fake(l, starts), CarLifeProtocolMode.AUTO, 1, 10_000);
        recovery.start(); Fake first = starts.poll(1, TimeUnit.SECONDS); assertNotNull(first);
        first.cleanup = new CompletableFuture<>(); first.fail("VERSION_REJECTED");
        CompletableFuture<Void> stopped = recovery.stop();
        first.cleanup.complete(null); stopped.get(1, TimeUnit.SECONDS);
        assertTrue(starts.isEmpty());
    }
}
