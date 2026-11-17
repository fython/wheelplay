# CarLife USB / AOA 验证

日期：2026-11-17。

## 已验证范围

Mac 作为 USB Host，经现有数据线连接 vivo X300 Ultra（V2547A，Android 17，
`com.baidu.carlife.vivo` 8.8.4 / 273）。独立 Python + PyUSB/libusb 探针完成
AOA 切换、CarLife 3.0 握手、认证、1280×720 H.264 视频接收与单指点击。
媒体和触控直接经过 USB Bulk；ADB 仅用于设备诊断与退出配件模式，没有媒体端口转发。

这一阶段是独立探针验证，尚未安装新的 APK 或修改正式接收代码；后续输入源适配见下文。
Pixel 作为 USB Host、宿主权限流程、音频、麦克风、4.1 加密、其他品牌及长期稳定性均未验证。

## 实机结果

- `GET_PROTOCOL` 返回 `02 00`，即 AOA 版本 2。
- 配件匹配字符串为 `Baidu` / `CarLife` / `1.0.0`；vivo 系统注册了对应入口。
- 重枚举为 `18d1:2d01`。接口 0 是 accessory：Bulk IN `0x81`、OUT `0x01`，
  最大包长 512；接口 1 是 ADB，实验没有占用它。
- 命令 `0x18001` 请求 CarLife 3.0，`0x10002` 返回 protobuf `08 01`（接受）。
- 收到设备信息 `0x10004`、认证结果 `0x1004b`（`08 01`）。
- `0x10008` 返回 `08 80 0a 10 d0 05 18 1e`，即 1280×720、30 fps 编码配置。
  这是协商帧率，不是实测吞吐率。
- 第一轮视频采集收到 90 个非空视频包、285,611 字节。FFprobe 识别 H.264 1280×720，
  FFmpeg 全量解码无报错。解码画面包含启动页和实际车载首页。
- 第二轮采集在第 90 个视频包后，通过通道 6 发送 `0x68002` / `0x68003`，
  protobuf 字段 1/2 为坐标 `(61,650)`，按下和抬起间隔约 80 ms。
  解码后续画面确认由首页进入应用列表。共采集 180 个视频包、910,579 字节，解码无报错。
- 两次成功会话之间通过退出并重新进入配件模式重建连接；不等同于真实拔插恢复测试。
  实验结束已退出配件模式，确认 ADB 重新在线。

## USB 封装与实现注意

本次验证的外层是 8 字节：`channel:u32BE + innerLength:u32BE`，随后为内层消息。
命令及输入内层沿用 `bodyLength:u16BE + reserved:u16BE + type:u32BE + body`；
视频内层沿用 `bodyLength:u32BE + timestamp:u32BE + type:u32BE + body`。
实测使用通道 1（命令）、2（视频）、6（输入）。不要据此假定现有 TCP 第七路也已验证。

**发送时将 8 字节外层头和内层消息分为两次 Bulk OUT。** 首次合并发送虽返回写入成功，
15 秒内未收到回应；重建连接并改为分开发送后握手成功。公开参考也指出源端短缓冲读取
可能丢弃同一次 USB transfer 的余下字节。由于两轮还存在启动时机差异，尚不能将失败唯一
归因于合并写入；正式实现应保留已成功的分开发送方式，并做针对性回归。

读取使用 16 KiB 缓冲并累积解析，不能把一次 Bulk read 当成一个完整 CarLife 帧。
正式实现还需限制帧长、保证头和体整体写入串行化、处理短写及关闭时的阻塞读取消。
本次能力配置将 `CONNECT_TYPE` 设为 2；现有 TCP 接收代码使用 5，不能原样照搬。

## 后续集成

1. 抽取 Android Auto 已有 USB 授权、AOA 切换、重新枚举和端点访问逻辑，使用 CarLife 身份。
2. 将 `CarLifeSession` 的 Socket 访问改为逻辑通道接口，保留 TCP 实现，新增 USB 分发实现。
3. 用 USB 接入现有握手、视频、音频和触控；按传输类型生成连接能力配置。
4. 接入输入源设置、USB 资源互斥、拔插和停止清理。在 Pixel → vivo 真正双手机接线下验收。

## 证据及参考

本机忽略目录 `.local/carlife-usb-validation/` 保存 `probe.py`、`handshake.py`、
`video_probe.py`、`touch_probe.py`，以及 `aoa*.log`、`handshake*.log`、`video.log`、
`touch.log`、H.264 样本与解码截图。脚本是绑定本次 vivo 的实验工具，尚非通用发布工具。
截图、媒体及原始设备诊断不提交仓库。

封装参考：[Headswap 协议研究](https://github.com/YegorkaPomidorka/headswap/blob/c9bee7bad17871592af236a69edeb1d79b6674fe/docs/carlife-protocol.md)，
核对其所列 AOA 身份、外层帧头和通道定义；实验的内层消息和握手取自本仓库 `Wire` / `CarLifeSession`。
外部项目仅用于本机研究，没有将其实现复制进产品。

## Android 输入源适配（同日）

已接入正式 CarLife 输入源的实验性 USB 模式：

- 输入源页新增「USB 有线」，准备页提供接线、授权和源手机确认提示；USB 协议选择独立保存，默认自动。
- `CarLifeTransport` 隔离传输。TCP 保留七路 Socket；USB 使用单一 Bulk 读取线程拆外层头，
  分发到有界逻辑通道缓冲。命令、输入、语音写入串行化，外层头与消息体分开发送。
- 与 Android Auto 共用 `AoaUsbConnection`，使用不同配件身份。授权等待可以取消，
  切换后重新授权，明确选择 accessory 接口，避免占用 ADB 接口。只允许一台候选源手机。
- USB 预留宿主 USB 资源，媒体会话按外部源处理；不会误用本机 CarLife 的浏览器音频准备流程。
- 媒体音频、TTS 进入既有 PCM 输出路由；麦克风按源端请求启停，经语音通道发送。
  4.1 继续使用已有 RSA/AES 协商、视频/TTS 解密、媒体音频兼容规则和麦克风/触控加密；
  3.0 与 4.1 在 USB 下均声明 `CONNECT_TYPE=2`。
- USB 非版本拒绝错误直接结束并提示拔插重连，不套用 TCP 两分钟重试。
  自动模式仍只在明确拒绝 4.1 时尝试 3.0；不因认证或解密失败降级。

验证：`:common:testDebugUnitTest` 的 CarLife、`AapUsbConnectionTest`、`SourceNavigationTest`
共 107 项通过；`:mobile:assembleDebug` 成功。新增回环测试通过生产 USB 分帧实现，
完成 3.0 / 4.1 握手、加密视频、媒体音频、TTS、麦克风启停/上行和触控；另覆盖非法帧长、
内外长度不匹配、队列溢出、停止打断等待，以及并发命令/麦克风写入不会交错外层头和消息体。
测试中的 PCM 和加密对端为模拟数据，不代表实机音频听感或语音识别验收。

新版 Debug APK 已安装到 Pixel 3。按用户要求，Pixel 与 vivo 的 USB / OTG 接线由用户人工测试；
不依赖 Pixel 无线调试。代码适配阶段 vivo 未连接电脑，因此没有新增 4.1 USB 实机验证结论。

人工测试入口：输入源 → CarLife → USB 有线 → 连接 USB 手机。允许接收端 USB 授权，
在源手机确认车载连接，并配对浏览器启动显示。先用默认自动协议检查画面、音乐、导航提示音，
再从源端语音入口检查麦克风；若使用 Android 麦克风，请允许 WheelPlay 的录音权限。
需要比较时在 CarLife 设置将 USB 协议切到 3.0 或 4.1，停止后拔插重连。
异常反馈请附所选协议、症状及 CarLife 设置里的「连接诊断」，不要仅以 USB 已授权判断投影成功。

Pixel 安装后已通过 UI 选择 USB 并保存自动协议，确认准备页正常；点击继续后进入「正在等待浏览器画面尺寸」，
说明自适应尺寸仍需浏览器配对并启动显示才开始传输。随后已停止此等待会话，界面保留 USB 选择。
因为没有源手机接入且未启动浏览器，未将这一步计为 USB 授权或无设备错误路径的实机验收。
