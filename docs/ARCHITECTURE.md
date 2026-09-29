# APK 服务端架构

核对日期：2026-09-30。本文描述 WheelPlay 当前实现，非原版车机本地显示模式。

```text
iPhone ─ USB / 无线 CarPlay ─ CarPlayController / CarPlayMediaEngine
                                      │ H.264 / HEVC
                               AndroidMediaSink
                                      │ decoder Surface
                              OffscreenVideoOutput
                                      │ EGL → JPEG；支持压缩帧 WebRTC 直通
                                WebSession 最新帧
                                      │
车机浏览器 ← HTTP/HTTPS 静态资源及配对 WebSocket ← LanWebServer :8080 / :8443
    │ normalized contacts                         │
    └──────────────────────────────────── TouchLease
                                                  │
                                      CarPlayController.sendTouch

iPhone 音频 → AndroidMediaSink 解码 PCM → BrowserAudioBridge → 浏览器 Web Audio
浏览器麦克风 → AudioWorklet PCM → BrowserAudioBridge → MicrophoneUplink → iPhone
```

Web 服务由 DiPlaySessionService 创建/销毁；离屏 Surface 生命周期属于媒体 sink，而不是 Activity 的 TextureView。返回首页或切换应用不会解绑离屏 Surface。原版 CarPlay 控制器仍由后台会话快照持有；原版 Activity 负责权限、配对和重连配置。服务使用非粘性前台生命周期，不宣称能在系统强杀后恢复。显示基准固定为 1280×720，避免服务端屏幕旋转触发 CarPlay 重协商；原版显示缩放设置仍可能改变实际协商分辨率，网页使用收到的图像尺寸映射触摸坐标。

## 协议 v1

1. `GET /`、`GET /app.js`、`GET /style.css` 提供 APK 内置资产。
2. `GET /stream?code=六位码` 升级为 WebSocket，要求 `Origin` 与 `Host` 同源。错误码返回 401；跨源返回 403；五次失败后暂停认证 30 秒。
3. JPEG 模式下，服务端二进制消息是一张完整 JPEG。客户端 `<img>` 触发 `load` 后发送 `{"type":"ack"}`。每连接最多一帧等待 ACK；最新帧覆盖旧帧。WebRTC 视频可用时，压缩帧改由 WebRTC 发送。
4. 客户端每 400 ms 发送 `{"type":"ping"}`；服务端回复 `{"type":"status","width":1280,"height":720,"streaming":true,"stage":"..."}`。
5. 触摸消息 `{"type":"touch","contacts":[{"id":0,"x":0.5,"y":0.5,"down":true}]}`。坐标归一化到实际图像区域，排除 letterbox；最多两个固定槽位，缺失槽位为抬起。长按期间随心跳重发，超过 1500 ms 未收到触摸则释放。
6. 已被占用时新连接收到 `{"type":"busy","message":"..."}` 后关闭。断线释放所有触点。页面失焦、隐藏或退出也主动释放。
7. App 中的两个转发选项默认关闭并独立保存。服务端向已配对网页发送 `audio-route`，网页只用 `audio-ready` 回报浏览器音频与麦克风是否就绪；服务端按 App 选项和就绪状态决定是否转发。解码 PCM 打包为 `WPA1` 二进制帧（流号、采样率、声道、序号、PCM16LE）；浏览器通过 `audio-ack` 确认，最多四帧在途，过期帧丢弃。麦克风先等待 `mic-config`（采样率、声道、输入 ID），随后发送 `WPM1` PCM 帧；仅当前已配对浏览器且 ID/序号匹配时接收。断开、浏览器未就绪或关掉选项时恢复 Android 路由。

## 生命周期与资源约束

- 每个媒体 sink 有独立的离屏输出和代次编号；旧会话结束不会覆盖/清空新会话画面。
- MediaCodec 输出回调总是消费 SurfaceTexture，未连接车机时省去像素读取和 JPEG 压缩。
- GL 工作在独立 HandlerThread；网络发送在独立工作线程，不阻塞解码回调。
- 输出最小间隔为 66 ms；限帧期间保留最新纹理，最多安排一次尾帧补发，关闭时取消。
- JPEG、音频与控制回复共用单一写线程。控制回复优先，音频队列最多 16 帧并在 100 ms 后过期；音频和 JPEG 各自背压，接收线程不等待网络写入。
- 浏览器拥塞时保留未提交的 ACK 和触摸边界事件，合并连续移动与心跳，每 10 ms 重试。待发送触摸最多 64 条，1500 ms 无发送进展或队列溢出时关闭连接；断线清空待发送状态。
- 客户端首次连接超时为 8 秒；服务端在超过 6 秒未收到客户端消息或帧 ACK 时关闭连接并释放控制权，客户端也检查 6 秒状态回复超时。服务端看门狗每 500 ms 检查一次。
- 静止画面不会仅因缺少新帧被判为断线；状态依据屏幕流是否激活。新浏览器加入时，离屏输出会补发已有静止画面。
- 前台服务持有 PARTIAL_WAKE_LOCK，并在销毁时释放。移除界面任务不会主动停止服务；系统强杀和厂商省电策略仍需真机验证。
- 停止服务释放监听端口、客户端、定时器和输入状态。
- JPEG 视频与浏览器播放音频可使用普通局域网 HTTP。浏览器麦克风和 AudioWorklet 需要安全上下文：服务额外开放 HTTPS 8443，证书由本机私有存储中保留的安装专属 CA 签发；根证书下载与 SHA-256 指纹供用户在车机浏览器核对并配置。HTTPS 与 HTTP 共用扫码配对状态和单浏览器控制权，来源按协议分别校验。部分车机浏览器不允许安装本地 CA，此时保持 Android 麦克风。

浏览器音频使用 PCM/Web Audio，与 JPEG 或 WebRTC 视频模式独立。浏览器不能获得麦克风权限、关闭麦克风或连接断开时，服务端重新使用 Android 麦克风；CarPlay 设备端的实际音频路由与延迟仍需真机验证。


## APK 界面层

`DiPlayActivity` 是唯一的主界面与启动入口，承载启动（Launch）、iPhone、设置三个同级页面。`ServerUi.Shell` 持有三页内容、底部标准 BottomNavigationView 和状态栏遮罩，切换仅改变页面可见性，保留页面控件及滚动位置；重复点击当前 Tab 不重建视图，不启动 Activity，不重置滚动。

使用 Material Components 1.13.0 与 `Theme.Material3.Dark.NoActionBar`。按钮、开关、文本、分隔线、输入框、弹窗与连接进度指示器分别使用对应的 Material 组件；Tab 指示器采用组件默认动效，页面本身没有整屏平移。主题集中在 `themes.xml`。

`CarPlayHostActivity` 保留协议控制器与权限申请的所有权，仅作为显式连接或 USB 接入流程使用，不再承载 Tab。会话启动后返回主界面，连接仍在后台运行。通知也直接打开主界面。打开主界面启动 Web 服务；自动连接继续受用户设置控制。断开 iPhone 后恢复独立 Web 服务，让服务面板继续可用。

控制器、认证、离屏解码和触摸协议未改动。连接方式与设置仍调用原有持久化和重连流程；启动连接时的权限由系统界面处理。主界面旋转时重建布局，但不重建 CarPlay 会话。

`ServerWindow` 统一要求主界面及连接流程显示系统状态栏和导航栏，内容区避开系统栏和屏幕开孔。服务端不再读取旧全屏偏好或提供本机全屏开关，浏览器全屏控制不受影响。

选择型偏好使用 `ServerUi.PreferenceRow`：左侧标题，右侧当前值及方向提示，无按钮边框，整行带主题水波纹并支持键盘焦点和读屏。选择确认后更新值文本，原有 Material 单选弹窗与保存/重连回调保留。

`LanAddresses` 结合 Android ConnectivityManager 的网络类型/当前网络及 NetworkInterface 的 IPv4 地址，保留接口来源；无法取得系统元数据时按接口名降级识别。稳定排序优先普通 Wi-Fi / 以太网，其次热点、USB、未知网络、Wi-Fi Direct、VPN、移动网络，同级优先当前网络。仅显示启用的非回环接口上的私有 IPv4 地址。主界面只展示首选项，其余默认折叠；每秒刷新地址变化时保留展开状态，避免反复重建地址列表。排序不代表对车机可达性的实际探测。


## 扫码配对

网页通过同源 `POST /pair/request` 创建两分钟有效的临时请求。服务返回请求 ID 和独立的轮询凭据；`GET /pair/qr` 使用这些凭据获取本地生成的 SVG 二维码。二维码内容是 `wheelplay-pair:v1` 格式，包含服务实例标识、请求 ID 和独立的扫码授权随机值，不包含六位配对码或可打开的网络地址。网页每秒通过同源 `POST /pair/status` 查询结果。

扫码使用 Quickie Activity Result API 与 bundled ML Kit，在设备本地识别二维码；只把文本结果交回 App，不会打开二维码中的 URL。App 在本服务内验证二维码并显示车机来源 IP，经本机用户允许后签发随机浏览器令牌。没有网络侧批准接口。原有 `/stream` 同源校验、单浏览器控制权和 ACK 背压保持不变，握手增加令牌认证分支。

请求、轮询凭据和令牌均使用 192 位安全随机数；临时请求每来源 IP 最多 4 个、全局最多 32 个，授权令牌最多 32 个且最长有效 12 小时。请求过期、其他服务的二维码、重复批准均拒绝。令牌只保留在服务与网页内存，网页主动断开／关闭时尝试调用同源 `/pair/revoke`，服务停止全部清空；断网期间保留令牌用于重连。相机仅在扫码页面启用，退出释放；无相机或拒绝权限可使用配对码。HTTP 局域网链路保持现有实现。


主界面采用透明状态栏，无固定工具栏。ScrollView 顶部接收状态栏 / 刘海安全区 padding，并关闭 clipToPadding，使顶部内容起始位置安全且滚动后可绘制到状态栏下。首页 App 名称在滚动内容中；状态栏区域覆盖独立、不接收点击的深色半透明视图，透明度随当前页面前 24 dp 滚动增加，回到顶部完全透明，切换 Tab 或替换页面时同步当前滚动位置。底部导航的外层背景容器预留系统导航安全区，内部 BottomNavigationView 保留完整内容高度。
