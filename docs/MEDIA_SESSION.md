# WheelPlay MediaSession integration

The background controller owns CarPlay media state, and the foreground service owns the native Android media session and notification. Leaving or recreating a page does not release the session. The implementation includes metadata, artwork, playback progress, play/pause, and track skipping. **Seek protocol validation is incomplete, so system scrubbing is currently unavailable.** Device acceptance testing remains incomplete.

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
| 0x5003 | 0 | ElapsedTime | uint32; outbound units unspecified in the reference |

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

## Seek delivery gap

`seekTo(positionMs)` currently returns `UNSUPPORTED`. `PlaybackState` does not declare `ACTION_SEEK_TO`, and identification does not additionally advertise sending 0x5003. The raw encoder accepts a wire value without treating milliseconds as a verified outbound unit.

Enabling seek requires reliable protocol evidence or a device capture confirming:

1. Units and boundary values for 0x5003 parameter 0, with correct 0x5001 elapsed-time reports after sending on the ready tunnel.
2. AirPlay audio sequence/timestamp continuity around seeking, and which buffered local/browser samples must be discarded.
3. Implemented and tested bounds, latest-request coalescing, disconnect cancellation, and stale-link isolation before enabling `ACTION_SEEK_TO` and identification capabilities.

Synthetic fixtures and successful sends cannot establish these protocol behaviors.

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
| Seek and stale audio after seeking | Unimplemented; protocol evidence pending | No guessed wire encoding or system scrubber |
