# ICCOA CarLink independent plugin

`projection-plugin-carlink` is a separate APK using projection plugin API v2. It does not add
CarLink code to the WheelPlay host. The default Surface mode uses the existing JPEG/WebP browser output. Plugin settings also
provide **WebRTC**, which forwards compressed H.264 for browser decoding.
The setting is saved in the plugin and takes effect on the next connection; stop and reconnect
from WheelPlay after changing it. Image format, quality and FPS controls apply only to Surface.
Both the host and plugin must be updated for WebRTC; older hosts receive an incompatibility
message before SDK resources are acquired.

P2P, manual hotspot, USB, Surface and WebRTC have passed device validation. See the current
acceptance status below for its scope and the known USB reconnect workaround. For installation
and everyday use, start with the [user guide](CARLINK_USER_GUIDE.md).

## Fixed dependency baseline

The local bundle is pinned to [MiAuto v0.1.3](https://github.com/PiggyBackBayonet/MiAuto/releases/tag/v0.1.3).
The APK's SHA-256 is `b5b9acb0457772a8369be8a906b21784b323bb2ceccf3e7873f0f99ba641c358`;
its signing certificate SHA-256 is `2dbd314e1f7f87e5e52f6b33e40283901ef2ade3e2ece934e052e4c7cb2c3373`.
The SDK identifies itself as `v1.5.3-202401092110-支持方控`.

| Input | Location in reference APK | Validation |
| --- | --- | --- |
| `icar-sdk.dex` | `classes3.dex` | 7,084,144 bytes; required classes checked through `class_defs` |
| `libovmsink.so` | `lib/arm64-v8a/` | Pinned ELF64 AArch64; 4 KB segment alignment |
| `libusbio.so` | `lib/arm64-v8a/` | Pinned ELF64 AArch64; loaded during SDK startup even for wireless use |
| `ccd.data` | `assets/` | Pinned 2,120-byte device verification data |

Exact hashes are in [`sdk-lock.json`](../projection-plugin-carlink/sdk-lock.json). The SDK also
references Room 2.4.3, AndroidX Core and Collection, supplied as normal Gradle dependencies.
The SDK's Guava, Gson, protobuf and crypto classes are already in its dex and must not be
added again. The two native libraries' direct ELF dependencies are platform libraries;
MiAuto's FFmpeg, AA and hotspot helper libraries are not copied into this plugin.

The SDK embeds bitmap resource ID `0x7f0700a8`. `stable-ids.txt` and `stable-ids-debug.txt` reserve it for an original
bitmap; the final APK check verifies that mapping. Omitting it can crash the connection in
`BitmapUtil.decodeResource`, even if the SDK initializes successfully.

## Build and distribution

The packaging tool accepts a local reference APK and does not install or run it. CI downloads
the pinned `apkUrl` from `sdk-lock.json`, then uses the same hash-checked prepare/package path.
Runtime files remain build inputs rather than source-controlled files. Verified plugin APKs
can be distributed with their `carlink-build.json` dependency manifest.

```bash
python3 scripts/carlink_sdk.py prepare \
  --apk /absolute/path/MiAuto-0.1.3.apk --sdk .local/carlink-sdk
python3 scripts/carlink_sdk.py verify --sdk .local/carlink-sdk
ANDROID_HOME=/absolute/path/android-sdk ./gradlew \
  :projection-plugin-carlink:assembleDebug
```

Alternatively use `-PcarlinkSdkDir=/absolute/path/to/bundle`. The directory must contain the
four files above with the pinned hashes. No fallback APK is produced when they are missing.
Debug and Release variants are supported. Debug uses application ID
`moe.feng.wheelplay.plugin.carlink.debug` and the Android debug key. Release uses
`moe.feng.wheelplay.plugin.carlink` and the same signing environment variables as `mobile`:
`ANDROID_KEYSTORE_PATH` (absolute path), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
and `ANDROID_KEY_PASSWORD`. Once these are configured, run
`./gradlew :projection-plugin-carlink:assembleRelease`. Missing release signing configuration
fails the build; it never falls back to the debug key. Keep the release key stable for upgrades.

The final output is `projection-plugin-carlink/build/outputs/apk/debug/projection-plugin-carlink-debug.apk`.
AGP's artifact transformation limits packaging to this variant, appends the SDK once, adds
native libraries/assets, zipaligns, signs with the selected variant’s key and verifies the
signature, hashes, resource ID, duplicate classes and missing external SDK types.
`carlink-build.json` alongside the APK records its hash and dependency baseline. Never install
an intermediate APK from `build/intermediates/`. Release output is
`projection-plugin-carlink/build/outputs/apk/release/projection-plugin-carlink-release.apk`.
The `carlink-apk` job in `.github/workflows/debug-build.yml` uploads Debug APK and dependency
manifest as `wheelplay-carlink-debug-apk`, alongside the mobile workflow artifacts. It needs
no signing secrets. A Release build uses the configured release key and must be distributed
through the chosen release channel; this CI job does not publish GitHub Releases.

## Setup and behavior

1. Install the host and plugin on an **arm64 device with 4 KB pages**. The plugin rejects
   other architectures/page sizes before touching the vendor SDK.
2. Open the plugin settings and grant nearby-device and location permissions. Enable
   Bluetooth, Wi-Fi and location services. Settings do not start the SDK or advertising.
3. In WheelPlay, open **Input sources → ICCOA CarLink**, explicitly enable the plugin and connect.
4. Open plugin settings to see the current pairing code; select the receiver from the sending
   phone's CarLink client. The configured certification data determines the advertised name.
5. Pair the car browser with WheelPlay as usual. SDK pairing and browser pairing are separate.

The settings page shares WheelPlay's dark Material 3 theme. Canvas size comes from the host's
browser viewport, respecting WheelPlay's browser-adaptive setting. With updated host and plugin,
resizing/fullscreen changes are coalesced for 800 ms and sent through display-size extension v1.
CarLink compares the effective constrained configuration: if it changes, the host automatically
stops and recreates the session after confirmed SDK cleanup and process exit. This SDK negotiates
screenInfo during connection; `startCast` dimensions only update display/input mapping and cannot
renegotiate phone output. Reconnection may briefly interrupt video and require the phone to respond
to pairing again. Video output and touch coordinates are recreated with the same new configuration.
Opening the browser after starting the plugin also updates its initial fallback size. A user stop
or source switch cancels any pending automatic reopen. Older hosts/plugins still apply new sizes
only on manual reconnection. Without a viewport (or with adaptation disabled),
the fallback is 1280×720. Balanced rendering (default) fits into 320–1920 × 240–1080.
Native pixels and custom 0.5–2× scaling allow up to 4096 per edge and 8,388,608 pixels;
these are application allocation limits, not verified SDK limits. Dimensions are even and
aspect ratio is preserved except for extreme ratios. Rendering mode and DPI (120–480,
default 160) are plugin preferences applied at the next connection. The SDK request remains 30 fps.

Opening plugin settings from the updated host grants a scoped ResultReceiver capability to
read/write the host's existing JPEG/WebP and quality preferences, plus a persisted 10/15/20/30
FPS limit for Surface-to-browser image publishing (default 15). These changes apply live;
requested FPS is an upper bound, not guaranteed throughput. Direct launcher/notification entry
still offers plugin settings and explains that host controls need the host settings entry.
Diagnostics show the requested pixels, configured output Surface, session DPI, current browser
pixel report, last published format/quality and image publications in the last second. The
browser pixel report already includes DPR; CSS size/DPR are not separately exposed. The SDK's
actual encoded video dimensions and browser presentation FPS are not observable here.
 Old manually saved canvas dimensions are ignored. Only one touch is advertised in the initial integration.
The translator supports and unit-tests multiple contacts, but that does not establish real
multi-touch compatibility. Night mode and native input cancellation are not advertised.

The session reports READY only after WFD readiness and a decoded Surface-ready callback
(Surface mode) or the first compressed video callback (WebRTC mode).
Accepting `startCast` or connecting the phone is insufficient. Initial pairing has a 55-second
budget inside the host's 60-second startup deadline; unsuccessful pairing requires an explicit
new connection. Unconnected advertising is refreshed after 30 seconds, and wireless setup
errors have bounded backoff. No reconnect loop survives stop or an ended session.

Plugin settings now select one phone transport per session, independently of Surface/WebRTC.
P2P remains the default. Changes apply on the next connection; the settings page also shows
the current/last session's selection. Partial saves preserve rendering mode, scale and DPI.

- **Wi-Fi Direct (P2P):** BLE discovery and pairing, with Wi-Fi Direct for the phone link.
  An existing P2P group blocks startup rather than being silently removed.
- **Manual hotspot / SoftAP:** enable the receiver's hotspot in Android settings,
  then save that exact SSID and WPA2 password in the plugin. The SSID must have 9–32
  characters and fit within 32 UTF-8 bytes; the password must be 8–63 printable ASCII characters. The app does not request privileged
  hotspot permissions and never starts, reconfigures or stops the user's hotspot. A local
  replacement for the SDK AP service supplies the existing hotspot address and user credentials
  to the pinned BLE/authentication protocol. Wi-Fi uplinks and P2P interfaces are excluded when
  locating the hotspot; ambiguous/missing addresses fail with an actionable message. If Android
  permits reading the active configuration, SSID is checked and frequency is reported; otherwise
  credentials remain user-supplied and frequency is unknown (0), without guessing a channel.
  The receiver's already-ready LAN advances SDK transport setup; host READY still requires
  authenticated WFD and a video frame. Interface loss ends the session. The settings page has a
  system hotspot shortcut and masked password input. Credentials stay in the plugin's private,
  non-backed-up preferences; they are not exposed through session status. SDK debug/info logging
  is disabled for this mode because its AP publication logs otherwise include the password.
- **USB:** receiver USB Host/OTG and a data cable. Uses Android's USB permission
  dialog before enabling the SDK's AOA scanner, and requests permission again when the phone
  re-enumerates as an accessory. Only one phone may be attached. Denial ends the attempt;
  stop cancels polling and pending permission capabilities. USB does not start BLE advertising,
  refresh wireless pairing, or require P2P/Wi-Fi/location readiness. The SDK's phone allowlist
  and standard PTP interfaces determine candidate devices. Sender AOA/CarLink compatibility,
  USB role negotiation remains device-dependent. USB video and touch have passed device
  validation; the 10007 reconnect workaround is documented below.

All modes retain the 55-second startup budget. The SDK still binds its helper services;
the replacement AP service only releases local observers and callbacks on stop, in every mode. A working CarLink
link alone does not prove that the browser LAN can coexist: test both links together.
P2P, SoftAP and USB have passed device validation within the recorded compatibility scope.

## Stop contract and known SDK behavior

The SDK adapter and all four vendor services run in `:projection`. The settings provider stays
in the main process and carries status/PIN through private same-UID IPC. PINs are not persisted
by the plugin (the proprietary SDK may produce its own diagnostic logs).

A component factory retains the SDK's fixed service names while observing service lifecycle
completion. SDK Context wrappers track bindings/receivers and reject new registrations during
teardown. Stop cancels plugin callbacks/retries, releases touch, pauses/disconnects/deinitializes
the SDK, detaches bindings and stops services. Successful cleanup requires completed service
hooks, absent P2P group observations, and a barrier on the SDK worker after `deInit()`.
The barrier verifies that SDK/video initialization flags are cleared, the WFD session is null,
and the decoder is absent. These private-field checks are pinned to this SDK version and fail
closed if its layout changes. `deInit()` returning alone is not confirmation: it waits only
500 ms internally. The host still requires the dedicated process's Binder death after the
clean acknowledgement before admitting another session.

Do not require connection state 5 for an explicit local stop. `stopWfdServer()` calls native
stop, unregisters the listener, and clears the WFD session. A successful local stop can thus
omit the unsolicited sink-stopped callback. Requiring it caused false cleanup failures and
permanent host quarantine despite actual shutdown. This was reproduced from the home-screen
Stop projection button and corrected on 2026-11-11. Genuine cleanup failures now also retain
the plugin's specific diagnostic in the host instead of showing only a generic message.

The plugin has a 10-second cleanup watchdog, inside the host's 15-second deadline. Missing or
failed evidence yields an exceptional stop future and host quarantine. Process exit then
reclaims native/thread residue; it does not turn a failed cleanup into a clean one. The SDK
itself can exit its process when native shutdown times out, which the host must also treat as
unconfirmed cleanup. Follow the host's recovery instructions after quarantine.

Microphone/camera/voice wake capabilities are disabled and RECORD_AUDIO/CAMERA permissions are
not requested. Downstream audio may still be played by the SDK on Android; there is no plugin
browser audio, microphone or media metadata. Encoded-video IPC is opt-in as described below.

## WebRTC implementation and recovery

The optional encoded-video extension v1 forwards the pinned SDK's private H.264 callback
without starting its decoder. Foreground/UIBC initialization remains active. A new browser
consumer can replay one complete bounded GOP (600 frames / 16 MiB); live buffering has the
same separate cap and the plugin writer is limited to 128 records / 8 MiB. The SDK has no
verified on-demand keyframe API. Framing errors, timestamp regression, overflow or format
changes fail with a reconnect/Surface message rather than corrupting predictive frames.
Signed SDK timestamps are normalized relative to the first callback before host validation.
There is no automatic switch to Surface. The 45-second wait budget applies only while a
connected browser consumer awaits display; offline retention stays under the host's policy.
See [the plugin protocol](PROJECTION_PLUGINS.md#压缩视频扩展-v1) for the IPC contract.

## Checks without proprietary files

```bash
./gradlew :projection-plugin-carlink:testDebugUnitTest :projection-plugin-carlink:lintDebug
python3 -m unittest discover -s test -p 'carlink_sdk_test.py'
```

Compile-only declarations are built from source in `sdk-stubs/`. Tests use an engine substitute
and a virtual clock; they cover readiness, state/error mapping, retry budgets, late callbacks,
stopping without invented acknowledgement, touch ordering/release and hardware gates. Python
fixtures test integrity, class-definition checks, ABI rejection and repeatable provisioning.
CI runs these checks in the main build job. A separate `carlink-apk` job provisions the
pinned runtime, builds the final APK and uploads it with the dependency manifest.

## Current acceptance status (2026-11-11)

The maintainer has confirmed successful device validation of P2P, USB, manual hotspot and
WebRTC output. Treat those paths as verified for the tested devices, including video, touch,
normal stop/reconnect and the remaining acceptance items. This supersedes earlier pending
acceptance notes; the latest USB/hotspot/WebRTC confirmation is maintainer-reported rather
than a new automated device run. Do not extrapolate this to every phone or car browser.

Earlier local evidence used Pixel 3 (Android 15, arm64, 4 KB pages) receiving from vivo V2547A,
with Chrome as the browser. It includes P2P + LAN coexistence, background/lock-screen operation,
single-touch, source switching, viewport adaptation and both Surface and WebRTC output.
Local diagnostic artifacts remain in the ignored `.local/carlink-validation/` directory.
Raw logs can contain device identifiers and pairing material and are not release artifacts.

**Known USB recovery requirement:** after stopping a USB session, a subsequent connection can
return 10007 while the sender remains in the old AOA accessory session. Disconnect the USB
cable, reconnect it, keep the sender unlocked, then connect from WheelPlay and allow the USB
and phone-side CarLink prompts. Automatic recovery without cable reconnection is not promised.

The host's encoded-video keyframe watchdog now clears its timer when no connected browser
consumer exists and grants a new wait budget to each consumer. Offline time does not stop a
session configured to remain connected. Unit regression tests cover offline, reconnect,
consumer replacement, successful display and genuine first-frame timeout.

See [the user guide](CARLINK_USER_GUIDE.md) for installation, connection choices and recovery;
[NOTICE](../projection-plugin-carlink/NOTICE.md) records source attribution and binary packaging.
