# 验证记录

2026-09-29，本地 macOS 构建与浏览器验证。

## 已完成

- `:mobile:assembleDebug`：通过，生成约 40 MB 调试 APK。
- `:shared:testDebugUnitTest`：196 项通过，0 失败/跳过。
- `:common:testDebugUnitTest`：45 项通过，0 失败/跳过。
- 新增测试覆盖：触摸控制权、双指槽位不漂移、非法输入拒绝、长按心跳与超时释放、HTTP 静态资源、配对拒绝、同源校验、真实 TCP/WebSocket ACK 背压、服务停止后端口复用、前台服务移除任务后继续运行、旧媒体代次不覆盖新画面。
- `:common:assembleDebugAndroidTest`：通过，设备测试代码编译成功。
- `:mobile:lintDebug`：0 errors，17 warnings。警告涉及上游/依赖的 TrustManager、未使用资源及原有 HUD 调试界面的国际化文案，未抑制这些警告。
- `npm test`：4 项浏览器坐标映射测试通过；`npm run check`：通过。
- 实际浏览器 + 本地协议测试夹具：正确配对、JPEG 彩条显示、触摸按下/抬起消息、错误配对提示、主动断开、再次连接、竖屏及 1280×720 横屏布局。测试画面不是 CarPlay 真机输出。
- 初始未配置认证资产时的 APK ZIP 检查：包含三项 `assets/web/` 资源，不包含 `.pk8`、`.p7b`、`.jks` 或 `.keystore`。
- 原版 DiPlay 项目的 Git 工作区保持干净。

## 脚手架清理后的复核

删除了空的 `src/`、`public/`、`adapters/` 和 `tsconfig.json`，移除了未使用的 TypeScript、tsx 和类型依赖。保留网页预览脚本及坐标测试，依赖统一为开发依赖。清理后重新运行 `npm test`（4 项通过）和 `npm run check`（通过）。此次脚手架清理未改变 Android 源码与 APK，校验值当时与清理前一致。后续工程迁移产生的新构建见下方记录。

## 工程迁移后的复核

Android 模块和 Gradle 配置已迁至项目根目录，原版资料归档至 `docs/upstream/`。已更新网页预览、测试、npm 命令及当前文档中的路径。

- 从根目录重新执行 `:mobile:assembleDebug`、`:common:testDebugUnitTest`、`:shared:testDebugUnitTest`，全部通过（45 + 196 项 Android 测试）。
- `npm test` 的 4 项测试与 `npm run check` 通过。
- 启动桌面预览，`/`、`/app.js`、`/style.css` 均成功返回资源。
- 当前分支文档的本地 Markdown 链接检查通过；最新产物校验值见下方。

## 未完成的设备验证

初次验证时本机 ADB 无连接设备，已有 AVD 配置引用的 Android 36 系统镜像不存在，模拟器无法启动。后续已连接 Android 设备用于蓝牙诊断，但 **connectedDebugAndroidTest 没有执行**，离屏 EGL → JPEG 的设备级方向/色彩测试目前只有已编译的测试代码。

本地认证资产现已配置，用户已重新配对并选择 iPhone；仍未验证完整 CarPlay 连接、真实触摸响应、熄屏串流、无线并发、长时间稳定性、温度、带宽或端到端延迟。Robolectric 服务生命周期测试不能代替这些真机验证。

建议首次实测先用 USB 连接 iPhone，Android 服务端与车机浏览器连接同一路由器；确认画面四角方向、点按/拖动、双指松开顺序、浏览器断线重连与服务端熄屏，再验证无线 CarPlay。

## 本地认证资产打包复核

已配置本地运行时认证资产并完成打包检查；资产不纳入 Git。

- `:mobile:assembleDebug` 构建成功。
- APK 内所需运行时资产完整。
- 认证文件格式和配对一致性检查通过；不代表 iPhone 信任验证已通过。
- 三项网页资源仍完整包含在 APK 中。此次仅调整资产打包配置，未重新运行前述完整测试集。

## NIO 入口更新

默认入口名称及图标已替换为 NIO，旧默认 BYD 名称兼容迁移。`:mobile:assembleDebug` 通过；APK 内所需图标及运行时资产完整。尚未验证 iPhone 重连后的图标显示。

## APK 界面统一后的复核

服务、iPhone、设置改为同级导航，共享深色控件与中文文案。原版宣传首页和独立关于页面已替换。连接与设置回调、协议层和后台会话架构保留。

- `:mobile:assembleDebug` 成功。
- `:common:testDebugUnitTest` 48 项、`:shared:testDebugUnitTest` 196 项通过，共 244 项。
- 新增三项测试覆盖同级导航与复用服务 Activity、设置保存跨页保留、320px 窄屏与 800px 横屏下导航和滚动内容布局。
- `:mobile:lintDebug` 无错误，17 项既有警告。
- APK 内 NIO 图标、运行时资产和三项网页资源齐全。
- 当前 ADB 没有连接设备，未完成新版界面的真机视觉检查与 CarPlay 端到端验证。布局检查采用 Robolectric，不等同于真机截图验证。

## Material Design 3 更新复核

主界面改用 Material Components 1.13.0、M3 深色主题及标准 TabLayout。服务 / iPhone / 设置均在 `DiPlayActivity` 内切换，持有原页面，Tab 切换不启动 Activity。原协议宿主仅处理连接与权限流程。

- `:mobile:assembleDebug` 成功；本机直接构建可使用 `.local/maven/` 的官方依赖缓存，无需临时代理进程。
- Android 测试 245 项通过：common 49 项、shared 196 项，0 失败。
- 新导航测试覆盖反复切页不启动 Activity、不更换导航栏和内容视图、MaterialSwitch 设置保存、160px 滚动偏移保留、窄屏与横屏布局。
- Robolectric 原生图形渲染的三个页面已人工检查，图片位于 `common/build/reports/material-ui/page-0.png` 至 `page-2.png`。此为模拟渲染，不是真机截图。
- Lint 无错误，17 项既有警告。
- 打包内 NIO 图标及运行时资产完整；launcher 已指向单一主界面。
- 已通过 ADB 覆盖安装到连接设备，保留应用数据。检查时设备锁屏，真机 Tab 动画检查等待解锁；尚未验证本版完整 CarPlay 会话。

## 服务端系统栏调整

主界面与连接流程始终显示状态栏、导航栏，移除本机全屏设置与旧偏好读取。增加系统栏和屏幕开孔的内容边距处理。`:mobile:assembleDebug` 与四项 `ServerNavigationTest` 通过；本次未重跑完整测试集。浏览器全屏代码未改动。已覆盖安装到连接设备，并在解锁后的服务页、设置页确认状态栏和底部手势导航条可见，内容未被系统栏遮挡。

## 偏好选择器调整

iPhone、连接网络、热点名称/密码及画面音频选项改为无边框设置行，标题与当前值分开展示。构建及四项界面回归测试通过，原生渲染图已检查，保存与重连回调保留。

## 网络接口与地址折叠

构建及七项定向测试通过（接口分类/排序 3 项，界面回归 4 项）。已覆盖安装，并在真机确认 `wlan0` 的普通 Wi-Fi 地址优先，VPN 与移动网络地址默认折叠，展开/收起检查通过。保留接口名、网络类型和使用提示。

## 卡片、底部导航与扫码配对（2026-09-29）

- 服务 / iPhone 使用带标题图标的 MaterialCardView；三个入口使用 BottomNavigationView。页面与滚动状态保留，窄屏及横屏布局测试通过，并检查原生渲染图。
- Common 56 项单元 / Robolectric 测试通过，网页 7 项 Node 测试通过；新增覆盖临时请求期限、其他服务二维码拒绝、重复授权拒绝、轮询凭据隔离、容量限制、令牌撤销、HTTP 同源限制、二维码资源、扫码令牌 WebSocket 握手和手动配对并发保护。
- `:mobile:assembleDebug`、`:mobile:lintDebug`、`npm run check` 通过。Lint 0 错误、17 项现有警告。
- APK 已覆盖安装到当前连接的 Pixel 10 Pro。通过 ADB 转发访问真实 APK 的 Web 服务，浏览器成功显示服务生成的二维码。
- 手机处于锁屏状态，未完成物理相机扫码及实际车机浏览器端的全流程验证；完整 iPhone CarPlay 真机串流等原有待测项仍保留。

## 系统导航栏透明（2026-09-29）

系统导航栏改为透明并关闭系统对比度遮罩；BottomNavigationView 背景延伸至屏幕底部，系统导航栏安全区作为组件内部底部留白，避免按钮被手势条或导航键遮挡。构建及 4 项现有界面回归测试通过，已覆盖安装，并在 Pixel 10 Pro 手势导航模式确认系统导航区与 Bottom Tabs 同色。三键导航未进行真机切换验证。

## 滚动内容与透明状态栏（2026-09-29）

移除主界面固定工具栏，首页 App 名称随内容滚动；顶部安全区放入 ScrollView 内，透明状态栏下按当前页面滚动位置叠加半透明背景。构建和 4 项界面回归通过；原生渲染检查覆盖三个页面的顶部及滚动状态，模拟顶部 / 底部系统安全区。新版已覆盖安装；本轮手机锁屏，未进行真机滚动操作验证。

## 启动页与地址折叠入口（2026-09-29）

服务 Tab 更名为「启动」（Launch），删除第一张卡片上方重复的局域网说明。「其他地址」使用靠右的小字与箭头，行高从 72 dp 收紧到最小 48 dp，保留完整触摸区域；去除展开文字和数量，箭头在展开 / 折叠之间旋转 200 ms，并遵循系统关闭动画的设置。构建和 4 项现有界面回归测试通过，新版已覆盖安装；安装后设备进入锁屏，未完成本轮真机展开 / 收起验证。

## WheelPlay 品牌统一（2026-09-29）

桌面标签（含 debug 变体）、首页、关于、前台通知、车机网页标题 / 品牌、诊断报告名称 / 下载目录、iPhone 连接展示名称及默认厂商 / 型号已统一为 WheelPlay。旧默认厂商 / 型号在读取时转换，自定义值保留。Gradle 项目和 npm 包名同步更新，二维码请求使用 wheelplay-pair:v1。

构建、Common 56 项与 Shared 196 项测试、网页 7 项测试、JS 语法检查通过。检查 APK 打包资源，全部语言的桌面标签和内嵌网页均为 WheelPlay，并已覆盖安装。保留应用 ID、偏好存储键与稳定设备序列号，以支持覆盖升级和原有配置；上游来源及许可证署名保留。

## 应用包名迁移（2026-09-29）

applicationId 改为 moe.feng.wheelplay，Debug 版保留 .debug 后缀。assembleDebug 与 processReleaseMainManifest 通过；APK 元数据确认 Debug 包名为 moe.feng.wheelplay.debug，Release 合并清单确认包名为 moe.feng.wheelplay。源码 namespace 保留原值。新包独立安装，旧包及其数据保留，配置不自动迁移。

## 组件间距检查（2026-09-29）

完成 APK 三个 Tab 与共用组件的边距检查，处理 7 组间距 / 文字列宽问题，详见 [检查报告](SPACING_REVIEW.md)。构建与 5 项界面回归测试通过；覆盖 320×640、360×800（130% 字体）、800×400，每个页面顶部、中段、底部，共 27 张原生渲染图。新版已覆盖安装；本轮手机进入锁屏，真机完整点击检查未完成。

## Bottom Tabs 内部布局复查（2026-09-29）

修正上一轮间距检查遗漏：系统底部安全区放入独立导航背景容器，内部 BottomNavigationView 最小 80 dp，不受安全区 padding 挤压；固定 24 dp 图标，选中和未选中使用一致文字尺寸 / 字重与 20 dp 标签区域，统一字体留白并关闭 fallback 行距扩张。

构建及 6 项界面测试通过，新增覆盖底部 24 / 48 dp inset、100% / 130% 字体、三个选中状态的图标完整可见及文字基线检查。用户解锁后已在 Pixel 10 Pro 逐个切换启动 / iPhone / 设置并检查实际截图与 UI 边界：三个图标均为 72×72 px，Y 范围 2580–2652；三个标签 Y 范围均为 2676–2736，切换时没有位移。新版已安装。该结果限于已测设备与测试配置，不等同于所有设备无差异。

## 设置齿轮图标修正（2026-09-29）

发现此前设置图标是手写简化轮廓，齿形不规则；此前的边界检查不能证明图形本身正确。现替换为 Google 官方 Material Icons settings 的原始矢量 path，保持 24 dp 图标尺寸及既有选中指示器。构建和 6 项界面测试通过，来源和许可记录于 UPSTREAM.md。

## 功能图标统一为官方 Material Icons（2026-09-29）

已替换全部自绘功能矢量图标：启动、车机访问、iPhone、配对、Wi-Fi、USB、设置箭头和服务通知；设置图标继续使用官方来源。启动 Tab 独立采用 play_circle_filled，车机访问与通知采用 cast，网页连接按钮采用 arrow_forward 内嵌 SVG。全部 9 个 Android 矢量资源保留官方原始路径、统一 24dp，来源与 Apache-2.0 许可记录见 UPSTREAM.md。NIO、原有应用品牌位图与功能性二维码矩阵保持原用途。

验证：离线 assembleDebug 成功，6 项 ServerNavigationTest 和 7 项网页测试通过，JavaScript 语法检查通过。逐张检查 Android 原生渲染测试生成的启动 / iPhone / 设置页面，图标完整显示，导航标签对齐。新版已通过 adb install -r 安装；设备当前锁屏，本轮未完成解锁后的真机截图复查。此处不以测试渲染结果替代真机验证。

## 次要操作按钮收紧（2026-09-29）

服务端当前页面中的管理 iPhone、停止服务、断开连接、USB 连接、热点设置、应用权限、系统蓝牙设置、无线连接帮助、诊断导出、取消扫描和返回服务面板统一改成右对齐的 wrap_content 按钮。次要按钮保留 48dp 最小触控高度；扫码配对和连接 iPhone 保留全宽主要按钮。图片裁剪页底部操作也改为右对齐自适应宽度。

assembleDebug 与 6 项 ServerNavigationTest 通过。已检查 320dp 窄屏和 130% 字体渲染截图中的启动、iPhone、设置页面，次要按钮按内容宽度靠右，主按钮保持全宽。通过 adb install -r 安装新版。

## 主要按钮图标与 RTL（2026-09-29）

扫码配对采用官方 qr_code_scanner，连接 iPhone 采用 phone_iphone；连接过程中按钮变为返回服务面板时同步换成 cast 图标。通过 MaterialButton 的 ICON_GRAVITY_TEXT_START 将图标与文字整体居中：LTR 图标在左，RTL 图标在右；24dp 图标、8dp 间距，着色跟随按钮状态。

APK 构建成功，7 项 UI 测试通过，其中新增双方向测试验证图标实际位于对应的起始侧、尺寸与文字同色。已检查两种方向的原生渲染截图，且通过 adb install -r 安装新版。

## 浏览器 Canvas 视频显示（2026-09-29）

网页连接界面增加默认关闭的「使用 Canvas 显示视频（实验性）」。当前浏览器保存选择；WebRTC 解码 video 保持 DOM 布局并移到屏幕外，可见画布通过 drawImage 绘制。优先视频帧回调；无此 API 或回调超过 250 ms 未到达时使用动画帧刷新。绘制失败回退 JPEG，断开 / 媒体更换取消刷新并释放画布缓冲。

- `npm test`：39 项通过；`npm run check` 与 `git diff --check` 通过。新增测试覆盖默认原生视频、选择保存 / 存储禁用、离屏帧复制、动态分辨率、旧浏览器、停顿恢复、迟到回调、JPEG 回退、触控和重连。修正配对测试夹具，使图像赋值触发 onload；修复迟到 JPEG onload 在视频就绪后重新显示旧图片的问题。
- 真实 Chrome + 本地 WebRTC/H.264 合成画面：验证 Canvas 红 / 蓝像素和持续动画，分别检查正常 API、禁用视频帧回调、模拟帧回调不触发三种情况。真实解码从 1280×720 切换到 720×1280 后，像素和比例保持正确；黑边点击不发送触控，画面中心按下 / 松开正确映射；断开清空画布、刷新保留选择、关闭选项恢复原生 video。测试使用合成信令与浏览器发送端，不能代替 Android / iPhone 端到端验证。
- 已检查 1280px / 390px 连接界面及横竖画面截图，文件保存在 Git 忽略的 `.local/canvas-qa/`。本机 VPN 会让无媒体权限的 Chrome 测试只公布 VPN ICE 地址；测试用隔离浏览器和虚拟摄像头授权让本地 WebRTC 可连接，产品代码仍不申请摄像头或麦克风权限。
- `:mobile:assembleDebug --no-configuration-cache` 通过；APK 中的 app.js / index.html / style.css 与当前源码逐字节一致。首次离线构建因当前工程新引入的 Quickie 1.12.0 未缓存而失败，在线构建完成依赖下载后成功；本次未安装到设备。
- 尚未验证蔚来实机、前进档遮罩、功耗或端到端延迟。Canvas 模式不承诺绕过系统级遮罩或恢复被系统暂停的解码；需停车验证。

## 首页卡片与连接操作收紧（2026-09-30）

删除本机浏览器体验按钮下方的说明；「其他地址」改为右对齐的紧凑按钮，悬停、聚焦和按压时显示胶囊背景，保留最小 48dp 触控高度及箭头展开动画。浏览器已连接时隐藏扫码与配对码，断开后恢复。已选择 iPhone 且无进行中的会话时，首页提供「连接」入口，复用现有无线连接流程；iPhone 页连接期间及连接后隐藏连接按钮，移除「返回服务面板」操作。卡片标题行关闭基线对齐，图标与标题垂直居中。

- `:mobile:assembleDebug --offline` 通过。
- `ServerCardStateTest` 3 项及 `ServerNavigationTest` 7 项通过，0 失败。状态测试使用真实本地 WebSocket 握手验证配对区收起、断开恢复及卡片高度缩减；验证已选设备的连接入口、连接流程启动、会话期间隐藏入口与断开后的恢复，以及按钮悬停 / 按压的胶囊背景。
- 已检查状态截图及 320×640、360×800（130% 字体）、800×400 的原生渲染图；各卡片图标与标题的垂直中心检查通过。图片位于 `common/build/reports/material-ui/cards/` 与 `spacing/`。
- 本轮未安装到设备；iPhone 会话状态采用测试模拟，尚未进行本版真实 iPhone 连接验证。

## 浏览器音频与麦克风（2026-09-30）

新增 App 侧独立、默认关闭并持久保存的音频与麦克风转发开关；浏览器页面不再提供这两项设置。Android 解码 PCM 可在配对 WebSocket 上转发，浏览器 PCM 麦克风采样经既有 CarPlay RTP 加密上行。HTTPS 8443 使用每次安装生成的本地 CA 签发短期服务证书，证书私钥不进入 APK。普通 HTTP 仍可播放音频，但服务端不会启用浏览器麦克风。操作步骤与车机浏览器的证书要求见 [BROWSER_AUDIO.md](BROWSER_AUDIO.md)。

- `:shared:testDebugUnitTest` 202 项、`:common:testDebugUnitTest` 93 项完整测试通过，包括 HTTP/HTTPS 音频 WebSocket 集成测试和 App 主导的独立音频路由测试。
- `npm test` 43 项通过，`npm run check` 通过；新增测试覆盖 PCM 帧解析、持续降采样、播放确认与清理、非 HTTPS 麦克风拒绝和断线恢复。
- `:mobile:assembleDebug` 与 `:mobile:lintDebug` 通过；Lint 0 错误、18 项警告，其中一项来自新增的 Bouncy Castle PKIX 依赖代码。
- 真实本地 TLS 握手测试验证签发证书主机名、可信根证书访问、HTTPS WebSocket 同源校验、普通 HTTP 拒绝麦克风，以及 HTTPS 浏览器 PCM 麦克风数据接收。真实 TCP/WebSocket 测试验证浏览器音频帧格式、确认与有界队列。
- 本地浏览器预览曾确认音频按钮与视频画面并存；之后开关迁移到 App 设置，网页已移除按钮。此预览未连接 iPhone；尚未在车机验证证书信任、实际音频焦点、回声消除、通话质量和端到端延迟，也未安装此版 APK 到设备。

## 产物

`mobile/build/outputs/apk/debug/mobile-debug.apk`

SHA-256：

```text
34d69d6cb165e8018cbab65a03b35f0d9780aba955823de2cc3f10caf45f3655
```

包名 `moe.feng.wheelplay.debug`，调试签名，此本地产物包含上述 CarPlay 认证资产。
