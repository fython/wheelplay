---
layout: ../../layouts/DocsLayout.astro
title: 使用指南
description: 从安装到配对，将 iPhone 的 CarPlay 连接到车机浏览器。
---

WheelPlay 安装在 Android 设备上，通过局域网向车机浏览器提供 CarPlay 画面与触控。车机无需安装客户端，也无需部署额外服务器。

## 准备设备

- 一台运行 **Android 9 或更高版本**的服务设备。
- 一部支持 CarPlay 的 iPhone。
- 支持现代网页与触控、能访问 Android 服务设备的车机浏览器。
- Android 与车机可以互相访问的局域网。

不同车机、系统版本和网络环境的兼容性不同，请在车辆停稳后完成首次配置和验证。

## 下载与安装

在 Android 设备上打开[下载最新开发构建](/download)，前往 GitHub 下载 `wheelplay-debug-apk`，解压后安装其中的 APK。

下载入口选择 `debug-build.yml` 工作流中 `main` 分支最新成功构建的产物，结果最多缓存 5 分钟。GitHub 下载可能需要登录；构建产物过期或最新构建没有对应产物时，可稍后再试或查看[构建列表](https://github.com/fython/wheelplay/actions/workflows/debug-build.yml)。

需要自行编译时，请阅读项目的[源码构建说明](https://github.com/fython/wheelplay/blob/main/README.zh-CN.md#构建)。

## 导入认证资源

公开分发的开源构建默认不包含 CarPlay 认证资源。在 Android 应用中打开 **设置 → CarPlay 认证资源**，导入有效资源。校验通过后，应用才能启动服务。

支持选择 `identity.pk8` 和 `certificate.p7b`，或从有效的 DiPlay APK 导入。导入与校验在 Android 本机完成，资源保存在应用私有目录，不上传、不参与备份。

完整步骤见[认证资源导入说明](https://github.com/fython/wheelplay/blob/main/docs/AUTHENTICATION_IMPORT.md)。

## 打开车机浏览器

1. 在 Android 服务设备上安装并启动 WheelPlay，完成认证资源配置。
2. 将 Android 与车机连接到同一局域网。
3. 在车机浏览器中打开 **WheelPlay 应用显示的实际访问地址**。

本官网提供下载和使用指引，连接设备时应使用应用中的局域网地址。

## 配对并连接 iPhone

1. 首次使用时，在 WheelPlay 中扫描车机网页上的二维码并确认配对；也可以在网页输入配对码。
2. 选择网页选项，点击「启动显示」。
3. 保持 Android 应用在前台，按应用提示通过 USB 或无线方式连接 iPhone。
4. 完成设备选择或授权，并在 iPhone 上允许使用 CarPlay。
5. 连接完成后，在车机浏览器中查看和操作 CarPlay。

已记住的浏览器可在后续访问时恢复配对。清理浏览器数据、禁用存储、切换 HTTP / HTTPS 或更换访问地址后，需要重新配对。

在 **设置 → 浏览器设备 → 连接过的设备** 中可以查看、重命名或移除设备。移除后会立即断开该设备，并要求下次重新配对。同一时间仅允许一个浏览器控制会话。

## 音频与麦克风

默认由 Android 设备处理音频与麦克风。在 **设置 → 音频与麦克风** 中可以选择浏览器路由。

浏览器麦克风要求受信任的本地 HTTPS 证书及对应浏览器能力，需从 HTTPS 入口重新配对并授权。不支持时可继续使用 Android 麦克风。配置步骤见[浏览器音频说明](https://github.com/fython/wheelplay/blob/main/docs/BROWSER_AUDIO.md)。

## 连接遇到问题

- 确认认证资源已经通过校验，Android 应用中的服务已启动。
- 确认车机与 Android 能通过局域网互相访问，并使用应用显示的地址。
- 浏览器限制私有网段访问时，可查看[实验性兼容模式](https://github.com/fython/wheelplay/blob/main/docs/TESLA_HTTP.md)。
- 更多兼容性、权限和下载问题见[常见问题](/docs/faq/)。

服务仅用于可信局域网，请勿将其暴露到公网。导航、音乐等 App 的在线内容仍可能需要互联网。
