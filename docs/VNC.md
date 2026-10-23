# VNC 输入源（实验版）

基线：main `5da98cd`（包含 #39 的输入源实现及 #42 的官网更新）。VNC 是第三个真实协议输入源，复用 #28 的单会话调度与 framebuffer 输出，不依赖 CarPlay、Android Auto、Shizuku 或本机 ADB。WheelPlay 在这里是 **VNC 客户端**；远端设备必须已有 VNC 服务端，本功能不会自行创建本机虚拟桌面。

## 使用

1. 在远端电脑或 Android 设备运行 VNC 服务端，允许 WheelPlay 宿主访问其 TCP 端口。建议先使用 1280×720 或更小的桌面。
2. WheelPlay 底部“输入源” → **VNC** → “连接 VNC”。输入数字 IPv4/IPv6 地址和端口（默认 5900），有传统 VNC 认证时填写密码；勾选“仅查看”可禁止全部远端输入。
3. 连接成功后，车机/电脑浏览器使用原有 WheelPlay 地址和配对流程查看桌面。切换源后若浏览器连接结束，按原有流程重新连接。
4. 触屏单指对应鼠标左键，可点按、拖动。实体鼠标支持悬停、左/中/右键与滚轮；点击画面取得焦点后可用实体键盘。浏览器保留的系统快捷键不保证可转发。
5. 在 VNC 二级页面停止投影。服务器断开后不会自动重连；再次连接需要重新输入密码。

地址、端口和只读选项会保存在本机；密码不写入偏好设置、日志或浏览器，只在内存中交给一次会话。传统 VNC 密码最多 8 个 Latin-1 字符；界面拒绝超长密码，不会静默截断。

**安全边界：** 仅支持无认证和传统 VNC Authentication，不支持 TLS/VeNCrypt 或厂商账户认证。传统 VNC 认证和桌面传输不能提供现代加密保护，应使用可信局域网或 VPN。WheelPlay 浏览器入口的 HTTPS 不会加密另一段 VNC TCP 连接。

## 实现

- `shared/.../vnc/RfbClient.kt`：纯 Kotlin/JVM RFB 3.3、3.7、3.8 协议协商；No Authentication / DES challenge-response；以 shared 模式连接，不请求踢掉其他查看者。
- 明确请求 32 bpp、24-bit true colour、little-endian 像素格式；声明并解码 Raw、CopyRect、Hextile 和 DesktopSize。所有增量矩形先按顺序应用到 `ProjectionFramebuffer`，再把完整快照交给输出层，不能直接丢弃中间增量包。
- 一次只发送一个 framebuffer update request，更新请求约限频至 15 fps。画面静止时允许无限等待下一个消息，不因桌面没变化而断开。
- framebuffer 最大 4,194,304 像素，单边最多 8192；更新矩形数量、累计处理像素、服务端名称和剪贴板消息长度均有上限。未声明的编码/认证、非法尺寸、越界矩形和截断数据会结束会话。
- DesktopSize 后关闭旧视频输出并按新尺寸创建输出，申请完整画面；视频 epoch 更新会撤销旧输入。当前通过 JPEG/WebP 图像管线输出，未新增 framebuffer→H.264/WebRTC 编码器。
- `common/.../projection/vnc/VncSource.kt`：数字地址校验、连接/认证及消息负载超时、异步读写、有界发送队列及停止清理。停止先释放本会话的输入，再关闭 socket 并等待发送线程退出，最后关闭视频输出；清理失败保留资源归属。连接期间也可停止。
- `ProjectionInput` 与 `pointerInput/keyboardInput` 是通用框架能力，不是浏览器中的 VNC 特例。Pointer、Scroll、Key、Release、KeepAlive 复用浏览器独占控制租约，必须携带当前 `sessionId/videoEpoch`。旧会话事件、非租约持有人、越界坐标和超限滚动不会进入协议会话。
- 鼠标/键盘与触控共用有界有序发送队列，仅合并相邻移动；不会跨按下/抬起/按键边界合并。失焦、关闭浏览器、租约超时、调整分辨率和切源均释放按键。长按由心跳续租。

## 范围与验证

已覆盖的自动化检查：

- 三种 RFB 版本、DES 独立已知答案、共享模式与显式像素格式、认证降级拒绝、Raw/重叠 CopyRect/Hextile、桌面 resize、异常长度与坐标、输入 wire 格式。
- 本地真实 TCP socket 上的模拟服务端：握手、图像传递、键盘输入、停止前按键释放；另测停止中断未完成握手、停止先于激活、密码不持久化及只读能力。
- 通用路由的能力检查、独占租约、心跳/超时释放、resize 与切源后的旧版本拒绝；输入源卡片和二级导航。
- Node 浏览器逻辑测试，以及真实 Chromium + WebSocket 的右键拖动、滚轮、键盘、失焦释放和现有触控/媒体回归。

仍需实际服务端与 Android 设备验收：

- 记录宿主型号/Android 版本、VNC 服务端名称/版本/认证方式、浏览器版本及分辨率，至少验收一个常见服务端（如 TigerVNC/x11vnc）。
- 验证密码正确/错误、仅查看、右键菜单、长按修饰键、拖动到画面外、滚轮、浏览器失焦与断线。
- 验证静止桌面、远端分辨率变化、网络断开/恢复后手动重连，连续切换 CarPlay / AA / VNC，确认旧桌面不再接收输入且无残留连接。
- 在目标设备测量帧率、延迟、CPU/内存和长时间稳定性；自动化模拟端不代表真实服务端兼容性或车机性能已经通过。

暂不支持：Tight/ZRLE、TLS/VeNCrypt、音频、远端剪贴板、IME 组合输入/软件键盘、相对指针、真正多点触控、自动发现/自动重连、主机名解析、IPv6 zone ID。VNC 卡片不是 ICCOA SDK 的替代实现，也不实现 #40 的 Shizuku/ADB 虚拟桌面方案。

协议参考：[RFC 6143 — The Remote Framebuffer Protocol](https://www.rfc-editor.org/rfc/rfc6143)。
