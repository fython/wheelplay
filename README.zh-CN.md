# WheelPlay

[English](README.md) · **简体中文**

在 Android 设备上运行 CarPlay，通过车机浏览器查看和操控。WheelPlay 在局域网内提供画面、触控与配对服务，车机无需安装额外 App，也无需部署独立服务器。

[官网](https://wheelplay.gwo.app/) · [下载开发版](https://wheelplay.gwo.app/download) · [使用指南](https://wheelplay.gwo.app/docs/) · [常见问题](https://wheelplay.gwo.app/docs/faq/)

## 支持范围

| 输入源 | 当前状态 |
| --- | --- |
| **CarPlay** | 支持 iPhone USB 与无线连接、浏览器显示与触控；需要有效的 CarPlay 认证资源。 |
| **Android Auto（实验性）** | 已实现 USB AOA、显式 Head Unit Server TCP 连接及本机连接引导；设备兼容性仍需真机验证。 |
| **VNC（实验性）** | 通过 RFB 3.3/3.7/3.8 连接远程桌面，支持 JPEG/WebP 画面、触控、鼠标、实体键盘及仅查看模式。实际服务端与设备兼容性仍需验证。 |
| **Android 虚拟桌面（实验性）** | Shizuku UserService、本机应用选择、固定尺寸 JPEG/WebP、多指触控与返回。需要 Android 10+/Shizuku 13+，尚未完成真机兼容性验证，见[使用与验收指南](docs/ANDROID_DESKTOP.md)。 |
| **诊断输入源** | 提供 H.264、Surface 和帧缓冲测试源，无需连接手机即可检查画面链路。 |
| **插件 APK** | 安装并启用独立的 Surface 画面／触控输入源；提供独立 SDK 和示例 APK，见[插件指南](docs/PROJECTION_PLUGINS.md)。 |
| **ICCOA CarLink** | 计划通过独立插件 APK 提供；本版本不包含专有 SDK 或 CarLink 插件。 |

同一时间只运行一个投影输入源，并允许一个浏览器控制会话。Android Auto 的音频播放和麦克风输入均使用 Android 宿主，尚不支持浏览器音频／麦克风转发或自动无线发现。连接方式与限制见 [Android Auto 指南](docs/ANDROID_AUTO.md)。

## 主要功能

- **画面与触控**：WebRTC H.264／受支持的 HEVC 直通、JPEG／WebP 兼容串流、全屏，以及可调分辨率、帧率和图像质量。默认使用原生 Video 渲染，另提供两种实验性 Canvas 模式。
- **浏览器配对**：扫描网页二维码或输入配对码，后续可恢复已记住的配对；支持查看、重命名和撤销设备。
- **CarPlay 音频与控制**：默认使用 Android 音频和麦克风，可选浏览器路由；在浏览器及手机 App 支持时提供 Media Session 媒体信息和播放控制。
- **CarPlay 偏好**：日夜模式、安全边距、自定义返回车机入口的名称与图标、浏览器离线策略，以及可选的 Android 或浏览器 GPS。默认使用 iPhone 自身定位。
- **网络兼容**：可配置 HTTP／HTTPS 服务和导入证书；可选实验性无 Root VPN 或 Root 热点路由，适配限制私有 IP 访问的车机浏览器。
- **语言与诊断**：App 和局域网页面随 Android 系统语言提供英文、简体中文及繁体中文，可在本地导出诊断报告；独立官网支持中英文和深浅主题。

## 快速开始

### 1. 安装 WheelPlay

准备一台 **Android 9 或更高版本**的宿主设备、一部受支持的手机，以及能够通过局域网访问宿主的现代车机浏览器。普通局域网使用不需要 Root。

打开[下载最新开发版](https://wheelplay.gwo.app/download)，下载 `wheelplay-debug-apk` 并解压安装其中的 APK。该入口跳转到 [Android Debug Build](https://github.com/fython/wheelplay/actions/workflows/debug-build.yml) 工作流中 `main` 分支最新成功构建的产物，结果最多缓存五分钟。GitHub 下载可能需要登录，构建产物也可能过期。这些安装包是开发构建，并非稳定发行版。

### 2. 配置输入源

使用 **CarPlay** 时，打开 **设置 → CarPlay 认证资源**。公开 CI 构建不包含这些资源，请导入匹配的 `identity.pk8` 和 `certificate.p7b`，或按应用引导从兼容的 DiPlay APK 导入，无需安装 DiPlay。导入和校验均在本机完成，资源存放于应用私有目录，不上传、不参与备份。详细步骤见[认证资源导入](docs/AUTHENTICATION_IMPORT.md)。

缺少 CarPlay 认证资源不影响 Web 服务、浏览器配对和诊断输入源。使用 **Android Auto** 时，进入 **输入源 → Android Auto**，按 [USB／本机／TCP 说明](docs/ANDROID_AUTO.md) 配置；本机模式需要先手动启动 Android Auto 的车机服务器。

使用 **VNC** 时，进入 **输入源 → VNC**，填写已有 VNC 服务端的 IP 地址、端口及可选的临时密码。请使用可信局域网或 VPN：VNC 传输不加密，暂不支持 TLS/VeNCrypt。配置步骤与限制见 [VNC 指南](docs/VNC.md)。

### 3. 配对车机浏览器

1. 启动 WheelPlay，让 Android 宿主与车机处于能够互相访问的网络中。
2. 在车机浏览器打开 **App 显示的局域网地址**。官网仅提供下载和使用文档。
3. 用 WheelPlay 扫描网页二维码并确认配对，或在网页输入配对码；选择网页选项并启动显示。
4. 使用 CarPlay 时，配置期间保持 WheelPlay 在前台，通过 USB 或无线方式连接 iPhone，完成 Android 权限和 iPhone 的 CarPlay 授权。也可从已配对浏览器的等待页面发起连接。
5. 连接成功后，通过车机触摸屏操作投影界面。

再次访问可恢复配对，但仍需启动显示。清理浏览器存储、更换访问地址或切换 HTTP／HTTPS 后需要重新配对。已记住的设备可在 **设置 → 浏览器设备 → 连接过的设备** 中移除。

## 兼容性说明

- 浏览器麦克风和 GPS 需要受信任的 HTTPS 及浏览器授权。CarPlay 默认使用 Android 音频／麦克风；浏览器 GPS 每次打开页面默认关闭。配置见[音频与 HTTPS 指南](docs/BROWSER_AUDIO.md)。
- 实际帧率和稳定性取决于手机、宿主、浏览器、网络及 Android 后台限制。WebRTC 使用局域网 UDP，不依赖外部 STUN／TURN，必要时回退到图像串流。Canvas 模式不保证提高帧率或绕过车机浏览器限制。
- 限制私有 IP 访问的浏览器可尝试 [Tesla 兼容模式](docs/TESLA_HTTP.md)，但不能据此保证所有车机和 Android 设备均可用。
- 仅在可信局域网使用。HTTP 流量未加密，可选 HTTPS 需要受信任的证书；请勿将服务暴露到公网。首次配置和兼容性测试应在车辆停稳后进行。

## 构建

### Android 应用

当前使用以下工具链，仓库已提交 Gradle wrapper 和 daemon JVM 配置。

| 工具 | 版本 |
| --- | --- |
| JDK | 25 |
| Gradle／Android Gradle Plugin | 9.3.1／9.1.0 |
| Android SDK／Build Tools | API 36／36.0.0 |
| Android NDK／CMake | 28.2.13676358／3.22.1 |
| 浏览器测试与开发工具所需 Node.js | 22 或更高版本 |

通过 `ANDROID_HOME` 或未纳入版本控制的 `local.properties` 中的 `sdk.dir` 配置 Android SDK，安装上述工具链后，在仓库根目录执行：

```sh
./gradlew :mobile:assembleDebug
```

产物为 `mobile/build/outputs/apk/debug/mobile-debug.apk`，应用 ID 为 `moe.feng.wheelplay.debug`。首次原生构建会从 GitHub 获取固定版本的 libdatachannel 和 Mbed TLS。默认构建不包含 CarPlay 认证资源，安装后可自行导入。

如需在私有构建中预置资源，将 `offline-mfi/identity.pk8` 和 `offline-mfi/certificate.p7b` 放到 `.local/auth-assets/` 或 `WHEELPLAY_AUTH_ASSETS_DIR` 指定的目录下，再运行 `./gradlew :mobile:assembleProvisionedDebug` 检查打包结果。不要将这些资源提交到 Git 或公开 APK。

运行 CI 使用的 Android 检查：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :common:assembleDebugAndroidTest :mobile:lintDebug :mobile:assembleDebug
```

该命令会构建仪器测试 APK；CI 另行在 Android 9 和 Android 16 模拟器上运行浏览器页面测试。浏览器侧检查和桌面演示使用：

```sh
npm ci
npm test
npm run check
npm run demo
```

演示地址为 `http://localhost:8081`，配对码为 `123456`，使用合成测试画面。这只是开发工具，真实手机连接和局域网服务由 Android App 提供。

### 产品官网

[`website/`](website/README.md) 是依赖独立的 Astro 官网。推荐使用 Node.js 24 LTS，不需要 Android SDK。

```sh
npm ci --prefix website
npm run site:dev
```

检查与构建：

```sh
npm run site:check
npm run site:build
npm run site:test
```

静态产物输出到 `website/dist/`，中英文文档正文使用 Markdown 维护。Cloudflare Worker 或 Pages Functions 通过共用的缓存解析器处理 `/download`，跳转到 GitHub 构建产物；Astro 开发服务器不运行该接口。部署配置见[官网维护指南](website/README.md)。

## 仓库结构与文档

| 路径 | 内容 |
| --- | --- |
| [`mobile/`](mobile/) | Android 应用入口与打包配置 |
| [`common/`](common/) | 宿主界面、Compose 设置、局域网服务、浏览器资源、投影源及原生 WebRTC 桥接 |
| [`shared/`](shared/) | CarPlay／AirPlay、Android Auto 与 VNC 协议组件、共享资源 |
| [`website/`](website/) | Astro 官网与 Cloudflare 下载接口 |
| [`scripts/`](scripts/)、[`test/`](test/) | 桌面演示及浏览器／仓库检查 |
| [`docs/`](docs/README.md) | 实现说明、验证记录及保留的上游文档 |

进一步排查可阅读[投影输入源](docs/PROJECTION_IMPLEMENTATION.md)、[WebRTC 恢复](docs/WEBRTC_RECOVERY.md)、[图像串流](docs/IMAGE_STREAM.md)、[渲染模式](docs/MEDIA_PRESENTATION.md)和[媒体控制](docs/MEDIA_SESSION.md)。浏览器控制台中的 `window.wheelplayStats` 提供各阶段诊断数据，但不代表端到端延迟。

## 来源与许可

WheelPlay 基于 **DiPlay／xcertplay**，保留上游许可与声明。详见 [LICENSE](LICENSE)、[上游来源说明](docs/UPSTREAM.md)和[第三方声明](docs/upstream/THIRD_PARTY_NOTICES.md)；第三方组件及资源仍受各自许可条款约束。原生 WebRTC 依赖的[独立许可声明](common/src/main/assets/webrtc-licenses.txt)也随 APK 分发。
