---
layout: ../../layouts/DocsLayout.astro
title: 使用指南
description: 安装并配对浏览器，选择 CarPlay、实验性 Android Auto 或 VNC 输入源。
---

WheelPlay 安装在 Android 宿主上，通过局域网向车机浏览器提供画面与控制。支持 CarPlay，并提供实验性的 Android Auto 与 VNC 输入源。车机无需安装客户端；使用 VNC 时，远端设备需要已有 VNC 服务端。

## 准备设备

- 一台运行 **Android 9 或更高版本**的服务设备。
- 根据输入源准备：支持 CarPlay 的 iPhone、支持 Android Auto 的手机，或运行 VNC 服务端的远端设备。诊断源无需连接手机。
- 支持现代网页与触控、能访问 Android 服务设备的车机浏览器。
- Android 与车机可以互相访问的局域网。

不同车机、系统版本和网络环境的兼容性不同，请在车辆停稳后完成首次配置和验证。

## 下载与安装

在 Android 设备上打开[下载最新开发构建](/download)，前往 GitHub 下载 `wheelplay-debug-apk`，解压后安装其中的 APK。

下载入口选择 `debug-build.yml` 工作流中 `main` 分支最新成功构建的产物，结果最多缓存 5 分钟。GitHub 下载可能需要登录；构建产物过期或最新构建没有对应产物时，可稍后再试或查看[构建列表](https://github.com/fython/wheelplay/actions/workflows/debug-build.yml)。

需要自行编译时，请阅读项目的[源码构建说明](https://github.com/fython/wheelplay/blob/main/README.zh-CN.md#构建)。

## 选择输入源

在应用底部「输入源」中选择连接方式。先查看[输入源与兼容性](/docs/sources/)，确认你的设备和所需功能受支持。

- **CarPlay**：USB 或无线连接 iPhone，需要导入有效认证资源。
- **Android Auto（实验性）**：USB、本机或 Head Unit Server TCP；本机／TCP 连接前需手动启动车机服务器。
- **VNC（实验性）**：填写已有服务端的数字 IP 地址、端口与可选密码；支持仅查看模式。
- **诊断源**：无需认证资源，可直接测试画面链路。ICCOA 当前不可用。

### CarPlay 认证资源

公开分发的开源构建默认不包含 CarPlay 认证资源。在 Android 应用中打开 **设置 → CarPlay 认证资源**，导入有效资源。有效资源用于 CarPlay 连接；Web 服务、浏览器配对和其他输入源不依赖这些资源。

支持选择 `identity.pk8` 和 `certificate.p7b`，或从有效的 DiPlay APK 导入。导入与校验在 Android 本机完成，资源保存在应用私有目录，不上传、不参与备份。

完整步骤见[认证资源导入说明](https://github.com/fython/wheelplay/blob/main/docs/AUTHENTICATION_IMPORT.md)。

## 打开车机浏览器

1. 在 Android 服务设备上安装并启动 WheelPlay；仅在使用 CarPlay 时配置认证资源。
2. 将 Android 与车机连接到同一局域网。
3. 在车机浏览器中打开 **WheelPlay 应用显示的实际访问地址**。

本官网提供下载和使用指引，连接设备时应使用应用中的局域网地址。

## 配对并启动显示

1. 首次使用时，在 WheelPlay 中扫描车机网页上的二维码并确认配对；也可以在网页输入配对码。
2. 选择网页选项，点击「启动显示」。
3. 在 App 中按所选输入源的[连接步骤](/docs/sources/)发起连接。CarPlay 配置期间保持 App 在前台，完成设备授权并在 iPhone 上允许 CarPlay。
4. 连接完成后，在车机浏览器中查看和控制画面。VNC 还支持鼠标、实体键盘；仅查看模式禁止远端输入。
5. 同一时间只运行一个输入源。切换后若浏览器断开，请重新连接并启动显示。

已记住的浏览器可在后续访问时恢复配对。清理浏览器数据、禁用存储、切换 HTTP / HTTPS 或更换访问地址后，需要重新配对。

在 **设置 → 浏览器设备 → 连接过的设备** 中可以查看、重命名或移除设备。移除后会立即断开该设备，并要求下次重新配对。同一时间仅允许一个浏览器控制会话。

## 音频与麦克风

**CarPlay** 默认由 Android 设备处理音频与麦克风，在 **设置 → 音频与麦克风** 中可以选择浏览器路由。**Android Auto** 仅使用宿主音频与麦克风，不支持浏览器路由；**VNC** 不提供音频。

浏览器麦克风要求受信任的本地 HTTPS 证书及对应浏览器能力，需从 HTTPS 入口重新配对并授权。不支持时可继续使用 Android 麦克风。配置步骤见[浏览器音频说明](https://github.com/fython/wheelplay/blob/main/docs/BROWSER_AUDIO.md)。

## 连接遇到问题

- 确认 Android 应用中的服务已启动；使用 CarPlay 时，确认认证资源已经通过校验。
- Android Auto 本机／TCP 连接需先启动车机服务器；VNC 需确认远端服务端的地址、端口及认证方式受支持。
- 确认车机与 Android 能通过局域网互相访问，并使用应用显示的地址。
- 浏览器限制私有网段访问时，可查看[实验性兼容模式](/docs/tesla/)。
- 更多兼容性、权限和下载问题见[常见问题](/docs/faq/)。

服务仅用于可信网络，请勿将其暴露到公网。VNC 应运行在可信局域网或 VPN 中，浏览器入口的 HTTPS 不会加密 VNC 链路。导航、音乐等 App 的在线内容仍可能需要互联网。
