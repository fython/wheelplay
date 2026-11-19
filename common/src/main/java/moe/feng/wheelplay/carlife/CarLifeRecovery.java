package moe.feng.wheelplay.carlife;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Keeps the host transport alive while a manually prepared source returns. Never launches it. */
public final class CarLifeRecovery {
    public interface Listener extends CarLifeSession.Listener {
        void waiting(String reason);
    }
    interface Attempt {
        void start();
        default CompletableFuture<Boolean> mediaKey(int code) { return CompletableFuture.completedFuture(false); }
        CompletableFuture<Void> stop();
        void touch(int action, int x, int y);
        default void touch(int action, int x, int y, int px, int py) { throw new UnsupportedOperationException("multitouch"); }
    }
    interface Factory { Attempt create(CarLifeSession.Listener listener); }
    private final Listener listener;
    interface ModeFactory { Attempt create(CarLifeSession.Listener listener, CarLifeProtocolMode mode); }
    private final ModeFactory factory;
    private final CarLifeProtocolMode selection;
    private final long retryMillis, recoveryMillis;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Object lock = new Object();
    private Attempt current;
    private Thread worker;
    private long lastPts = -1;

    public CarLifeRecovery(Listener listener, CarLifeSession.AudioListener audio, VideoSize size) {
        this(listener, audio, size, CarLifeProtocolMode.LEGACY_3_0);
    }
    public CarLifeRecovery(Listener listener, CarLifeSession.AudioListener audio, VideoSize size, CarLifeProtocolMode mode) {
        this(listener, audio, size, mode, null);
    }
    public CarLifeRecovery(Listener listener, CarLifeSession.AudioListener audio, VideoSize size,
            CarLifeProtocolMode mode, CarLifeMicrophone.Listener microphone) {
        this(listener, audio, size, mode, microphone, "127.0.0.1");
    }
    public CarLifeRecovery(Listener listener, CarLifeSession.AudioListener audio, VideoSize size,
            CarLifeProtocolMode mode, CarLifeMicrophone.Listener microphone, String sourceHost) {
        this(listener, audio, size, mode, microphone, () -> new CarLifeTcpTransport(sourceHost));
    }
    public CarLifeRecovery(Listener listener, CarLifeSession.AudioListener audio, VideoSize size,
            CarLifeProtocolMode mode, CarLifeMicrophone.Listener microphone, CarLifeTransport.Factory transports) {
        this(listener, (target, attemptMode) -> {
            CarLifeSession session = new CarLifeSession(target, null, audio, size, attemptMode, microphone, transports.create());
            return new Attempt() {
                public void start() { session.start(); }
                public CompletableFuture<Boolean> mediaKey(int code) { return session.mediaKey(code); }
                public CompletableFuture<Void> stop() { return session.stop(); }
                public void touch(int action, int x, int y) { session.touch(action, x, y); }
                public void touch(int action, int x, int y, int px, int py) { session.touch(action, x, y, px, py); }
            };
        }, mode, 1500, 120_000);
    }
    CarLifeRecovery(Listener listener, Factory factory, long retryMillis, long recoveryMillis) {
        this(listener, (target, mode) -> factory.create(target), CarLifeProtocolMode.LEGACY_3_0, retryMillis, recoveryMillis);
    }
    CarLifeRecovery(Listener listener, ModeFactory factory, CarLifeProtocolMode selection, long retryMillis, long recoveryMillis) {
        this.listener = listener; this.factory = factory; this.selection = java.util.Objects.requireNonNull(selection);
        this.retryMillis = retryMillis; this.recoveryMillis = recoveryMillis;
    }
    public void start() {
        synchronized (lock) {
            if (stopped.get() || !started.compareAndSet(false, true)) return;
            worker = new Thread(this::run, "carlife-recovery");
            worker.start();
        }
    }
    private void run() {
        long deadline = 0;
        final long origin = System.nanoTime();
        int generation = 0;
        CarLifeProtocolMode activeMode = selection == CarLifeProtocolMode.AUTO ? CarLifeProtocolMode.XIAOMI_4_1 : selection;
        boolean protocolAccepted = false;
        Throwable cleanupError = null;
        try {
            while (!stopped.get()) {
                CompletableFuture<Void> ended = new CompletableFuture<>();
                AtomicReference<String> failure = new AtomicReference<>();
                AtomicBoolean hadVideo = new AtomicBoolean();
                AtomicBoolean versionAccepted = new AtomicBoolean();
                Attempt attempt = factory.create(new CarLifeSession.Listener() {
                    private long ptsOffset = Long.MIN_VALUE;
                    public void log(String message) {
                        if (message.startsWith("VERSION_MATCHED ")) versionAccepted.set(true);
                        listener.log(message);
                    }
                    public void media(CarLifeMedia.Snapshot state) { if (!stopped.get()) listener.media(state); }
                    public void goToDesktop() { if (!stopped.get()) listener.goToDesktop(); }
                    public void configured(int width, int height) { listener.configured(width, height); }
                    public void failed(String reason) { failure.compareAndSet(null, reason); }
                    public void video(byte[] bytes, long ptsUs) throws Exception {
                        if (stopped.get()) return;
                        // Previous attempt has fully joined before this reader is created.
                        // Preserve monotonic RTP timestamps across source clock resets.
                        if (ptsOffset == Long.MIN_VALUE) ptsOffset = Math.max(lastPts + 12,
                            TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - origin)) - ptsUs;
                        long pts = Math.max(ptsOffset + ptsUs, lastPts + 12);
                        listener.video(bytes, pts);
                        lastPts = pts;
                        hadVideo.set(true);
                    }
                    public void ended() { ended.complete(null); }
                }, activeMode);
                synchronized (lock) {
                    if (stopped.get()) { attempt.stop().get(8, TimeUnit.SECONDS); break; }
                    current = attempt;
                    listener.log("SOURCE_ATTEMPT generation=" + ++generation + " protocol=" + activeMode);
                    attempt.start();
                }
                if (deadline == 0) ended.get();
                else try {
                    // Bound only the wait for the next source. Once frames return, normal
                    // streaming can run indefinitely; the next disconnect gets a new budget.
                    while (!ended.isDone() && !hadVideo.get()) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) throw new TimeoutException();
                        try { ended.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS); }
                        catch (TimeoutException tick) { if (System.nanoTime() >= deadline && !hadVideo.get()) throw tick; }
                    }
                    ended.get();
                } catch (TimeoutException timeout) {
                    listener.failed("等待 CarLife 系统源恢复超时，请重新连接 CarLife");
                    break;
                }
                // An ended callback alone does not prove all socket/audio workers have joined.
                attempt.stop().get(8, TimeUnit.SECONDS);
                synchronized (lock) { if (current == attempt) current = null; }
                if (stopped.get()) break;
                String reason = failure.get();
                protocolAccepted |= versionAccepted.get() || hadVideo.get();
                if (selection == CarLifeProtocolMode.AUTO && activeMode == CarLifeProtocolMode.XIAOMI_4_1
                        && !protocolAccepted && "VERSION_REJECTED".equals(reason)) {
                    activeMode = CarLifeProtocolMode.LEGACY_3_0;
                    listener.log("AUTO_PROTOCOL_FALLBACK 4.1->3.0 reason=VERSION_REJECTED");
                    listener.waiting("源手机不支持 4.1，正在尝试 3.0；若未连接，请在系统车载中重新开始连接并返回 WheelPlay");
                    if (deadline == 0) deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(recoveryMillis);
                    continue;
                }
                if (!retryable(reason)) {
                    listener.failed(reason == null ? "CarLife 源已结束" : reason);
                    break;
                }
                long now = System.nanoTime();
                if (deadline == 0 || hadVideo.get()) {
                    deadline = now + TimeUnit.MILLISECONDS.toNanos(recoveryMillis);
                    listener.waiting("CarLife 源已断开，请在智能车载中重新开始连接并返回 WheelPlay；接收端将等待 2 分钟");
                }
                if (now >= deadline) { listener.failed("等待 CarLife 系统源恢复超时，请重新连接 CarLife"); break; }
                synchronized (lock) { if (!stopped.get()) lock.wait(retryMillis); }
            }
        } catch (Exception error) {
            cleanupError = error;
            listener.failed("CarLife 恢复失败：" + error.getClass().getSimpleName());
        } finally {
            stopped.set(true);
            Attempt remaining;
            synchronized (lock) { remaining = current; current = null; }
            if (remaining != null) try { remaining.stop().get(8, TimeUnit.SECONDS); }
            catch (Exception error) { if (cleanupError == null) cleanupError = error; }
            try { listener.ended(); } catch (Exception error) { if (cleanupError == null) cleanupError = error; }
            if (cleanupError == null) closed.complete(null); else closed.completeExceptionally(cleanupError);
        }
    }
    static boolean retryable(String reason) {
        return reason != null && (reason.startsWith("CONNECT_FAILED ") ||
            (reason.startsWith("CHANNEL_FAILED ") && (reason.contains("reason=SocketException:") || reason.contains("reason=EOFException:") || reason.contains("reason=SocketTimeoutException:"))) || reason.equals("VERSION_TIMEOUT (no automatic retry)") ||
            reason.equals("FIRST_VIDEO_TIMEOUT") || reason.startsWith("PROTOCOL_FAILED SocketException:"));
    }
    public void touch(int action, int x, int y) {
        synchronized (lock) { if (!stopped.get() && current != null) current.touch(action, x, y); }
    }
    public void touch(int action, int x, int y, int px, int py) {
        synchronized (lock) { if (!stopped.get() && current != null) current.touch(action, x, y, px, py); }
    }
    public CompletableFuture<Boolean> mediaKey(int code) {
        synchronized (lock) {
            return !stopped.get() && current != null ? current.mediaKey(code) : CompletableFuture.completedFuture(false);
        }
    }
    public CompletableFuture<Void> stop() {
        synchronized (lock) {
            stopped.set(true);
            if (current != null) current.stop();
            lock.notifyAll();
            if (!started.get()) closed.complete(null);
        }
        return closed;
    }
}
