---
layout: ../../../layouts/DocsLayout.astro
title: Sources & compatibility
description: Choose CarPlay, experimental Android Auto, or VNC and understand connection options and current limitations.
---

WheelPlay runs on an Android host and provides one browser interface for display and control. Connection requirements, video, and audio capabilities depend on the selected source.

| Source | Status | What you need |
| --- | --- | --- |
| CarPlay | Supported; compatibility depends on devices | An iPhone, USB or wireless connection, and valid authentication assets |
| Android Auto | Experimental; device testing still needed | An Android Auto phone, USB or a running Head Unit Server |
| VNC | Experimental; real server and device testing still needed | A remote device running a VNC server and a reachable IP address |
| Diagnostic sources | Display pipeline testing | No phone connection or authentication assets |
| ICCOA CarLink | Unavailable | Proprietary SDK missing; no protocol integration implemented |

Only one source and one browser control session can be active at a time. Opening a source's settings does not switch sessions; starting a connection does. If the browser disconnects after a switch, reconnect and start the display again.

## CarPlay

Connect an iPhone to the Android host over USB or wirelessly, then view and control CarPlay in the car browser.

1. Public builds do not include authentication assets. Under **Settings → CarPlay authentication assets**, import a matching `identity.pk8` and `certificate.p7b`, or import them from a compatible DiPlay APK.
2. Choose a connection under **Input sources → CarPlay (iPhone)**. Keep WheelPlay in the foreground during setup, complete Android device and permission prompts, and allow CarPlay on the iPhone.
3. Follow the [user guide](/en/docs/) to pair the browser and start the display. You can also initiate an iPhone connection from the paired browser's waiting screen.

CarPlay supports WebRTC H.264 / compatible HEVC passthrough and JPEG / WebP image compatibility mode. Actual frame rates depend on the phone, network, and browser.

Audio and microphone use Android by default, with optional browser routing. Browser microphone input needs trusted HTTPS, browser support, and permission. Day/night mode, safe areas, car branding, and location settings are CarPlay features; other sources do not necessarily offer the same capabilities.

Authentication assets are only needed for CarPlay. The web service, browser pairing, and other sources work without them. See the [authentication import](https://github.com/fython/wheelplay/blob/main/docs/AUTHENTICATION_IMPORT.md) and [audio setup](https://github.com/fython/wheelplay/blob/main/docs/BROWSER_AUDIO.md) guides. Advanced repository guides are currently in Chinese.

## Android Auto

**Experimental.** Real Android Auto protocol connections are implemented, but compatibility across phones and Android Auto versions still needs device testing. Under **Input sources → Android Auto**, choose:

- **Same phone:** WheelPlay and Android Auto run on the same phone. Complete Android Auto setup, enable developer mode, and manually select “Start head unit server,” then return to WheelPlay to connect. This uses `127.0.0.1:5277`; you may need to restart the server after rebooting the phone.
- **USB:** Connect the Android Auto phone to the WheelPlay host's USB Host / OTG port. Attach only one candidate phone and accept USB permission prompts. The two devices must support the host and accessory roles respectively.
- **Head Unit Server (TCP):** Start the server on the Android Auto phone and enter an IPv4 address reachable from the WheelPlay host, on port `5277`. A server listening only on loopback needs separate port forwarding; being on the same Wi-Fi is not sufficient on its own.

Video is currently **1280×720 at 30 fps using H.264**, through the shared WebRTC and image fallback pipeline. Audio plays only on the Android host. Microphone input requires selecting the Android microphone and granting recording permission.

Browser audio/microphone, automatic wireless discovery/pairing, media metadata, HFP calls, dynamic resolution, HEVC, and automatic reconnection are not supported. Same-phone mode does not enable developer mode or start the server for you. See the [protocol and validation guide](https://github.com/fython/wheelplay/blob/main/docs/ANDROID_AUTO.md).

## VNC

**Experimental.** WheelPlay is a VNC client for an existing remote desktop. It does not create a virtual desktop on the Android host and does not depend on Shizuku or ADB.

1. Run a VNC server on the remote computer or Android device and allow the WheelPlay host to reach its port. Start with a desktop of 1280×720 or smaller.
2. Open **Input sources → VNC → Connect VNC** and enter a numeric IPv4 / IPv6 address and port (default `5900`). Hostnames and IPv6 zone IDs are not supported.
3. For traditional VNC Authentication, enter a password of up to eight Latin-1 characters. Select “View only” to disable all remote input.
4. Use the usual browser pairing flow to view the desktop. Single-finger taps and drags act as the left mouse button. A physical mouse supports hover, left/middle/right buttons, and scrolling. Click the display to focus it before using a physical keyboard.
5. Reconnect manually after disconnection and enter the password again. The address, port, and view-only option are saved; the password is used only for the current session and is not written to preferences, logs, or the browser.

Supported protocols are RFB 3.3 / 3.7 / 3.8, with Raw, CopyRect, Hextile, and DesktopSize. The desktop uses **JPEG / WebP image streaming**, without WebRTC video encoding or audio.

Only no authentication and traditional VNC Authentication are supported. TLS / VeNCrypt, Tight / ZRLE, clipboard transfer, IME composition / software keyboards, true multitouch, automatic discovery, and automatic reconnection are not supported. Browser-reserved system shortcuts may not reach the remote desktop.

**Use a trusted local network or VPN.** Traditional VNC authentication and desktop transport do not provide modern encryption. HTTPS on the browser interface does not encrypt the connection from Android to the VNC server. Real server compatibility and device performance still need validation; see the [VNC implementation and testing guide](https://github.com/fython/wheelplay/blob/main/docs/VNC.md).

## Diagnostic sources and ICCOA

**Diagnostic sources** provide H.264, Surface, and framebuffer test images to check the display pipeline without connecting a phone. They are synthetic images, not phone projection, and do not establish real-device compatibility.

**ICCOA CarLink is unavailable.** The proprietary SDK is missing. Its entry only reports the missing dependency; it does not start a protocol connection or stop an existing session when selected. VNC is not an ICCOA implementation.
