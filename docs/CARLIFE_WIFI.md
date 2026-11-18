# CarLife 跨手机 Wi-Fi 验证

## 2026-11-17：协议与接入完整迁入 common

已移除 `carlife-receiver`、`projection-plugin-carlife`、`carlife-probe` 三个模块。CarLife 以原生输入源直接注册，协议、设置、音频和媒体控制均在 `common` 内；不再启动独立进程或通过插件 IPC 传输。

验证：完整 `common` 单元测试 665 项通过；之后补充固定尺寸模式回归，最终 63 项 CarLife / 音频线程定向测试通过。`common:lintDebug`、`mobile:lintDebug`、`mobile:assembleDebug`、actionlint 和 diff 检查通过。页面入口与设置跳转的 Compose 测试通过。最终主 APK 已安装到 vivo；本轮尚未重新连接真实 CarLife 源验证音视频、触控和恢复。模拟器系统 Activity 服务发生超时，未作为本轮设备界面验收证据。


## 2026-11-17：新版输入源页面的 vivo 本机复验

vivo V2547A 覆盖安装当前 APK，从 CarLife 输入源的「本机 → 连接本机 CarLife」进入准备页；本机选中状态正确，打开 vivo 系统连接设置、开始连接并取消蓝牙检查后，返回保存连接成功。Pixel Chrome 作为 Wi-Fi 显示端，启动显示后实际解码尺寸为 2160×848，点击应用列表成功切换画面。vivo 的 CarLife 七通道全部为 127.0.0.1 本机连接。点击新版页面「停止投影」后恢复未连接状态，七通道均释放。

本轮验证覆盖页面模式联动、系统准备、出画、单击触控和主动停止；未重复音频、双指、重连及长期稳定性验收。终态为 vivo 本机模式已选中、投影已停止。截图位于 `.local/carlife-builtin/new-ui-vivo-local-verified.png` 和 `new-ui-vivo-local-touch.png`。

## 2026-11-17：内置输入源

CarLife 接收器现在随 WheelPlay 主 APK 分发，无需安装或启用独立插件。在输入源列表选择 CarLife，点击连接后按准备页操作；本机、远端发现、手动地址、协议模式以及音视频与触控流程沿用原实现。

CarLife 已完整迁入 `common`：协议代码位于 `common/src/main/java/moe/feng/wheelplay/carlife`，Android 接入和设置位于 `common/src/main/java/moe/feng/wheelplay/projection/carlife`。`CarLifeSource` / `CarLifeProjectionSession` 直接实现内置输入源接口，音视频和媒体状态在宿主进程内传递，不经过插件 Binder 或管道。会话生命周期由宿主的 `ProjectionRuntime` 和前台服务管理，不再有独立 CarLife 服务、进程或状态 provider。设置仍为私有 Activity，原连接配置保持兼容。

`carlife-receiver`、`projection-plugin-carlife` 和 `carlife-probe` 模块均已移除，协议和接入测试迁入 `common`，由主程序 CI 一起执行：

```sh
./gradlew :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

本机 CarLife 仅转发浏览器媒体状态，不激活 WheelPlay 的 Android Media Session；远端模式保留本机媒体控制。PCM 播放队列仍有容量上限，拥堵时丢弃旧 PCM；语音采集按请求启动并隔离前后两次捕获。第三方许可见 [CARLIFE_NOTICE](../common/CARLIFE_NOTICE.md)。

以下分阶段测试数量、进程名称和独立插件操作均为迁移前的历史记录。

已安装的旧官方 CarLife 插件不再被发现，原首页默认选择迁移为内置 CarLife。旧插件私有设置不会跨应用自动读取，首次使用需重新选择连接配置。下文是历史插件版本的实机记录，不代表内置版本已完成实机验收。

本次迁移验证：49 项接收器测试、18 项 CarLife 模块测试、71 项宿主相关测试通过；主程序 `assembleProvisionedDebug`、`lintDebug` 和 CarLife 模块 lint 通过，workflow actionlint 与 diff 检查通过。模拟器未安装独立 CarLife 插件，已验证内置入口显示可连接、打开连接设置、保存后返回当前 WheelPlay；真实 CarLife 源的音视频、触控和停止重连仍需实机回归。

## 2026-11-17：内置版双设备回归

Pixel 3 与 vivo V2547A 均覆盖安装内置版主程序。旧独立 CarLife APK 未用于接收；实际服务进程为主程序包下的 `:carlife`。手机间媒体直接走 Wi-Fi；ADB 用于安装、界面操作、日志和 Chrome DevTools，不转发投影媒体。

| 场景 | 验证结果 |
| --- | --- |
| Pixel 接收 vivo | 原生 UDP 广播发现 vivo，选择地址后使用自动模式协商 4.1；七通道对端为 Pixel 的局域网地址。浏览器解码 2160×848，单击打开应用列表与地图，双指实际放大地图，浏览器缩放保持 1。播放音乐后接收端 44.1 kHz 双声道 PCM 非静音计数持续增长。音频修复后连续样本播放时间 115 秒、1648 帧；后续握手修复版再次连接，31 秒、532 帧。 |
| vivo 本机接收 | 从内置准备页打开系统入口，开始连接、取消蓝牙检查并返回继续。七通道均为 `127.0.0.1`，自动模式协商 4.1。Pixel Chrome 访问 vivo 局域网地址，解码 2160×848；应用切换、地图双指放大通过。持续样本 206 秒、2740 帧，浏览器 Web Audio 调度 10295 个 buffer，其中 7894 个非静音。 |
| 停止与重连 | 主动停止记录 `STOPPED_CLEAN`，私有接收进程退出而主程序存活。重新完成源端系统准备后，两种接收路径均恢复出画；vivo 本机再次连接样本 2160×848、190 帧。 |

本轮定位并修复两个问题：

- Pixel 的 Android 音频播放在 PCM 短时积压时出现 `Plugin audio playback queue overflow`，导致整个输入源结束。播放队列现在保持固定容量，对可独立解码的 PCM 丢弃最旧数据以追赶实时播放；编码音频仍保留严格溢出报错。三项回归测试覆盖 PCM 突发、正常顺序和编码数据不被静默丢弃。
- 一次 4.1 重连在收到 AES 种子后、加密就绪前出现解密长度错误。结合源端在收到 ACK 后才开启加密的实现，将控制通道的解密边界改为源端 READY；READY 前允许尚在途中的明文控制包，之后损坏密文仍直接失败，绝不尝试明文回退。新增握手时序回归测试，后续 Pixel 连接及 vivo 本机两次连接均通过。

最终 50 项协议测试、18 项 CarLife 模块测试、74 项宿主相关测试通过，主 APK 构建、主程序 lint 和 diff 检查通过。两台实机已安装包含上述两处修复的 APK。终态保留 vivo 本机接收和 Pixel 浏览器显示，音乐暂停；Pixel 的跨手机连接配置保留。

边界：本轮音频以接收及浏览器调度的非静音 PCM 为证据，未做扬声器听感、音画同步或受控语音识别验收。最终视频样本仍有丢帧（Pixel 接收样本 158 帧，vivo 本机样本 199 帧），不宣称稳定 30 fps 或长期稳定性。未覆盖强制 3.0、断网恢复和全部麦克风输入路由。

证据保存在 `.local/carlife-builtin/`：`pixel-fixed-final.*`、`pixel-handshake-fixed.*`、`pixel-retry-final.json`、`pixel-stopped.xml`、`vivo-fixed-remote-sockets.txt`、`vivo-local-sockets.txt`、`vivo-local-final.*`、`vivo-local-long.json`、`vivo-local-stopped.xml`、`vivo-local-reconnected.*`、地图缩放前后截图及 `final-checks.log`。原始截图和日志只保留本地。

## 2026-11-16：vivo 源 → Pixel 3 WheelPlay

本轮已验证同一局域网内的手动地址连接。vivo（192.168.32.226）运行系统 CarLife 源；Pixel 3（192.168.32.153）运行 WheelPlay 和 CarLife 插件。Pixel Chrome 访问本机 http://127.0.0.1:8080/ 显示接收画面。两机 USB 线仅用于安装、日志和输入注入；CarLife 七通道直接走 Wi-Fi，没有使用 adb 转发媒体。测试中曾用 adb 转发 Pixel 8080 端口做 HTTP 可达性检查，该转发不承载投影链路。

### 操作步骤

1. 两台手机加入同一 Wi-Fi，保持解锁。停止源手机上的 WheelPlay CarLife 接收，避免争用系统源。
2. vivo 打开系统的「车机热点连接百度 CarLife+」页面，点击开始连接，在蓝牙检查弹窗中取消，然后返回桌面。
3. Pixel 安装并启用 CarLife 插件。在插件准备页选择「另一台手机 · Wi-Fi」，填入 vivo 的 IPv4 地址，源手机系统选择 vivo，然后继续。
4. 在 Pixel 的 WheelPlay 中连接 CarLife，并配对、启动浏览器显示。当前插件等待有效浏览器尺寸后才开始接收。

### 实机结果

- vivo 的七个已建立 TCP 连接（7240、8240、9240、9241、9242、9340、9440）对端均为 Pixel 的 192.168.32.153。
- Pixel 日志记录远端 host、SEVEN_CHANNELS_CONNECTED、VERSION_MATCHED 3.0、AUTH_RESULT status=1、FIRST_VIDEO。
- 请求与确认尺寸均为 2160×848，Chrome videoWidth/videoHeight 同值。帧数持续增长至 2257，视频字节数超过 32 MB，验证期间未触发恢复重连。
- Pixel 普通点击可切换到高德地图和 QQ 音乐；双指展开后地图范围实际放大。浏览器缩放比例为 1。
- 播放音乐后 Pixel 收到 44.1 kHz 双声道 PCM16，nonSilent 从 0 增至 579。仅证明音频数据跨 Wi-Fi 到达接收器；未做扬声器听感或麦克风识别验收。随后暂停测试音乐。

### 实现与边界

接收层和恢复层新增显式源地址参数，原有构造函数仍默认 127.0.0.1。插件保存本机／远端选择，远端协议由用户选择的源手机系统决定，不依据 Pixel 品牌猜测。远端地址限制为 IPv4；设置变更需重新连接。小米远端模式可选，但本轮未实机测试。

当前实现是接收器按地址连接源手机监听端口，不包含标准无线 CarLife 的自动发现、蓝牙引导或热点建联。两机同一 Wi-Fi 已通过；Pixel 热点、Wi-Fi Direct、跨网段、长期稳定性、断网恢复和 USB 均未在本轮验收。Pixel 原有 Tesla VPN 未改动，实际 CarLife socket 对端仍为 Wi-Fi 地址。

接收器 43 项、插件 15 项测试通过（包括显式源地址的七通道协议测试和地址校验）；主程序、插件构建通过，git diff --check 通过。实机使用含 Wi-Fi 接收实现的 APK，之后仅补充说明文案与测试并完成构建，保留当前工作连接未重新安装。

证据位于 .local/carlife-vivo/：wifi-pixel-status.xml、wifi-pixel-final.xml、wifi-vivo-sockets.txt、wifi-pixel-video.json、wifi-pixel-first.png、wifi-pixel-map.png、wifi-pixel-pinch.png、wifi-pixel-music.png、wifi-pixel-final.png、wifi-final-build.log、wifi-tests.log、wifi-config-tests.log。

## UDP 自动发现（2026-11-16 后续）

源端 ConnectManager.sendHotspotUdp 在等待连接时向接口广播地址 UDP 7999 发送精确内容 `CarlifeHost`。插件准备页现默认监听该广播 20 秒，按报文来源 IPv4 去重展示可选手机；不主动探测或占用源端 TCP 通道。可重新搜索，页面离开时关闭监听；保留手动输入 IP 的折叠备用入口和上次保存的地址。用户点选后再继续，发现不自动发起投影。广播不提供名称、品牌或认证，列表用地址区分，源手机系统仍由用户选择。

构建与插件 16 项测试通过。Pixel 实机收到电脑在同一 Wi-Fi 广播的相同测试报文，显示 192.168.32.185 并可点选；这是接收/UI 测试，不是 vivo 自动发现实机验收。测试发现地址未保存为投影源。此时 vivo 已不在 USB 设备列表，旧 Wi-Fi 地址也不可达，故未能重新启动 vivo 等待连接并验证原生广播。上一节手动 IP 的 vivo 视频/触控/音频链路验收仍有效。证据：discovery-build.log、discovery-pixel.png。

### vivo 原生广播实机补验通过

同日 vivo 重新上线并由用户解锁后，从系统无线 CarLife 页面开始连接，取消蓝牙检查并返回桌面。Pixel 点击重新搜索后，列表真实出现 `CarLife 手机 · 192.168.32.226`；本轮未发送电脑模拟广播，也未输入源手机 IP。点选该列表项保存，回到 WheelPlay 前台连接插件并完成准备，日志记录新一轮七通道连接、版本匹配、认证成功以及 2160×848 首帧。

浏览器旧页配对/启动状态曾阻碍播放，重载并重新配对、启动显示后，Pixel Chrome 解码尺寸确认 2160×848，显示 vivo 实际车载界面。随后 Pixel 点击地图图标完成反向触控验证。自动发现到投影连接链路实机通过，当前保留连接。此结果补齐上一节未完成的 vivo 原生广播验收，不扩大为小米自动发现或 USB 验收。

证据：discovery-vivo-real.png、discovery-real-status.xml、discovery-real-final.xml、discovery-real-sockets.txt、discovery-real-browser.json、discovery-real-video.png、discovery-real-touch.png（均位于 .local/carlife-vivo/）。

### 多地址选择界面

候选地址以单选列表逐行展示，按 IP 去重并显示发现数量；每行支持整行点选，并标记当前选择。已保存或手动指定但本轮未发现的地址单独标注，不宣称在线。广播没有设备身份字段，因此不同 IP 分别展示，不推断它们属于同一手机。本次界面调整已构建通过，未覆盖安装到正在投影的 Pixel。

### 覆盖安装与热点多地址验证

已按用户要求将地址单选列表版本覆盖安装到 Pixel 3。开启系统热点 Pixel_carlink 后，wlan0 仍保留 192.168.32.153/24，wlan1 新增 192.168.95.20/24，证实 Wi-Fi 与热点可同时拥有不同的局域网 IPv4。另有 tun1 的 192.168.229.229/32 VPN 地址，以及处于 DOWN 状态的 tun0 地址，不计入这两个真实局域网地址。

WheelPlay 首页「其他地址」同时显示两个局域网 HTTP URL；VPN 地址另标注“通常无法从局域网访问”。wlan1 当前被通用接口分类标为 Wi-Fi，结合热点开关前后接口变化可确认它是本轮新增的热点接口。CarLife 插件新版列表显示“1 个已发现”、vivo 的 192.168.32.226、“本轮发现”和“已选择”，热点开启不影响此轮来自原 Wi-Fi 网络的真实发现。

本机访问地址与远端 CarLife 源地址属于两个列表；本轮并未观察到 vivo 同时广播多个源 IP，也未让 vivo 切入 Pixel 热点测试投影。热点保持开启供用户继续验证。安装替换了旧投影插件，本轮终态在插件列表页，未重新启动视频。证据：pixel-hotspot-addresses.txt、hotspot-local-address-list.png、hotspot-source-address-list.png。

### 协议模式界面去品牌化

本机和远端统一使用独立的“协议兼容模式”区，提供“3.0 · 明文兼容”和“4.1 · 加密兼容”，取消作为协议选择器的 vivo／小米品牌标签。系统准备入口仍依据实际本机环境；当前没有自动协议探测，界面明确保存后下次连接生效。旧品牌与本机覆盖设置按原有效协议映射，新协议设置优先；迁移回归测试覆盖旧 vivo、小米、本机预设和显式覆盖。连接配置已拆分为地址、协议与麦克风能力，不再借用虚假的手机品牌代表协议；会话起始诊断改为 SOURCE_PROTOCOL。

插件构建及测试通过，Pixel 已覆盖安装并检查横屏排版，原小米选择正确显示为 4.1。vivo 当前运行的上轮加密会话未因本次 UI 预览而重装。截图：.local/carlife-vivo/protocol-ui-pixel.png。

### 自动协议选择（2026-11-16）

协议兼容模式新增「自动（推荐）」，本机和远端均可选。新配置默认自动；已有显式 3.0／4.1 和旧品牌设置保留原有效选择。自动先使用 4.1，仅初始版本回复明确为拒绝（65538 的字段 1 为 0）时，在完整关闭旧七通道及工作线程后创建 3.0 连接。只降级一次；已接受 4.1 的会话恢复时仍使用 4.1。认证失败、解密失败、非法或重复版本回复不会降级；网络错误及超时继续现有恢复逻辑并保留当前协议。

降级使用新 TCP 连接，不保证源端保持监听。界面会提示必要时在系统车载中重新开始连接；等待沿用两分钟预算，可随时停止。诊断记录 SOURCE_ATTEMPT 的实际协议和 AUTO_PROTOCOL_FALLBACK 原因。

接收器 49 项、插件 18 项测试通过，包括真实七路 TCP 模拟的 4.1 版本拒绝→关闭→3.0 握手→视频，以及清理屏障、取消、禁止错误降级和设置迁移。构建成功。vivo 已安装并选中自动，实机走 4.1 加密出画及点击通过；旧源设备拒绝 4.1 后的实机降级兼容性待验证。
