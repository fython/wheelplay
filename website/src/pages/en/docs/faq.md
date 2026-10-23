---
layout: ../../../layouts/DocsLayout.astro
title: Frequently asked questions
description: Answers about devices, downloads, connections, and audio.
---

If this is your first time using WheelPlay, follow the [user guide](/en/docs/) to install the app, import authentication assets, and pair your browser.

## Will it work with my car?

Your car browser needs modern web and touch support, plus local network access to the Android service device. Compatibility varies by vehicle, software version, and network. Support for every model is not guaranteed; test your setup while safely parked.

Video performance also depends on iPhone output, network quality, Android performance, and the browser's decoding capabilities.

## Does WheelPlay require root?

Normal local network connections and the web service do not require root.

For browsers that restrict access to private network addresses, the app offers experimental Tesla compatibility mode. The root-free VPN option needs Android VPN permission; hotspot routing needs root. Both require testing on your device. See the [compatibility guide](https://github.com/fython/wheelplay/blob/main/docs/TESLA_HTTP.md). Some advanced repository guides are currently available in Chinese.

## Why do I need authentication assets?

Public open-source builds do not include CarPlay authentication assets by default. Import valid assets under **Settings → CarPlay authentication assets**. The service can start after validation succeeds.

Import happens locally on Android. Assets stay in the app's private directory and are neither uploaded nor included in backups. See the [authentication import guide](https://github.com/fython/wheelplay/blob/main/docs/AUTHENTICATION_IMPORT.md) for supported formats and instructions.

## Why does downloading open GitHub?

The [download entry](/download?lang=en) selects `wheelplay-debug-apk` from the latest successful `main` run of the specified workflow, then redirects to its GitHub artifact page. This website does not store or proxy installation files.

- GitHub may require you to sign in before downloading artifacts.
- Artifacts expire; expired or deleted files cannot be downloaded.
- If the latest successful run has no matching artifact, the entry does not select an older run.
- Results are cached for up to five minutes, so a new build may take a little time to appear.
- If GitHub is temporarily unavailable or rate-limited, try again later or open the [workflow runs](https://github.com/fython/wheelplay/actions/workflows/debug-build.yml) directly.

## Where do audio and microphone input go?

Android handles audio and microphone input by default. Browser routing can be enabled under **Settings → Audio and microphone**.

Browser microphone input requires trusted local HTTPS and browser support. Otherwise, you can keep using the Android microphone. After enabling HTTPS, pair again from the HTTPS address and grant permission. See the [audio setup guide](https://github.com/fython/wheelplay/blob/main/docs/BROWSER_AUDIO.md).

## Do I need a server or Internet access?

Android streams CarPlay over your local network without an extra cloud server. Online content in navigation, music, and other apps may still require Internet access.

Use the service only on trusted local networks; do not expose it publicly. This website provides information, downloads, and instructions. Connect your devices using the local address shown in the app.

## Why do I need to pair my browser again?

Device memory is tied to the browser and access address. Clearing browser data, disabling local storage, switching between HTTP and HTTPS, or changing the host or port requires pairing again.

Removing a remembered browser in the Android app also invalidates its existing credentials. Only one browser control session is allowed at a time.

## Is WheelPlay open source?

Yes. WheelPlay builds on DiPlay / xcertplay under GPL-3.0 and preserves upstream and third-party notices. Third-party components and assets retain their own licenses.

Read the [source code](https://github.com/fython/wheelplay), review the [license](https://github.com/fython/wheelplay/blob/main/LICENSE), or report a problem in [GitHub Issues](https://github.com/fython/wheelplay/issues).
