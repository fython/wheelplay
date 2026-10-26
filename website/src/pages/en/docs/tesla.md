---
layout: ../../../layouts/DocsLayout.astro
title: Tesla compatibility mode (experimental)
description: An alternative entry point for car browsers that restrict private network addresses, with no-root VPN and root hotspot routing options.
---

Some car browsers cannot open ordinary local network addresses. Tesla compatibility mode attempts to provide an alternative address for the WheelPlay web service running on your Android phone. Despite its name, it may also help other car browsers with the same restriction.

**This is experimental and does not guarantee support for every car or Android system.** Leave it off if the normal local address already works. It changes the browser access address; it is not a projection source and does not add capabilities to CarPlay, Android Auto, or VNC.

## Choose a connection method

Select a method under **Settings → Tesla compatibility (experimental) → Connection method**. Both options are off by default. Switching methods disables the current mode, so you must explicitly enable it again; each method retains its address settings. Neither forwards Internet traffic from third-party VPNs.

| Method | Permission required | Default HTTP address | Ports |
| --- | --- | --- | --- |
| No-root VPN (experimental) | Android VPN permission; no root or Shizuku | `http://6.6.6.6:8080` | Actual web listener port; no mapping for 80 / 443 |
| Root hotspot routing | A rooted device and root permission for WheelPlay | `http://100.96.0.1/` | Maps 80 / 443 to web listener ports 8080 / 8443 by default |

These addresses are default examples. Always use the complete address shown in the app, especially after changing the IP, hostname, or port.

## Before you start

1. Open WheelPlay on the Android host and confirm that the web service is running. Valid authentication assets are only required for CarPlay; the compatibility address itself does not require an iPhone connection.
2. Enable the **system hotspot** on that same Android device and connect the car to it. This mode does not change the hotspot's IP or DHCP subnet.
3. Configure and test while parked. First connect another phone or computer to the hotspot to check access, then test the car browser.

The normal local web listener runs independently. If the compatibility method fails, you can still try the ordinary local address shown in the app.

## No-root VPN (experimental)

1. Select **No-root VPN (experimental)**, enable it, and accept the Android VPN prompt. Denying permission leaves the mode disabled. Use **Authorize or retry VPN** when you want to try again.
2. Wait for “No-root address configured,” then enter the complete address in the car browser, for example `http://6.6.6.6:8080`. Use the updated port if you changed the web listener settings.
3. Follow the [user guide](/en/docs/) to pair the browser and start the display, then connect your chosen input source.
4. If wired CarPlay is already connected, disconnect the iPhone before enabling the mode, changing its IP, or recreating the address. The app defers these changes until disconnection. Disabling the no-root mode itself does not stop wired CarPlay.

This method occupies Android's VPN slot. Coexistence with another VPN is not guaranteed, and it does not provide an Internet VPN service or system Always-on VPN support. Disabling the mode, stopping the web service, or revoking VPN permission removes the compatibility address. Revoked permission requires a manual retry.

“Address configured” only confirms that the phone detected the address. It does not prove hotspot return traffic, car browser access, or WebRTC connectivity. Android ROMs, hotspot firewalls, and system updates can affect the result.

`6.6.6.6` has the form of a public IP address; it is not a public server provided by WheelPlay. With working local configuration, traffic is delivered locally. If the address or route fails, requests may travel upstream, so the status message alone cannot guarantee that traffic always stays on the local network.

## Root hotspot routing

This method needs a rooted Android device with working `su`, `ip`, and `iptables` commands. The routing adaptation is built in; there is no need to install VPN Hotspot separately.

1. Select **Root hotspot routing**, choose **Request root permission**, and grant WheelPlay access in your root manager. Enable compatibility mode after the check succeeds. This method itself does not request Android VPN permission.
2. The default shared IP is `100.96.0.1`. If you change it, avoid conflicts with mobile networks, other VPNs, or local network addresses.
3. HTTP / HTTPS mapped ports default to `80` / `443`. You may choose distinct ports in `1–1023`; they forward to the actual listeners under **Settings → Web service**. HTTPS mapping is installed only when HTTPS is enabled and its listener starts successfully.
4. Wait for “Root hotspot routing enabled,” then open the complete address shown in the app and pair the browser. The default HTTP address is `http://100.96.0.1/`; include the port if you selected a non-default mapping.
5. If permission is denied, a command is missing, a port is occupied, or rule installation fails, resolve the displayed error before selecting **Restart root routing**. The app does not repeatedly request root permission.

Disabling the mode, stopping the web service, or turning off the hotspot removes its routing rules. Settings are retained, and an enabled root method checks permission again when the app process restarts. If cleanup fails, follow the prompt to inspect the state or restart the device; do not assume old rules have been removed.

Root routing is independent of the iPhone session. Wired CarPlay still requires its own Android VPN permission, even when this method is selected.

## HTTPS, hostnames, and pairing

- Enable HTTPS separately under **Settings → Web service**. The no-root method uses the actual HTTPS listener port (`8443` by default); root routing can use the mapped port (`443` by default).
- The certificate must cover the actual IP or hostname and be trusted by the car browser. The default local certificate is refreshed when the access IP or hostname changes; check coverage yourself for imported certificates. Do not configure browser microphone access by bypassing certificate errors.
- Entering a custom hostname does not configure DNS. You must arrange for it to resolve to the actual access IP.
- Changing the protocol, address, or port changes the browser origin and requires pairing again. The compatibility address uses the existing pairing, device revocation, and single-browser control rules.
- Browser microphone input still requires trusted HTTPS, browser support, and permission, and is available only when the selected [input source](/en/docs/sources/) supports it.

## Troubleshooting

1. **Check the hotspot and address first.** The car must be connected to the phone running WheelPlay. Confirm that the web service is running, the method is authorized and enabled, and the complete protocol, address, and port match the app.
2. **Try another hotspot client.** If another phone or computer cannot open the page either, check Android permissions, the hotspot, ports, and routing status first. If it works there but not in the car, check browser policy and whether the URL is being forced to HTTPS.
3. **Check video after the page opens.** WebRTC ICE / UDP connectivity needs separate validation. A working page does not guarantee video passthrough; use image compatibility mode if necessary.
4. **Follow the method's status message.** For a missing no-root address or revoked permission, disable the mode or retry manually. Disconnect wired CarPlay if prompted. For root routing, resolve the specific error; inspect the state or reboot if cleanup failed.
5. **Include your setup in reports.** Record the phone model, Android / ROM version, car firmware, connection method, access address, forced HTTPS behavior, and results from another hotspot client. Diagnostic reports can be exported from the app.

Automated checks do not establish real Android routing, hotspot, or car browser compatibility. See the repository's [Tesla compatibility notes](https://github.com/fython/wheelplay/blob/main/docs/TESLA_HTTP.md) for implementation details and validation scope (in Chinese).
