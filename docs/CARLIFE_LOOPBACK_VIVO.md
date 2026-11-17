# vivo 本机 CarLife 接收探针

> 历史调试记录：独立探针和插件模块已移除，当前代码、构建命令与架构见 [CarLife 内置实现](CARLIFE_WIFI.md)。下文旧模块路径及安装命令仅用于还原当时的验证环境。

日期：2026-11-16。更新：**手动连接并跳过蓝牙后，探针真实动态画面与触控已通过；更新主程序后，WheelPlay 插件到浏览器的 WebRTC 画面与单指触控也已通过。**

## 正确入口与复测

用户指出并验证：在「车机热点连接百度 CarLife+」页点击「开始连接」，随后在「检查车机蓝牙」弹窗点击「取消」，会跳过蓝牙检查并继续连接，而不是取消整次连接。之前对该按钮的理解错误，导致将可用路径误判为阻塞。

代理随后独立复测，不再使用 ADB 启动 CarLife Service、不自动开启模块：通过该手动入口后返回探针，收到真实桌面；持续超过两分钟，点击应用列表、横向拖动翻页、返回主页均产生正确响应。证据为本地 `manual-success.log`、`manual-repro.png`、`grid-confirm.png`、`swipe-confirm.png`。ADB 仅用于界面操作与诊断，电脑不转发媒体。

每次连接建议先完成此手动准备，再及时返回接收端连接，避免系统准备超时。无需把其他蓝牙附件确认为车机，也无需获取签名权限。

`carlife-receiver` 是探针和新增 `projection-plugin-carlife` 共用的纯 Java 接收层；插件固定 1280×720，通过压缩视频扩展 v1 直接输出 H.264，回传单指触控，不声明音频／麦克风能力。

以下保留初轮排查记录；其中“阻塞／未通过”属于修正前的实验结果，不代表当前手动路径。


## 更新 WheelPlay 后的浏览器复测（2026-11-16）

用户指出测试机主程序过旧。核对旧 `moe.feng.wheelplay.debug` 的更新时间为 2026-11-05 14:28:33；按要求卸载后，安装当前工作区构建的主程序（基线提交 `d132cf7`），新安装时间为 2026-11-16 11:47:28。版本名仍为 `0.3.0-web-debug`、versionCode 21，因此不能仅凭版本名判断是否包含压缩视频扩展。

`:mobile:assembleProvisionedDebug :projection-plugin-carlife:assembleDebug` 构建成功，主程序的本地运行资源校验通过。安装 APK SHA-256：`06c26f18e283be4e908e152ffaa91ba7c52c7b1acf4a53b832c83a713c7db71d`。

新版启用插件后，通过系统手动连接入口完成准备，点击蓝牙检查弹窗的「取消」，点击系统连接页左上角返回，再点击插件的「已手动连接，继续」。必须及时返回；本轮准备阶段曾因滞留连接页而超时，重新完成流程后连接成功。

浏览器使用局域网直连测试机，未使用 ADB 端口转发。Chrome 原生 Video 模式收到 1280×720 的 live 视频轨道，显示真实 CarLife 地图／桌面；连续播放超过两分钟，WebRTC 接收统计持续增加，并有 SRTP 发送统计。点击应用列表、拖动到下一页和点击主页均有正确画面响应。此前「请更新 WheelPlay 以使用 CarLife WebRTC 输出」限制已解除。

本轮只验证视频与单指触控；插件未提供音频。实际接收／解码采样约 10–13 fps，呈现帧率有波动，不能宣称实测 30 fps，也未完成长时间稳定性和五次重连验收。

本地证据：`.local/carlife-vivo/latest-host-build.log`、`latest-host-installed.txt`、`webrtc-home.png`、`webrtc-grid.png`、`webrtc-swipe.png`、`webrtc-stats-first.json`、`webrtc-stats-final.json`。截图含个人地图等信息，不提交仓库。

## 初轮结果（已由上面的手动路径修正）

普通 Android App 已从同机 `127.0.0.1` 连通七路 CarLife TCP，完成 3.0 版本匹配、设备信息、功能配置和编码协商，并解码 1280×720 H.264。电脑仅用于 ADB 调试，没有端口转发、协议代理或媒体中继。

默认视频流程显示“请先在手机端点击同意智能车载用户协议，以继续使用车载投屏”的提示页，不能作为投屏成功。曾在向所有模块发送开启控制后显示真实地图／桌面和语音界面，但这同时启动了不属于本轮范围的功能，且点击未确认产生目标响应、会话发生解码器清理异常。因此不能据此宣布真实界面与触控闭环通过。最终代码移除了自动模块控制。

对照最终的视频流程，协议主页命令仍未解除提示页；连续约 134 秒收到约 968 个视频包，画面仍是提示页（约 7.2 包/秒，不等于真实动态帧率）。请求和编码确认的 30 fps 也不能当作实测帧率。

## 环境

| 项目 | 本轮值 |
| --- | --- |
| 手机 | vivo V2547A |
| 系统 | Android 17，PD2547B_A_17.1.13.4.W10 |
| 内存页大小 | 4096 bytes |
| 智能车载 | `com.vivo.car.networking` 7.0.0.9 |
| 车载桌面 | `com.vivo.carlauncher` 7.0.0.12 |
| CarLife | `com.baidu.carlife.vivo` 8.8.4 |
| 探针 | `moe.feng.wheelplay.carlife.probe` 0.1-experimental |

ADB 辅助启动是当前实验的必要准备步骤；未验证探针独立自动拉起源，也未证明断开 USB 后的持续运行。未使用 Root、Hook、系统 APK 修改或签名权限绕过。音频只排空通道，不播放、不录音；不宣称音频支持。

## 固定参考与状态机

许可和固定提交见 [NOTICE](../common/CARLIFE_NOTICE.md)。

| 内容 | CarPToCarL | mcarplay | 本探针 |
| --- | --- | --- | --- |
| 本地通道 | 七路 TCP | 七路 TCP | 同机 loopback 七路 |
| 启动入口 | 荣耀适配 | 小米适配 | ADB 启动 vivo CarLife 导出 Service，再绑定 vivo 混合画面服务 |
| 版本／加密 | 3.0，无 RSA/AES | 含认证、RSA/AES 实现 | 3.0；收到加密要求或认证拒绝即停止 |
| 视频 | H.264 转发 | H.264 转发 | MediaCodec 本机预览，固定请求 1280×720 |
| 输入 | 单点及扩展动作 | 触控回传 | 9340，单指 DOWN/MOVE/UP，主页／返回诊断命令 |

连接序列：

1. 依次连接 7240（命令）、8240（视频）、9240（媒体）、9241（TTS）、9242（语音）、9340（输入）、9440（数据）。
2. 发送 `98305` 版本 3.0，等待 `65538` 匹配值 1；12 秒内有限重复版本请求，不自动降级或重连。
3. 收到 `65540` 设备信息后发送 `98307` 车机信息；发送 `98343` 统计信息，包含本 vivo 版本要求的七个字段。
4. 处理 `65611` 认证结果、`98380` ACK、`65617` 功能配置请求，发送 `98386`。
5. 请求 `98311` 1280×720@30，收到 `65544` 编码参数后发送 `98313` 开始视频。只接受固定画布，防止触控坐标误配。
6. 首个视频包、解码格式、渲染计数分别记录；30 秒无首包退出。真实动态界面和触控仍需人工观察。

命令／触控使用 8 字节大端头，媒体流使用 12 字节头。解析器限制视频包 4 MiB、其他输入包 256 KiB，拒绝截断及溢出 protobuf。控制队列有界；停止尝试发送抬手，并在 250 ms 后强制关闭探针自己的套接字，等待工作线程退出。异常清理不会因 MediaCodec 已出错而再次崩溃。

本 vivo 的数据通道 9440 也按 8 字节命令头读取。仅通道连通不代表其帧格式相同。

注意：本 vivo 的 `98344` 模块控制是单个 `{moduleID,statusID}`，不是状态列表。它会实际控制电话、音乐、语音等功能，**不可把所有状态 0 自动改为 1 作为视频握手步骤**。

## 可复现实验步骤

构建与检查：

```sh
./gradlew :carlife-probe:assembleDebug :carlife-receiver:test :carlife-probe:lintDebug
```

安装 `carlife-probe/build/outputs/apk/debug/carlife-probe-debug.apk`，在手机保持解锁、前台运行探针。先通过系统智能车载正常 CarLife 连接页准备；探针提供该页面入口，但它仍要求真实车机 Wi-Fi／蓝牙条件。不要把其他蓝牙附件确认为车机。

本轮实际能完成协议握手的 ADB 准备命令（替换设备序列号）：

```sh
adb -s DEVICE shell am startservice \
  -n com.baidu.carlife.vivo/com.baidu.carlife.service.CarlifeConnectService \
  --ei usb_type 0 \
  --es bindPackageName com.vivo.car.networking \
  --es bindClassName com.vivo.vivo_car_service.CarLifeMixingService \
  --es manufacturer vivo
```

等待源初始化后打开探针，点“开始 3.0”。不要同时运行第二个接收器。普通 CarLife Activity 裸启动曾连通七路但协商静默；不能用它替代上述准备。源出现残留状态时，本轮曾用 ADB 强停 CarLife／智能车载后重新准备，**这是故障恢复，不算正常停止重连通过**。

探针“停止”只释放自己的会话。“8 秒后连接”仅延迟接收连接，不负责系统源启动，且需要预览 Surface 就绪。诊断保存在私有 `files/probe.log`，上次 Activity 的日志保留为 `probe-previous.log`；视频样本最多 16 MiB，位于私有 `files/capture.h264`。样本可能包含地图、位置或个人内容，不上传、不提交仓库。

## 分阶段证据

| 阶段 | 结果 | 证据／限制 |
| --- | --- | --- |
| 导出 CarLife Service 启动 | 通过，依赖 ADB 准备 | 能绑定混合画面服务并进入协议 |
| 七路 App 本机访问 | 通过 | `SEVEN_CHANNELS_CONNECTED` |
| 3.0 协商 | 通过 | `VERSION_MATCHED 3.0`、`AUTH_RESULT status=1` |
| 编码确认 | 通过 | `ENCODER_CONFIRMED 1280x720 fps=30` |
| 解码 | 通过 | `FIRST_VIDEO`、`DECODED_FORMAT 1280x720`、渲染计数 |
| 正常动态界面 ≥2 分钟 | 未通过 | 最终流程持续出提示页；模块控制下曾出现桌面，未计入验收 |
| 单指点击／拖动／抬手 | 未通过 | 确认发送，未确认正确目标响应 |
| 主页／返回 | 仅发送已确认 | 主页未解除提示页；返回无目标响应证据 |
| 正常停止 | 探针清理通过 | `STOPPED_CLEAN`；已修复解码器异常后的二次崩溃 |
| 五次正常重连 | 未通过 | 存在源残留和一次首帧超时，使用过故障恢复 |
| WheelPlay／浏览器闭环 | 未开始 | 探针门槛未满足，未新增插件 |

本地原始材料在 `.local/carlife-vivo/`，不提交：`service-first-video.log`、`video-only.log`、`final-static-session.log`，以及提示页、短暂真实桌面和主页命令截图。原始系统日志和截图可能包含个人信息；对外结论以本文件的脱敏阶段记录为准。

最终 `assembleDebug`、`testDebugUnitTest`、`lintDebug` 通过：11 项单元测试，0 失败。覆盖分片读取、大小限制、截断／溢出 protobuf、统计字段、固定画布、并发停止、清理异常和停止后禁止重启。lint 无错误，仍有实验 UI 的图标／触控可访问性、备份规则和 target SDK 提示。未完成全部握手分支的模拟引擎测试，也未将单元测试当作真机触控验收。

## 初轮阻塞假设（不再适用于已验证的手动路径）

只读分析显示 vivo 的欢迎提示与 `ts.d0` 的 CarLife 授权状态有关，正常 `Carlifeboot` 流程会检查授权并设置该状态。仅授予 Android 定位权限未解决提示页。反编译的权限检查调用还存在参数顺序疑点，未通过动态观测确认，不能据此断言厂商缺陷或唯一根因。

vivo 的 `CarlifeAdbService` 是更完整的正常启动入口，但实测 shell 返回 `Requires permission com.vivo.car.networking.CARLIFE_ADB`；该权限为 `signature|privileged`，普通 ADB／Shizuku shell 不具备。系统车机热点连接页仍要求外部车机条件，本轮未找到可独立完成授权和投影初始化的同机入口。

下一步需要确认该系统版本中**可调用的正常 CarLife 授权／初始化路径**，或用真实 CarLife 接收设备完成一次正常连接作为对照，检查授权状态能否在后续本机连接复用。不能以强行开启语音模块替代这一验证。

既有 [ICCOA 同机实验](CARLINK_LOOPBACK_VIVO.md)仍停在发现阶段；当前没有新证据足以启动另一轮完整 ICCOA 开发。以上结论只适用于本设备和已试路径，不推广为所有 vivo 或所有同机方案不可行。

## 音频转发接入与实机验证（2026-11-16）

已接入 CarLife 媒体端口 9240 与导航播报端口 9241，解析各自的 INIT／PCM／STOP，以及媒体 PAUSE／RESUME。仅接受声明格式的 PCM16 LE，校验采样率、单双声道、帧对齐与包长度；暂停后的尾包不播放。两路分别映射到宿主媒体流 16 和导航流 6，共用有序插件音频管道，复用现有手机／浏览器输出设置。恢复及格式变化创建新的流 ID；大包按采样帧边界拆分。结束投影关闭音频管道并等待写线程退出。麦克风上行尚未接入。

执行 `:carlife-receiver:test :projection-plugin-carlife:testDebugUnitTest :projection-plugin-carlife:assembleDebug` 成功，共 23 项测试通过，其中新增音频测试 10 项，覆盖格式拒绝、媒体暂停恢复、独立播报流、并发 ID 顺序、分包保真和关闭后的写入抑制。音频版插件已安装到 vivo。

实机输出选择「车机浏览器」。通过投影主页的音乐播放按钮触发真实媒体，浏览器 Web Audio 探针观察到 44100 Hz／双声道，AudioContext 为 running；暂停前共调度 552 个 buffer，其中 509 个含非静音样本，峰值约 0.980。暂停后 started／ended 均为 552，后续采样计数不再增长；恢复后 started 增至 746，非静音 buffer 增至 702。测试结束再次暂停音乐。上述结果验证音频从 CarLife 到浏览器播放队列的完整链路，未进行扬声器听感及音画同步的人工验收。导航播报已接入并通过协议／路由单元测试，本轮未触发真实导航语音，不记作实机通过。

本地证据：`.local/carlife-vivo/audio-build-test.log`、`audio-browser-playing.json`、`audio-browser-paused.json`、`audio-browser-resumed.json`、`audio-session-status.xml`。探针仅保存计数及格式，不保存音频内容。

## 视频时间轴修复与复测（2026-11-16）

确认本机 CarLife 的 Recorder.sendVideoData 把包头时间写成 System.currentTimeMillis()/1000（整秒）。旧插件误当毫秒，再以 previousPts+1 补齐重复帧，造成同一秒内帧间隔只有 1 微秒；经过 90kHz 转换后不同画面的 RTP 时间大量重复。旧连续 67.893 秒窗口中源输入 13.32 fps、解码 12.37 fps、呈现仅 4.07 fps，VideoPlaybackQuality 丢帧增加 567。

修复在接收器读取完整视频包后立即使用 System.nanoTime()，以会话首帧为原点生成微秒 PTS。VideoTimeline 保留实际到达间隔与空闲间隔；连续突发帧最小间隔 12 微秒，保证转为 90kHz 后仍不同。插件与预览解码器直接消费该 PTS，不再换算包头整秒。该时间轴是本机到达时间的近似，尚不是源端采集时间；不声称已解决源端帧率或音画同步。

接收器及插件 26 项单元测试通过（新增 3 项时间轴测试，覆盖 30fps 时间尺度、RTP 唯一性、空闲与新会话）。插件与探针 APK 构建成功，git diff --check 通过。修复插件已覆盖安装到 vivo，SHA-256：aba2a273fa9ae4113d9b6655e5268f20675a9bb7d4a9cd1fcc4b8f98723feac0。

使用相同 vivo、局域网和浏览器原生 Video 模式复测。启动稳定后采集 60 个一秒样本，首末相隔 59 秒，包含主页、应用列表、横向拖动翻页及返回主页：平均源输入 13.54 fps、解码 13.51 fps、呈现 13.36 fps；VideoPlaybackQuality 丢帧仅增加 9，发送队列过期与溢出均为 0。最后窗口平均 PTS 间隔 74.17ms，与源帧率相符。呈现帧率相较旧窗口约提高 3.3 倍；不同时间窗口包含不同交互，不作为严格同内容性能基准。仍未达到 30fps，下一阶段需定位 CarLife 源端实际捕获／编码节奏。

本地证据：`.local/carlife-vivo/fps-build-test.log`、`fps-fixed-samples.json`；旧对照为 `webrtc-stats-first.json` 与 `webrtc-stats-final.json`。原始截图与设备信息仅保留本地。

## WebUI 尺寸协商实验（2026-11-16）

目标为采用 WheelPlay WebUI 的显示尺寸。插件现已声明显示尺寸扩展，连接时读取宿主请求，将宽高按 8 对齐并限制在约 1080p 像素预算内；协议视频请求、车机信息与触控范围不再固定为 1280×720。触控采用 CarLife 协商坐标范围。

**目标尚未完成。** 本机实测请求 800×480／960×536 时，CarLife 的 ENCODER_CONFIRMED 确认相同数值，但浏览器 videoWidth/videoHeight 仍为 1920×1080。因此诊断中的 DISPLAY_ACTIVE actual 仅代表协议回报，不能当作实际码流尺寸。源码证据为 MediaFormatUtils 的 CARLIFE_ENCODER_FORMAT 厂商回调可覆盖 MediaFormat 宽高；vivo CarlifeCastResolution 在 vivoUserSet 时优先采用保存的 userResolution。仅打开 DISABLE_RESOLUTION_OPTION 不能确保覆盖 vivo 编码设置。

另一次实验发现，窗口变化触发宿主会话重建会关闭 CarLife 七通道，系统随后撤销监听并退出源。尝试在插件中绑定 CarlifeConnectService 恢复失败。已删除该未成功的恢复路径；当前窗口变化仅记录 DISPLAY_REQUEST deferred=true，不破坏正在播放的会话。重新手动连接会请求最新 WebUI 尺寸，但仍受上述厂商覆盖限制。设置页已明确说明这一状态，不承诺动态切换成功。

最终接收器 22 项、插件 10 项单元测试通过，插件及探针 APK 构建成功，git diff --check 通过；修正版插件已安装。实机从 960×540 改为 800×480 后浏览器累计帧数从 78 增至 159，插件计数继续增长且无重连，实际码流仍为 1920×1080。后续需验证可正常控制 vivo 编码尺寸的入口，或单独评估解码后重新编码的开销，才能完成实际输出跟随 WebUI 的目标。

本地证据：dynamic-final-build.log、dynamic-final-status.xml、resize-failure-final.log，均在 .local/carlife-vivo/；不提交包含个人信息的原始日志与截图。

## DISABLE_RESOLUTION_OPTION 重新适配与复测（2026-11-16）

用户确认 vivo 设置页没有自动分辨率选项。本轮保留系统现有设置，继续固定发送 DISABLE_RESOLUTION_OPTION=1。修复发送门禁：原 featuresSent 在安排延迟发送时便置 true，可能让提前到达的 65551 触发 98311；现在拆分 handshakeStarted 与 featuresSent，只有 98386 成功写入命令流后才允许请求编码。正常主动请求及源端请求均使用同一门禁，并明确记录 FEATURES_SENT DISABLE_RESOLUTION_OPTION=1。该标记表示写入成功，不宣称收到独立的配置确认 ACK。诊断将 DISPLAY_ACTIVE actual 改为 DISPLAY_NEGOTIATED confirmed，避免将协议回报误称为实际编码尺寸。

覆盖安装后，按系统连接页→开始连接→取消蓝牙检查→返回插件→继续的流程分别建立两次会话：

| WebUI 请求 | 98311 请求／65544 回报 | 浏览器实际 videoWidth × videoHeight | 首次采样累计帧 |
| --- | --- | --- | --- |
| 800×480 | 800×480 | 1280×720 | 73 |
| 1280×800 | 1280×800 | 1280×720 | 71 |

两次均记录 98386 配置在 VIDEO_REQUESTED 前写入，同一 TCP 命令流保持顺序。结果排除了本轮测试中先请求视频、后发送配置的原因，仍不支持“仅此字段足以覆盖 vivo 编码尺寸”的结论。与此前 1920×1080 的观测不同，本轮实际输出为 1280×720；本轮未修改 vivo 分辨率设置，不据此推断设置由谁或何时改变。请求、协议回报与码流实际尺寸必须分开记录。

接收器 24 项、插件 10 项测试通过（新增配置字段值／计数及提前编码请求门禁测试），APK 构建成功并安装，git diff --check 通过。证据：resolution-option-build.log、resolution-option-tests.log、option-800.xml、option-800-browser.json、option-1280.xml、option-1280-browser.json，位于 .local/carlife-vivo/。完整系统日志未找到可直接确认 useVehicleResolution 值的可用行，因此源码分支分析与设备观测分开表述。

## 删除旧车辆配对后的完整流程复测（2026-11-16，修正前述结论）

用户先删除 vivo 智能车载的旧车辆配对。本轮使用相同修正版插件、不修改代码、不手动选择系统分辨率，从全新浏览器会话开始，输入 WheelPlay 配对码建立浏览器授权，再走插件准备→系统智能车载连接页→开始连接→取消蓝牙检查→返回插件继续的完整流程。

| 阶段 | WebUI 请求 | 98311／65544 | 浏览器实际视频 | 采样累计帧 |
| --- | --- | --- | --- | --- |
| 删除旧车辆后的首次连接 | 1024×600 | 1024×600 | 1024×600 | 42 |
| 保留新车辆、改变 WebUI 后手动重连 | 1280×800 | 1280×800 | 1280×800 | 89 |

两次均在请求编码前发送 DISABLE_RESOLUTION_OPTION=1，且实际解码尺寸随请求改变。第二次没有再次删除车辆，也没有修改 vivo 分辨率设置。由此修正前面的局限结论：该字段在当前 vivo 上可以实现连接时采用 WebUI 分辨率；旧车辆持久化设置是前几次覆盖结果的关键影响因素，而不能概括为 vivo 始终不支持该字段。因没有直接读取系统私有数据库，不把具体残留字段或存储位置写成已验证事实。

当前仍不支持连接中随浏览器窗口变化无缝重配；onDisplaySizeChanged 保持会话并记录 deferred，需手动重新连接后应用新尺寸。本轮未改动这项行为。旧车辆手动分辨率设置可能干扰后续协商，遇到请求与码流不符时应先考虑移除旧车辆记录并重新建立连接。

证据：.local/carlife-vivo/fresh-1024.xml、fresh-1024-browser.json、fresh-1280.xml、fresh-1280-browser.json。本轮仅设备验证及文档更新，未新增代码或重新构建。

## 原连接重配与源恢复接回验证（2026-11-16）

按 mcarplay／CarPToCarL 的分层恢复思路分别验证，不把“系统源可被自动启动”与“已有源恢复后接回”混为一谈。

### 原连接 98311 热改尺寸：未通过

独立探针保留全部七通道，在稳定输出 1280×720 后，依次发送 1024×600、1280×800 的 98311。两次均收到 ENCODER_CONFIRMED 1280×720 fps=20，解码器没有发生输出尺寸变化，连接及帧计数仍持续。与本机 VideoMsgHandler 的 mInited 分支吻合：再次 INIT 回报旧编码尺寸，不重新初始化。热改入口仅保留在诊断探针，未接入正式插件窗口变化路径，避免改变帧率并作出错误的尺寸承诺。

### 系统源恢复后自动接回：视频与触控通过

新增 CarLifeRecovery，在断线后保留插件宿主视频／音频管道；严格等待旧接收线程与音频通道清理完成后创建新尝试。仅网络断线／连接及首帧超时进入恢复，认证拒绝与格式错误不循环重试。恢复等待有两分钟预算，用户可随时停止；不主动启动或绑定 vivo 系统服务。源时钟重新从零开始时，按本次宿主会话的单调时间重映射 PTS，保留断线间隔；视频 sequence 不重置。新的源仍需符合原宿主固定视频格式，尺寸变化须停止并重新连接插件。

设备验证使用 force-stop com.baidu.carlife.vivo 注入源退出（不作为所有真实故障的等价证明）。随后从系统连接页点击开始连接、取消蓝牙检查、返回 WheelPlay；不再点击插件准备页的“继续”，也不在浏览器刷新或点击重新连接。连续两轮恢复后均重新输出 1024×600。第二轮浏览器采样累计帧 475，随后增长到 1089，点击应用列表按钮得到实际界面响应。恢复过程允许浏览器重建 video 元素，不承诺保留同一个解码器或无黑屏。

重要设备限制：停留在 vivo 系统连接页时，dumpsys 显示插件进程的厂商冻结状态，宿主浏览器连接也可能暂时失联。仅等待后台接回并不可靠；返回 WheelPlay 前台后，接收线程、握手和浏览器视频能够自动恢复。前一轮定位时挂起调试进程影响了超时与浏览器链路，因此不把该轮计入最终成功验收。界面提示已明确要求返回 WheelPlay。

再注入一次源退出，在等待期间点击“停止投影”：最后 STOPPED_CLEAN，projection 进程退出，重试没有继续。音频管道及流 ID 生命周期保留并有单元测试覆盖，本轮未播放音乐或导航做恢复后的音频听感验收。

新增 4 项恢复测试：清理屏障与 PTS 单调性、等待中取消不复活、恢复截止时间、认证拒绝不重试。最终接收器 28 项、插件 10 项测试通过，插件和探针 APK 构建成功，git diff --check 通过。实机证据在 .local/carlife-vivo/：hot-resize-probe.log、recovery2-foreground.json/xml、recovery3-after.json/xml、recovery-touch.png、recovery-cancel.xml、recovery-final-build.log。日志及截图保留本地。

## 首次 WebUI 尺寸晚到的启动门禁（2026-11-16）

本次诊断中 WebUI 上报 2494×1728（预算内换算为 1728×1192），但宿主已在没有窗口尺寸时用默认 1280×720 绑定插件。后续尺寸通知被标记 deferred，源恢复也继承旧尺寸。这次是启动时序问题，不是 vivo 覆盖了正确请求。

新增可选插件元数据 REQUIRES_INITIAL_VIEWPORT，CarLife 声明启用。自适应开启且没有浏览器尺寸时，宿主显示等待提示，先订阅窗口尺寸而不绑定插件；收到有效尺寸后读取最新值并绑定一次。等待最多使用现有启动预算 60 秒；取消、超时均移除订阅，迟到的窗口事件不会复活会话。关闭自适应时可立即按默认尺寸继续。其他插件保持既有行为，CarLife 源恢复仍使用首次确定的尺寸，不引入连接中热改。

宿主会话与注册表 24 项、接收器 28 项、插件 10 项测试通过；新增覆盖尺寸晚到、默认尺寸作为真实首个上报、取消、超时、关闭自适应与元数据发现。主程序和插件构建成功。实机验收另记，不将单元测试当作实际码流尺寸验证。构建记录：.local/carlife-vivo/viewport-gate-final.log。

修正版主程序与插件已覆盖安装到 vivo。本轮沿用已连接浏览器，重新完成系统源连接后，插件首个 VIDEO_REQUESTED 为 1040×1728，ENCODER_CONFIRMED 同值，系统 vivo_car_screen 也为 1040×1728，视频接收计数由 103 增至 326。此次验证了实际设备未退回 1280×720；没有独立读取浏览器 videoWidth/videoHeight，不将虚拟屏幕尺寸等同于浏览器解码尺寸。无浏览器时的等待／迟到尺寸／取消路径由上述回归测试覆盖。证据：viewport-gate-device.xml、viewport-gate-display.txt。

## WebUI 手动尺寸重连提示（2026-11-16）

插件新增可选 PluginVideoInfo.manualDisplaySizeReconnect（默认 false）。CarLife 启用后，尺寸回调仅在约束后的输出尺寸确实变化时报告需重建；宿主保存待应用的窗口尺寸并推送给 WebUI，不自动停止源。WebUI 弹窗提供「稍后」「重新连接」，同一会话同一尺寸不重复提醒，设置页可再次打开。恢复原尺寸会撤销提示，弹窗期间不透传触控。若 WheelPlay 不在手机前台，重连按钮禁用并提示返回手机。

点击重连使用独立操作，校验当前输入源、请求 revision 和会话 generation；等待旧会话清理完成后，再走该插件原有的手机准备页。清理失败、手机前台回调失效、输入源替换或选择变化均不重新打开。此能力仅用于尺寸变化，与源断线后的自动接回分开；CarPlay 及未声明该字段的插件保持原有路径。新插件也检查宿主 EXTRA_MANUAL_DISPLAY_RECONNECT 标记，在旧宿主上保留 deferred 行为，避免被旧宿主自动重启。

验证：宿主相关 25 项、插件 API 25 项、CarLife 插件 10 项测试通过；204 项 Node 测试通过；真实 Chromium 回归通过，新增检查弹窗显示、稍后不重复提示、设置中重新打开、前台门禁、重复点击只提交一次、精确 source/revision、恢复原尺寸关闭，以及手机宽度下的布局。浏览器测试为合成视频夹具，不记作 vivo 系统连接流程的端到端验收。构建通过，主程序与插件覆盖安装。记录：resize-prompt-final.log、resize-prompt-node.log、resize-prompt-browser.log；截图在 resize-prompt-browser/。

## 小米适配后的 vivo 回归（2026-11-16）

新增小米 4.1/RSA/AES 路径使用显式会话策略，旧构造器仍为 3.0。七通道集成测试校验原有 vivo 报文、明文音视频及触控；新版共享接收层在 vivo 实机完成真实桌面、点击应用列表、横向翻页和停止清理回归。详见 [小米实验适配与验证范围](CARLIFE_XIAOMI.md)。


## 麦克风验证前置检查（2026-11-16）

重新连接 vivo V2547A 后检查当前 CarLife 实现，确认麦克风上行尚未接入，不能进行端到端麦克风验收。设备插件安装时间为 2026-11-16 15:56:39；本轮未更改权限、未启动录音，也未将系统语音助手自行使用手机麦克风计作插件能力。

具体缺口：CarLifeService 返回的 PluginVideoInfo 未启用 microphone（默认 false）；CarLifeAudio 只写媒体/导航下行消息，收到的 microphone 文件描述符仅保存并在退出时关闭，没有读取 MicrophoneData；CarLifeSession 对 9242 通道仅排空，没有处理语音采集请求及上行音频封包。宿主虽已有 PluginMicrophone 和手机/浏览器输入路由，但插件未请求开启，现有音频播放验证不能覆盖此链路。

结论为“功能未实现，麦克风闭环未验证”，而非“vivo 麦克风不兼容”。后续需先核对 vivo CarLife 的语音开始/停止命令、PCM 格式和上行消息，接入宿主采集请求、上行管道、停止与断线清理，再分别验证手机麦克风和浏览器麦克风；最终以受控语音被源端正确识别为闭环证据，不能只依据非静音计数。


## 麦克风上行实现与验证边界（2026-11-16）

已补齐上一节的代码缺口。vivo 插件声明 microphone 能力，沿用宿主的 Android／浏览器麦克风路由和权限控制。接收层处理 65570（唤醒采集）、65572（识别采集）、65571（停止）命令；开始时通过插件音频管道请求 16 kHz、单声道 PCM16，读取宿主 MicrophoneData，按 20 ms 分块经 9242 通道发送 0x58001 帧。时间戳由已发送采样数推导。功能表声明 VOICE_MIC=0，表示车机麦克风模式。

重复开始不重复采集；停止、重连及销毁隔离旧 capture 和宿主消息 ID，清空待发送数据。队列有界，拥塞结束会话；关闭 socket 后等待发送线程退出。停止时可能已有一个正在发送的包，不能保证撤回该包。小米协议仍未开放麦克风能力。

验证：接收层 40 项、插件 12 项测试通过，0 失败；插件 APK 构建成功并覆盖安装到 vivo。真实七通道 loopback 验证开始命令、PCM 内容、上行类型和时间戳、停止及旧会话数据丢弃；单元测试覆盖重复启停、无效 PCM、拥塞与关闭。以上属于协议和管道测试，不代表设备语音识别验收。

实机重新连接后收到画面与媒体数据，功能表发送记录包含 VOICE_MIC=0；点击 vivo 语音助手会触发媒体暂停，但本轮没有观察到 65570／65572 或 MICROPHONE_START／MICROPHONE_SENT。不能确认该系统语音助手采用本次实现的 CarLife 录音协议，也不能把手机自身语音能力当作上行成功。Android 麦克风、浏览器麦克风的实机 PCM 到达与受控语音识别均未验收通过，需继续定位 vivo 系统语音入口的采集请求机制。

证据：`.local/carlife-vivo/microphone-build.log`、`microphone-feature.xml`、`microphone-final.xml`、`mic-current.png`、`mic-click-after.png`。结论更新为“上行实现已接入、自动化回归通过，vivo 端到端语音验证仍未通过”。


### 后续用户验收反馈

用户随后确认 vivo 麦克风已验证可用。上一节“未通过”是当时自动化实机尝试的状态，现补充用户验收结论；用户未提供具体输入路由与识别记录，不扩展推断为所有路由均完成验收。小米麦克风现已接入，详见 [小米麦克风上行](CARLIFE_XIAOMI.md)。


## Pixel 3 跨设备双指触控验收（2026-11-16）

vivo 运行 WheelPlay 和 CarLife 源，Pixel 3 的 Chrome 通过局域网直接访问 vivo 的 8080 端口。使用 Android 输入注入在 Pixel 上生成双指手势，浏览器记录到 isTrusted=true 的两个 pointerId；媒体不经过电脑转发，也不在 vivo 本机浏览器播放。

vivo 插件现声明两个触点。单指按下／移动／抬起保留原厂独立消息，双指阶段通过 425985 发送 action 与两组坐标（261/262 表示第二指按下／抬起）。全程使用通用报文曾导致普通点击不可靠，最终版本已恢复单指消息路径。主指先抬起时，结束旧手势并将剩余触点重新映射到主指，避免 vivo 单指 MOVE 重置 pointerId 后状态不一致；这不保证主指切换时手势完全连续。取消、尺寸变化和释放均清理触点。小米仍声明单指，未据此推断其多指兼容性。

最终安装版本在 Pixel 上完成高德地图双指放大、缩小，截图显示实际地图范围变化；visualViewport.scale 始终为 1，确认不是网页缩放。另注入主指先抬起和双指取消，浏览器记录对应 pointerup/pointercancel，随后普通点击成功打开 vivo 设置页，无触点卡住。浏览器解码尺寸 2160×848，视频持续播放。此次只验收触控，不作为跨设备音频或声学回授测试。

接收器 42 项、插件 14 项测试通过，0 失败，插件构建成功。证据保存在 .local/carlife-vivo/：multitouch-build.log、pixel-final-events.json、pixel-final-build-status.xml、pixel-final-zoom-in.png、pixel-final-zoom-out.png、pixel-final-after-release.png。

后续调整：按用户要求取消品牌门禁，小米与 vivo 统一启用上述双指实现（最多两个触点）。前述“小米仍声明单指”为当时验收版本状态；小米实机多指验证仍待完成。

## vivo 本机使用 4.x 加密模式实机验收（2026-11-16）

此前品牌选择仅作用于远端，本机固定按环境选择。新增独立 localProtocol 覆盖项，默认仍按本机环境；选择“小米兼容 · 4.x 加密（实验）”后，在 vivo 本机使用 XIAOMI_4_1 协议，源地址仍为 127.0.0.1。系统准备页仍打开 vivo 车载入口，不因协议覆盖而跳转 CarWith。新版已覆盖安装到 vivo 并保留该设置。

明确的新会话日志：SOURCE_PROFILE brand=xiaomi protocol=XIAOMI_4_1 host=127.0.0.1，VERSION_MATCHED 4.1 XIAOMI，CONTENT_ENCRYPTION=1，XIAOMI_ENCRYPTION_READY，XIAOMI_PHONE_ADMITTED，ENCODER_CONFIRMED 2160x848 XIAOMI，FIRST_VIDEO。日志中的 xiaomi 为所选兼容分支，并非远端品牌识别结果。不存在 3.0 回退。

Pixel Chrome 仅访问 vivo WebUI 显示，避免源手机本机浏览器播放形成音频回环。浏览器确认解码 2160×848，普通点击打开高德地图；双指展开成功放大地图并出现建筑细节，日志包含 261/262 双指事件。帧计数持续超过 1500。首次系统准备超时后重新操作；首次显示端短暂卡帧后重新加载/配对恢复，接收器期间继续收帧。当前保留加密投影连接，不能据此声称长期稳定性已验收。

结论：本台 vivo 的本机投影可使用原标“小米”的 4.x 加密模式，版本/加密握手、出画、普通触控和双指缩放通过；本轮未重验非静音音乐与麦克风。插件及接收器回归测试、插件构建通过。证据 .local/carlife-vivo/local-4x-build.log、local4-status.xml、local4-final.xml、local4-reloaded.png、local4-map.png、local4-pinch.png。

## 独立协议兼容模式双协议复测（2026-11-16）

在新版去品牌化界面中选择「本机」，分别使用独立的「3.0 · 明文兼容」和「4.1 · 加密兼容」重新建立会话。源地址均为 127.0.0.1；系统准备仍从 vivo 连接页开始，取消蓝牙检查后返回插件，点击「准备完成，保存并连接」。未使用 ADB 启动系统 CarLife 服务。Pixel 3 Chrome 经局域网访问 vivo WebUI，电脑仅用于操作及诊断，未转发本轮媒体。

| 检查项 | 3.0 明文兼容 | 4.1 加密兼容 |
| --- | --- | --- |
| 新会话协议 | SOURCE_PROTOCOL mode=3.0 plaintext；VERSION_MATCHED 3.0 | SOURCE_PROTOCOL mode=4.1 encrypted；VERSION_MATCHED 4.1，无 3.0 回退 |
| 认证／加密 | AUTH_RESULT status=1 | CONTENT_ENCRYPTION=1、XIAOMI_ENCRYPTION_READY、XIAOMI_PHONE_ADMITTED |
| 实际浏览器视频 | 2160×848，采样播放时间由 0.70 秒增至 115.21 秒，累计 1816 帧 | 2160×848，采样播放时间由 8.79 秒增至 157.44 秒，累计帧由 225 增至 2526 |
| 点击与双指 | 打开应用列表及地图，双指展开后实际地图范围改变 | 打开应用列表及地图，双指展开、地图拖动、手势结束后再次点击均正确响应 |
| 音乐下行 | 44100 Hz 双声道；播放采样 309 个 buffer，其中 289 个非静音 | 44100 Hz 双声道；播放采样 306 个 buffer，其中 297 个非静音 |
| 结束状态 | 正常停止，STOPPED_CLEAN，随后切换 4.1 | 保留连接，音乐已暂停 |

两个模式均记录 261/262 双触点事件，浏览器 visualViewport.scale 为 1，地图变化不是网页缩放。音频以浏览器 Web Audio 实际调度的非静音 PCM 为证据，没有保存音频内容；不是扬声器听感或音画同步验收。3.0 最终累计非静音 buffer 为 319，4.1 为 323，暂停后后续调度主要为静音数据。

本轮功能验证通过，但浏览器仍有丢帧：上述最终样本分别为 206 和 309 个累计丢帧，不宣称稳定 30 fps 或长期稳定性通过。未重新验收麦克风识别、导航播报和多轮断线恢复。启动显示时曾操作到 Pixel 的旧后台标签，切到实际前台标签点击「启动显示」后出画；4.1 切换沿用同一前台页面，无需刷新或重新配对。

证据位于 `.local/carlife-vivo/`：`protocol30-start.xml`、`protocol30-final.xml`、`protocol30-stopped.xml`、`protocol41-start.xml`、`protocol41-final.xml`、`protocol41-settings.xml`，以及 `protocol30/41-video-*.json`、`protocol30/41-audio.json` 和对应 home/grid/map/pinch 截图。原始设备日志与截图仅保留本地。本轮未修改实现代码。

## 自动协议模式实机验证（2026-11-16）

覆盖安装自动模式版本，在独立协议选择区选「自动（推荐）」并通过正常系统准备入口连接。日志为 SOURCE_PROTOCOL mode=auto (4.1 preferred)、SOURCE_ATTEMPT generation=1 protocol=XIAOMI_4_1、VERSION_MATCHED 4.1、XIAOMI_ENCRYPTION_READY、XIAOMI_PHONE_ADMITTED 及 FIRST_VIDEO。没有降级。

Pixel Chrome 解码 2160×848，两个样本间播放时间由 11.59 秒增至 34.28 秒，累计视频帧由 256 增至 568；点击应用列表有正确响应。当前保留自动设置及该加密连接。本轮没有重复音频／麦克风验收，也不以本台同时支持两协议的设备证明旧源降级兼容性；降级分支由七通道 TCP 集成测试覆盖。

接收器 49 项和插件 18 项测试全部通过，APK 构建成功，git diff --check 通过。证据：.local/carlife-vivo/auto-build-final.log、auto-selected.png、auto-settings.xml、auto-start.xml、auto-video-first.json、auto-video-final.json、auto-home.png、auto-touch.png。
