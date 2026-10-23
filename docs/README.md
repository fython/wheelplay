# 文档索引

核对日期：2026-10-21。

本目录描述 **WheelPlay 当前分支**：

- [使用与构建说明](../README.md)：安装、配对、认证资产和构建方式。
- [架构与协议](ARCHITECTURE.md)：离屏解码、Web 服务、触摸及生命周期。
- [Android Auto 协议接入与 ICCOA 依赖边界](ANDROID_AUTO.md)：USB/TCP、宿主音频、协议测试及真机验收清单。
- [VNC 远程桌面](VNC.md)：连接方法、RFB 协议范围、通用桌面输入和设备验收。
- [投影输入源实现与设备验收](PROJECTION_IMPLEMENTATION.md)：当前框架、CarPlay 适配、三类诊断源及后续协议接入范围。
- [通用输入源框架设计草案](INPUT_SOURCE_FRAMEWORK.md)：CarPlay、Android Auto、ICCOA、桌面与 VNC 的接口边界、研究证据及分阶段迁移建议（研究时点的方案）。
- [WebRTC 稳定性与卡顿恢复](WEBRTC_RECOVERY.md)：回退原因、可配置容忍时间及真机排查方法。
- [认证资源导入](AUTHENTICATION_IMPORT.md)：手动选择资源、从用户下载的 DiPlay APK 提取及本地存储。
- [Tesla HTTP 兼容模式](TESLA_HTTP.md)：无证书虚拟地址入口、可选域名和实机验证限制。
- [验证记录](VALIDATION.md)：已通过的检查、APK 校验值和未完成的真机验证。
- [组件间距检查](SPACING_REVIEW.md)：本轮发现、尺寸约定、修正和验证范围。
- [上游来源](UPSTREAM.md)：原版提交及本分支改动范围。
- [NanoHTTPD 许可](NANOHTTPD-LICENSE.txt)。

`upstream/` 保留上游文档及许可证，属于原版参考资料，其中安装位置、构建版本及 HUD 流程可能不适用于 Web 分支。本分支用法以根目录 README 和本目录为准。
