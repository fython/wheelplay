# 上游 main rebase 后真机验证

验证日期：2026-11-16。代码：`78a1862`，基于上游 `077c86d`。本轮未修改实现代码。

## 环境与范围

- vivo V2547A：系统智能车载发送端。
- Pixel 3：WheelPlay debug 宿主、CarLink debug / CarLife 插件、Chrome 显示端。
- 从当前提交重新构建并覆盖安装宿主与两个插件；vivo 也覆盖安装了宿主和 CarLife 插件，但本轮验证使用 Pixel 接收，不测试 vivo 本机回环。
- CarLink 使用 P2P；CarLife 使用同一局域网 Wi-Fi。Pixel Chrome 访问本机 `http://127.0.0.1:8080/`。
- USB 仅用于部署、界面操作及日志；ADB 转发 Chrome 调试端口用于读取播放统计和注入双指手势，不转发媒体。

## 结果

| 功能 | 结果 | 证据与限制 |
| --- | --- | --- |
| CarLink 发现、配对、认证 | 通过 | vivo 发现 MiAutoCar；验证码交互、P2P 和认证成功 |
| CarLink 兼容模式画面 | 通过 | Pixel Chrome 实际显示 2160×850 WebP；发送端视频为 2160×848 |
| CarLink 单指触控 | 通过 | 点击打开地图，拖动后地图位置实际变化；接收端记录 DOWN/MOVE/UP |
| CarLink 停止及重新连接 | 通过一次模式切换重连 | WebRTC 会话停止后切为兼容模式，重新连接成功；不是五轮稳定性测试 |
| CarLink 低延迟 WebRTC | **未通过** | 两次独立连接均已认证且宿主持续收编码帧，但 Chrome 视频宽高及已解码帧数为 0；重试接收、刷新页面未恢复 |
| CarLife 原生 UDP 发现 | 通过 | vivo 正常系统连接入口启动后，Pixel 显示本轮发现的 vivo 地址；未发送模拟广播 |
| CarLife 自动协议、跨 Wi-Fi 连接 | 通过 | 自动模式选择 4.1，版本匹配、加密就绪、认证成功、2160×848 首帧；没有触发 3.0 降级 |
| CarLife 原生 Video 显示 | 通过 | 播放时间从 4.38 秒增至 139.07 秒，累计帧从 59 增至 1899；最终累计丢帧 169，不宣称稳定 30 fps |
| CarLife 单指与双指 | 通过 | 打开应用列表和地图；双指展开后地图实际放大，日志含 action=261/262、contacts=2；网页缩放比例保持 1 |
| CarLife 音频数据 | 接收通过 | 44100 Hz 双声道 PCM16，nonSilent 从 0 增至 1317；测试后暂停音乐。未验证扬声器听感、音画同步或麦克风识别 |
| CarLife Canvas + WebGL | 通过一次显示验证 | `selectedMode=video-canvas`、`activeMode=video-canvas`、`renderBackend=webgl`，采样已绘制 94 帧，无回退；截图确认实际画面 |
| CarLife 正常停止 | 通过 | `STOPPED_CLEAN`；全会话最终接收 2730 帧、约 37 MB 视频 |

## 观察到的问题

### CarLink 低延迟模式黑屏

认证和输入视频链路工作，但 Pixel Chrome 日志反复出现 `Failed to decode frame` 和 `No decodable frame ... requesting keyframe`。兼容模式能显示同一发送设备画面；同一浏览器的 CarLife WebRTC 能解码，因此应优先检查 CarLink 编码视频、参数集、关键帧和时间戳路径。

本轮没有在 rebase 前提交上执行相同设备、配置的 A/B 对照，不能据此认定是上游引入的回归，也不能仅凭单元测试通过宣布该路径正常。

### 首轮 P2P 与浏览器会话状态

Pixel 开始时保留了之前测试的热点。首次连接在 P2P 建组阶段失败，关闭热点后继续测试。另一次配对期间启动浏览器引入新的 viewport，观察到接收会话重新初始化及验证码变化；固定浏览器尺寸并重新配对后连接成功。不能将两次失败统一归因于 rebase。

切换输入源和后台/前台页面过程中，Chrome 曾显示旧会话的等待/刷新提示；刷新后恢复配对并启动显示可恢复 CarLife。未将此现象归因为明确的代码回归。

## 结束状态与未覆盖范围

- 两种投影会话均已停止，测试音乐已暂停。
- Pixel CarLink 保留 P2P + 兼容模式，CarLife 保留远端 Wi-Fi + 自动协议；浏览器恢复原生 Video 模式。Pixel 热点已关闭以完成 P2P 验证。
- 没有进行 USB、手动热点、多轮断线恢复、长时间压力测试、麦克风识别或完整音频路由验收；CarLife 显式 3.0 和 vivo 本机回环未在本轮重验。
- 当前 APK 留在测试设备上。未修改系统应用、使用 root 或绕过签名权限。

## 本地证据

原始材料在忽略目录 `.local/rebase-device-validation/`，不提交。包含 APK SHA-256、CarLink 首轮/复测日志、兼容模式触控截图、CarLife 协议日志、原生 Video/WebGL 统计和截图。原始材料可能包含设备、网络或地图信息。

关键文件：`apk-sha256.txt`、`carlink-webrtc-retest-final.json`、`carlink-webrtc-retest.log`、`carlink-surface-drag.png`、`carlife-protocol-final.log`、`carlife-video-first.json`、`carlife-video-final.json`、`carlife-pinch.png`、`carlife-webgl.json`。
