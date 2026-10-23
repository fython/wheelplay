# WheelPlay

[EN](README.md) | **CN**

WheelPlay 是一款 Android 局域网 CarPlay 服务端应用，可将 iPhone 的 CarPlay 画面传输到车机浏览器，并支持通过车机触屏操作。

应用安装在 Android 设备上，车机通过浏览器访问即可使用，无需安装客户端或部署额外服务器。

## 项目官网

Astro 官网位于 [`website/`](website/README.md)，与 Android 内置的局域网浏览器客户端独立。推荐 Node.js 24 LTS，在仓库根目录先运行 `npm ci --prefix website` 安装官网依赖，再运行 `npm run site:dev` 开发预览，或运行 `npm run site:build` 生成 `website/dist/`。Cloudflare Pages 和 Workers 部署配置见[官网说明](website/README.md)。

## 功能

- **浏览器显示与触控**：支持 CarPlay 画面串流、触摸操作和全屏显示。
- **可选串流技术**：设置中可选 WebRTC 视频直通（H.264 / HEVC）或图片兼容模式，并调整 JPEG / WebP 格式和 1–100 的图片质量。图片串流保留 JPEG 兼容回退，最多两帧并行传输，音频与触控保持原有路径。详见[图片串流与多缓冲](docs/IMAGE_STREAM.md)。
- **可选浏览器音频**：配对后可改由浏览器播放 CarPlay 音频；浏览器麦克风可通过可信 HTTPS 页面接入 CarPlay 语音输入，默认仍由 Android 处理。
- **便捷配对**：首次扫描车机网页二维码或输入配对码，之后自动恢复已记住的浏览器配对；启动前仍可选择网页选项。
- **iPhone 连接管理**：支持 USB 与无线连接，查看连接状态并管理会话。
- **局域网访问**：自动识别可用网络地址，便于车机访问。
- **后台服务**：支持后台运行及自动连接。
- **显示与音频设置**：可调整画面尺寸、分辨率、帧率和音频缓冲。
- **CarPlay 使用偏好**：可选择跟随 Android／始终日间／始终夜间、Android／浏览器音频输出，以及 Android／浏览器／禁用麦克风。自定义四边 0–40% 安全区域，并预览布局；修改麦克风或安全区域会重新连接。浏览器离线策略可选保持连接或等待 1／5 分钟后断开 iPhone，重连浏览器会取消计时。
- **自定义车厂入口**：在「设置 → 车厂与定位 → OEM 车厂名称与图标」修改 CarPlay「返回车机」入口的名称，选择图片并裁剪为正方形，或恢复默认 NIO 名称与图标；下次连接 iPhone 生效。图片保存在应用私有目录，无需保留原文件。
- **定位来源**：「设置 → 车厂与定位 → 定位来源」默认选择 iPhone 自身定位，未启用浏览器 GPS 时，WheelPlay 不上报外部定位。可选择 Android 设备定位，在下次连接并取得精确定位权限后，按 iPhone 请求提供设备位置。具体定位融合由 iOS 和导航 App 决定；旧版 Android 的无线连接仍可能需要定位权限。
- **可选浏览器 GPS**：在受信任的 HTTPS 连接页勾选「将浏览器 GPS 定位传给 CarPlay」，检查并授予浏览器定位权限后，启动显示即可转发。每次打开页面默认关闭，不保存此选项。浏览器定位优先于 Android 定位；权限撤销、关闭选项或断开浏览器时立即停止采集并清除缓存，超过 30 秒的定位不再转发。浏览器暂时无定位时回退到已启用的 Android 定位，否则由 iPhone 自身定位。为支持连接期间启用，CarPlay 会话会声明外部定位通道，但默认不采集或上报位置。
- **诊断导出**：支持将诊断报告保存到本地，便于排查问题。

设置首页提供九个分类入口：iPhone 连接、画面与串流、音频与麦克风、车厂与定位、浏览器设备、Web 服务、Tesla 兼容、CarPlay 认证资源、帮助与关于。点击进入二级页，顶部返回按钮或系统返回键回到分类首页；切换底部标签和旋转屏幕后保留当前分类及滚动位置。

## 使用方式

1. 在 Android 服务设备上安装并启动 WheelPlay。
2. 将服务设备与车机连接到同一局域网，在车机浏览器中打开应用显示的访问地址。
3. 首次使用 WheelPlay 扫描网页上的二维码并确认配对，再选择网页选项并点击「启动显示」；也可在网页输入配对码并点击「配对并启动显示」。之后再次打开网页会自动恢复配对，选择选项后点击「启动显示」即可。勾选「启动后隐藏顶栏并全屏」可在启动时进入全屏、连接后隐藏顶栏；退出全屏会恢复顶栏，不支持全屏的浏览器仅隐藏顶栏。
4. 保持 Android 上的 WheelPlay 在前台，在已配对浏览器的等待画面页点击「连接 iPhone」，即可使用 App 当前的 USB / 无线设置发起连接；也可直接在应用中连接。按提示在 Android 上完成设备选择或授权，并在 iPhone 上允许使用 CarPlay。
5. 连接完成后，在车机浏览器中查看和操作 CarPlay。

在 App「设置 → 浏览器设备 → 连接过的设备」可查看浏览器名称、首次配对时间、最近连接时间与地址，并重命名、移除单台设备或移除全部。移除会立即断开该设备并使已有会话凭据失效，下次需要重新配对；主动断开网页或重启服务会保留设备记忆。最多保存 32 个浏览器，达到上限时需移除旧设备才能记住新设备。

设备记忆保存于当前浏览器及访问地址（协议、主机、端口）的本地存储；清理浏览器数据、禁用存储、切换 HTTP / HTTPS 或访问地址后需要重新配对。Android 仅持久保存随机设备密钥的哈希，凭据不包含六位配对码，也不进入应用备份。

追求 60 fps 时，在「设置 → 画面与串流」选择 WebRTC 视频直通，并将协商帧率设为 60；应用设置并重新连接后生效。实际帧率取决于 iPhone 输出、网络和浏览器解码能力，静态画面可能降低帧率。WebRTC 使用局域网 UDP，不依赖外部 STUN/TURN；UDP 不通或源视频编码不兼容时自动回退 JPEG。允许 iPhone 使用 HEVC 后，可向支持 HEVC / WebRTC 的浏览器直通 H.265；较旧浏览器建议保持 H.264。

「WebRTC 卡顿容忍时间」可选 4 秒、10 秒（默认）或 30 秒，下次浏览器连接生效。短暂断流时保留最后一帧并等待恢复，持续异常超过阈值才回退 JPEG；不支持的编码或彻底失败的连接仍立即回退。该设置不会扩大视频缓冲，控制与音频保持各自的连接规则。更新 App 后请刷新网页。详见 [稳定性分析与排查](docs/WEBRTC_RECOVERY.md)。

网页连接界面的「视频显示方式」提供三张单选卡片，默认「原生 Video」。两个 Canvas 模式均为实验性：

- **原生 Video**：由视频元素直接显示 WebRTC 画面。
- **兼容 Canvas**：保留离屏 `<video>` 解码并使用 `drawImage()` 绘制，优先视频帧回调，旧浏览器或离屏回调停顿时使用动画帧补偿。
- **直接 Canvas**：使用主线程 `MediaStreamTrackProcessor` 读取 WebRTC `VideoFrame` 并绘制，全程不创建 `<video>`，也不使用 `VideoDecoder`。仅在 Worker 中提供 Processor 的浏览器不在首版支持范围；不支持或运行失败时直接使用 JPEG / WebP，不切换到旧的视频元素模式。

浏览器保存所选方式，刷新和自动重连后保留；禁用存储时仅当前页面有效。原有 Canvas 偏好自动迁移为「兼容 Canvas」。需要断开后重新选择，自动重连不会改变本次启动的方式。两种 Canvas 都在首帧绘制成功后才停止图像串流；尺寸与触控按实际画面映射。Canvas 可能增加 GPU / CPU 消耗，不保证达到 60 fps。直接 Canvas 状态栏的 fps 是成功绘制次数，其他模式保留原有视频呈现 / 解码统计，均不等于屏幕实际刷新率。`window.wheelplayStats.presentation` 可查看所选 / 实际方式、绘制统计和回退原因。

Canvas 模式需要蔚来等车机实测：若遮罩只覆盖原生视频元素，改变显示路径可能有效；若系统停止解码或在整个页面上叠加遮罩，则无法保证有效。请在车辆停稳后验证，驾驶员行驶中只应使用必要的导航界面。

在 App「设置 → 音频与麦克风」选择「音频输出」和「麦克风来源」。音频输出即时切换；麦克风来源修改后重新连接，禁用时停止采集与上传并取消输入能力声明。页面显示当前路由，浏览器未就绪时回退 Android；禁用麦克风时始终禁止回退采集。浏览器麦克风需要先配置该设备的本地 HTTPS 证书，再从 HTTPS 入口重新配对并授权；步骤见 [浏览器音频说明](docs/BROWSER_AUDIO.md)。若车机浏览器不支持可信 HTTPS 或 AudioWorklet，继续使用 Android 麦克风。

## 使用要求与限制

- 服务设备需运行 Android 9 或更高版本；车机浏览器需支持现代网页与触摸交互功能。
- 麦克风上行使用 Android 系统 Opus 编码器；系统不支持时，仅向 iPhone 声明 PCM 输入格式，由连接协商选择。项目不内置软件 Opus 编码库；无 Opus 设备上的 Siri 与通话兼容性仍需实机验证。
- 服务设备与车机必须能够通过局域网互相访问。无线连接兼容性取决于设备的网络能力。
- 音频与麦克风默认由 Android 服务设备处理；浏览器路由需手动启用。麦克风还要求车机浏览器允许安装并信任本地证书。
- 同一时间仅允许一个浏览器控制会话。
- 画面流畅度与连接稳定性受设备性能、网络质量及系统后台管理影响。
- 仅适用于可信局域网；HTTP 配对、WebSocket 控制与 JPEG 通道未加密，WebRTC 视频使用 DTLS-SRTP。浏览器麦克风使用本地 HTTPS/WSS，不应通过证书警告强行进入页面；请勿将服务暴露到公网。

## Web 监听配置

「设置 → Web 服务」集中配置 HTTP 端口（默认 `8080`）、HTTP / HTTPS 共用的自备 Web 域名和可选 HTTPS，无需 Root。实际监听端口范围为 `1024–65535`；打开 HTTPS 后显示 Web HTTPS 端口（默认 `8443`）及页面内的证书类型选择器：默认本地证书、PEM、PKCS#12。系统文件选择器支持 PEM 证书链＋私钥（含加密私钥），或 PKCS#12（`.p12` / `.pfx`，可选择私钥条目）。导入前验证证书与私钥，保存到 App 私有且不备份的存储；失败保留当前证书，端口绑定失败保留原监听器。关闭 HTTPS 仅停止 TLS 监听，保留配置与证书。详见 [Web 监听与证书说明](docs/BROWSER_AUDIO.md)。

## Tesla 兼容模式（实验）

此模式主要帮助屏蔽私有网段访问的车机浏览器，通过兼容访问地址绕过地址限制，访问手机上的本地服务。功能名称沿用「Tesla 兼容」，也适用于有同类限制的其他品牌车机。

在「设置 → Tesla 兼容（实验）→ 连接方案」选择 **免 Root VPN（实验）** 或 **Root 热点路由**。两种方案都默认关闭，切换方案会关闭模式，需重新主动启用；已有 Root 设置保留。

- **免 Root VPN**：无需 Root 或 Shizuku，需要 Android VPN 授权。开启手机系统热点，让车机连接后尝试 `http://6.6.6.6:8080`；直接使用 Web 监听端口，不映射 `80` / `443`。与有线 CarPlay 共用一个 VPN 服务，保留 IPv4 地址接口，再让 NCM IPv6 接口最后成为活动 VPN；断开 iPhone 后恢复兼容接口。连接有线 CarPlay 时启用或修改 IP，会等待断开后生效。此方案利用公开 API 之外未保证的旧 TUN 保留行为，可能不兼容部分设备，系统更新后也可能失效。「地址已配置」仅表示本机检测到该地址，热点和 Tesla 访问仍需实测；WebRTC 的 ICE / UDP 通道需单独验证。
- **Root 热点路由**：内置适配 [VPN Hotspot](https://github.com/Mygod/VPNHotspot)，需要 `su`、`ip`、`iptables`。手动请求 Root 后启用，默认入口 `http://100.96.0.1/`；映射端口范围 `1–1023`，默认 `80` / `443`，转发到 Web 的高位监听端口。仅已启用的 Root 方案会在启动时后台检查 Root；免 Root 模式不请求 Root。Root 方案不申请 VPN 授权，有线 CarPlay 的 IPv6 VPN 独立保留。

两种方案都在关闭模式或停止 Web 服务时清理资源；普通局域网 Web 监听可独立使用。可选 HTTPS 仍要求可信证书。详见 [使用与验证说明](docs/TESLA_HTTP.md)。

## 构建

Android 设置界面使用 Jetpack Compose。分类页面与共用控件位于 `common/src/main/java/com/shilapi/xcertplay/settings/`；`SettingsViewModel` 管理偏好与可恢复的导航状态，`DiPlayActivity` 协调系统权限、文件选择和服务操作。`SettingsScreen.kt` 提供手机及大字体预览，设置交互测试通过 Robolectric 和 Compose 语义节点执行。

界面文案使用 Android 字符串资源：`values` 为默认英文，`values-zh-rCN` 为简体中文，`values-zh-rTW` 为繁体中文，由系统自动选择，应用不提供语言切换入口。新增文案请使用 `stringResource` / `getString` 和带序号的格式占位符。`BrowserStrings` 为浏览器显示端提供同一套系统选中的资源；协议标识和诊断日志保持稳定。`npm test` 会检查三套文案的完整性与占位符一致性。

使用 Android NDK 28.2.13676358 和 CMake 3.22.1。首次构建会从 GitHub 获取固定版本的 libdatachannel 及 Mbed TLS；后续复用本地 CMake 缓存。运行 `./gradlew :mobile:assembleDebug`。

源码构建默认不包含 CarPlay 认证资源。未配置有效资源时不会启动 Web 服务或 CarPlay；导入校验通过后自动启动 Web 服务。包内自带有效资源的安装包可直接使用，仍允许用户导入替换，且用户资源优先。在 **设置 → CarPlay 认证资源** 中手动选择 `identity.pk8` 和 `certificate.p7b`，或通过下载入口获取 DiPlay 官网 APK，再选择“从 DiPlay APK 导入”；也可在文件管理器中将该 APK 分享给 WheelPlay，无需安装 DiPlay。导入在本机完成，校验私钥与证书匹配后才替换原有资源；资源保存在应用私有目录，不上传、不参与备份。详见 [认证资源导入说明](docs/AUTHENTICATION_IMPORT.md)。

开发者仍可通过 `WHEELPLAY_AUTH_ASSETS_DIR`（兼容 `DIPLAY_AUTH_ASSETS_DIR`）指定本地资产目录，或放置在 `.local/auth-assets/`；目录下必须有 `offline-mfi/identity.pk8` 和 `offline-mfi/certificate.p7b`。这些文件不纳入 Git。配置后运行 `./gradlew :mobile:assembleProvisionedDebug`，构建并校验 APK 内的两项资产与本地配置一致。公开分发的开源安装包请使用不配置认证资产的普通构建，由用户安装后导入。

## 来源与许可

本项目基于 DiPlay / xcertplay 开发，保留上游许可证与第三方声明。详情参见 [LICENSE](LICENSE) 和 [第三方声明](docs/upstream/THIRD_PARTY_NOTICES.md)。第三方素材与组件遵循各自许可证。

WebRTC 使用 [libdatachannel 0.24.1](https://github.com/paullouisageneau/libdatachannel/tree/v0.24.1) 和 [Mbed TLS 3.6.5](https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.5)，版本固定在 CMake 配置中；[原生依赖许可](common/src/main/assets/webrtc-licenses.txt)随 APK 分发。

串流调试时，可在浏览器控制台读取 `window.wheelplayStats`：包含源输入与发送速率（2 秒窗口）、解密/排队/发送耗时（最近 240 次的均值和 P95）、丢弃计数，以及浏览器接收/解码/呈现速率、丢包和 RTT。速率与耗时分段统计，不代表端到端延迟；未支持的浏览器指标为 `null`。直通排队预算为 50 ms，超时丢弃参考链并等待关键帧。原生媒体发送反馈的固定版本补丁见 `common/src/main/cpp/media_send_feedback.cmake`；本地发送成功仅表示传输层接受，接收端反馈用于判断后续进度。
