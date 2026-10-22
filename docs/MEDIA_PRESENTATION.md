# 浏览器画面、媒体源与触控状态

浏览器显示状态由 `createPresentationController` 统一管理。WebSocket 存活、媒体源就绪、
已经提交的画面、触控通道就绪是四个独立事实。`status.streaming` 保留兼容用途，不能单独
决定画面是否存在，也不能用正在解码的图片元素的 `naturalWidth` 判定之前的画面是否有效。

## 显示行为

| 状态 | 画面与提示 | 触控 |
| --- | --- | --- |
| disconnected | 等待 / 重连界面；清理图片与冻结画面 | 禁用 |
| waiting | 尚无首帧，或恢复超时；已有画面调暗并显示等待遮罩 | 禁用 |
| showing | 当前版本已有画面，媒体源就绪 | 仅在 inputReady 时启用 |
| recovering | 保留上一帧、不调暗；画面内显示「等待恢复 · 触控已暂停」 | 释放触点，禁止新输入 |

首次进入 recovering 时，以 `performance.now()` 设置 2000 ms 截止时间，并启动独立
定时器。重复停流消息、旧版本消息和恢复中的重复帧均不延长期限。恢复、明确 stopped、
断连会取消定时器；定时器带有独立代号，迟到回调不能影响后续恢复或重连。页面恢复执行、
状态更新及心跳时也检查同一截止时间。浏览器后台节流或主线程暂停时，无法保证回调准点，
但恢复执行后按截止时间立即更新，不重新获得两秒窗口。

静态 CarPlay 画面可以长期没有新帧；没有新帧本身不启动恢复计时。同一视频版本恢复 ready
后可继续使用已提交的静态画面；更换版本后必须等待该版本的首帧。WebRTC 现有的 4 / 10 / 30 秒
传输恢复和 JPEG 回退策略保持独立，媒体显示计时器不触发重连或改写 WebRTC 容忍策略。
回退后的[自动重试](WEBRTC_RECOVERY.md#jpeg-回退后的自动重试)由服务端控制，退避间隔上限为
30 秒；协商期间继续呈现 JPEG 并沿用其触控尺寸，实际视频首帧到来后才切换。

## 帧的提交与保留

图片使用三个 img 元素：前缓冲继续显示，最多两个后缓冲加载并解码 JPEG / WebP。只有成功解码、
版本仍匹配时，才在同一个事件回调中切换可见图片和触控尺寸，随后释放旧 Blob URL。
内存中最多保留一个显示帧和两个待解码帧；较新图片先完成时取消旧任务。失败 / 取消的帧
也必须 ACK，以免阻塞服务端的在途窗口；断连后的回调不能向新 socket ACK 或提交画面。
格式协商、v3 精确 ACK 和有界流水线见 [静态图片串流](IMAGE_STREAM.md)。

原生 WebRTC 与 Canvas 显示都向同一个控制器提交呈现信息。RTC 重建 / 回退前尝试把最后
画面复制到独立 Canvas，避免清空 srcObject 时突然变黑。截图失败时退回等待状态；该冻结
画面只用于视觉过渡，不能恢复触控。新画面成功提交或连接结束后释放冻结 Canvas。

触控矩形始终使用已提交画面的宽高。服务端广告尺寸、待解码 JPEG 和新 offer 均不能提前
替换此几何。媒体源恢复窗口内会释放正在进行的触点，即使上一帧仍然可见。

## 媒体协议 v2

客户端通过 `/stream?...&media=2` 协商，新增的 `images=3` 可进一步启用图片协议 v3。
未指定 media 参数的旧客户端继续收到原始 JPEG；新增
status / offer 字段可被旧客户端忽略。新客户端连接旧服务端时，继续支持原始 JPEG 和
streaming 状态，但无法识别旧服务端没有提供的同尺寸重建或明确结束原因。

status 新增：

- `mediaProtocol: 2`。
- `sessionId`：媒体生命周期的随机安全整数；结束后再次 beginVideo 时更换。
- `videoEpoch`：WebSession 的递增视频代号，beginVideo / endVideo / stop 会推进。
  同尺寸重建也产生新代号。它同时隔离旧媒体生产者的 publish / endVideo。
- `sourceState`：stopped（生命周期结束）、starting（尚未就绪且无 JPEG）、
  recovering（源暂不可用且已有 JPEG）、ready（videoActive）。
- `inputReady`：源就绪且当前手机会话的加密 HID 事件通道已建立；它不能由画面是否存在推导。

字段由同步的媒体快照提供，版本和广告尺寸来自同一次读取。RTC offer 带相同 sessionId /
videoEpoch；RTC 自身的 id 与 receiver generation 继续隔离编解码配置协商和迟到回调。

v2 JPEG 是单个二进制 WebSocket 消息，前 36 字节为 little-endian 头，随后是 JPEG：

| 偏移 | 类型 | 内容 |
| --- | --- | --- |
| 0 | uint32 | 0x32565057，字节为 WPV2；与 PCM 的 WPA1 区分 |
| 4 | float64 | sessionId，整数，范围不超过 JavaScript 安全整数 |
| 12 | float64 | videoEpoch，整数 |
| 20 | float64 | sequence，整数 |
| 28 | uint32 | JPEG 宽 |
| 32 | uint32 | JPEG 高 |
| 36 | bytes | JPEG |

版本放在每个帧消息内，不依赖可能被合并的 status，也没有独立 frame-meta 消息的配对竞态。
帧可能先于 status 到达：先使旧画面不可交互，解码可继续，但输入要等该版本的 ready 状态。
较旧代号的帧、status、offer 被忽略；客户端重连会重置其版本作用域。

v2 touch 同样携带 sessionId / videoEpoch。服务端在配置锁内检查代号与输入就绪情况，
再交给 TouchLease。过期输入会被拒绝并释放触点；空 contacts 的释放请求始终允许处理。
这也保护已经排在浏览器发送队列中、配置切换后才抵达服务端的旧触控消息。

## 验证

- `node --test test/*.test.js`：状态转换、计时器、缓冲提交、包头，以及 JPEG / RTC / Canvas
  客户端回归。可用 `--test-isolation=none` 查看单个测试结果。
- `npm run test:browser:media`：本机 Chromium + 真实 WebSocket + 合成 JPEG，验证解码期间
  无遮罩误闪、恢复时释放并禁止触控、无后续 status 的超时、取消定时器、同尺寸重建、旧帧拒绝、
  旋转和真实 socket 重连。需要 Chromium（可通过 BROWSER_BIN 指定）；不验证真实 RTC 或手机硬件。
- Android `VideoPacketTest`、`WebStreamSenderTest`、`LanWebServerTest` 验证编码布局、有界 ACK
  背压、真实 WebSocket 协商、状态快照、同尺寸重建和旧生产者 / 触控代号拒绝。

iPhone、实车和 Android 车机上的实际网络抖动仍需硬件回归。2 秒是当前界面策略，尚未根据
实车数据调优；本方案不改变 AirPlay 接入、音频、认证或 WebSocket 断连阈值。
