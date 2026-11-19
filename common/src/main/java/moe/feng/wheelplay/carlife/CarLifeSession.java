package moe.feng.wheelplay.carlife;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One attempt over TCP or USB; version fallback belongs to CarLifeRecovery. */
public final class CarLifeSession {
    public static final int MAX_VIDEO_BYTES = Wire.MAX_VIDEO;
    private static final int GO_TO_DESKTOP = 0x10021;
    public interface Listener {
        default void configured(int width, int height) {}
        default void failed(String message) {}
        default void media(CarLifeMedia.Snapshot state) {}
        default void goToDesktop() {}
        void log(String message);
        // Session-relative microseconds from complete-frame arrival, not the coarse wire timestamp.
        void video(byte[] annexB, long ptsUs) throws Exception;
        void ended();
    }
    /** Optional downlink sink. The media and navigation readers can call it concurrently. */
    public interface AudioListener {
        void started(int port, int sampleRate, int channels) throws Exception;
        void pcm(int port, byte[] bytes) throws Exception;
        void stopped(int port) throws Exception;
    }
    private final CarLifeMedia mediaState = new CarLifeMedia();
    private final CarLifeTransport transport;
    private final XiaomiHandshake xiaomi;
    private final Listener listener;
    private final AudioListener audio;
    private final CarLifeMicrophone microphone;
    private final ExecutorService readers = Executors.newFixedThreadPool(8);
    private final ScheduledThreadPoolExecutor control = new ScheduledThreadPoolExecutor(1);
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final File capture;
    private VideoSize requestedSize;
    private volatile VideoSize videoSize;
    private volatile boolean matched, configured, streaming;
    private boolean infoSeen, handshakeStarted, featuresSent;
    private int lastX, lastY;
    private boolean touching, genericTouch, secondTouch;
    private int lastPointerX, lastPointerY;
    private final Set<Integer> seen = new HashSet<>();

    public CarLifeSession(Listener listener, File capture) { this(listener, capture, null); }
    public CarLifeSession(Listener listener, File capture, AudioListener audio) {
        this(listener, capture, audio, VideoSize.DEFAULT);
    }
    public CarLifeSession(Listener listener, File capture, AudioListener audio, VideoSize requestedSize) {
        this(listener, capture, audio, requestedSize, CarLifeProtocolMode.LEGACY_3_0);
    }
    public CarLifeSession(Listener listener, File capture, AudioListener audio, VideoSize requestedSize, CarLifeProtocolMode mode) {
        this(listener, capture, audio, requestedSize, mode, null);
    }
    public CarLifeSession(Listener listener, File capture, AudioListener audio, VideoSize requestedSize,
            CarLifeProtocolMode mode, CarLifeMicrophone.Listener microphoneListener) {
        this(listener, capture, audio, requestedSize, mode, microphoneListener, "127.0.0.1");
    }
    public CarLifeSession(Listener listener, File capture, AudioListener audio, VideoSize requestedSize,
            CarLifeProtocolMode mode, CarLifeMicrophone.Listener microphoneListener, String sourceHost) {
        this(listener, capture, audio, requestedSize, mode, microphoneListener, new CarLifeTcpTransport(sourceHost));
    }
    public CarLifeSession(Listener listener, File capture, AudioListener audio, VideoSize requestedSize,
            CarLifeProtocolMode mode, CarLifeMicrophone.Listener microphoneListener, CarLifeTransport transport) {
        this.transport = Objects.requireNonNull(transport);
        if (mode == CarLifeProtocolMode.AUTO) throw new IllegalArgumentException("AUTO requires CarLifeRecovery");
        xiaomi = Objects.requireNonNull(mode) == CarLifeProtocolMode.XIAOMI_4_1 ? new XiaomiHandshake(microphoneListener != null, transport.usb()) : null;
        microphone = microphoneListener == null ? null : new CarLifeMicrophone(microphoneListener, packet -> {
            if (closing.get()) return;
            transport.write(9242, xiaomi == null ? packet : xiaomi.voice(packet));
        }, listener::log, reason -> { if (!usbReceiveEnded()) fail(reason); });
        this.listener = listener; this.capture = capture; this.audio = audio;
        this.requestedSize = Objects.requireNonNull(requestedSize);
    }
    public void start() {
        if (closing.get() || !started.compareAndSet(false, true)) return;
        try { readers.execute(() -> {
            try {
                transport.connect();
                if (closing.get()) { transport.close(); return; }
                if (transport.usb()) listener.log("USB_CHANNELS_CONNECTED");
                else {
                    for (int port : Wire.PORTS) listener.log("PORT_CONNECTED " + port);
                    listener.log("SEVEN_CHANNELS_CONNECTED");
                }
                for (int port : Wire.PORTS) if (port != 9340) readers.execute(() -> read(port));
                // The engine may still be admitting the seven channels. Repeat only this
                // idempotent version request, within one fixed attempt deadline.
                control.scheduleWithFixedDelay(() -> safe(() -> {
                    if (!matched) { send(98305, xiaomi == null ? Wire.version() : XiaomiHandshake.version()); listener.log("VERSION_SENT " + (xiaomi == null ? "3.0" : "4.1 XIAOMI")); }
                }), 300, 1500, TimeUnit.MILLISECONDS);
                control.scheduleWithFixedDelay(() -> safe(() -> {
                    if (matched) send(131074, Wire.EMPTY);
                }), 1, 1, TimeUnit.SECONDS);
                control.schedule(() -> { if (!matched) fail("VERSION_TIMEOUT (no automatic retry)"); }, 12, TimeUnit.SECONDS);
                control.schedule(() -> { if (frames.get() == 0) fail("FIRST_VIDEO_TIMEOUT"); }, 30, TimeUnit.SECONDS);
                control.scheduleWithFixedDelay(() -> listener.log("COUNTERS frames=" + frames.get() + " bytes=" + bytes.get()), 5, 5, TimeUnit.SECONDS);
            } catch (Exception e) { fail("CONNECT_FAILED " + e.getClass().getSimpleName() + ":" + e.getMessage()); }
        }); } catch (RejectedExecutionException e) { stop(); }
    }
    private void read(int port) {
        CarLifeAudioChannel sound = audio != null && (port == 9240 || port == 9241)
            ? new CarLifeAudioChannel(port, audio, listener::log) : null;
        try (OutputStream dump = port == 8240 && capture != null ? new FileOutputStream(capture) : new OutputStream() { @Override public void write(int b) {} }) {
            InputStream in = transport.input(port);
            VideoTimeline timeline = new VideoTimeline();
            AudioArrivalStats arrivalStats = sound == null ? null : new AudioArrivalStats(port);
            while (!stopped.get()) {
                long beforeRead = sound == null ? 0 : System.nanoTime();
                Wire.Packet p = Wire.read(in, port == 7240 || port == 9440, port == 8240 ? Wire.MAX_VIDEO : 256 * 1024);
                long arrival = sound == null ? 0 : System.nanoTime();
                if (arrivalStats != null) arrivalStats.received(beforeRead, arrival);
                if (port == 7240) enqueue(() -> message(p));
                else if (port == 8240 && p.body.length > 0) {
                    if (xiaomi != null && p.type != 0x20001) continue;
                    byte[] frame = xiaomi == null ? p.body : xiaomi.media(port, p.type, p.body);
                    long ptsUs = timeline.accept(System.nanoTime());
                    long n = frames.incrementAndGet();
                    long total = bytes.addAndGet(frame.length);
                    if (n == 1) listener.log("FIRST_VIDEO type=" + p.type + " size=" + frame.length);
                    if (total <= 16 * 1024 * 1024) dump.write(frame);
                    listener.video(frame, ptsUs);
                } else if (sound != null) sound.accept(p.type, xiaomi == null ? p.body : xiaomi.media(port, p.type, p.body));
                if (arrivalStats != null) {
                    String report = arrivalStats.handled(arrival, System.nanoTime());
                    if (report != null) listener.log(report);
                }
                // Unused voice/data channels are drained. No audio payload or device metadata is recorded.
            }
        } catch (Exception e) {
            String reason = "CHANNEL_FAILED port=" + port + " reason=" + e.getClass().getSimpleName() + ":" + e.getMessage();
            if (e instanceof CarLifeUsbTransport.ReceiveEndedException) {
                // Other USB streams can observe the same EOF before the command reader drains its queue.
                // Enqueue the terminal event behind all complete commands, including VERSION_REJECTED.
                if (port == 7240) enqueue(() -> fail(reason));
            } else fail(reason);
        }
        finally {
            if (sound != null) try { sound.close(); }
            catch (Exception e) { fail("AUDIO_CLOSE_FAILED port=" + port); }
        }
    }
    private void message(Wire.Packet p) throws Exception {
        if (matched && CarLifeMedia.supports(p.type)) {
            // Optional metadata must not tear down the audiovisual session if malformed.
            try {
                CarLifeMedia.Snapshot state = mediaState.accept(p.type, xiaomi == null ? p.body : xiaomi.decrypt(p.body));
                if (state != null) listener.media(state);
            } catch (Exception error) { listener.log("MEDIA_IGNORED type=" + p.type + " reason=" + error.getClass().getSimpleName()); }
        }
        if (microphone != null && matched && !closing.get() && (xiaomi == null || xiaomi.running()))
            microphone.command(p.type, xiaomi == null ? p.body : xiaomi.decrypt(p.body));
        if (seen.add(p.type) || (xiaomi != null && !xiaomi.running())) listener.log("RX_COMMAND id=" + p.type + " size=" + p.body.length);
        if (p.type == GO_TO_DESKTOP) {
            if (streaming && !closing.get()) listener.goToDesktop();
            return;
        }
        if (xiaomi != null) {
            java.util.List<XiaomiHandshake.Action> actions;
            try { actions = xiaomi.accept(p.type, p.body, requestedSize); }
            catch (XiaomiHandshake.VersionRejectedException rejected) { fail("VERSION_REJECTED"); return; }
            matched = xiaomi.matched();
            if (p.type == 0x10002) listener.log("VERSION_MATCHED 4.1 XIAOMI");
            if (p.type == 0x1004B) listener.log("XIAOMI_PHONE_ADMITTED");
            if (p.type == 0x1006E) listener.log("XIAOMI_ENCRYPTION_READY");
            if (p.type == 0x10008) {
                videoSize = xiaomi.confirmed();
                listener.configured(videoSize.width, videoSize.height);
                configured = true;
                listener.log("ENCODER_CONFIRMED " + videoSize + " XIAOMI");
            }
            for (XiaomiHandshake.Action action : actions) {
                send(action.type, action.payload);
                listener.log("XIAOMI_TX id=" + action.type);
                if (action.type == 98386) listener.log("FEATURES_SENT DISABLE_RESOLUTION_OPTION=1 CONTENT_ENCRYPTION=1" + (microphone != null ? " VOICE_MIC=0" : "") + " XIAOMI");
                if (action.type == 98311) listener.log("VIDEO_REQUESTED " + requestedSize + " XIAOMI");
            }
            streaming = xiaomi.running();
            return;
        }
        switch (p.type) {
            case 65538:
                if (Wire.integer(p.body, 1, -1) != 1) { fail("VERSION_REJECTED"); return; }
                if (!matched) { matched = true; listener.log("VERSION_MATCHED 3.0"); send(98343, Wire.statistics()); }
                advance(); break;
            case 65540:
                infoSeen = true; if (matched) advance(); break;
            case 65611:
                long auth = Wire.integer(p.body, 1, -1);
                listener.log("AUTH_RESULT status=" + auth);
                if (auth != 1) { fail("AUTH_REJECTED"); return; }
                send(98380, Wire.EMPTY); break;
            case 65617:
                if (matched) sendFeatures(); break;
            case 65574:
                // These are phone/navigation/music/voice/microphone states, not video
                // readiness flags. Only activate modules supported by the configured listeners.
                break;
            case 65544:
                if (!matched) throw new IOException("encoder reply before version match");
                int width = (int) Wire.integer(p.body, 1, 0), height = (int) Wire.integer(p.body, 2, 0);
                VideoSize nextSize = new VideoSize(width, height);
                videoSize = nextSize;
                listener.log("ENCODER_CONFIRMED " + width + "x" + height + " fps=" + Wire.integer(p.body, 3, 0));
                listener.configured(width, height);
                configured = true;
                send(98313, Wire.EMPTY); streaming = true; break;
            case 65551:
                requestVideo(); break;
            case 65642:
                fail("ENCRYPTION_REQUIRED: 3.0 does not support encrypted negotiation; reconnect using protocol 4.1"); break;
            default: break;
        }
    }
    private void advance() throws IOException {
        if (!matched || !infoSeen || handshakeStarted) return;
        handshakeStarted = true;
        send(98307, Wire.device(requestedSize));
        control.schedule(() -> safe(this::sendFeatures), 1200, TimeUnit.MILLISECONDS);
        control.schedule(() -> safe(this::requestVideo), 2400, TimeUnit.MILLISECONDS);
    }
    private void sendFeatures() throws IOException {
        send(98386, Wire.features(microphone != null, transport.connectionType()));
        // Set only after writing the command: an early VIDEO_ENCODER_INIT request
        // must not race ahead of DISABLE_RESOLUTION_OPTION on the same TCP stream.
        featuresSent = true;
        listener.log("FEATURES_SENT DISABLE_RESOLUTION_OPTION=1" + (microphone != null ? " VOICE_MIC=0" : ""));
    }
    private void requestVideo() throws IOException {
        if (!matched || !featuresSent || configured) return;
        send(98311, Wire.video(requestedSize));
        listener.log("VIDEO_REQUESTED " + requestedSize + "@30");
    }
    /** Experiment: request a new canvas without closing any transport channel. */
    public void requestResolution(VideoSize size) {
        Objects.requireNonNull(size);
        enqueue(() -> {
            if (xiaomi != null) { listener.log("XIAOMI_RESIZE_REQUIRES_RECONNECT"); return; }
            if (size.equals(requestedSize)) return;
            requestedSize = size;
            if (!matched || !featuresSent) return; // Initial handshake uses the latest request.
            send(98311, Wire.video(size));
            listener.log("VIDEO_RECONFIG_REQUESTED " + size + "@30 keepChannels=true");
        });
    }
    interface Action { void run() throws Exception; }
    private boolean usbReceiveEnded() {
        return transport instanceof CarLifeUsbTransport && ((CarLifeUsbTransport) transport).receiveEnded();
    }
    private void safe(Action a) {
        if (!closing.get()) try { a.run(); }
        catch (CarLifeUsbTransport.ReceiveEndedException ignored) { /* Command reader queues the terminal event. */ }
        catch (Exception e) { fail("PROTOCOL_FAILED " + e.getClass().getSimpleName() + ":" + e.getMessage()); }
    }
    private void enqueue(Action a) {
        if (control.getQueue().size() > 64) { fail("CONTROL_QUEUE_OVERFLOW"); return; }
        if (!closing.get()) try { control.execute(() -> safe(a)); } catch (RejectedExecutionException ignored) { /* stopped */ }
    }
    private void send(int type, byte[] payload) throws IOException {
        if (stopped.get()) return;
        transport.write(7240, Wire.command(type, encode(payload)));
    }
    private byte[] encode(byte[] payload) throws IOException {
        if (xiaomi == null) return payload;
        try { return xiaomi.encrypt(payload); }
        catch (Exception error) { throw new IOException("XIAOMI payload encryption failed", error); }
    }
    public void touch(int action, int x, int y) {
        if (!streaming || stopped.get()) return;
        // UI input is bounded: reject overload rather than accumulate delayed gestures.
        if (control.getQueue().size() > 32) { fail("INPUT_QUEUE_OVERFLOW"); return; }
        enqueue(() -> {
            int type;
            if (action == 0) { if (touching) releaseTouch(); genericTouch = false; touching = true; type = 425986; }
            else if (action == 2 && touching) type = 425988;
            else if ((action == 1 || action == 3) && touching) { touching = false; type = 425987; }
            else return;
            VideoSize size = videoSize;
            if (size == null) return;
            lastX = size.clampX(x); lastY = size.clampY(y);
            writeTouch(type, lastX, lastY);
            if (action != 2) listener.log("TOUCH_SENT action=" + action);
        });
    }
    /** CarLife TouchAction protobuf: action, x, y, pointerX, pointerY. */
    public void touch(int action, int x, int y, int px, int py) {
        if (!streaming || stopped.get()) return;
        if (control.getQueue().size() > 32) { fail("INPUT_QUEUE_OVERFLOW"); return; }
        enqueue(() -> {
            if (videoSize == null) return;
            if (action == 0) { if (touching) releaseTouch(); genericTouch = true; touching = true; }
            else if (!touching || !genericTouch) return;
            else if (action == 261) { if (secondTouch) return; secondTouch = true; }
            else if (action == 262) { if (!secondTouch) return; }
            else if (action != 1 && action != 2) return;
            lastX = videoSize.clampX(x); lastY = videoSize.clampY(y);
            lastPointerX = videoSize.clampX(px); lastPointerY = videoSize.clampY(py);
            writeTouchAction(action);
            if (action == 262) secondTouch = false;
            if (action == 1) { touching = false; secondTouch = false; }
        });
    }
    private void writeTouchAction(int action) throws IOException {
        // Preserve the vendor's established single-pointer path (including its click bookkeeping).
        if (action == 0 || action == 1 || (action == 2 && !secondTouch)) {
            writeTouch(action == 0 ? 425986 : action == 1 ? 425987 : 425988,lastX,lastY);
        } else {
            byte[] payload = Wire.join(Wire.number(1,action),Wire.number(2,lastX),Wire.number(3,lastY),
                Wire.number(4,lastPointerX),Wire.number(5,lastPointerY));
            transport.write(9340, Wire.command(425985,encode(payload)));
        }
        if (action != 2) listener.log("TOUCH_ACTION action=" + action + " contacts=" + (secondTouch ? 2 : 1));
    }
    private void releaseTouch() throws IOException {
        if (genericTouch) {
            if (secondTouch) { writeTouchAction(262); secondTouch = false; }
            writeTouchAction(1);
        } else writeTouch(425987,lastX,lastY);
        touching = false;
    }
    private void writeTouch(int type, int x, int y) throws IOException {
        transport.write(9340, Wire.command(type, encode(Wire.join(Wire.number(1, x), Wire.number(2, y)))));
    }
    public void key(int code) {
        if (!streaming || stopped.get()) return;
        enqueue(() -> {
            transport.write(9340, Wire.command(425992, encode(Wire.number(1, code))));
            listener.log("KEY_SENT code=" + code);
        });
    }
    public CompletableFuture<Boolean> mediaKey(int code) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!streaming || closing.get()) { result.complete(false); return result; }
        try { control.execute(() -> {
            if (!streaming || closing.get()) { result.complete(false); return; }
            try {
                transport.write(9340, Wire.command(425992, encode(Wire.number(1, code))));
                listener.log("MEDIA_KEY_SENT code=" + code);
                result.complete(true);
            } catch (Exception error) {
                // A failed USB write can leave the peer waiting for the remainder of this frame.
                if (transport.usb() && !(error instanceof CarLifeUsbTransport.ReceiveEndedException))
                    fail("MEDIA_KEY_FAILED " + error.getClass().getSimpleName() + ":" + error.getMessage());
                result.complete(false);
            }
        }); } catch (RejectedExecutionException ignored) { result.complete(false); }
        return result;
    }
    private void fail(String message) {
        if (!closing.get()) {
            // AOA reopen does not restart the phone's protocol engine. Do not silently retry a stale USB stream.
            String reason = transport.usb() && !message.equals("VERSION_REJECTED")
                ? "USB_" + message + "；请重新插拔 USB，解锁源手机并确认 CarLife 提示后重连" : message;
            listener.log(reason); listener.failed(reason); stop();
        }
    }
    public CompletableFuture<Void> stop() {
        if (!closing.compareAndSet(false, true)) return closed;
        CountDownLatch released = new CountDownLatch(1);
        try {
            control.execute(() -> {
                try {
                    if (touching) releaseTouch();
                } catch (IOException ignored) { /* disconnected input lease */ }
                finally { released.countDown(); }
            });
        } catch (RejectedExecutionException ignored) { released.countDown(); }
        new Thread(() -> {
            try {
                // A capture failure can call stop() from the capture thread itself.
                // Join it here, and keep cleaning up even if its stop callback fails.
                Exception microphoneFailure = null;
                if (microphone != null) try { microphone.close(); }
                catch (Exception error) { microphoneFailure = error; }
                // Give the control owner a bounded chance to lift a held pointer.
                // Closing the transport also unblocks a stalled writer or reader.
                released.await(250, TimeUnit.MILLISECONDS);
                stopped.set(true);
                IOException transportFailure = null;
                try { transport.close(); } catch (IOException error) { transportFailure = error; }
                control.shutdownNow(); readers.shutdownNow();
                if (microphone != null) microphone.awaitStopped();
                boolean controlStopped = control.awaitTermination(3, TimeUnit.SECONDS);
                boolean clean = readers.awaitTermination(3, TimeUnit.SECONDS) && controlStopped;
                if (clean && xiaomi != null) xiaomi.close();
                listener.log(clean ? "STOPPED_CLEAN" : "STOP_TIMEOUT");
                if (microphoneFailure != null) {
                    if (transportFailure != null) microphoneFailure.addSuppressed(transportFailure);
                    if (!clean) microphoneFailure.addSuppressed(new IOException("workers did not stop"));
                    throw microphoneFailure;
                }
                if (transportFailure != null) throw transportFailure;
                if (!clean) throw new IOException("workers did not stop");
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                try { listener.ended(); } catch (Exception callback) { e.addSuppressed(callback); }
                closed.completeExceptionally(e);
                return;
            }
            try { listener.ended(); closed.complete(null); }
            catch (Exception error) { closed.completeExceptionally(error); }
        }, "carlife-stop").start();
        return closed;
    }
}
