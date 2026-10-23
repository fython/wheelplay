---
layout: ../../layouts/DocsLayout.astro
title: 常见问题
description: 关于输入源支持、设备兼容、下载、连接和音频的常见疑问。
---

第一次使用时，建议先按[使用指南](/docs/)完成安装和配对，再按所选输入源配置连接。

## 只有 iPhone 可以使用吗？

不是。CarPlay 连接 iPhone，实验性 Android Auto 可通过 USB、本机或 Head Unit Server TCP 连接，实验性 VNC 可连接已有的远程桌面服务端。诊断源无需手机连接即可测试画面；ICCOA 因缺少专有 SDK 暂不可用。详见[输入源与兼容性](/docs/sources/)。

## VNC 可以直接显示这台 Android 的桌面吗？

VNC 需要设备上已有可访问的 VNC 服务端，WheelPlay 只充当客户端，不会创建本机虚拟桌面，也不提供 Shizuku／ADB 桌面方案。当前通过 JPEG／WebP 显示画面，支持触控、鼠标、实体键盘和仅查看模式，不支持音频或软件键盘。

VNC 不支持 TLS／VeNCrypt，请使用可信局域网或 VPN；浏览器入口的 HTTPS 不会加密 VNC 链路。详见 [VNC 连接说明](/docs/sources/#vnc)。

## 我的车机可以使用吗？

车机浏览器需要支持现代网页与触控，并能通过局域网访问 Android 服务设备。不同品牌、系统版本与网络环境的兼容性不同，无法保证所有车型可用，请在车辆停稳后验证。

画面流畅度还取决于 输入源输出、网络质量、Android 性能与浏览器解码能力。

## 使用 WheelPlay 需要 Root 吗？

普通局域网连接和 Web 服务无需 Root。

针对限制私有网段访问的浏览器，应用提供实验性的 Tesla 兼容模式。免 Root VPN 方案需要 Android VPN 授权，Root 热点路由方案需要 Root。两种方案均需根据设备实测，详见[兼容模式说明](/docs/tesla/)。

## 为什么安装后需要导入认证资源？

公开构建默认不包含 CarPlay 认证资源。使用 CarPlay 时，请在 **设置 → CarPlay 认证资源** 中导入有效资源；Web 服务、浏览器配对、Android Auto、VNC 和诊断源均不依赖这些资源。

导入在 Android 本机完成，资源保存在应用私有目录，不上传、不参与备份。具体格式和操作步骤见[认证资源导入说明](https://github.com/fython/wheelplay/blob/main/docs/AUTHENTICATION_IMPORT.md)。

## 为什么下载时会跳转到 GitHub？

[下载入口](/download)会选择指定工作流 `main` 分支最新成功构建中的 `wheelplay-debug-apk`，然后跳转到 GitHub 的产物页面。官网不存储或转发安装包。

- GitHub 下载构建产物可能要求先登录账户。
- 构建产物有有效期；过期或已删除的产物无法下载。
- 最新成功构建没有对应产物时，入口不会自动选择更旧的构建。
- 下载结果最多缓存 5 分钟，新构建完成后可能需要稍等才会更新。
- GitHub 暂时不可用或限流时，请稍后重试，也可手动打开[构建列表](https://github.com/fython/wheelplay/actions/workflows/debug-build.yml)。

## 声音和麦克风从哪里走？

**CarPlay** 默认由 Android 处理音频和麦克风，可在 **设置 → 音频与麦克风** 中启用浏览器路由。

**Android Auto** 的音频只在宿主播放，麦克风需要选择 Android 麦克风并授予录音权限；尚不支持浏览器路由。**VNC** 不提供音频或麦克风。

浏览器麦克风需要可信的本地 HTTPS 证书及相应浏览器能力；不支持时可继续使用 Android 麦克风。启用 HTTPS 后需要从 HTTPS 入口重新配对并授权，详见[音频配置说明](https://github.com/fython/wheelplay/blob/main/docs/BROWSER_AUDIO.md)。

## 需要服务器或公网连接吗？

浏览器显示由 Android 在局域网内提供，无需云端中转。VNC 需要远端设备已有 VNC 服务端；Android Auto 本机／TCP 模式需启动车机服务器。导航、音乐等在线内容仍可能需要互联网。

服务仅用于可信局域网，请勿暴露到公网。本官网提供介绍、下载与使用指引，设备连接应使用应用显示的局域网地址。

## 为什么浏览器需要重新配对？

设备记忆与浏览器及访问地址绑定。清理浏览器数据、禁用本地存储、切换 HTTP / HTTPS 或改变主机、端口后，都需要重新配对。

在 Android 应用中移除已记住的浏览器，也会使该浏览器的旧凭据失效。同一时间仅允许一个浏览器控制会话。

## 它是开源项目吗？

是。WheelPlay 基于 DiPlay / xcertplay 开发，采用 GPL-3.0 许可证并保留上游及第三方声明；第三方组件与素材遵循各自许可证。

可以阅读[源码](https://github.com/fython/wheelplay)、查看[许可证](https://github.com/fython/wheelplay/blob/main/LICENSE)，或通过 [GitHub Issues](https://github.com/fython/wheelplay/issues)反馈问题。
