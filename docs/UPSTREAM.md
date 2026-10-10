# 上游来源

- 来源项目：DiPlay（本地副本）
- 上游提交：`eae4e7babafb91f2768687061b5f22515b8cf444`
- 初始源码复制方式：仅复制 Git 已跟踪的 `mobile`、`common`、`shared`、Gradle 配置及许可证/文档；未复制构建产物、Git 元数据、认证资产、签名文件或 local.properties。
- 原版目录保持不变。
- 本分支修改：APK 包名/启动入口、前台服务及服务面板、离屏渲染输出、内嵌网页、网络协议和测试；构建基线调整为已安装的 SDK 36 / AGP 9.1.0 / Gradle 9.3.1。
- 原版文档作为来源记录保留在 `docs/upstream`，其「安装到车机」、版本号、SDK 37 和 HUD 工作流不代表本分支用法；本分支以根目录 README 为准。

## 当前目录布局

Android Gradle 工程已提升至项目根目录：`mobile/`、`common/`、`shared/` 和 Gradle 配置直接位于根目录。原版文档归档到 `docs/upstream/`，原版项目 README 参考副本为 [PROJECT-README.md](upstream/PROJECT-README.md)。根目录保留当前项目说明及许可证。

## 本地运行时资产

认证资源未纳入 Git；公开构建不包含资源，用户可在设置中手动导入，或从自己下载的 DiPlay APK 本地提取。开发者也可显式配置本地构建资产。打包验证见 [VALIDATION.md](VALIDATION.md)。

## CarPlay 返回入口图标

默认名称及图标改为 `NIO`，旧安装保存的默认 `BYD` 名称在读取时迁移为 `NIO`。相关商标及图标属于其原权利人，不纳入本项目代码许可证授权范围。此变更仅影响 CarPlay 返回入口的名称和默认图标，不集成蔚来 App 功能，也不改变点击行为。

## Material 界面组件

Material Components for Android 1.13.0（Apache-2.0），使用官方 Material 3 主题与控件。[底部导航开发文档](https://github.com/material-components/material-components-android/blob/master/docs/components/BottomNavigation.md)。本机 `.local/maven/` 仅为下载连接受限时的本地缓存，依赖来自 Google Maven / Maven Central，不提交到仓库。


## 离线二维码

扫码使用 [Quickie 1.12.0](https://github.com/G00fY2/quickie)，基于 CameraX 与 ML Kit，采用 bundled flavor，将识别模型随应用打包，因此扫码不依赖 Google Play 服务或网络下载。Quickie 使用 MIT 许可，见 [QUICKIE-LICENSE.txt](QUICKIE-LICENSE.txt)。本地生成配对二维码仍使用 [ZXing Core 3.5.3](https://github.com/zxing/zxing)（Apache-2.0）；依赖均来自 Maven Central。

本地 HTTPS 证书签发使用 [Bouncy Castle PKIX](https://www.bouncycastle.org/download/bouncy-castle-java/)；[许可证](https://github.com/bcgit/bc-java/blob/main/LICENSE.md) 基于 MIT。证书私钥在应用本地生成并保存在私有非备份目录，不包含在仓库或 APK 中。


## 当前应用品牌

本分支应用品牌为 WheelPlay，桌面标签、应用界面、通知、网页、诊断导出及连接设备名称统一使用该名称。原版来源、许可证和历史资产文件名保持真实记录；应用 ID 为 moe.feng.wheelplay，Debug 版追加 .debug；源码命名空间与持久化键沿用原实现。


## Material 官方图标

应用功能图标统一采用 Google 官方 [Material Icons](https://github.com/google/material-design-icons) 的 `materialicons/24px.svg`（Filled）系列，保留原始路径与填充规则，转换为 24dp Android VectorDrawable；网页箭头使用内嵌 SVG，无网络字体或 CDN 依赖。Apache-2.0 许可见 [MATERIAL-ICONS-LICENSE.txt](MATERIAL-ICONS-LICENSE.txt)。

| 用途 / 资源 | 官方图标源 |
| --- | --- |
| 启动 Tab · `ic_server_launch` | [play_circle_filled](https://github.com/google/material-design-icons/blob/master/src/av/play_circle_filled/materialicons/24px.svg) |
| 车机访问卡片、服务通知 · `ic_server_server` / `ic_diplay_notification` | [cast](https://github.com/google/material-design-icons/blob/master/src/hardware/cast/materialicons/24px.svg) |
| iPhone Tab、会话和状态卡片 · `ic_server_phone` | [phone_iphone](https://github.com/google/material-design-icons/blob/master/src/hardware/phone_iphone/materialicons/24px.svg) |
| 扫描二维码配对按钮 · `ic_server_scan` | [qr_code_scanner](https://github.com/google/material-design-icons/blob/master/src/communication/qr_code_scanner/materialicons/24px.svg) |
| 浏览器配对卡片 · `ic_server_qr` | [qr_code_2](https://github.com/google/material-design-icons/blob/master/src/communication/qr_code_2/materialicons/24px.svg) |
| 无线连接卡片 · `ic_server_wifi` | [wifi](https://github.com/google/material-design-icons/blob/master/src/notification/wifi/materialicons/24px.svg) |
| USB 连接卡片 · `ic_server_usb` | [usb](https://github.com/google/material-design-icons/blob/master/src/device/usb/materialicons/24px.svg) |
| 设置 Tab · `ic_server_settings` | [settings](https://github.com/google/material-design-icons/blob/master/src/action/settings/materialicons/24px.svg) |
| 设置选择器、其他地址展开箭头 · `ic_settings_chevron` | [chevron_right](https://github.com/google/material-design-icons/blob/master/src/navigation/chevron_right/materialicons/24px.svg) |
| 网页连接按钮 · 内嵌 SVG | [arrow_forward](https://github.com/google/material-design-icons/blob/master/src/navigation/arrow_forward/materialicons/24px.svg) |
| 媒体通知上一首 · `ic_media_skip_previous` | [skip_previous](https://github.com/google/material-design-icons/blob/master/src/av/skip_previous/materialicons/24px.svg) |
| 媒体通知播放 · `ic_media_play` | [play_arrow](https://github.com/google/material-design-icons/blob/master/src/av/play_arrow/materialicons/24px.svg) |
| 媒体通知暂停 · `ic_media_pause` | [pause](https://github.com/google/material-design-icons/blob/master/src/av/pause/materialicons/24px.svg) |
| 媒体通知下一首 · `ic_media_skip_next` | [skip_next](https://github.com/google/material-design-icons/blob/master/src/av/skip_next/materialicons/24px.svg) |
| 连接通知停止服务 · `ic_media_stop` | [stop](https://github.com/google/material-design-icons/blob/master/src/av/stop/materialicons/24px.svg) |

启动使用播放圆形图标，车机访问及服务通知使用投屏图标。通知采用白色遮罩；页面图标跟随 Material 主题、卡片颜色和导航选中状态。方向箭头保留展开旋转动画，设置选择器支持 RTL 镜像。NIO 返回入口与原有应用品牌位图不属于自绘功能图标；二维码矩阵、裁剪网格及安全区域辅助线是功能内容，不作图标替换。
