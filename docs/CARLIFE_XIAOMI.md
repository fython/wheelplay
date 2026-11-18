# 小米 CarWith TCP 实验适配

> 历史调试记录：独立探针和插件模块已移除，当前代码、构建命令与架构见 [CarLife 内置实现](CARLIFE_WIFI.md)。下文旧模块路径及安装命令仅用于还原当时的验证环境。

## 实现依据与范围

参考 [wangxso/mcarplay](https://github.com/wangxso/mcarplay/tree/8e25e0f69ab7a3bec14649542adea6eb22f74f9a)，固定提交 `8e25e0f69ab7a3bec14649542adea6eb22f74f9a`（2026-11-16 核对 origin HEAD）。主要依据 `CarLifeLauncher.kt`、`CarLifeStateMachine.kt`、`CarLifeAuthentication.kt`、`CarLifeSeedExchange.kt`、`CarLifeProtocol.kt` 和 `CarLifeAudioPayload.kt`。该项目为 MIT，许可已随探针/插件资产 `mcarplay-LICENSE.txt` 提供。新增 Java 实现按其协议流程改编，未复制其 CarPlay/蓝牙/Wi-Fi 接收端业务。

本轮实现的是手机本机 CarWith → 七路 TCP → WheelPlay 插件，不是 mixing，也不是跨设备无线启动流程。mcarplay 的实机/AVD兼容矩阵不等于 WheelPlay 实测。

## 与 vivo 隔离

`CarLifeProtocolMode` 显式区分 `LEGACY_3_0` 与 `XIAOMI_4_1`。原 Session、Recovery 构造器继续默认 3.0；仅 `CarLifeEnvironment` 检测到 xiaomi 时，插件向整个会话及恢复重试传递 4.1。没有全局修改 `Wire.version/features/statistics/device/video`，没有根据偶发数据包自动换协议，没有失败后降级重试。

vivo 保留原入口、3.0 明文、CONTENT_ENCRYPTION=0、DISABLE_RESOLUTION_OPTION、音频和手动尺寸重连策略。其他品牌、未知或歧义环境不自动启用小米路径。每次恢复尝试创建新握手与新 RSA 密钥；上次会话密钥不会复用。

## 小米连接入口

在前台插件准备页显示“启动 CarWith 连接”，仅调用可见、启用、导出且当前应用有权访问的：

`com.miui.carlink/com.miui.carlink.castfwk.usb.CarlifeAdbActivity`

名称中含 Adb，但通过普通 startActivity 调用，不需要电脑转发媒体。不会从后台服务自动弹窗，startActivity 返回也不标记连接成功。旧版没有公开入口时，保留 `UCarScreenSettingsActivity`/宿主启动页/系统设置的手动路径；本轮不集成需要 root 的旧版 CarlinkService 启动。用户完成系统准备并返回继续后，由正式七通道会话确认源是否就绪，不执行连上再关闭的端口探测。

## 协议流程

1. 4.1 版本匹配与小米统计信息（channel=29999999）。
2. 解析 MD_INFO 的 brand/model/sdk；发送新随机 `2.0;nonce` 挑战，按 mcarplay 的 CarLife 2.0 MD5 绑定规则验证响应。拒绝缺字段、错误证明和乱序；不伪造认证通过。
3. 收到功能请求且证明通过后声明 CONTENT_ENCRYPTION=1 和 DISABLE_RESOLUTION_OPTION=1；手机准入单独确认，拒绝时停止。
4. 临时 RSA-2048 公钥交换，PKCS1Padding 解密 AES 种子，只接受 16/24/32 字节。等待 ENCRYPT_READY 和手机准入均通过才请求视频初始化。
5. 按协议使用 AES/ECB/PKCS5Padding 处理非空命令、视频、触控和音频初始化载荷；空载荷保持为空。仅小米 9240/0x30006 混音 PCM 保持明文，导航 PCM 仍解密。解密失败绝不退回明文。
6. 以实际编码尺寸建立输出与触控映射；小米不启用在线尺寸重建，继续使用已有手动重连提示。

诊断只记录品牌、协议、阶段、消息 ID 和错误，不记录挑战证明、密钥或设备信息。连接超时、首帧超时与资源清理沿用现有接收层。

## 验证与限制

命令：

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew \
  :carlife-receiver:test :projection-plugin-carlife:testDebugUnitTest \
  :projection-plugin-carlife:assembleDebug :carlife-probe:assembleDebug
```

新增测试：认证失败/缺字段、过早 RSA、无效 AES 种子长度、重复 READY、延迟手机准入、密文损坏、小米 PCM 例外、品牌选择隔离。七通道 localhost 集成测试实际运行 Session，验证 4.1 握手、加密视频 INIT/视频载荷、媒体/导航 PCM、加密 DOWN/停止时 UP；另一套使用旧构造器校验 vivo 3.0 原始报文字节与明文音视频/触控。

小米实机验证已补充于下文；已验证的项目仅适用于所列设备和系统版本。公开入口缺失的旧版需手动准备；系统自身 SDK/版本限制不由本实现绕过。音频听感、导航播报、尺寸重连与后台恢复仍须单独验收。

### 本轮结果（2026-11-16）

- 47 项接收层/插件测试通过，0 失败；探针与插件 APK 构建成功；`git diff --check` 通过。
- 新版插件和共享新版接收层的探针均已安装到现有 vivo V2547A。通过原系统入口及跳过蓝牙步骤，以 3.0 握手，确认 1280×720，真实桌面解码成功；点击应用列表和横向翻页视觉确认有效。
- 探针运行约 111 秒，最后计数 1621 视频包、已报告渲染 1500 帧，停止后记录 STOPPED_CLEAN。包数和渲染日志不是持续帧率保证。本轮实机使用视频探针，不包含浏览器端音频听感、长时间播放或后台恢复复测；音频路径回归由七通道集成测试覆盖。
- 本地证据：`.local/carlife-vivo/xiaomi-build.log`、`xiaomi-regression-vivo.log`、`xiaomi-regression-vivo.png`、`xiaomi-regression-vivo-touch.png`、`xiaomi-regression-vivo-swipe.png`。画面可能包含个人信息，不提交截图。测试后已停止探针并返回 WheelPlay。


## 小米实机验证（2026-11-16）

设备为 Xiaomi 2509FPN0BC（popsicle），Android 17 / CP2A.260605.016，CarWith 4.0.20-20260923（104000020）。使用已安装的当前主程序与实验插件；浏览器通过局域网直连手机 8080，ADB forward 列表为空，没有电脑媒体转发。本轮没有修改协议代码或重新构建 APK。

### 已确认

- 从插件准备页点击「启动 CarWith 连接」→「已手动连接，继续」可完成七通道 TCP、VERSION_MATCHED 4.1 XIAOMI、RSA/AES 交换、XIAOMI_ENCRYPTION_READY、XIAOMI_PHONE_ADMITTED、ENCODER_CONFIRMED 1280x720 和 FIRST_VIDEO。公开入口调用无需 root 或 ADB 服务启动。
- 浏览器实际 videoWidth/videoHeight 为 1280×720，能显示真实地图桌面、应用列表；取消布局介绍、打开应用列表和返回主页都有画面响应。此结论覆盖点击，不包含拖动、多点触控或其他尺寸映射。
- 16:21:42–16:29:22 的接收日志计数从 8 增至 7223；期间浏览器实际解码与画面响应也得到确认，不把源端包计数当作浏览器帧率。
- 点击音乐播放后，浏览器 AudioContext=running，44100 Hz 双声道，非静音 buffer 计数从 1479 增至 1839；本地第一轮记录也有 AUDIO_PAUSE 和随后重新建立 AUDIO_FORMAT。仅证明 PCM 到浏览器播放队列的链路，未人工验收扬声器听感、音画同步或真实导航播报。
- 点击停止投影后记录 STOPPED_CLEAN。停止后重新连接可重新握手，但默认省电策略下存在下述冻结问题。

### 小米省电策略影响与复测

默认「智能限制后台运行（推荐）」下，16:29:55 新会话完成握手并收到首帧后停在启动画面，浏览器只有 4 帧。即使 WheelPlay 主程序在前台也未自行恢复。16:30:51 重新打开插件设置 Activity 后，系统 GreezeManager 记录插件 UID 的 THAW，接收线程立即继续，5 秒内计数达到 380，画面恢复。Android 标准 dumpsys 的 isFrozen=false 不能排除厂商冻结；厂商日志和前台解冻的对应时间才是本轮证据。

仅将「WheelPlay CarLife 实验版」的应用信息→省电策略改为「无限制」，未调整其他应用的策略或开启自启动。16:32:32 按相同准备流程重连，直接持续出流，不需要额外打开插件页面解冻。浏览器 75.951 秒采样窗口内 totalVideoFrames 从 547 增至 1608、droppedVideoFrames 从 23 增至 47，约 14.0 总帧/秒、扣除丢帧约 13.7 帧/秒；实际尺寸保持 1280×720，应用列表点击有效。该结果只覆盖短时前台使用及一次取消限制后的正常重连，不代表五次重连、锁屏或长时间后台可靠性已通过。

测试结束于 16:35:25，停止投影记录 STOPPED_CLEAN，并关闭测试浏览器。测试机插件省电策略保留为「无限制」，便于继续验证。若保持默认策略，临时恢复方法是打开一次插件页面再返回 WheelPlay；产品仍需补充小米使用引导并继续验证后台生命周期，不能把此问题误归为认证失败。

证据保存在忽略目录 `.local/carlife-xiaomi/`：`continued-session.log`、`browser-current.json`、`grid.png`、`reconnected.log/json/png`、`reconnect-freeze-events.txt`、`plugin-foreground.png`、`unrestricted-layout.json`、`unrestricted-session.log`、`unrestricted-first.json`、`unrestricted-final.json`、`unrestricted-home.png`、`unrestricted-grid.png`。截图可能包含个人地图和音乐信息，不提交。


## DISABLE_RESOLUTION_OPTION 尺寸适配（2026-11-16）

上轮设备状态记录实际为 `DISPLAY_NEGOTIATED requested=1280x632 confirmed=1280x720`，不能把已收到 720p 画面理解为尺寸适配通过。小米原功能表仅包含加密选项，未声明禁用系统分辨率预设。

本轮为小米功能表增加 `DISABLE_RESOLUTION_OPTION=1`，计数改为 2，保留 `CONTENT_ENCRYPTION=1`。功能表仍在认证证明通过后发送，视频请求仍等待加密就绪和手机准入。新增发送后诊断，明确功能表和视频请求的顺序及请求尺寸；vivo 路径未改动。

覆盖安装新版插件后，因宿主留有绑定释放失败状态，先重启 WheelPlay，再从准备页启动 CarWith 并继续。沿用已连接浏览器提供的尺寸，未删除车辆记录或修改系统分辨率。实机记录：

- 16:44:25.941：`FEATURES_SENT DISABLE_RESOLUTION_OPTION=1 CONTENT_ENCRYPTION=1 XIAOMI`。
- 16:44:26.020：`VIDEO_REQUESTED 1728x1192 XIAOMI`。
- 16:44:26.112：`ENCODER_CONFIRMED 1728x1192 XIAOMI`。
- 系统 CarWith 虚拟屏幕 `com.miui.carlink`（displayId 17）的 real/app 均为 1728×1192，首帧到达且接收计数持续增加。

因此本机连接时采用 WebUI 经像素预算和 8 像素对齐后的尺寸已验证，不能将其表述为原始浏览器像素逐一对应。本轮独立测试浏览器未完成配对，未另行读取现有浏览器的 videoWidth/videoHeight；虚拟屏幕尺寸与协议回报已核对，浏览器解码尺寸不作独立验收通过。在线窗口变化仍需手动重连，未改为无缝重配。

七通道集成测试校验两项功能字段和值，同时保留加密音视频与触控、vivo 原协议回归。接收层 37 项、插件 10 项测试通过，0 失败；插件 APK 构建安装成功，`git diff --check` 通过。证据：`.local/carlife-xiaomi/resolution-build.log`、`resolution-session.log`、`resolution-status.xml`、`resolution-display.txt`。


## 麦克风上行（2026-11-16）

小米与 vivo 均启用宿主 microphone 能力，复用手机／浏览器麦克风选择、权限请求、按需采集、停止和重连隔离。小米功能表在原有两项基础上增加 VOICE_MIC=0（共三项），表示使用车机麦克风；仅在已完成握手并开始视频会话后接受录音开始命令。

小米沿用 65570／65572 开始、65571 停止以及 16 kHz 单声道 PCM16。上行 9242 的 0x58001 帧保留 12 字节明文头，PCM 使用当前会话 AES/ECB/PKCS5Padding 加密；头部长度按密文重新计算，时间戳仍按原 PCM 采样数递增。640 字节 PCM 加密后为 656 字节。无密钥或会话关闭时拒绝发送，不回退明文。语音接收解密依据本地 CarLife ReceiveDataThread 的加密分支；本轮没有独立取得小米语音接收端样本，实际兼容性仍需小米实测确认。

真实七通道 loopback 新增小米麦克风测试，覆盖功能表、认证前不采集、加密 PCM 保真、报文长度、20 ms 时间戳和停止后不再发送；原 vivo 明文麦克风回归通过。接收层 41 项、插件 12 项测试通过，插件 APK 构建成功。证据：`.local/carlife-xiaomi/microphone-build.log`。

本轮小米设备未出现在 ADB 列表，未安装或进行小米实机语音识别验收。vivo 麦克风已由用户反馈验证可用；该反馈不等同于小米验证通过。


## 统一双指触控（2026-11-16）

取消按品牌限制触点数量，小米与 vivo 均声明最多两个触点，使用同一套触点映射、双指报文和释放逻辑；小米报文仍经过其原有加密路径。设置页统一显示支持双指缩放。vivo 已通过 Pixel 3 浏览器实机验收，小米已启用此功能，尚未进行小米双指实机验收。
