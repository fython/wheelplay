---
layout: ../../layouts/DocsLayout.astro
title: 输入源与兼容性
description: 选择 CarPlay、实验性 Android Auto 或 VNC，了解连接方式和当前支持范围。
---

WheelPlay 安装在 Android 宿主上，为车机浏览器提供统一的显示与控制入口。不同输入源的连接要求、画面和音频能力并不相同。

| 输入源 | 状态 | 需要准备 |
| --- | --- | --- |
| CarPlay | 已支持，兼容性取决于设备 | iPhone、USB 或无线连接、有效认证资源 |
| Android Auto | 实验性，仍需真机验证 | 支持 Android Auto 的手机，USB 或已启动的 Head Unit Server |
| VNC | 实验性，仍需实际服务端与设备验证 | 运行 VNC 服务端的远端设备及可达的 IP 地址 |
| 诊断输入源 | 用于画面链路测试 | 无需手机连接或认证资源 |
| ICCOA CarLink | 暂不可用 | 缺少专有 SDK，当前未实现协议接入 |

同一时间只运行一个输入源，并允许一个浏览器控制会话。在应用中进入输入源的设置页面不会自动切源；发起连接才会切换。切换后若浏览器断开，请重新连接并启动显示。

## CarPlay

将 iPhone 通过 USB 或无线方式连接到 Android 宿主，在车机浏览器中显示和触控 CarPlay。

1. 公开构建默认不包含认证资源。先在 **设置 → CarPlay 认证资源** 中导入匹配的 `identity.pk8` 与 `certificate.p7b`，或从兼容的 DiPlay APK 导入。
2. 在 **输入源 → CarPlay (iPhone)** 选择连接方式。配置期间保持 WheelPlay 在前台，完成 Android 的设备选择与权限提示，并在 iPhone 上允许 CarPlay。
3. 按[使用指南](/docs/)配对浏览器并启动显示。已配对浏览器的等待页面也可发起 iPhone 连接。

CarPlay 支持 WebRTC H.264／兼容的 HEVC 直通及 JPEG／WebP 图像兼容模式。实际帧率取决于手机输出、网络和浏览器能力。

音频与麦克风默认使用 Android，可选浏览器路由；浏览器麦克风需要受信任的 HTTPS、浏览器支持及授权。日夜模式、安全边距、车厂入口和定位等设置属于 CarPlay 功能，不代表其他输入源具备相同能力。

认证资源仅用于 CarPlay；缺少它们不影响 Web 服务、浏览器配对及其他输入源。详见[认证资源导入](https://github.com/fython/wheelplay/blob/main/docs/AUTHENTICATION_IMPORT.md)和[音频配置](https://github.com/fython/wheelplay/blob/main/docs/BROWSER_AUDIO.md)。

## Android Auto

**实验功能。** 已实现真实的 Android Auto 协议连接，但不同手机和 Android Auto 版本仍需真机验证。在 **输入源 → Android Auto** 选择：

- **本机**：WheelPlay 与 Android Auto 位于同一手机。完成 Android Auto 首次设置，开启开发者模式并手动选择「启动车机服务器」，然后返回 WheelPlay 连接。本机入口连接 `127.0.0.1:5277`；手机重启后可能需要重新启动服务器。
- **USB**：将 Android Auto 手机连接到 WheelPlay 宿主的 USB Host／OTG 端口，只接入一台候选手机，并确认系统 USB 授权。两个设备必须分别支持 host 和 accessory 角色。
- **Head Unit Server (TCP)**：在 Android Auto 手机启动车机服务器，填写宿主可访问的 IPv4 地址，端口为 `5277`。如果服务器只监听本机回环地址，需要另行建立端口转发；仅连接同一 Wi-Fi 不一定可达。

当前画面为 **1280×720、30 fps、H.264**，使用公共 WebRTC 与图像回退管线。音频只在 Android 宿主播放；麦克风仅在选择 Android 麦克风且授予录音权限后启用。

暂不支持浏览器音频或麦克风、自动无线发现／配对、媒体信息、HFP 通话、动态分辨率、HEVC 或自动重连。本机模式不会替你开启开发者模式或启动车机服务器。详见[协议接入与验证说明](https://github.com/fython/wheelplay/blob/main/docs/ANDROID_AUTO.md)。

## VNC

**实验功能。** WheelPlay 是 VNC 客户端，连接已有的远程桌面；它不会创建 Android 本机虚拟桌面，也不依赖 Shizuku 或 ADB。

1. 在远端电脑或 Android 设备运行 VNC 服务端，允许 WheelPlay 宿主访问其端口。建议先使用 1280×720 或更小的桌面。
2. 在 **输入源 → VNC → 连接 VNC** 填写数字 IPv4／IPv6 地址及端口（默认 `5900`）。暂不支持主机名或 IPv6 zone ID。
3. 服务端使用传统 VNC 认证时，输入密码，最多 8 个 Latin-1 字符；若只想观看，勾选「仅查看」。
4. 连接后使用相同的浏览器配对流程查看桌面。单指点按和拖动对应鼠标左键；实体鼠标支持悬停、左／中／右键及滚轮。点击画面取得焦点后可使用实体键盘。
5. 断开后需手动重连，并重新输入密码。地址、端口和仅查看选项会保存，密码只用于当前会话，不写入偏好设置、日志或浏览器。

当前支持 RFB 3.3／3.7／3.8，以及 Raw、CopyRect、Hextile 和 DesktopSize。桌面通过 **JPEG／WebP 图像串流**输出，不使用 WebRTC 视频编码，也不提供音频。

仅支持无认证或传统 VNC Authentication；暂不支持 TLS／VeNCrypt、Tight／ZRLE、剪贴板、输入法组合输入／软件键盘、真正多点触控、自动发现或自动重连。浏览器保留的系统快捷键不保证能转发。

**请使用可信局域网或 VPN。** 传统 VNC 认证和桌面传输不提供现代加密保护，浏览器入口的 HTTPS 不会加密 Android 宿主到 VNC 服务端的连接。实际服务端兼容性与设备性能仍需验证，详见 [VNC 实现与验证说明](https://github.com/fython/wheelplay/blob/main/docs/VNC.md)。

## 诊断输入源与 ICCOA

**诊断输入源**提供 H.264、Surface 和帧缓冲测试画面，可在没有手机连接时检查显示链路。它们是测试画面，不是手机投影，也不能用来证明真实设备兼容性。

**ICCOA CarLink 暂不可用。** 当前缺少专有 SDK，入口只提示依赖缺失；不会启动协议连接，也不会因选择不可用入口而停止现有会话。VNC 并非 ICCOA 的替代实现。
