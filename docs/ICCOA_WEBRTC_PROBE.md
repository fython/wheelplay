# ICCOA compressed-video / WebRTC validation

Current status: WebRTC has passed device validation; this document preserves the earlier experiment. See [current support and recovery](CARLINK.md) for release guidance.

Status: historical local feasibility experiment, 2026-11-11. **Direct H.264 playback and browser touch work; reliable recovery does not.** This is not an implemented public plugin API or production-ready transport.

At the time of this experiment the main worktree used API v2 Surface output. A subsequent implementation adds a device-validated selectable encoded-video extension; see [the current protocol](PROJECTION_PLUGINS.md#压缩视频扩展-v1). The isolated worktree is `.local/iccoa-webrtc-probe`; evidence is in `.local/carlink-validation/encoded-probe` (not committed; captures may contain private screen content). Both debug APKs were built offline and installed on the existing test receiver. No proprietary SDK files belong in this document or a commit.

## Experiment and observations

The pinned SDK from [CARLINK.md](CARLINK.md) exposes an internal `SinkNative.WfdListener.onVideoDataReceived` callback. A reflection proxy installed before advertising intercepts compressed bytes instead of calling the original video enqueue/decode path. Other listener events still reach the original listener. This depends on private members of this exact SDK; it is not a supported vendor API.

`startCast()` cannot simply be omitted: the probe separately preserves UIBC display dimensions and `sendGotoForeground(true)`. Logs show `foreground=true`, `mVideoPlayer=null` at first frame and foreground initialization. The host uses a dummy Surface solely to satisfy v2; it creates no video decoder, GL readback, JPEG/WebP publisher or video encoder. Video is forwarded through `WebVideoSource` → the existing RTP/SRTP WebRTC path → browser decoding.

| Check | Result |
| --- | --- |
| Format | H.264 Annex B; SPS/PPS found; profile-level-id `640032` (High, level 5.0) |
| Dimensions | Requested/touch display 1458 × 1546; SDK encoded video 1456 × 1544 |
| Initial capture | First 800 callbacks parse as Annex B; no repeated or decreasing callback PTS; initial IDRs in first two callbacks, none later during approximately 93 s |
| Timestamp behavior | Microsecond PTS, variable cadence; active transitions often approximately 16–17 ms apart, static screens much slower. Do not synthesize a fixed frame rate. |
| Connection without SDK decoding | Successful; video-ready and foreground transition retained |
| Direct browser output | Visually verified H.264 / WebRTC / Video, native video renderer |
| Touch | Browser click opened app grid; another click opened Settings, with the resulting screens received over WebRTC |
| Pause/resume as keyframe trigger | Attempted, no new IDR observed; not a usable replacement for keyframe requests |
| Bounded bootstrap | Successful final run replayed 46 frames / 545,796 bytes once after WebRTC connected, then continued live; final 5,843 callback records observed without PTS regression or repeated callback PTS |

Final successful session: first compressed frame at 11:23:31.993, WebRTC connected at 11:23:32.976, one bootstrap replay at 11:23:33.030. App grid and Settings interactions were visually verified afterwards. Screenshots are `webrtc-touch-apps.jpg` and `webrtc-touch-settings.jpg` in the evidence directory. Frame-rate labels are instantaneous UI readings, not performance benchmarks.

Browser refresh was also tested after the bootstrap cache expired. Playback eventually recovered, but only after repeated first-frame timeouts. Later spontaneous IDRs occurred at frames 1802 (11:25:49.244) and 3602 (11:26:46.007); the first was missed while WebRTC was reconnecting, and the latter restored output. The experiment’s request callback only logged `unsupported-public-api` and sent no SDK request, so the later IDRs are not evidence of request fulfillment. A third later IDR at frame 5402 was also captured. A 1,800-frame interval was observed three times; it is not a verified SDK guarantee or a wall-clock recovery bound.

## Failures and limits

- The two initial IDRs can arrive before WebRTC finishes connecting. Without a fresh IDR, the receiver stays black and eventually falls back; this probe deliberately has no image fallback.
- A first experiment replayed the growing GOP repeatedly. It produced a visible picture but blocked the reader long enough to overflow the producer queue and stop the session. This is evidence against treating GOP replay as general recovery.
- A single-replay experiment with a 180-frame / 4 MiB cache exhausted the cache before one approximately 7.6 s WebRTC connection completed. The final experiment allows at most 600 frames / 16 MiB and one replay after connection; the producer queue is bounded to 128 records. These are experiment limits, not recommended production defaults or a combined memory budget.
- The successful session eventually exceeded its GOP cache while live playback continued. An existing decoder can consume predictive frames; a new decoder cannot start from those frames. A cache cannot substitute for requesting a new keyframe.
- SDK stop/force-stop can leave a Wi-Fi Direct group active. The existing guard refused a subsequent start; resetting receiver Wi-Fi cleared the test group. Preserve the existing conservative stop contract.
- No controlled packet-loss injection, HEVC, other browsers/phones, rotation, multi-touch, audio or latency/power benchmark was completed. Touch success covers two ordinary taps, not a full coordinate or gesture acceptance suite.
- The prototype groups equal-PTS callbacks and flushes on the next PTS; this adds a frame of delay and can hold the final frame on a static screen. Production code must establish complete access-unit boundaries rather than relying on a later callback. Annex-B parsing alone is not proof that all SDK versions always emit one complete picture per callback.

## Proposed protocol extension (not implemented)

Keep v2 Surface support. Introduce an explicitly negotiated encoded-video capability and versioned control interface. Do not reinterpret existing `start(token, Surface)`, reorder existing AIDL transactions or send new transactions to a peer that only supports v2. Negotiate an extension Binder or a separately versioned service/action, with v2 fallback selected before starting resources.

1. **Capabilities and selection.** Advertise Surface and/or encoded access units, supported codecs/framing, maximum dimensions and AU size, keyframe-request support, timestamp semantics, and format-change support. Host chooses exactly one output mode. For this SDK, advertise keyframe requests as unsupported until a real mechanism is implemented and verified; keep encoded mode experimental.
2. **Control and ownership.** Negotiate token, stream generation, format revision and output mode; host supplies a bounded media-channel endpoint. Separate `onVideoFormat`, readiness, error, EOS and stop acknowledgements from bulk media. Every asynchronous event must be tied to the active token/generation. Close stale descriptors on rejection. Binder death and pipe failure cancel the active reader and inputs, but do not imply the SDK released wireless resources.
3. **Media transport.** Use a reliable `ParcelFileDescriptor` pipe first, with length-delimited, versioned records; avoid per-frame `byte[]` Binder calls. Each AU carries generation/revision, sequence, PTS in microseconds, flags and length. Specify byte order, maximum record length and framing (initially complete H.264 Annex-B access units). Config records carry SPS/PPS, actual encoded/cropped dimensions, rotation and separate touch-display geometry. Validate before allocating; reject unsupported profiles/formats. Shared-memory rings can follow only if profiling warrants the complexity.
4. **Bounded backpressure.** Bound bytes, record count and age in both producer and host queues. Never block SDK callbacks indefinitely or silently discard a predictive frame and continue its dependent sequence. On a gap/overflow, invalidate the chain and request a fresh IDR. If unsupported or timed out, report an explicit recovery failure and require controlled source restart or negotiated Surface fallback. Do not accumulate an unbounded GOP.
5. **Recovery.** Expose asynchronous `requestKeyframe(token, generation, reason, requestId)` with accepted/unsupported/failed response. Acceptance is not fulfillment; observe an actual IDR within a deadline. Route browser PLI/FIR, new viewer, queue loss and format changes through this path with rate limits. Re-emit parameter sets with the recovery IDR. Do not invent SPS/PPS or relabel a cached IDR as fresh.
6. **Timing and geometry.** Require a monotonic media timeline per generation; explicitly declare discontinuities and reset/rebase WebRTC on reconnect. Preserve variable cadence. If B-frame/reordered output is supported, define DTS as well as PTS or restrict the initial mode to no reorder. Use actual coded/crop geometry for video and explicitly mapped touch geometry; the measured two-pixel discrepancy must not be ignored.
7. **Fallback and lifecycle.** Browser codec rejection or recovery timeout may select Surface mode only after stopping the encoded producer and confirming resource release. A browser display failure must not leave an invisible interactive session. Cancel touch on disconnect/stop, close both media ends, and retain `onStopped(clean=true)` only after every SDK producer/platform resource has actually stopped. Audio remains out of scope.

## Implementation gates

Before enabling by default: vendor-supported compressed callback and keyframe request (or a verified bounded restart policy); parser tests for fragmented/multi-NAL/malformed AUs and SPS changes; transport tests for oversized/truncated records, queue overflow and stale generations; lifecycle tests for pipe/Binder death and stop timeout; device checks for late join, browser refresh, loss recovery, codec rejection, repeated reconnects, resolution changes and full touch mapping. The current successful first playback does not pass these gates.

## End-of-test state

The final session stopped normally; the receiver returned to “not connected”. Its final CSV
has 5,843 callbacks, no decreasing/repeated PTS, and IDRs at callbacks 1, 2, 1802, 3602,
and 5402. The final AU remains buffered until close in this prototype (5,842 forwarded
frames), another reason to replace its next-PTS flushing heuristic. The test receiver was
restored to freshly rebuilt main-worktree host/plugin debug APKs using Surface output.
Experimental code and evidence remain local; no public API change was merged.
