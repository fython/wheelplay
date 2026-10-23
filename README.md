# WheelPlay

**EN** | [CN](README.zh-CN.md)

WheelPlay is an Android-hosted CarPlay server for local networks. It streams your iPhone's CarPlay display to your car's browser and lets you control it through the car's touchscreen.

Install the app on an Android device and open the displayed address in your car's browser. No browser-side client installation or additional server is required.

## Product website

The Astro product website lives in [`website/`](website/README.md), separately from the Android-hosted LAN client. With Node.js 24 LTS, run `npm ci --prefix website`, then `npm run site:dev` for live development or `npm run site:build` to generate `website/dist/`. See the [website guide](website/README.md) for Cloudflare Pages and Workers deployment.

## Features

- **Browser display and touch control**: CarPlay streaming, touch input, and fullscreen display.
- **Selectable streaming modes**: Choose WebRTC video passthrough (H.264 / HEVC) or image compatibility mode in settings, with JPEG / WebP format and image quality from 1 to 100. Image streaming retains JPEG compatibility fallback and overlaps up to two frames while audio and touch keep their existing routes.
- **Optional browser audio**: After pairing, the browser can play CarPlay audio. The browser microphone can provide CarPlay voice input through a trusted HTTPS page; Android handles audio and microphone input by default.
- **Easy pairing**: Scan the QR code or enter a code once. Remembered browsers restore pairing automatically and retain a launch button for selecting options before streaming.
- **iPhone connection management**: USB and wireless connections, connection status, and session management.
- **Local network access**: Automatically detects available network addresses for browser access.
- **Background service**: Background operation and automatic connection.
- **Display and audio settings**: Adjustable display size, resolution, frame rate, and audio buffering.
- **CarPlay preferences**: Follow Android's day/night mode or force day/night; select Android/browser audio output and Android/browser/disabled microphone input. Preview custom 0–40% safe margins on each edge; microphone and safe-area changes reconnect the iPhone. Keep CarPlay when browsers go offline, or disconnect the iPhone after 1/5 minutes; browser reconnection cancels the timer.
- **Custom car branding**: In **Settings → Vehicle & location → OEM car name and icon**, rename CarPlay's return-to-car entry, select and crop a square image, or restore the default NIO name and icon. Changes apply on the next iPhone connection. Images are stored privately in the app, so the original file is no longer needed.
- **Location source**: **Settings → Vehicle & location → Location source** defaults to the iPhone's own location, with no external location reported by WheelPlay unless browser GPS is enabled. Select the Android device to provide its position when requested by the iPhone, after reconnecting and granting precise location permission. iOS and the navigation app determine location fusion; wireless connections on older Android versions can still require location permission.
- **Optional browser GPS**: Select **Send browser GPS to CarPlay** on the trusted HTTPS connection page, grant browser location permission, then start the display. The option is off on every page load and is not saved. Browser fixes take priority over enabled Android GPS. Disabling, revoking permission, or disconnecting stops capture and clears cached coordinates; fixes expire after 30 seconds. When browser GPS is unavailable, enabled Android GPS is used, otherwise the iPhone uses its own location. CarPlay advertises the location channel to allow opt-in during a session, but no location is collected or reported by default.
- **Diagnostic export**: Save diagnostic reports locally for troubleshooting.

## Getting started

1. Install and launch WheelPlay on the Android server device.
2. Connect the Android device and your car to the same local network, then open the address shown in the app in your car's browser.
3. On first use, scan the QR code with WheelPlay and approve pairing, then select browser options and click “启动显示” (Start display). Alternatively, enter the pairing code and click “配对并启动显示” (Pair and start display). On later visits, pairing restores automatically; select options and click Start display. Select “启动后隐藏顶栏并全屏” to request fullscreen when launching and hide the top bar after connecting. Exiting fullscreen restores the top bar; browsers without fullscreen support only hide the bar.
4. Keep WheelPlay in the foreground on Android. Click “连接 iPhone” (Connect iPhone) on the paired browser’s waiting screen to start using the app’s current USB / wireless settings, or connect from the app. Complete any device selection or permission prompts on Android and allow CarPlay on the iPhone.
5. Once connected, view and control CarPlay in your car's browser.

Manage remembered browsers under Settings → 浏览器设备 (Browser devices) → 连接过的设备 (Connected devices). View names, pairing times, last connection times and addresses; rename or remove one browser, or remove all. Removal immediately disconnects the device and revokes its existing sessions. Browser disconnection and server restarts retain device memory. Up to 32 browsers can be saved; remove an old entry if the limit is reached.

Device memory is scoped to the current browser and origin (scheme, host and port). Clearing or disabling browser storage, switching HTTP / HTTPS, or changing the address requires fresh pairing. Android persists only hashes of random device secrets; the six-digit code is never stored as a remembered credential, and browser credentials are excluded from app backups.

For 60 fps, select WebRTC video passthrough under Settings → Display & streaming and set the negotiated frame rate to 60. Apply the settings and reconnect for the changes to take effect. Actual frame rate depends on the iPhone's output, network conditions, and browser decoding capabilities; static scenes may run at a lower frame rate. WebRTC uses local-network UDP without external STUN/TURN servers. It automatically falls back to JPEG if UDP is unavailable or the source video codec is incompatible. Allowing HEVC on the iPhone enables H.265 passthrough to browsers that support HEVC over WebRTC; H.264 is recommended for older browsers.

The WebRTC interruption tolerance setting offers 4, 10 (default), or 30 seconds and applies on the next browser connection. Recoverable interruptions keep the last video frame visible while waiting for recovery; persistent problems fall back to JPEG after the grace period. Unsupported codecs and terminal connection failures still fall back immediately. This does not enlarge the video buffer or change control and audio connection rules. Refresh the webpage after updating the App. See the [stability and troubleshooting notes](docs/WEBRTC_RECOVERY.md) (Chinese).

The connection page offers three **Video display mode** radio cards. **Native Video** remains the default; both Canvas options are experimental:

- **Native Video** displays the WebRTC stream in a video element.
- **Compatibility Canvas** decodes with an offscreen `<video>` and copies frames with `drawImage()`. Video frame callbacks are preferred, with animation frames recovering older browsers or stalled offscreen callbacks.
- **Direct Canvas** reads WebRTC `VideoFrame` objects through the main-thread `MediaStreamTrackProcessor` and draws them directly. It never creates a `<video>` or uses `VideoDecoder`. Worker-only Processor implementations are outside this first version. Missing support or runtime failure falls back directly to JPEG / WebP without switching to a video-element mode.

The browser remembers the choice across reloads and automatic reconnects; unavailable storage retains it only in the current page. Existing Canvas preferences migrate to Compatibility Canvas. Disconnect before choosing another mode; automatic retries retain the launch choice. Both Canvas modes wait for a successfully drawn frame before stopping the image stream, and touch mapping follows actual frame dimensions. Canvas can increase GPU / CPU usage and does not guarantee 60 fps. Direct Canvas reports successful draw submissions in the status bar; the other modes retain video presentation / decoding statistics. Neither measures physical display refresh. `window.wheelplayStats.presentation` exposes selected / active modes, drawing statistics and fallback reasons.

Canvas mode requires testing on car browsers such as NIO's. Changing the rendering path may help if an overlay covers only the native video element; it cannot guarantee success if the system stops decoding or overlays the entire page. Test while the vehicle is parked. Drivers should use only essential navigation interfaces while driving.

Select Audio output and Microphone source under Settings → Audio & microphone. Output changes apply immediately; microphone source changes reconnect the iPhone. Disabling microphone input stops capture/uplink and removes input capability advertisements. Current routes are shown; unavailable browsers fall back to Android, while disabled microphone input blocks all fallback capture. Browser microphone input requires a trusted local HTTPS certificate, pairing through HTTPS, and granting microphone permission. See the [browser audio guide](docs/BROWSER_AUDIO.md) (Chinese). If trusted HTTPS or AudioWorklet is unavailable, use the Android microphone.

## Requirements and limitations

Tesla compatibility helps car browsers that block private IP ranges reach the phone's local service through a compatible access address. It also applies to other vehicle brands with the same restriction. Choose **No-root VPN (experimental)** or **Root hotspot routing** under Settings → Tesla compatibility → Connection method. Both are opt-in; switching methods disables the mode until explicitly enabled again, retaining each method's saved address.

- **No-root VPN** requires Android VPN consent, with no Root or Shizuku. Connect the Tesla to this phone's system hotspot and try `http://6.6.6.6:8080`, using the actual Web listener port; no low-port mapping is provided. The shared VPN service retains the IPv4 alias TUN and establishes wired CarPlay's IPv6/NCM TUN last. Disconnecting the iPhone restores the placeholder VPN. Enabling the alias or changing its IP during wired CarPlay waits until disconnect. Long-lived old TUN retention is not guaranteed by Android's API contract and may fail across devices or system updates. “Address configured” confirms the address exists on the phone; hotspot access, Tesla browser behavior, and WebRTC ICE/UDP require device testing.
- **Root hotspot routing** embeds the [VPN Hotspot](https://github.com/Mygod/VPNHotspot) adaptation and requires `su`, `ip`, and `iptables`. Grant Root manually and enable it; the default entry is `http://100.96.0.1/`. Ports `1–1023` (defaults `80` / `443`) map to the Web listener ports. Startup checks Root only for an enabled Root mode. This method does not request VPN consent and keeps wired CarPlay's IPv6 VPN independent.

Both methods clean up when disabled or when the Web service stops. Ordinary LAN Web listeners remain independent; optional HTTPS still requires a trusted certificate. See the [Tesla compatibility guide](docs/TESLA_HTTP.md) (Chinese).

Settings → Web service (Web 服务) contains the HTTP port (default `8080`), a self-managed Web hostname shared by HTTP and HTTPS, and an optional HTTPS switch. No Root access is required; listener ports range from `1024–65535`. Enabling HTTPS reveals its port (default `8443`) and inline certificate types: local default, PEM, and PKCS#12. Android's file picker imports PEM chains plus private keys (including encrypted keys) or PKCS#12 (`.p12` / `.pfx`) with passwords and key-alias selection. Identities are validated and saved in private non-backup storage; failed imports retain the current certificate, and occupied ports retain the current listener. Disabling HTTPS stops TLS while retaining its settings and certificate. See [Web listener and certificate setup](docs/BROWSER_AUDIO.md).

- The server device requires Android 9 or later. The car's browser must support modern web features and touch interaction.
- Microphone uplink uses the Android platform Opus encoder. If unavailable, only PCM input formats are advertised for the iPhone to negotiate. No software Opus library is bundled; Siri and call compatibility on devices without Opus still requires device validation.
- The server device and car must be able to reach each other over the local network. Wireless connection compatibility depends on the devices' networking capabilities.
- Android handles audio and microphone input by default; browser routing must be enabled manually. Browser microphone input also requires the car's browser to allow installing and trusting a local certificate.
- Only one browser control session is allowed at a time.
- Streaming smoothness and connection stability depend on device performance, network quality, and system background restrictions.
- Use only on trusted local networks. HTTP pairing, WebSocket control, and JPEG transport are unencrypted; WebRTC video uses DTLS-SRTP. Browser microphone input uses local HTTPS/WSS. Do not bypass certificate warnings or expose the service to the public internet.

## Build

Android settings use Jetpack Compose. Category screens and shared controls live in `common/src/main/java/com/shilapi/xcertplay/settings/`; `SettingsViewModel` owns preferences and saved navigation state, while `DiPlayActivity` coordinates platform permissions, file pickers, and service changes. `SettingsScreen.kt` includes phone and large-text previews. Settings interaction tests use Compose semantics with Robolectric.

User-visible text uses Android string resources: English in `values`, Simplified Chinese in `values-zh-rCN`, and Traditional Chinese in `values-zh-rTW`. Android selects resources from the system locale; there is no in-app language selector. Use `stringResource` / `getString` and positional format arguments for new copy. `BrowserStrings` supplies the same selected resources to the browser display; protocol identifiers and diagnostic logs remain stable. Translation completeness and format arguments are checked by `npm test`.

Use Android NDK 28.2.13676358 and CMake 3.22.1. The first build fetches pinned versions of libdatachannel and Mbed TLS from GitHub; subsequent builds reuse the local CMake cache. Run:

```sh
./gradlew :mobile:assembleDebug
```

Source-only builds omit CarPlay authentication resources. Web and CarPlay remain disabled until valid resources are configured; successful import automatically starts the Web service. APKs with valid bundled resources work immediately and still allow replacement, with imported resources taking priority. Open **Settings → CarPlay authentication resources** to select `identity.pk8` and `certificate.p7b`, or follow the DiPlay website download link and import the downloaded APK. You can also share that APK with WheelPlay from a file manager; DiPlay does not need to be installed. Imports run locally and replace existing resources only after key/certificate validation. Resources stay in private, backup-excluded app storage and are never uploaded. See the [authentication import guide](docs/AUTHENTICATION_IMPORT.md).

Developers may still set `WHEELPLAY_AUTH_ASSETS_DIR` (or `DIPLAY_AUTH_ASSETS_DIR`) to a local asset directory, or use `.local/auth-assets/`, containing `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. These files stay out of Git. Run `./gradlew :mobile:assembleProvisionedDebug` to build and verify both packaged assets against the local input. Public source-only APKs should use the ordinary build without configured authentication assets, letting users import their own resources after installation.

## Origins and licensing

This project is based on DiPlay / xcertplay and retains the upstream licenses and third-party notices. See [LICENSE](LICENSE) and [third-party notices](docs/upstream/THIRD_PARTY_NOTICES.md). Third-party assets and components remain subject to their respective licenses.

WebRTC uses [libdatachannel 0.24.1](https://github.com/paullouisageneau/libdatachannel/tree/v0.24.1) and [Mbed TLS 3.6.5](https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.5), with versions pinned in the CMake configuration. [Native dependency licenses](common/src/main/assets/webrtc-licenses.txt) are distributed with the APK.

For streaming diagnostics, inspect `window.wheelplayStats` in the browser console. It includes source input and send rates over a 2-second window; decryption, queueing, and sending times as means and P95 values over the latest 240 samples; drop counts; browser receive, decode, and presentation rates; packet loss; and RTT. Rates and timings are measured separately for each stage and do not represent end-to-end latency. Unsupported browser metrics are `null`. The passthrough queue budget is 50 ms; on timeout, the reference chain is dropped until a keyframe arrives. The pinned-version patch for native media send feedback is in `common/src/main/cpp/media_send_feedback.cmake`. A successful local send only means the transport layer accepted the data; receiver feedback indicates subsequent progress.
