# Android 虚拟桌面（实验性）

本实现对应 [Issue #40](https://github.com/fython/wheelplay/issues/40) 的首版软件链路。**尚未完成 P0 非 root 真机可行性验证，不能视为 Issue 全部验收通过。** Android 10+ 是代码门槛，不是已验证支持范围。直接 ADB 后端、VDM、桌面 Launcher、会话内动态旋转/DPI、浏览器音频/麦克风和键盘输入均未实现。

## 使用

1. 在宿主手机安装并启动 [Shizuku](https://shizuku.rikka.app/download/)，需要服务 API 13 或更高。Android 11+ 可用无线调试启动，旧系统需要电脑；非 root 模式重启后通常要重新启动 Shizuku。
2. 在 WheelPlay 打开“输入源 → Android 虚拟桌面”。卡片只导航，不切换当前输入源。
3. 点“授权 WheelPlay”。拒绝过授权时，在 Shizuku 管理界面重新授权。只在用户点击时请求权限，不自动重试或启动投影。
4. 点击画面参数按钮设置宽度、高度和 DPI（可恢复默认值）；设置会保存并在下次启动时生效，不改变当前会话。点“选择可启动的本机应用”，再点“启动虚拟桌面”。只列出 PackageManager 可查询、已启用且导出的 Launcher Activity。
5. 从已经配对的浏览器查看画面，使用单指/多指触控与左下角“返回”按钮。返回按钮在全屏模式仍可用，不意味着支持实体键盘或中文输入法。
6. 点二级页或首页的停止按钮释放虚拟桌面。出现“无法确认已完整释放”时，资源所有权保留，不能继续切源；重启 WheelPlay 恢复。

缺少 Shizuku、服务未启动、未授权、API/身份不满足要求、所选应用被卸载或禁用时，切源前的只读检查失败，不停止当前 CarPlay/Android Auto 会话。具体设备的显示创建、应用启动和输入权限只能在显式启动后验证；这些失败会显示原因并清理新会话，不保证恢复已停止的旧输入源。

## 能力和限制

- 单虚拟屏，默认 **1280 × 720、160 dpi、布局版本 1**；可设置宽高各 320–3840 像素、总像素数不超过 8,294,400、DPI 为 72–640。横屏、竖屏均可配置。参数在连接开始时固定，并用于输出 Surface、虚拟显示、触控映射和布局校验。独立画面，不镜像手机主屏，不使用 `AUTO_MIRROR`/`SECURE`/`ALWAYS_UNLOCKED`。
- 主进程拥有公共离屏 Surface，经 Binder 传句柄；助手只拥有 producer 和输入状态。复用现有有界、合并帧的 JPEG/WebP 管线，**本源没有 WebRTC 编码器**，其他输入源的 WebRTC 行为不变。
- 分开记录显示创建、Surface 首帧与浏览器图像发布。创建成功不会直接开放输入。首帧超时会失败；“图像输出已就绪”只说明收到并发布图像，不能自动判断应用内容是否正确。
- 部分受保护内容可能输出黑帧，目前不能可靠识别这类画面的应用语义。禁止副屏/DRM 应用不在支持范围，不能以收到黑帧宣称该应用通过验收。
- 声音使用手机原有行为；能力声明关闭音频播放、浏览器音频、麦克风、鼠标、键盘和 Home。不会创建第二套 Android 或隔离应用数据；单任务应用可能复用/移动主屏任务。
- 显示旋转、DPI 或尺寸变化时取消触点并停止会话，要求重新连接；首版不做动态坐标重映射。锁屏行为、手机同时操作和输入法显示均需实测，不强制解锁设备。
- 应用启动采用指定 displayId 的 `startActivityAsUser`，以宿主用户启动；返回值错误或两秒内未在目标屏发现所选应用的任务会中止。后续任务仍可能被系统/应用重定向，不保证任意应用留在副屏。销毁屏幕时请求 `DESTROY_CONTENT_ON_REMOVAL`，不对整个应用执行 force-stop。

## 进程、权限和清理

`DesktopBackend` / `DesktopConnection` 是 Android 平台层接口，将可用性、连接、身份能力与停止同 Shizuku 解耦；共享 `ProjectionSourceProvider` 不暴露 Binder/ADB 类型。

- 固定依赖 `dev.rikka.shizuku:api` 和 `provider` **13.1.5**。只接受实际 UID 2000（shell）或 0（root），状态页展示实际身份；二者不等价。
- `IDesktopService` 协议版本 2，仅包含协商、带尺寸/DPI 的显示启动、触控、返回、健康检查、停止和销毁。主进程与助手共用参数范围校验，助手在创建显示前再次校验。不接收命令行、任意 Intent 或任意系统调用。
- 每次连接使用独立 Shizuku tag，携带应用版本，避免旧绑定回调解除新会话。设置 `daemon(false)`，正常停止同时等待助手 Binder 死亡，按 UserService 协议实现销毁事务 `16777115` 和进程退出。
- 每个入口验证宿主 UID；每个会话绑定随机 token、客户端 Binder 和布局版本。输入必须显式设置属于本助手且大于 0 的 displayId，设置失败即拒绝，绝不回退到默认显示屏。
- 主进程输入工作队列上限 64，满队列即停止会话，不静默丢弃抬手；公共浏览器控制租约负责失焦、断网、过期、切源时释放触点。返回键的按下/抬起在助手同一调用内完成。
- 正常停止：拒绝新输入 → 清空未执行输入并等待正在执行调用 → 助手取消触点、销毁显示/任务、释放收到的 Surface 副本 → 助手退出 → 主进程关闭 Surface/GL → runtime 释放资源。无法确认任何步骤时保留资源所有权。
- 主进程 Binder 死亡立即触发助手清理/退出；健康检查续期独立看门狗（10 秒，未启动助手 30 秒）。Shizuku/助手死亡或授权失效由会话监测触发清理，不依赖 Activity 销毁。
- UserService 不是常规应用进程。显示使用 shell 包上下文创建 `DisplayManager`；启动 Activity 走无应用线程 caller 的系统接口；隐藏 API 仅在 shell/root 助手进程中反射调用。接口变化/厂商拒绝均显式失败。

## 验证与设备记录

自动化覆盖：依赖缺失保留现有会话、延迟激活与首帧输入门控、配置保存/非法值回退、连接期间配置快照、自定义横竖屏触控边界、多指索引和边界坐标、取消手势、拒绝非法坐标/重复 ID、独立 Back 能力、旧会话拒绝、部分启动失败清理，以及未确认清理时保留 Surface/资源。UI 回归检查独立二级页和原输入源导航；现有浏览器回归覆盖公共租约、图像与桌面输入。

2026-10-26 云环境软件验证（Temurin 25.0.1）：`shared` 479 项、`common` 435 项单元测试全部通过，`npm test`、`npm run check`、Android lint 和 arm64 Debug APK 构建通过。可复现的 Gradle 目标：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug -Pandroid.injected.build.abi=arm64-v8a
```

**真机结果：未测试。** 完成以下记录后才能判断支持范围，不能用单元测试代替 P0：

| 项目 | 记录内容 / 验收动作 |
| --- | --- |
| 设备 | 厂商、型号、系统/API、是否 root、厂商额外权限/后台设置 |
| Shizuku | 应用/服务版本、启动方式、实际 UID；至少一台非 root shell 模式设备 |
| 图像 | 默认 1280×720/160 dpi、自定义横屏/竖屏尺寸与 DPI、保存后重新启动；浏览器版本、首帧时间、JPEG/WebP、延迟、CPU/内存和连续运行时长 |
| 输入 | 单指、两指交替抬起、10 指、边缘坐标、返回；手机主屏不得收到注入 |
| 主屏与任务 | 主屏同时使用、同应用已运行/单任务、弹窗、权限页、IME、应用退出后的任务去向 |
| 布局 | 应用请求旋转、DPI/尺寸变化、屏幕休眠、锁屏/解锁；当前实现应安全停止或明确报告限制 |
| 失效 | 浏览器失焦/断网、控制租约失效、慢浏览器、切 CarPlay/AA/VNC；无卡住触点 |
| 清理 | 停止、杀助手、停 Shizuku、撤销授权、杀宿主；用 `dumpsys display`/进程列表检查无残留显示/助手 |
| 恢复 | 重启手机、更新 APK（含相同 versionCode 的调试重装）、重新授权、快速失败后再次启动 |
| 受限应用 | 禁副屏、受保护黑屏、任务重定向按不支持记录；不能标为成功 |

P0 真机验证及 P2/P3 后续工作仍由 Issue #40 跟踪。

Shizuku API 使用 MIT 许可，版权及许可文本见 [SHIZUKU-API-LICENSE.txt](SHIZUKU-API-LICENSE.txt)。

## 固定参考版本

- [Shizuku API 13.1.5](https://github.com/RikkaApps/Shizuku-API)，UserService/Binder/销毁契约与 Maven source artifact 13.1.5。
- [scrcpy v3.3.3 虚拟显示](https://github.com/Genymobile/scrcpy/blob/v3.3.3/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java)，用于核对显示标志/API 版本边界。
- [scrcpy v3.3.3 ActivityManager 包装](https://github.com/Genymobile/scrcpy/blob/v3.3.3/server/src/main/java/com/genymobile/scrcpy/wrappers/ActivityManager.java)，用于核对 shell 启动 Activity 的隐藏 API 签名。
