# 投影输入源框架：当前实现

后续 Android Auto USB/TCP 协议实现、使用方法和 ICCOA SDK 阻塞见 [Android Auto 接入](ANDROID_AUTO.md)。VNC 协议和通用 Pointer/Key 输入的后续实现见 [VNC 接入](VNC.md)。下文记录 #33 框架阶段的范围。

对应 [#28](https://github.com/fython/wheelplay/issues/28)，实现基线为 `66d0455`（PR #31 合入后的 main）。继续使用 Kotlin/C++，没有引入 Rust 后端或承诺普通 Linux 宿主支持。

本次落实公共框架、CarPlay 兼容适配及三种诊断源，即 P0–P2 的代码基础。P1 的 CarPlay 真机回归、P2 的设备性能验收，以及 P3–P5 的真实新协议接入仍需单独验收；不能据此关闭整个跟踪 Issue。完整研究背景保存在 [设计草案](INPUT_SOURCE_FRAMEWORK.md)。

## 使用

Web 服务和浏览器配对不再依赖 MFi 身份。CarPlay 的连接入口仍执行原有认证检查，包括 USB 外部启动。

在手机连接页面的「投影输入源」卡片选择「选择诊断输入源」：

| 源 | 视频路径 | 交互 |
| --- | --- | --- |
| Demo · encoded | 应用自生成 H.264 访问单元 → 原 WebRTC 直通或本地解码 → 图像回退 | 只读；关键帧请求回到片段首个 IDR |
| Demo · surface | Canvas 向宿主 Surface 写入 → SurfaceTexture/EGL → 图像 | 最多十点触控；支持夜间模式 |
| Demo · framebuffer | 有序增量、重叠 CopyRect → 完整像素快照 → 图像 | 最多十点触控；支持夜间模式 |

诊断源没有音频或麦克风能力，不申请相关资源。H.264 片段由 `scripts/generate-projection-fixture.py` 使用 ffmpeg/libx264 生成，320×180、15 fps、30 个访问单元，无第三方视频素材。Surface/framebuffer 尚无 WebRTC 重编码器，使用现有 JPEG/WebP 输出。切源会撤销浏览器控制租约并断开当前观看连接，需要重新连接；这是兼容没有会话版本字段的旧浏览器的明确行为。

## 接口和所有权

- `shared/.../projection/ProjectionSource.kt`：协议无关的描述、能力、可用性、触控快照、会话和 provider。核心接口不携带 Android Context、AAP protobuf 或 ICCOA SDK 对象。
- `ProjectionRuntime`：单活动源、资源占用、异步打开和停止。旧源停止完成前不能打开新源；停止失败保留资源，禁止继续输入。`stop(expected)`、`touch(expected, ...)` 和自然结束通知都验证具体会话对象，迟到操作不能改动新源。
- `common/.../projection/ProjectionVideoOutput.kt`：Android 媒体端口。选择 encoded、Surface、framebuffer 中一种入口，宿主组合层注入输出实现。`SyntheticSource` 不引用浏览器实现。
- `BrowserProjectionVideo`：复用 `WebVideoSource`、`AndroidMediaSink` 和 `OffscreenVideoOutput`。编码配置采用通用 ISO-BMFF avcC/hvcC，访问单元为 Annex-B；每次配置返回 revision，旧 revision 的帧被拒绝。现阶段仅主画面，未提供多显示器协议。
- `CarPlaySourceSession`：包装现有连接生命周期。HID 两槽位补齐仅留在适配器。重连期间保持资源占用，旧控制器、媒体线程和离屏输出退出后才允许切源。原发现、认证、iAP/AirPlay、音频和麦克风实现保留。
- `MediaControlSource`：Now Playing、媒体按键和 seek 的可选接口。`CarPlayMediaSource`、`airplay.VideoCodec` 和 `AirPlayContact` 保留类型别名，减少旧协议内部迁移风险。密钥解密器工厂回到 CarPlay 装配层，浏览器视频源不再接收解密密钥。

编码帧在回调内借用，异步分支继续使用原 retain/close 契约及有界队列、关键帧恢复逻辑。framebuffer 必须先应用全部有序增量，再覆盖待编码的完整快照；输出只保留最新完整快照，禁止对增量任意丢帧。Surface producer 必须先停止写入，再关闭并等待 EGL 输出退出。停止超时不能当作释放成功；应保留故障会话并由用户重启应用恢复。

源的 `touch()` 是短时、非阻塞的输入接纳操作，不等远端 ACK；它与停止的输入禁用操作串行。网络写入必须由适配器自己的有界队列处理。`stop()` 幂等，完成代表资源已释放，不能只发出停止请求便完成。provider 打开失败也必须先清理部分启动资源才完成异常。

浏览器 status 携带源 ID、名称及 `maxTouchContacts`（0–10）；缺少字段的旧宿主仍按两个触点兼容。输入还必须匹配原有 sessionId/videoEpoch、拥有控制租约、满足当前源就绪状态及坐标范围。旧版本的空触控释放也被拒绝，防止它取消新会话的手势。切源先取消旧手势并撤销租约；画面重配置取消当前手势。

## 验证与后续接入

自动化覆盖：运行时停止排序/停止失败/启动中停止/迟到操作/能力限制；framebuffer 增量、重叠复制、边界及快照所有权；浏览器版本与源切换隔离；无 MFi 的宿主启动及 USB CarPlay 拒绝；Chromium 五点触控、只读画面及原有 WebP/JPEG 回退、Canvas 呈现和版本恢复测试。

设备验收仍必须执行：

1. 三类诊断源分别连接真实车机浏览器，观察动态画面；Surface/framebuffer 画面触控有反馈，编码源无触控。切换至少 30 次，确认停止后没有旧画面、持续输入或增长的媒体线程。
2. 使用慢网络和重连验证有界背压；H.264 的 WebRTC→图像回退能请求 IDR 并恢复。测试固定画面重连、尺寸变化、夜间模式和后台服务退出。
3. CarPlay 有线/无线、重连、双指、媒体按键、浏览器音频、浏览器/本机/禁用麦克风均回归；对比同机同网的帧率、延迟、内存和耗电。本环境的编译和 JVM/Chromium 测试不能证明这些设备指标。

下一真实源仍优先 Android Auto：从 DiAuto 提取一种传输及协议会话，将完整压缩访问单元交给视频端口，并在适配器处理 ACK、明确的 touch action/actionIndex、音频和焦点。此次没有打包未经端到端验证的 AA 协议实现，也未复制外部证书私钥。音频 PCM/麦克风的跨协议端口和更完整的生命周期状态/取消启动接口仍需由第二个真实源驱动收敛；当前音频回路仍为 CarPlay 兼容路径。

ICCOA 继续受制于 MiAuto 未分发的 `icar-sdk.dex`。拿到适用 SDK 后，先验证离屏 Surface、触控注入和停止释放，再实现 provider；没有 SDK 时不能把包装层视作可用源。VNC 可复用 framebuffer 合成，但 Pointer/Key/Text 等能力尚未实现。新源只能声明实际支持的能力。
