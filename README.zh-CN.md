# WheelPlay

[EN](README.md) | **CN**

WheelPlay 是一款 Android 局域网 CarPlay 服务端应用，可将 iPhone 的 CarPlay 画面传输到车机浏览器，并支持通过车机触屏操作。

应用安装在 Android 设备上，车机通过浏览器访问即可使用，无需安装客户端或部署额外服务器。

## 功能

- **浏览器显示与触控**：支持 CarPlay 画面串流、触摸操作和全屏显示。
- **可选串流技术**：设置中可选 WebRTC 视频直通（H.264 / HEVC）或 JPEG 兼容模式。直通失败自动回退 JPEG，音频与触控保持原有路径。
- **可选浏览器音频**：配对后可改由浏览器播放 CarPlay 音频；浏览器麦克风可通过可信 HTTPS 页面接入 CarPlay 语音输入，默认仍由 Android 处理。
- **便捷配对**：支持扫描车机网页二维码或输入配对码连接。
- **iPhone 连接管理**：支持 USB 与无线连接，查看连接状态并管理会话。
- **局域网访问**：自动识别可用网络地址，便于车机访问。
- **后台服务**：支持后台运行及自动连接。
- **显示与音频设置**：可调整画面尺寸、分辨率、帧率和音频缓冲。
- **诊断导出**：支持将诊断报告保存到本地，便于排查问题。

## 使用方式

1. 在 Android 服务设备上安装并启动 WheelPlay。
2. 将服务设备与车机连接到同一局域网，在车机浏览器中打开应用显示的访问地址。
3. 使用 WheelPlay 扫描网页上的二维码并确认连接，或在网页输入配对码。
4. 在应用中连接 iPhone，并在 iPhone 上允许使用 CarPlay。
5. 连接完成后，在车机浏览器中查看和操作 CarPlay。

追求 60 fps 时，在「设置 → 画面与音频」选择 WebRTC 视频直通，并将协商帧率设为 60；应用设置并重新连接后生效。实际帧率取决于 iPhone 输出、网络和浏览器解码能力，静态画面可能降低帧率。WebRTC 使用局域网 UDP，不依赖外部 STUN/TURN；UDP 不通或源视频编码不兼容时自动回退 JPEG。允许 iPhone 使用 HEVC 后，可向支持 HEVC / WebRTC 的浏览器直通 H.265；较旧浏览器建议保持 H.264。

车机浏览器的视频元素显示异常时，可在网页连接界面勾选「使用 Canvas 显示视频（实验性）」，再连接设备。默认关闭；选择保存在当前浏览器，自动重连及刷新后保留（浏览器禁用存储时仅当前页面有效）。WebRTC 视频仍由 `<video>` 解码，元素放在屏幕外，使用 `drawImage()` 直接绘制到可见 Canvas；优先使用视频帧回调，旧浏览器或离屏回调停顿时使用动画帧刷新。触控区域按画面比例映射，绘制失败自动回退 JPEG。该模式可能增加 GPU / CPU 消耗；状态栏中的视频帧率仍为浏览器的视频呈现统计，不能代表 Canvas 的实际绘制帧率。

Canvas 模式需要蔚来等车机实测：若遮罩只覆盖原生视频元素，改变显示路径可能有效；若系统停止解码或在整个页面上叠加遮罩，则无法保证有效。请在车辆停稳后验证，驾驶员行驶中只应使用必要的导航界面。

浏览器音频和麦克风在 App 的「设置 → 画面与音频」分别启用，可在连接过程中切换。浏览器麦克风需要先配置该设备的本地 HTTPS 证书，再从 HTTPS 入口重新配对并授权麦克风；步骤见 [浏览器音频说明](docs/BROWSER_AUDIO.md)。若车机浏览器不支持可信 HTTPS 或 AudioWorklet，继续使用 Android 麦克风。

## 使用要求与限制

- 服务设备需运行 Android 9 或更高版本；车机浏览器需支持现代网页与触摸交互功能。
- 服务设备与车机必须能够通过局域网互相访问。无线连接兼容性取决于设备的网络能力。
- 音频与麦克风默认由 Android 服务设备处理；浏览器路由需手动启用。麦克风还要求车机浏览器允许安装并信任本地证书。
- 同一时间仅允许一个浏览器控制会话。
- 画面流畅度与连接稳定性受设备性能、网络质量及系统后台管理影响。
- 仅适用于可信局域网；HTTP 配对、WebSocket 控制与 JPEG 通道未加密，WebRTC 视频使用 DTLS-SRTP。浏览器麦克风使用本地 HTTPS/WSS，不应通过证书警告强行进入页面；请勿将服务暴露到公网。

## 构建

使用 Android NDK 28.2.13676358 和 CMake 3.22.1。首次构建会从 GitHub 获取固定版本的 libdatachannel 及 Mbed TLS；后续复用本地 CMake 缓存。运行 `./gradlew :mobile:assembleDebug`。

## 来源与许可

本项目基于 DiPlay / xcertplay 开发，保留上游许可证与第三方声明。详情参见 [LICENSE](LICENSE) 和 [第三方声明](docs/upstream/THIRD_PARTY_NOTICES.md)。第三方素材与组件遵循各自许可证。

WebRTC 使用 [libdatachannel 0.24.1](https://github.com/paullouisageneau/libdatachannel/tree/v0.24.1) 和 [Mbed TLS 3.6.5](https://github.com/Mbed-TLS/mbedtls/tree/mbedtls-3.6.5)，版本固定在 CMake 配置中；[原生依赖许可](common/src/main/assets/webrtc-licenses.txt)随 APK 分发。

串流调试时，可在浏览器控制台读取 `window.wheelplayStats`：包含源输入与发送速率（2 秒窗口）、解密/排队/发送耗时（最近 240 次的均值和 P95）、丢弃计数，以及浏览器接收/解码/呈现速率、丢包和 RTT。速率与耗时分段统计，不代表端到端延迟；未支持的浏览器指标为 `null`。直通排队预算为 50 ms，超时丢弃参考链并等待关键帧。原生媒体发送反馈的固定版本补丁见 `common/src/main/cpp/media_send_feedback.cmake`；本地发送成功仅表示传输层接受，接收端反馈用于判断后续进度。
