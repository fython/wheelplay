# Android Auto 协议接入（实验版）

基线：main `4a68a2e`（#33）。本阶段增加真正的 AAP 会话，不使用诊断视频代替手机画面。代码已实现 USB AOA 和显式 TCP 连接，仍需 Android Auto 手机真机验收；模拟端测试不能证明所有手机版本兼容。

## 连接

底部“输入源” → Android Auto → 选择“本机 / USB / 网络”连接方式。页面只展示所选方式的连接操作；选择方式不会启动或中断投影，页面重建后保留选择。详细配置和能力限制可展开“连接说明与限制”。输入源列表同时展示首页默认源与当前会话状态；插件管理和诊断位于“更多工具”。进入页面不会切换当前会话，点击连接才会切源。

- **Android Auto · 本机**：在 Android Auto 二级页面中提供本机连接和首次设置入口。首次引导提供 Android Auto 设置跳转：完成 AA 首次配置，连续点击版本信息开启开发者模式，再从菜单选择“启动车机服务器”（Start head unit server）。返回 WheelPlay 后点击连接；之后保持服务器运行即可一键连接，无需填写 IP 或插线。固定连接 `127.0.0.1:5277`，不修改远程 TCP 地址。手机重启后可能需要重新开启服务器；连接被拒绝时会提示操作，并在下次点击时重新显示引导。未安装/停用 AA 时拒绝切源，保留现有会话。服务是否运行在实际连接时确认，点击连接仍会按正常流程切换当前源。
- **Android Auto · USB**：用数据线将 Android Auto 手机接到 WheelPlay 宿主的 USB Host/OTG 端口。只连接一台候选手机，确认系统 USB 权限弹窗。宿主发送 AOA GET_PROTOCOL / SEND_STRING / START，等待手机重新枚举，再申请新设备权限并使用 bulk IN/OUT 通道。两个 Android 设备必须能分别承担 USB host 和 accessory 角色。不会自动接管 iPhone 或开启 Android Auto 无线热点。
- **Android Auto · Head Unit Server (TCP)**：在手机 Android Auto 开发者设置启动 Head Unit Server，填写宿主能访问的 IPv4 地址，端口固定为 5277。同设备实验可填 `127.0.0.1`；跨设备必须确认服务实际监听可访问地址。只监听 loopback 的手机需要另行建立端口转发，连接同一 Wi-Fi 本身并不足够。此入口不是 Bluetooth/Wi-Fi 自动发现与无线配对。
- 浏览器使用原有 WheelPlay 地址和配对流程。切换源后需重新连接浏览器。
- 分辨率固定 1280×720、30 fps、H.264。WebRTC 直通和图像回退沿用公共视频输出。
- 音乐/提示音/语音 PCM 播放在 **WheelPlay 宿主设备**。尚不转发到浏览器。
- 麦克风仅在设置为 Android 麦克风且 RECORD_AUDIO 已授权时向手机声明；浏览器麦克风和禁用设置均不声明 AA 麦克风。关闭或切走 Android 麦克风会终止本机录音并丢弃排队的麦克风包；重新启用能力需要重新连接 AA。

启动失败会显示原因并清理会话；USB 权限等待、模式切换、TCP 连接和 TLS 握手都有超时。停止可打断启动。当前连接断开后由用户重新选择源，不包含自动重连策略。

音频播放线程保留部分写入后尚未消费的 PCM，遇到暂满缓冲区每 5 ms 重试；连续约 2 秒无进展或设备返回错误时结束会话并显示原因。停止会话、停止音频通道或丢失焦点可以取消重试，协议读取与 ACK 不等待音频设备。播放队列仍有容量上限，持续过载时可丢弃旧的排队音频。

视频需要关键帧时先发送限频的焦点通知；2 秒内未收到交付给公共输出的 IDR，则释放视频焦点并在至少 250 ms 后恢复，允许手机重启媒体通道。两次焦点循环至少间隔 10 秒，每次会话最多 3 次；预算用完且仍等待 IDR 5 秒后关闭连接，提示用户重连。停止会话或手机切到本地界面会取消恢复；恢复计时独立于入站媒体包，静止画面也能触发。此流程仍需不同 AA 手机版本的真机验证。

本机模式不自动打开开发者模式或启动车机服务器，也不依赖 root、Shizuku、无障碍点击或私有无线启动广播。Android Auto 的设置 Activity 随版本变化；打不开时回退到应用设置/系统应用详情，并提供手动操作说明。DiAuto 的旧版本兼容路径调用内部无线启动组件，而其 AA 17.4+ 分支也改用本机 Head Unit Server；这不能证明所有新旧 AA 版本的同机兼容性。本机音频焦点及麦克风与 AA 共处同一设备的行为仍需真机验证。

## 已实现的协议路径

1. 明文 AAP 版本交换；channel 0 封装 TLS 1.2 握手；认证完成消息。每个会话生成自己的自签名 RSA 身份，没有复制外部项目的证书私钥。AAP 的自签名证书互信仅用于用户选定的手机连接，不能用于 HTTPS。
2. 服务发现，通道开放、ping、导航焦点、音频焦点、结束会话。只发布实现的传感器、视频、触控、三路 PCM 和可选麦克风通道。
3. 媒体 setup/config/start/stop；视频逐片解密、有界重组，**每片 ACK**，不等待完整帧或浏览器消费。完整 Annex-B 访问单元进入独立有界视频队列，SPS/PPS 转换为 avcC 后配置公共输出。溢出丢弃积压并请求 IDR。
4. 触控快照转换为 DOWN / POINTER_DOWN / MOVE / POINTER_UP / UP；UP 包包含正在抬起的手指，稳定 ID 与 actionIndex 对应；重复快照不制造新 DOWN。队列溢出断开会话，防止漏发抬手后继续运行。
5. 16 kHz 单声道语音/系统声音、48 kHz 双声道媒体 PCM；独立有界播放队列及 Android 音频焦点。16 kHz PCM 麦克风按手机请求开关，发送窗口由 ACK 归还。
6. 夜间模式传感器订阅；切源/停止先封闭输入、关闭传输，等待音视频线程退出后释放输出。清理失败保留资源归属。

`ProjectionSession.activate()` 在运行时接纳会话后启动异步连接，避免快速连接失败通知先于会话入场。`browserAudio` / `browserMicrophone` 与本机能力分开，阻止浏览器启用尚未实现的 AA 音频通道。

## 验证

自动化测试包含 proto2 边界与未知字段、AAP 字节流拆包、视频分片、SPS/PPS 转换、多指触控，以及使用实际 SSLEngine 的模拟手机端：版本 → TLS → 发现 → 开通通道 → 视频配置与分片 ACK → 公共画面输出 → 浏览器触控格式消息 → 退出。另测停止先于启动时不能连接或创建输出。

回归测试覆盖 PCM 部分写入/暂满/设备错误/取消、USB 授权等待锁注册前后的停止竞态、关键帧恢复的限频/超时/取消，以及模拟手机忽略焦点通知后经历媒体 stop/start、恢复视频并正常退出的完整 TLS 会话。

待真机验收：至少一台 Android Auto 手机通过 USB 完成首次授权、投屏、触控、媒体/导航声音、语音助手和拔插重连；TCP 路径实际连接 Head Unit Server；切换 CarPlay/AA/诊断源、多次进入退出、慢浏览器回退、静止画面恢复、关闭麦克风后的无录音验证。测试需记录宿主/手机型号、Android/AA 版本、线材/OTG 和浏览器。

本机入口另需验证：未安装/已停用 AA、首次设置跳转及返回、服务器未启动时提示、再次点击恢复引导、服务器已运行时一键连接、手机重启后恢复、音频焦点/语音助手，以及连接成功后从车机浏览器控制。自动化测试覆盖本机与远程地址隔离、缺失/停用 AA 的可用性和连接失败后恢复引导。

未实现：自动无线发现/配对、浏览器音频和麦克风、Now Playing 元数据、车载通话 HFP、AA 导航元数据、动态分辨率、HEVC、自动重连。不能据此将 #28 的完整 Android Auto 验收标为完成。

## ICCOA CarLink 阻塞

**此版本没有 ICCOA 协议实现。** 列表中的 ICCOA 项只返回依赖缺失原因；probe 发生在停止当前源之前，点击它不会拆掉现有会话。没有 SDK 时不会启动 BLE/P2P、绑定服务或创建假投屏。

MiAuto 使用专有 `icar-sdk.dex`，公开仓库仅有 `icar-sdk-stubs.jar` 签名桩和 `IccoaManager` 包装层，CI 构建不含运行库。其 SDK 移植还涉及固定名称的 Service、Room 依赖、权限、初始化一次的生命周期、Surface 投屏、触控和 SDK 退出完成语义。当前未获得 SDK，无法验证这些行为，也没有复制编译桩冒充协议实现或给出未经验证的运行库注入流程。

取得适用 SDK 后，应独立 PR 完成真实适配与离屏 Surface、触控、断开/资源释放验证。部分 SDK 网络权限需要系统签名或特定车机环境，不能因为 MiAuto 在某台车机运行就推断普通 Android 手机可用。

## 参考版本

- [DiAuto](https://github.com/shihabal3amri/DiAuto/tree/f3046721814bff289a78e58f597b45d895efbb95)：AAP 的 control/media/input/sensors 协议字段、AOA 和消息处理研究参考。WheelPlay 使用自己的小型 wire codec、连接生命周期和公共视频端口，没有引入 DiAuto 的 Activity/Service 或证书私钥。
- [DiAuto 本机模式与设置入口](https://github.com/shihabal3amri/DiAuto/blob/f3046721814bff289a78e58f597b45d895efbb95/app/src/main/java/com/andrerinas/openheadunit/aap/AapService.kt#L3003)：版本差异及 Head Unit Server 引导的研究参考。
- [MiAuto SDK 依赖说明](https://github.com/PiggyBackBayonet/MiAuto/blob/059b2bb6b4d7fd3b506e7c29bbf153a8e38a0c4d/app/libs/README.md) 与 [集成说明](https://github.com/PiggyBackBayonet/MiAuto/blob/059b2bb6b4d7fd3b506e7c29bbf153a8e38a0c4d/docs/ICCOA_SDK_INTEGRATION.md)。
