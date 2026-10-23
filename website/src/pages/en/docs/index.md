---
layout: ../../../layouts/DocsLayout.astro
title: User guide
description: Install, pair, and bring iPhone CarPlay to your car browser.
---

WheelPlay runs on Android and delivers CarPlay video and touch control to your car browser over a local network. You do not need to install a client on your car display or deploy an extra server.

## What you need

- An Android device running **Android 9 or later** to host the service.
- An iPhone that supports CarPlay.
- A car browser with modern web and touch support that can reach the Android device.
- A local network that allows Android and your car to communicate.

Compatibility varies across vehicles, software versions, and networks. Complete the initial setup and testing while safely parked.

## Download and install

On your Android device, open [Download the latest build](/download?lang=en) to get `wheelplay-debug-apk` from GitHub. Extract the downloaded archive and install the APK inside.

The download entry selects the artifact from the latest successful `main` run of the `debug-build.yml` workflow. Results are cached for up to five minutes. GitHub may require you to sign in. If the artifact has expired or is missing from the latest build, try again later or check the [workflow runs](https://github.com/fython/wheelplay/actions/workflows/debug-build.yml).

To compile your own APK, see the [source build instructions](https://github.com/fython/wheelplay/blob/main/README.md#build).

## Import authentication assets

Public open-source builds do not include CarPlay authentication assets by default. In the Android app, open **Settings → CarPlay authentication assets** and import valid assets. The service can start only after validation succeeds.

You can select `identity.pk8` and `certificate.p7b`, or import from a valid DiPlay APK. Import and validation happen locally on Android. Assets are stored in the app's private directory; they are neither uploaded nor included in backups.

See the [authentication import guide](https://github.com/fython/wheelplay/blob/main/docs/AUTHENTICATION_IMPORT.md) for detailed instructions. Some advanced repository guides are currently available in Chinese.

## Open your car browser

1. Install and open WheelPlay on Android, then configure the authentication assets.
2. Connect Android and your car to the same local network.
3. In your car browser, open **the actual address displayed in the WheelPlay app**.

This website provides downloads and instructions. Use the local address in the app to connect your devices.

## Pair and connect your iPhone

1. On first use, scan the QR code on the car browser page with WheelPlay and confirm pairing. You can also enter the pairing code in the browser.
2. Choose your browser options and select “Start display.”
3. Keep the Android app in the foreground and follow its prompts to connect your iPhone via USB or wirelessly.
4. Complete any device selection or authorization, and allow CarPlay on your iPhone.
5. Once connected, view and control CarPlay in the car browser.

Remembered browsers can restore pairing on later visits. Clearing browser data, disabling storage, switching between HTTP and HTTPS, or changing the access address requires pairing again.

Under **Settings → Browser devices → Connected devices**, you can view, rename, or remove devices. Removing a device immediately disconnects it and requires it to pair again. Only one browser control session is allowed at a time.

## Audio and microphone

Android handles audio and microphone input by default. You can select browser routing under **Settings → Audio and microphone**.

Browser microphone input requires a trusted local HTTPS certificate and the necessary browser capabilities. Pair again from the HTTPS address and grant permission. If your browser does not support this, continue using the Android microphone. See the [browser audio guide](https://github.com/fython/wheelplay/blob/main/docs/BROWSER_AUDIO.md) for configuration details.

## Connection troubleshooting

- Confirm that authentication assets passed validation and the service is running in the Android app.
- Confirm that the car and Android can reach each other on the local network, and use the address shown in the app.
- If your browser restricts access to private network addresses, see the [experimental compatibility mode](https://github.com/fython/wheelplay/blob/main/docs/TESLA_HTTP.md).
- Read the [FAQ](/en/docs/faq/) for more on compatibility, permissions, and downloads.

Use the service only on trusted local networks. Do not expose it to the public Internet. Online content in navigation, music, and other apps may still require Internet access.
