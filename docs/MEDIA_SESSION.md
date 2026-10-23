# WheelPlay MediaSession integration

The background controller owns CarPlay media state, and the foreground service owns the native Android media session and notification. Leaving or recreating a page does not release the session. The implementation includes metadata, artwork, playback progress, play/pause, track skipping, and capability-gated absolute seeking. Device acceptance testing remains incomplete.

## Protocol fields

The mapping follows Apple's [Accessory Interface Specification R32, publicly hosted copy](https://www.scribd.com/document/863231068/Accessory-Interface-Specification-R32-No-Watermark), dated 2019-09-26, sections 54.3, 95.12.5, and 95.13. Numbers are big-endian and UTF-8 strings are null-terminated. Unknown fields are ignored; malformed known fields reject the entire media update.

| Message/group | Parameter | Meaning | Encoding |
| --- | --- | --- | --- |
| 0x5000 | 0, 1 | Subscribe to media item and playback attributes | Void markers inside groups |
| 0x5001/0 | 0 | Persistent item identifier | uint64 |
| 0x5001/0 | 1, 6, 12 | Title, album, artist | utf8 |
| 0x5001/0 | 4 | Duration | uint32 milliseconds |
| 0x5001/0 | 26 | Artwork transfer identifier | uint8 |
| 0x5001/1 | 0 | Playback status | uint8: 0 stopped, 1 playing, 2 paused, 3/4 scanning |
| 0x5001/1 | 1 | Elapsed time | uint32 milliseconds |
| 0x5001/1 | 2 | Queue index | uint32 |
| 0x5001/1 | 7, 16 | Playback application name, bundle ID | utf8 |
| 0x5001/1 | 12 | Playback speed | uint16: 100 means 1x, 0 means unavailable |
| 0x5001/1 | 13 | Setting elapsed time supported | bool: 0/1 |
| 0x5003 | 0 | ElapsedTime | uint32 milliseconds; unit follows the upstream implementations below |

Artwork uses the file transfer session. Setup contains the transfer identifier, 0x04, and a 64-bit byte count; v2 adds the 16-bit file type 0x0002. START 0x01, CANCEL 0x02, SUCCESS 0x05, and FAILURE 0x06 coordinate transfers. Data flags are FIRST 0x80, DATA 0x00, LAST 0x40, and FIRST_LAST 0xC0. Artwork is JPEG.

[JJTech's iAP2 Wireshark dissector](https://gist.github.com/JJTech0130/78527c600f7b4d0a7aeb294eab06d8ba) provides another reference for the file session structure.

## State and threading

The existing `Iap2LinkChannel` worker remains the sole reader and writer of the underlying byte stream. Control and file data use separate queues while sharing link negotiation, ordering, acknowledgement, and retransmission. The existing control loop dispatches navigation and Now Playing messages by type. A separate background worker consumes the file queue without introducing a second byte-stream receiver.

One artwork worker lives for the coordinator's lifetime, including AirPlay reconnections that reuse an authenticated iAP2 link. Metadata callbacks publish the latest immutable transfer expectation without calling the file receiver. The artwork worker applies that expectation before processing packets; JPEG decoding and reply backpressure cannot block navigation/control reads. Reconnecting replaces the receiver without starting a second file consumer. Outbound file replies validate the binding at queue admission and dispatch, so retired replies are rejected or discarded even if they were waiting behind the link window.

`CarPlayMediaCoordinator` owns immutable snapshots and serializes initial snapshots with incremental updates. Connection generation, track revision, and transfer ID constrain artwork results. A new connection clears the old track. A wireless tunnel handoff retains metadata and completed artwork for the same track, but retires the old link's transfer association. Reannouncing an artwork ID on the tunnel preserves that image; real track changes and disconnections clear it. Input is limited to 4 MiB, transfers time out after 15 seconds, and background decoding scales the longest edge to at most 512 pixels. An invalid initial image fails the transfer and leaves the application icon as the fallback.

Title, artist, and album are display metadata. Titles may contain live lyrics and do not establish track boundaries. Boundaries require an explicit player change, changed known PID, or changed known queue index. If both PIDs are present, PID takes precedence; otherwise, known queue indices are compared. Omitting identity fields preserves the previous identity without falling back to title comparison. A confirmed boundary retires both the old PID and queue index, so later incremental identity fields do not increment the track revision again. Learning an initially unknown identity also does not establish a boundary.

A new artwork transfer ID for the same track retires the old transfer association while retaining its successfully decoded image until the replacement decodes successfully. A failed replacement retains the valid image. Without a valid image, the application icon is used. Real track changes, player changes, new connections, and disconnections clear old artwork and the progress anchor immediately. Stale decode callbacks cannot restore old images.

For players without comparable identity fields, a title alone cannot distinguish lyrics from a new track. Such updates change display metadata while waiting for new artwork and progress reports; boundaries are not guessed from titles. Diagnostics record the identity decision, changed field names, presence of PID/queue fields, and artwork retention, without recording identity values or lyrics.

Artwork SETUP may arrive before its metadata. Unassociated SETUPs wait for their identifiers, subject to a four-entry limit and a 15-second timeout. Transfers already associated with the previous track are still cancelled.

File replies use the existing bounded send queue and await link acknowledgements. Temporary backpressure does not mean negotiation is missing. A failed reply retires only the affected transfer, without terminating the entire artwork receiver or failing the control loop. Diagnostics include negotiated session IDs/types/versions, SETUP length/type/declared size, first/last fragment totals, reply queue acceptance or failure, and unexpected receiver termination. `queued` means queue acceptance, not receipt by the iPhone. Logs omit track names, image contents, and application-icon bundle IDs.

Android `PlaybackState` uses iPhone reports and an `elapsedRealtime` anchor. A pause report stops progress extrapolation. HID controls send presses and releases through the existing descriptor; asynchronous results indicate sending only. A pause command temporarily blocks local buffered music, while reported state remains authoritative. If no pause is reported within three seconds, local output resumes according to the existing state.

`DiPlaySessionService` creates and releases `CarPlayMediaSessionBridge`, which subscribes to the background controller independently of page listeners. The media notification includes the session token, artwork, and previous/play-or-pause/next actions. The stop-service action appears only in the connection notification. Actions use the official Material Icons 24dp monochrome vectors, supplied through resource-backed `Icon` objects; attribution is in [UPSTREAM.md](UPSTREAM.md). On Android 13 and later, the system can generate media controls from `PlaybackState`. The `mediaPlayback` foreground service type follows reported playback. Disconnecting restores the connection notification and deactivates the session. Old notification actions and callbacks cannot control a new connection.

## Output and audio focus

Local output uses `AudioTrack` with `USAGE_MEDIA`, following the system's current media route, including connected A2DP devices. No pairing, automatic Bluetooth connection, or output selector is added.

The browser route becomes remote only after browser readiness and successful media PCM delivery. Volume is fixed from Android's perspective and managed by the browser device. Browser disconnection or output cancellation immediately restores the local playback type. Remote playback does not request local audio focus. Local music requests focus before playback; focus loss or ducking pauses iPhone music and blocks local output. Regaining focus does not resume playback automatically. Navigation, calls, and Siri retain their existing handling.

The renderer's idle maintenance only checks output permission. Actual music PCM that cannot be forwarded remotely creates one local focus demand, coalesced until permission is granted, the listener changes, or remote forwarding succeeds. Paused or denied local output therefore does not repeatedly post focus requests to the main thread. Browser fallback can create a fresh local demand, while navigation PCM does not request music focus.

Android API references: [media controls](https://developer.android.com/media/implement/surfaces/mobile), [MediaSession](https://developer.android.com/reference/android/media/session/MediaSession), and [audio focus](https://developer.android.com/media/optimize/audio-focus).

## Absolute seeking

The outbound unit is supported by open-source implementations, rather than inferred from the inbound elapsed-time field:

- [LIVI message definition](https://github.com/f-io/LIVI/blob/f999f55735278acd76f0fa207218cac00a739047/native/livi-helperd/crates/iap2-csm/src/messages/now_playing.rs#L76) and [runtime sender](https://github.com/f-io/LIVI/blob/f999f55735278acd76f0fa207218cac00a739047/native/livi-helperd/crates/livi-runtime/src/bringup.rs#L497): `0x5003`, parameter `0`, `elapsed_ms: u32`.
- [Nocturne definition](https://github.com/usenocturne/nocturne/blob/7021896b4422a947efa35315cf55f98aa64ffb3e/crates/iap2/src/csm/now_playing.rs#L167): milliseconds, direct user actions, and the phone's `PlaybackSetElapsedTimeAvailable` gate.
- [MacPlay encoding test](https://github.com/Roylyl/MacPlay/blob/12daeecdd5fad38554823ce6166a65370cf75f40/native/livi-helperd/crates/iap2-csm/src/messages/now_playing.rs#L96): 42 seconds produces `4040000e5003000800000000a410`. This is a synthetic test, not a device capture.

Wired and wireless identification advertise sending `0x5003`. `canSeek` requires an available phone, an explicit seek capability, a positive duration, and a known position. Android advertises `ACTION_SEEK_TO` only while these conditions hold. A confirmed track change clears the position anchor until fresh progress arrives. As with other incremental playback attributes, the latest duration and seek capability remain in effect unless updated; switching players or disconnecting clears them.

`seekTo(positionMs, connectionGeneration, trackRevision)` clamps to the current duration and the unsigned 32-bit limit. The coordinator keeps at most one queued target in addition to the active send. Superseded queued requests complete as `SUPERSEDED`; disconnect completes outstanding futures as `UNAVAILABLE`. The captured iAP2 session, connection generation, track revision and request serial are checked again at link queue admission and dispatch. Cancellable CSM frames must fit in one link packet, so dropping a stale seek cannot leave a partial CSM frame. Already transmitted commands cannot be recalled.

Seeking does not imply play, acquire audio focus, clear the pause gate, or change the reported playback position optimistically. `SENT` means transport queue acceptance, not confirmation that the phone applied the seek. Subsequent `0x5001` reports update progress. Audio continues through the existing CarPlay audio path; no guessed AirPlay flush boundary is injected. iPhone/app compatibility, resulting elapsed-time reports, and audible continuity on local/browser output remain device acceptance items.

## Browser / head-unit Media Session

The paired browser receives the same iPhone-authoritative Now Playing state as the Android native session. `browser-media-session.js` maps it to the optional Web Media Session API (`navigator.mediaSession`): title, artist, album, JPEG artwork, playback state, duration, position, and play/pause/previous/next controls. Stop maps to pause. `seekto`, `seekforward`, and `seekbackward` are registered only while `canSeek` is true and the timeline is valid. Missing or partially implemented APIs do not interrupt video, touch, or browser audio. This also works as a remote control when audio is routed through Android; it does not change the selected audio route or add a synthetic audio track to activate system UI.

`/stream?...&nowPlaying=1` opts into the protocol; clients without the capability keep their existing messages. `BrowserMediaBridge` follows background controller replacement, subscribes to the initial and incremental media snapshots, and closes both subscriptions when its viewer leaves. Observers only post work to the main thread. Small state messages use the existing coalescing `WebStreamSender` control queue; no writes run on the iAP2 reader.

Server → browser `now-playing` fields:

| Field | Meaning |
| --- | --- |
| `sessionId` | Opaque binding ID, replaced whenever the controller changes, including replacement controllers with the same connection generation |
| `revision` | Monotonically increasing snapshot revision within this WebSocket connection |
| `connectionGeneration`, `trackRevision` | iPhone connection and track identity boundaries |
| `connected`, `available` | Connection and Now Playing availability; unavailable snapshots clear the browser session |
| `title`, `artist`, `album`, `appName` | Nullable display metadata, including possible live lyrics |
| `status` | `unknown`, `stopped`, `playing`, `paused`, `seek_forward`, or `seek_backward`; only playing/paused map to those Web API states, others map to `none` |
| `durationMs`, `positionMs`, `speed` | Nullable duration/position in milliseconds, position extrapolated using the server's monotonic clock at publication, and reported playback rate |
| `canSeek` | Available phone with seek capability, positive duration and known position |
| `artworkUrl` | Nullable same-origin `/now-playing/artwork?id=...` capability URL |

The browser converts milliseconds to seconds, clamps position to duration, clears unknown positions, and uses a positive API playback rate while setting `playbackState` to paused. It does not compare Android `elapsedRealtime` with a browser clock. Metadata is republished only when its display values or media identity change. Artwork URLs remain stable across position and lyric updates. Each URL is an unguessable bearer capability that serves only the current scaled JPEG while its originating viewer remains authorized, and is invalidated on image replacement, track/connection/controller changes, pairing revocation, or viewer close. Responses are `private, no-store`; pairing codes and tokens do not appear in artwork URLs.

Browser → server controls use `{"type":"media-command","requestId":1,"sessionId":"opaque-binding","connectionGeneration":7,"action":"pause"}`. Basic actions are `play`, `pause`, `stop`, `previoustrack`, `nexttrack`; seek is described below. The server validates pairing, viewer ownership, binding, connection generation and increasing accepted request IDs; it allows one pending command with a 150 ms admission interval. Controls pass through the foreground service's native media bridge so local audio focus and buffered pause gating behave like notification controls. Replies are `{"type":"media-command-result","requestId":1,"result":"sent"}` (or `unavailable`/`failed`). `sent` is transport completion, never a playback acknowledgement. The browser waits for a reply before another command, with a three-second client wait limit, and does not retry successful sends or update playback optimistically.

Seek requests add the track revision and an absolute millisecond target: `{"type":"media-command","requestId":2,"sessionId":"opaque-binding","connectionGeneration":7,"trackRevision":3,"action":"seekto","positionMs":42000}`. Only `seekto` is sent over the wire; the browser converts relative actions to absolute positions using the reported position and a browser-monotonic progress anchor (default step: 10 seconds). It rejects non-finite inputs, clamps the target, coalesces dragging for 150 ms, and retains the last target while another command is pending. Both intermediate `fastSeek` hints and the final callback use absolute seeks. Position is updated only by phone snapshots.

The server independently validates integral, nonnegative position/track fields, pairing, source, connection and track. A 150 ms trailing queue replaces intermediate seeks and preserves the final target while a control is pending; it rechecks authorization and current capability before dispatch. Results additionally include `unsupported` and `superseded`. Capability loss, track/connection changes, controller replacement, and viewer closure retire queued seeks. Browser handlers also retire on track/capability changes.

WebSocket closure, page exit, and iPhone disconnection clear metadata, position state, and action handlers. Old snapshot revisions and callbacks are ignored. Native clients embedding a WebView still need a host implementation if their engine does not expose Media Session to the system; this protocol does not define a vendor-specific JS bridge. Actual head-unit media cards, steering-wheel keys, audio-focus ownership, HTTP/HTTPS exposure and Web Audio-only session activation depend on the browser/OS and require device acceptance testing.

Validation includes Robolectric state/identity/command tests, real HTTP/WebSocket pairing and artwork revocation, native pause gating, Node browser lifecycle/backpressure tests, and real Chromium metadata/cover/control roundtrips under the production CSP. Chromium action callbacks are invoked by the test harness; this does not verify physical head-unit buttons or OS media-card visibility.

## Validation

`shared/src/test/resources/media/` contains synthetic full-update and pause-delta CSM fixtures. Their README documents their origin; they are not iPhone captures.

Automated coverage includes fields and malformed parameters, track changes, monotonic progress, artwork fragments/cancellation/limits/timeouts/invalid images, shared file/navigation ordering and retransmission, HID mapping, send outcomes, disconnects, stale connections, and completion of pending commands. Robolectric covers metadata, playback state, callbacks, notifications, fixed remote volume, focus-loss pausing, browser routing, and service release. Its shadows record API calls and link `MediaController` to the session; real Android Binder services, audio devices, and AVRCP are outside JVM validation.

On 2026-10-08, a V2547A device (API 37) was detected. The attempted Debug APK installation returned `INSTALL_FAILED_ABORTED: User rejected permissions`. No automatic retry or device acceptance run followed that attempt. Later user-supplied logs provide the partial wireless evidence below.

| Device check | Status | Acceptance criteria |
| --- | --- | --- |
| Wired/wireless iPhone media | Wireless audio/video and artwork reception partly verified | 15:27/15:29 logs confirm seven decoded covers, followed by local clearing on metadata updates; lyric-refresh fix, wired behavior, and controls await retesting |
| Android notification/lock screen | Unverified | Controls change iPhone playback; paused progress stops |
| Page recreation/screen off/home | Unverified | Session persists; stopping the service leaves no session |
| Browser takeover/disconnection | Unverified | Controls remain available; remote/local types follow actual output |
| Another player taking focus | Unverified | iPhone and local music pause; resuming requires user action |
| A2DP audio output | Unverified | Local PCM follows the system route without new pairing behavior |
| AVRCP buttons/metadata/artwork | Unverified | Record compatibility by receiver model and implementation |
| Seek and stale audio after seeking | Implemented; automated protocol/browser tests pass; device testing pending | Drag while playing and paused; phone reports the target; no stale audio; live/non-seekable items disable scrubbing |
