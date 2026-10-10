# WheelPlay

**EN** | [CN](README.zh-CN.md)

WheelPlay is an Android-hosted CarPlay server for local networks. It streams your iPhone's CarPlay display to your car's browser and lets you control it through the car's touchscreen.

Install the app on an Android device and open the displayed address in your car's browser. No browser-side client installation or additional server is required.

## Features

- **Browser display and touch control**: CarPlay streaming, touch input, and fullscreen display.
- **Selectable streaming modes**: Choose WebRTC video passthrough (H.264 / HEVC) or JPEG compatibility mode in settings. If passthrough fails, video automatically falls back to JPEG while audio and touch keep their existing routes.
- **Optional browser audio**: After pairing, the browser can play CarPlay audio. The browser microphone can provide CarPlay voice input through a trusted HTTPS page; Android handles audio and microphone input by default.
- **Easy pairing**: Scan the QR code or enter a code once. Remembered browsers restore pairing automatically and retain a launch button for selecting options before streaming.
- **iPhone connection management**: USB and wireless connections, connection status, and session management.
- **Local network access**: Automatically detects available network addresses for browser access.
- **Background service**: Background operation and automatic connection.
- **Display and audio settings**: Adjustable display size, resolution, frame rate, and audio buffering.
- **Diagnostic export**: Save diagnostic reports locally for troubleshooting.

## Getting started

1. Install and launch WheelPlay on the Android server device.
2. Connect the Android device and your car to the same local network, then open the address shown in the app in your car's browser.
3. On first use, scan the QR code with WheelPlay and approve pairing, then select browser options and click “启动显示” (Start display). Alternatively, enter the pairing code and click “配对并启动显示” (Pair and start display). On later visits, pairing restores automatically; select options and click Start display. Select “启动后隐藏顶栏并全屏” to request fullscreen when launching and hide the top bar after connecting. Exiting fullscreen restores the top bar; browsers without fullscreen support only hide the bar.
4. Keep WheelPlay in the foreground on Android. Click “连接 iPhone” (Connect iPhone) on the paired browser’s waiting screen to start using the app’s current USB / wireless settings, or connect from the app. Complete any device selection or permission prompts on Android and allow CarPlay on the iPhone.
5. Once connected, view and control CarPlay in your car's browser.

Manage remembered browsers under Settings → 浏览器设备 (Browser devices) → 连接过的设备 (Connected devices). View names, pairing times, last connection times and addresses; rename or remove one browser, or remove all. Removal immediately disconnects the device and revokes its existing sessions. Browser disconnection and server restarts retain device memory. Up to 32 browsers can be saved; remove an old entry if the limit is reached.

Device memory is scoped to the current browser and origin (scheme, host and port). Clearing or disabling browser storage, switching HTTP / HTTPS, or changing the address requires fresh pairing. Android persists only hashes of random device secrets; the six-digit code is never stored as a remembered credential, and browser credentials are excluded from app backups.

For 60 fps, select WebRTC video passthrough under Settings → Display & Audio and set the negotiated frame rate to 60. Apply the settings and reconnect for the changes to take effect. Actual frame rate depends on the iPhone's output, network conditions, and browser decoding capabilities; static scenes may run at a lower frame rate. WebRTC uses local-network UDP without external STUN/TURN servers. It automatically falls back to JPEG if UDP is unavailable or the source video codec is incompatible. Allowing HEVC on the iPhone enables H.265 passthrough to browsers that support HEVC over WebRTC; H.264 is recommended for older browsers.

If the video element renders incorrectly in your car's browser, enable “Use Canvas to display video (experimental)” on the webpage's connection screen before connecting. This is disabled by default. The selection is saved in the current browser and persists across automatic reconnects and page reloads; if browser storage is disabled, it applies only to the current page. WebRTC video is still decoded by an offscreen `<video>` element and drawn directly onto a visible Canvas with `drawImage()`. Video frame callbacks are preferred, with animation frames used for older browsers or when offscreen callbacks stall. Touch coordinates are mapped to the video's aspect ratio, and drawing failures trigger a fallback to JPEG. This mode may increase GPU / CPU usage. The video frame rate in the status bar still reflects the browser's video presentation statistics, not the actual Canvas drawing rate.

Canvas mode requires testing on car browsers such as NIO's. Changing the rendering path may help if an overlay covers only the native video element; it cannot guarantee success if the system stops decoding or overlays the entire page. Test while the vehicle is parked. Drivers should use only essential navigation interfaces while driving.

Enable browser audio and microphone input separately under Settings → Display & Audio in the Android app. Both can be toggled while connected. Browser microphone input requires configuring a local HTTPS certificate for that device, then pairing again through the HTTPS entry point and granting microphone permission. See the [browser audio guide](docs/BROWSER_AUDIO.md) (Chinese) for instructions. If the car's browser does not support trusted HTTPS or AudioWorklet, continue using the Android microphone.

## Requirements and limitations

An optional experimental Tesla HTTP mode creates a local virtual IPv4 address through Android's VPN API without changing the system hotspot subnet or requiring certificates. Connect the car to the server device's hotspot, enable the mode in Settings, approve VPN access, and try `http://100.96.0.1:8080/`. A custom shared-space address, HTTP port (default `8080`), and a self-managed HTTP hostname are supported. Port changes apply to all HTTP entries, including same-device preview; if a new port cannot bind, the running listener and saved configuration are retained. Hotspot routing and Tesla HTTP support require device testing; creating the interface does not prove browser reachability. See the [Tesla HTTP guide](docs/TESLA_HTTP.md) (Chinese). This mode shares WheelPlay's USB CarPlay VPN and may replace another VPN.

- The server device requires Android 9 or later. The car's browser must support modern web features and touch interaction.
- The server device and car must be able to reach each other over the local network. Wireless connection compatibility depends on the devices' networking capabilities.
- Android handles audio and microphone input by default; browser routing must be enabled manually. Browser microphone input also requires the car's browser to allow installing and trusting a local certificate.
- Only one browser control session is allowed at a time.
- Streaming smoothness and connection stability depend on device performance, network quality, and system background restrictions.
- Use only on trusted local networks. HTTP pairing, WebSocket control, and JPEG transport are unencrypted; WebRTC video uses DTLS-SRTP. Browser microphone input uses local HTTPS/WSS. Do not bypass certificate warnings or expose the service to the public internet.

## Build

Use Android NDK 28.2.13676358 and CMake 3.22.1. The first build fetches pinned versions of libdatachannel and Mbed TLS from GitHub; subsequent builds reuse the local CMake cache. Run:

```sh
./gradlew :mobile:assembleDebug
```

Source-only builds omit CarPlay authentication resources. After installation, open **Settings → CarPlay authentication resources** to select `identity.pk8` and `certificate.p7b`, or follow the DiPlay website download link and import the downloaded APK. You can also share that APK with WheelPlay from a file manager; DiPlay does not need to be installed. Imports run locally and replace existing resources only after key/certificate validation. Resources stay in private, backup-excluded app storage and are never uploaded. See the [authentication import guide](docs/AUTHENTICATION_IMPORT.md).

Developers may still set `WHEELPLAY_AUTH_ASSETS_DIR` (or `DIPLAY_AUTH_ASSETS_DIR`) to a local asset directory, or use `.local/auth-assets/`, containing `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. These files stay out of Git. Run `./gradlew :mobile:assembleProvisionedDebug` to build and verify both packaged assets against the local input. Public source-only APKs should use the ordinary build without configured authentication assets, letting users import their own resources after installation.

## Origins and licensing

This project is based on DiPlay / xcertplay and retains the upstream licenses and third-party notices. See [LICENSE](LICENSE) and [third-party notices](docs/upstream/THIRD_PARTY_NOTICES.md). Third-party assets and components remain subject to their respective licenses.

WebRTC uses [libdatachannel 0.24.1](https://github.com/paullouisageneau/libdatachannel/tree/v0.24.1) and [Mbed TLS 3.6.5](https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.5), with versions pinned in the CMake configuration. [Native dependency licenses](common/src/main/assets/webrtc-licenses.txt) are distributed with the APK.

For streaming diagnostics, inspect `window.wheelplayStats` in the browser console. It includes source input and send rates over a 2-second window; decryption, queueing, and sending times as means and P95 values over the latest 240 samples; drop counts; browser receive, decode, and presentation rates; packet loss; and RTT. Rates and timings are measured separately for each stage and do not represent end-to-end latency. Unsupported browser metrics are `null`. The passthrough queue budget is 50 ms; on timeout, the reference chain is dropped until a keyframe arrives. The pinned-version patch for native media send feedback is in `common/src/main/cpp/media_send_feedback.cmake`. A successful local send only means the transport layer accepted the data; receiver feedback indicates subsequent progress.
