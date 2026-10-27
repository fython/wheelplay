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

主界面改用 Material Components 1.13.0、M3 深色主题及标准 TabLayout。服务 / iPhone / 设置均在 `WheelPlayActivity` 内切换，持有原页面，Tab 切换不启动 Activity。原协议宿主仅处理连接与权限流程。

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

## 浏览器设备记忆与管理（2026-10-01）

首次二维码或配对码授权后保存浏览器的随机设备凭据。再次打开网页自动恢复配对，保留启动选项及「启动显示」按钮；恢复配对本身不占用串流、音频或触控会话。主动断开、刷新及服务重启保留设备记忆，已有串流遇到服务重启时可用设备凭据重新认证。App「设置 → 浏览器设备 → 连接过的设备」支持查看名称、首次配对时间、最近连接时间与地址，重命名、单台移除及全部移除。移除同步撤销会话并关闭连接，持久保存时仅记录随机密钥哈希，延续现有备份排除规则。

- `npm test`：54 项通过；`npm run check`、预览脚本语法检查和 `git diff --check` 通过。新增覆盖首次扫码等待启动、手动配对并发保护、刷新自动恢复、启动前 options、设备撤销、临时离线保留记忆、访问链接配对、页面恢复、存储不可用以及串流重连重新认证。
- `:common:testDebugUnitTest`：101 项通过，0 失败 / 错误 / 跳过。真实 TCP / WebSocket 覆盖服务重启后恢复、同源校验、错误密钥拒绝、配对码限流、设备移除断开及旧凭据失效、全部移除；持久存储测试覆盖仅保存哈希、注册表重建、名称与最近地址更新、容量限制。App 操作测试覆盖设置入口、重命名、单台移除和全部移除。修复共用文本输入框传入错误布局参数引起的崩溃。
- `:mobile:assembleDebug` 与 `:mobile:lintDebug` 通过；Lint 0 错误、18 项警告。APK 内的 app.js / index.html / style.css 与当前源码逐字节一致。
- 内置浏览器访问本地合成画面夹具：首次配对并启动、刷新后免输入恢复配对、启动前勾选 Canvas、点击启动及主动断开保留设备记忆均通过。恢复配对页面截图保存在 Git 忽略的 `.local/browser-device-qa/remembered-browser.jpg`。该夹具不是真实 CarPlay 输出。
- 设备记忆按浏览器访问地址（协议、主机、端口）隔离，切换地址或清理浏览器数据后需重新配对。本轮未安装到设备，未完成车机 / iPhone 端到端实测。

## CarPlay MediaSession（2026-10-08）

前台服务维护系统原生 MediaSession、MediaMetadata、PlaybackState 和 MediaStyle 通知，后台控制器接收 iAP2 Now Playing 增量元数据及 JPEG 文件传输。支持标题、歌手、专辑、封面、状态、时长、进度，以及 HID 播放、暂停和切歌。浏览器接管音频后保留控制，使用固定远程音量；本机播放增加音频焦点管理。协议字段、线程模型、交付缺口和真机验收项见 [MEDIA_SESSION.md](MEDIA_SESSION.md)。

- Shared 218 项、Common 113 项测试通过，0 失败 / 错误 / 跳过。覆盖增量更新、异常字段、切歌、封面分片与上限、过期结果隔离、文件与导航消息排序及重传、发送阻塞期间断线、通知及旧回调、焦点、浏览器接管和本机回退。Robolectric shadow 记录 MediaSession API 并关联 MediaController，不能代替真实 Binder、蓝牙或真机验收。
- 54 项 npm 测试、`npm run check` 和 `git diff --check` 通过。assembleDebug 通过，包含 arm64-v8a、armeabi-v7a 和 x86_64 原生库；Manifest 包含 mediaPlayback 服务类型及权限。
- 下载缓慢时使用经过 SHA-512 校验的官方 Maven Robolectric SDK 缓存，以及项目固定提交的本地 MbedTLS / libdatachannel 源码，通过 Git 忽略的 `.local/` 中的临时 Gradle 初始化脚本配置。Gradle 临时使用 JDK 21，测试使用 JDK 25。原有 HTTPS 测试在 JDK 21 上未通过 JCE 框架校验，但在 JDK 25 上全部通过；原 Java 25 工具链配置已恢复。
- 0x5003 ElapsedTime 的发送单位及 seek 前后的音频连续性尚未验证。seekTo 返回 UNSUPPORTED，不声明 ACTION_SEEK_TO。回放夹具为合成样本，非真机抓包；完整 seek 交付标准尚未满足。
- V2547A（API 37）安装返回 `INSTALL_FAILED_ABORTED: User rejected permissions`，未继续重试。启动、有线 / 无线 iPhone、锁屏、A2DP / AVRCP 和浏览器音频端到端验收尚未完成。

## MediaSession APK 认证资产打包修复（2026-10-08）

首个 MediaSession APK 提示认证资产未就绪。检查确认该 APK（SHA-256 `e8b7db0af82136b79cadc05212a35a0751e89b2ba777ba7cd0c530e01f0de95e`）缺少 `assets/offline-mfi/identity.pk8` 和 `certificate.p7b`：未配置资产目录环境变量，默认 `.local/auth-assets/` 也不存在。此前产物说明沿用了旧构建的资产记录，未核对新 APK；此处更正。Debug 构建可以包含认证资产。

- 从设备上已安装的原版 DiPlay APK 恢复现有运行时资产，保存至 Git 忽略的 `.local/auth-assets/`，未生成新身份。OpenSSL 校验单一证书与私钥的公钥一致；未输出或提交资产内容。
- 新增 `:mobile:assembleProvisionedDebug`，构建后验证两项 ZIP 条目存在、非空，且与本地输入逐字节一致。普通 assembleDebug 继续支持不含资产的 CI 构建；两个 README 均说明完整构建方式。
- 完整构建及独立 ZIP 检查通过，Java 25 配置已恢复。本阶段仅修改打包、配置和文档，未重复运行原有媒体测试。
- 本次检查未安装修复后的完整 Debug APK；资产加载及实际 iPhone 信任、连接仍需单独验证。

## 首次连接封面（2026-10-08）

`WheelPlay-20261008-144708-214.txt` 确认手动热点方式启动 AirPlay，并完成蓝牙到 Wi-Fi 的切换；14:44:47.699 出现 HEVC 首帧，后续视频与 AAC 音乐持续运行。用户报告首次连接没有封面，切歌后才出现。该日志缺少文件传输诊断，无法唯一确定封面丢失的顺序。

- 同一 iPhone 切换至隧道时保留已完成的封面，清除旧传输 ID 的关联。重新报告 ID 不清除图像；真实切歌和新连接仍清理旧封面。
- 修复 SETUP 早于元数据 ID 到达时被取消的竞态。未关联的 SETUP 在四项 / 15 秒限制内等待；旧曲目传输仍会取消。
- 首次收到标题 / PID 不再被误判为切歌而取消正在接收的封面。新增不含歌曲或图像内容的传输诊断。
- Shared 223 项、Common 113 项完整测试通过，0 失败 / 错误 / 跳过。五项新增回归覆盖早到 SETUP、增量初始身份、隧道封面保留与 ID 重报、旧曲目取消，以及合成对端下的真实链路 / 文件队列。完整 APK 构建、独立认证资产比较和差异检查通过。
- 同一曲目重连时的首次通知封面仍需用户复测；未自动操作 iPhone，也未声称完成真机验收。

## 封面复测与响应背压（2026-10-08）

`WheelPlay-20261008-151140-090.txt` 中最新会话于 15:03:45 开始。封面 129 的 START 出现在 15:03:51.481，但没有 SUCCESS。隧道在 15:03:52.667 取消 128 后，文件工作线程不再输出诊断，控制消息仍持续报告 129 / 130 / 131。此前启动顺序修复未通过此次真机复测。较早会话在 14:42:37.815 记录 File-transfer session unavailable，但最新会话没有记录同一异常。

- 复现发送缺陷：短暂的 Event.Writable(false) 被 sendFileTransfer 的 !ready 条件误判为缺少文件能力。文件线程吞掉异常并退出，控制消息仍继续。该行为与症状一致，但缺少当前异常及协商细节，不能确定它是唯一真机原因。
- 文件响应按已协商能力进入现有有界发送队列，等待链路确认。未协商、已终止链路和队列超时仍拒绝发送。响应失败只清理对应传输，后续封面继续处理；线程异常退出会记录日志。START / CANCEL / SUCCESS 仅在队列接受后记录 queued，不代表 iPhone 已收到。
- 新增会话 ID / 类型 / 版本、SETUP 长度 / 类型 / 声明大小、首末分片累计大小、异常数据和发送失败诊断，不记录歌曲、图像或应用 bundle ID 内容。按 Apple R32 第 54.3.3 节及 MEDIA_SESSION.md 中的解析器参考核对协议结构，未猜测字段。
- 三项回归覆盖真实链路窗口背压解除后响应送达且控制继续、START 被拒绝后清理旧数据且后续封面完成，以及切歌取消失败不影响控制循环。队列接受断言在修复前失败，修复后通过。Shared 226 项、Common 113 项完整测试通过，0 失败 / 错误 / 跳过。
- 完整构建通过。诊断补充后再次运行 Shared 测试及构建；构建校验与独立 ZIP 比较确认认证资产完整，Java 25 配置已恢复。未自动安装或操作 iPhone，首次连接及切歌封面仍待复测。

## 歌词 / 元数据刷新导致封面清空（2026-10-08）

`WheelPlay-20261008-153102-285.txt` 记录了七次封面解码成功，没有 SEND FAILED 或文件线程退出。封面 137 在 15:29:31.084 完成，但 15:29:34.251 曲目修订从 24 变为 25，封面 ID 变为 none，之后约每 3.5 秒重复。增量更新省略 PID / 队列字段时，状态归并回退为标题比较，将变化的文本误判为曲目边界，清空封面和进度。日志确认更新触发了清空，但未记录字段值，不能将每次边界都归因于歌词而非 PID / 队列变化。

- 移除标题切歌判断。标题、歌手、专辑属于增量显示元数据；省略身份字段时保留已有身份。明确的播放器、已知 PID 或队列变化仍清理封面和进度。确认曲目边界后清除旧 PID / 队列字段，避免随后分批收到新身份时再次清空新封面。缺少身份时不根据标题猜测边界，详见 MEDIA_SESSION.md。
- 同曲目更换封面传输 ID 时保留有效图像，直到替代图像成功解码；损坏替代图像不清除旧图像。真实曲目、播放器、连接变化及断线仍清理封面，旧解码结果受曲目修订和 ID 限制。
- 新增 track changed / metadata refreshed、身份判断原因、变化字段名以及 PID / 队列是否存在的诊断，不记录身份值、歌词或应用名称。
- 七项新增回归覆盖省略身份的歌词更新保留封面 / 进度、无稳定身份的标题更新、分批曲目身份、播放器切换清理、真实文件队列中传输期间及之后的 20 次歌词更新、成功 / 失败封面替换与真实切歌，以及解码阻塞跨越曲目边界。三项针对性检查在修复前失败，修复后通过。
- Shared 233 项、Common 113 项完整测试通过，0 失败 / 错误 / 跳过。完整构建、独立资产检查及差异检查通过，Java 25 配置已恢复。未自动安装或操作 iPhone；歌词刷新及真实切歌时的通知端到端行为仍需真机复测。

## 媒体通知矢量图标（2026-10-08）

媒体操作采用官方 Material Icons 24dp 单色 VectorDrawable，通过资源形式的 Icon 提供。连接通知使用同系列的停止服务图标；来源及许可记录于 UPSTREAM.md，媒体操作的无障碍标签使用字符串资源。

- `:common:testDebugUnitTest`：113 项通过，0 失败 / 错误 / 跳过。`:mobile:assembleProvisionedDebug` 通过；独立 ZIP 检查确认五项矢量资源及与本地逐字节一致的认证资产。
- 检查矢量资源的 24dp 尺寸、24×24 视口及不透明白色路径。`git diff --check` 通过，Java 25 工具链配置已恢复。本轮未安装设备或验证原生通知截图。

## MediaSession 审查修复（2026-10-08）

审查发现三项问题：旧封面线程可能在复用的有效 iAP2 链路上消费数据或发送 FAILURE；同步更新封面预期状态可能因解码或响应背压阻塞控制 / 导航循环；输出暂停或被拒绝时，音频空闲维护可能每约 10 毫秒提交一次本机焦点请求。

- 将每次绑定启动的线程改为贯穿控制器生命周期的单一封面消费者。元数据发布最新不可变传输预期，不进入接收器锁。连接替换使旧响应在入队和发送前失效；底层字节流、排序和重传仍由链路线程独占。
- 新增解码阻塞期间同链路重连并复用传输 ID 的回归，验证不会启动第二个消费者，也不会向合成 iPhone 对端发送旧 FAILURE / SUCCESS。强化真实切歌回归，在释放解码器前从控制队列读取导航帧。两项真实链路窗口测试拒绝等待队列容量或已排队等待确认的旧响应。
- 本机输出权限检查不再触发请求。只有无法远程转发的实际音乐 PCM 会产生合并后的焦点需求。三项 Robolectric 测试使用真实 LPCM 渲染流程，覆盖空闲维护、重复受阻 PCM 与权限恢复后的重新请求、浏览器回退及导航独立处理。
- Shared 236 项、Common 116 项完整测试通过，共 352 项，0 失败 / 错误 / 跳过。新增六项测试并强化一项已有测试。`:mobile:assembleProvisionedDebug`、独立认证资产 ZIP 比较、打包矢量检查和 `git diff --check` 通过；原 Java 25 配置已恢复。
- 保留原 CarPlay 服务通知的频道、状态及停止文案；媒体通知操作标签使用字符串资源。
- 未自动安装或操作 iPhone。同链路重连、锁屏封面 / 控制、暂停输出、浏览器切换及 A2DP / AVRCP 仍需真机验收。seek 仍等待协议验证，暂不可用。

## 移除媒体通知的停止服务操作（2026-10-08）

媒体通知仅包含上一首、播放 / 暂停和下一首；原连接通知保留停止服务操作、文案及矢量图标。

- 八项 MediaSession bridge 测试全部通过。已有服务测试检查播放与暂停期间的三项操作，以及恢复连接通知后的停止服务 Intent。
- `:mobile:assembleProvisionedDebug`、独立认证资产 ZIP 比较和 `git diff --check` 通过，Java 25 配置已恢复。未安装设备或验证通知截图。

## Root 热点路由与自定义 HTTPS（2026-10-10）

Tesla HTTP 模式改为内置 VPN Hotspot 的 Root 路由适配，按实际热点接口共享 WheelPlay 的 HTTP/HTTPS 服务。共享地址支持一般 IPv4 单播地址；Web HTTPS 增加持久端口设置、系统文件选择器导入 PEM 证书/私钥及 PKCS#12，支持加密私钥、多个私钥条目和不同的私钥密码。

- `:common:testDebugUnitTest` 146 项、`:shared:testDebugUnitTest` 239 项通过，共 385 项，0 失败 / 错误 / 跳过。Gradle 守护进程使用项目指定的 Java 25；本轮测试进程通过仓库外临时初始化脚本使用本机 JDK 21，仓库工具链配置未修改。
- Root 测试覆盖输入校验、拒绝授权后不重复申请、热点 / 端口变更、异常退出和清理失败；真实 shell 配合命令替身验证成功步骤逆序撤销、部分失败回滚和 stdin EOF 清理。未执行真实 Android 内核路由操作。
- HTTPS 测试包含可信客户端的真实 TLS 握手、PEM / 加密 PEM / PKCS#12、错误密码、私钥不匹配、导入大小限制、持久保存与恢复默认。端口占用时保留原监听器及配对状态；新端口与重启后的保存值验证通过。文件选择器验证 `ACTION_OPEN_DOCUMENT`、`CATEGORY_OPENABLE` 与可选文件类型。
- `npm test` 56 项通过，JavaScript 语法检查通过；新增覆盖 HTTPS 自定义端口、IPv6 地址及失效入口撤下。`git diff --check` 通过。
- `:mobile:lintDebug` 通过，0 错误、18 项警告。`:mobile:assembleProvisionedDebug` 通过，并验证本地认证资产与 APK 条目逐字节一致；APK 包含网页资源、VPN Hotspot 许可证，以及 arm64-v8a、armeabi-v7a、x86、x86_64 原生库。
- 本轮未安装到设备。Root Android 热点客户端与 Tesla 的双向访问、真实规则清理、第三方 VPN 并存以及车机证书信任仍需真机验证；本适配不提供第三方 VPN 的 Internet 转发。

## 显式 Root 授权与低位端口（2026-10-10）

普通 App 绑定 `80` / `443` 时可收到 `EACCES`；路由 shell 获得 Root 不改变 App 的绑定权限。新增「设置 → Root 权限 → 请求 Root 权限」，独立执行授权检查并展示结果。`1–1023` 通过 Root 将发往本机的 IPv4 / IPv6 TCP 连接转发到动态高位监听器，继续由原 HTTP / TLS 服务处理；公开端口与浏览器 origin 保持用户配置。

- Common 156 项、Shared 239 项完整测试通过，共 395 项，0 失败 / 错误 / 跳过；Lint 0 错误、18 项警告。
- 新增 10 项回归覆盖：按钮独立授权、无规则权限探测、拒绝授权的错误处理、低位端口占用及已有转发保护、IPv4/IPv6 规则与失败回滚、清理失败阻止新转发、80 的高位监听及配置回退、443 的真实 TLS 握手 / 证书更换 / 重启，以及转发进程退出后的入口撤下和恢复。内核命令使用记录替身，未执行真实设备 NAT。
- arm64 发行版构建通过，包名 `moe.feng.wheelplay`，版本 `0.2.0` / 20，沿用现有发行密钥。`apksigner verify`、签名 SHA-256 指纹对照、仅 `arm64-v8a` 检查及 ZIP 对齐检查通过；认证资产与网页资源逐字节对照通过，APK 包含新的 Root 授权与端口转发类。
- 本轮未安装到设备；Magisk 授权弹窗、实际 80/443 NAT、热点共享及规则清理仍待用户真机复测。

## EC 证书导入算法名称兼容修复（2026-10-10）

未加密的 PKCS#8 PEM EC 私钥原本可完成签名匹配校验，但 PEM 转换器将其算法标为 `ECDSA`。Android Conscrypt 从 PKCS#12 取得 `PrivateKeyEntry` 时，要求私钥算法名称与服务器证书公钥的 `EC` 完全一致，因此导入时误报算法不匹配。导入准备阶段统一转换为 `EC`，保留原私钥、曲线及完整证书链；PEM 与 PKCS#12 共用此处理。

- 使用用户提供的原始文件及空密码，在 Android Conscrypt 密钥管理器与 Bouncy Castle PKCS#12 存储组合下复现相同异常；修复后导入、PKCS#12 规范化及按证书域名验证的真实 HTTPS 请求通过。个人文件及临时验证源码保存在 Git 忽略的本地目录中，未加入应用源码或资产。
- 新增生成式 EC 回归夹具，覆盖 P-256 服务器证书与 P-384 签发链、PKCS#8 / SEC1 / 加密 PEM、PKCS#12、持久保存后重新加载及错误私钥拒绝。测试调用 Android Conscrypt 的实际密钥管理器；桌面默认 SunPKCS12 会重写算法名称，单独使用它无法捕获原始错误。
- Common 159 项完整回归通过，0 失败 / 错误 / 跳过；Lint 0 错误、18 项警告。新夹具使用直接 TLS 握手与双向加密数据交换，检查完整证书链及主机名；用户文件的临时检查另行验证了真实 HTTP 服务上的 HTTPS 请求。
- 新版 arm64 发行包的签名、现有签名指纹、ZIP 对齐及认证资产 / 网页资源逐字节检查通过，确认个人 HTTPS 证书、私钥与临时验证测试没有进入 APK。本轮未安装到设备，导入界面的真机复测仍待完成。

## Web 监听与 Tesla 兼容设置重组（2026-10-10）

「Web 监听」集中管理无需 Root 的 HTTP 端口、HTTP 域名和可选 HTTPS。HTTPS 开关停止或恢复 TLS 监听，保留证书、端口与 HTTP 配对；开启后显示 HTTPS 端口和页面内的默认 / PEM / PKCS#12 证书选择器。「Tesla 兼容（实验）」第一行请求 Root，仅本次检查成功后显示模式开关、低位端口映射与自定义 IPv4 地址。80 / 443 映射到普通高位监听，仅匹配配置的访问 IP，与热点路由统一安装和清理。

- Common 168 项、Shared 239 项完整测试通过，共 407 项，0 失败 / 错误 / 跳过。测试进程继续使用本机 JDK 21 临时初始化脚本，项目守护进程与工具链配置保留 Java 25。
- 新增和调整的回归覆盖：Root 授权前后控件、拒绝授权、页面内证书类型及文件选择、HTTPS 开关的监听与配对状态、启用时端口占用回退、映射条件、HTTPS 链接的映射端口选择，以及旧域名 / 高低位端口迁移与端口冲突处理。
- 使用 Robolectric 原生渲染检查 360 dp 页面，核对授权前后排版；截图保存在忽略的 `common/build/reports/web-settings/`。Root shell 的事务安装、映射失败回滚与清理仍由记录命令替身验证，未操作真实 Android 内核。
- 网页测试 57 项通过，JavaScript 语法检查通过；覆盖 HTTPS 关闭时撤下入口并提示在 Web 监听中开启。Lint 0 错误、18 项警告；`git diff --check` 通过。
- arm64 发行版构建通过。`apksigner verify`、原发行证书 SHA-256 指纹、16 KiB ZIP 对齐和仅 `arm64-v8a` 检查通过；认证资产及网页资源逐字节一致，个人 HTTPS 证书 / 私钥与临时测试材料未进入 APK。
- 本轮未安装到设备。Magisk 授权、热点客户端双向访问、低位端口实际映射、HTTPS 证书信任及 App 退出后的规则清理仍需真机验证。

## 自动 Root 检查与 addrtype 兼容修复（2026-10-10）

App 启动时后台检查一次 Root，检查成功自动更新设置控件并恢复已启用的热点路由；拒绝后等待手动重试，普通 Web 监听不等待 Root。HTTP 域名改名为「Web 域名（可选）」，说明其同时用于 HTTP / HTTPS，并要求自定义证书覆盖该域名。

- Common 172 项完整回归通过，0 失败 / 错误 / 跳过；Lint 0 错误、18 项警告。新增覆盖启动后台检查、拒绝后不循环请求、手动重试，以及回滚后保留具体失败命令 / NAT 预检错误。Shared 未改动，沿用此前 239 项通过的结果。
- 在用户的一加 7 Pro（GM1911，EvolutionX Android 16，Magisk，iptables 1.8.11 legacy）上通过 ADB 精确复现：`addrtype` 提示缺少内核模块并返回 `No chain/target/match by that name`；conntrack、state、comment 均可用。临时测试链不挂接真实流量并在退出后清理。去除冗余 addrtype 后，精确目的 IP 的 DNAT 规则可安装；保留原连接状态约束。
- 同发行签名覆盖安装 arm64 修复版，保留设备数据。两个冷启动均在未点击 Root 按钮的情况下授权成功、添加访问 `/32` 并安装映射；从手机自身请求映射 HTTP 80 和 HTTPS 443 成功，HTTPS 使用配置域名并通过系统 CA / 主机名验证，未关闭 TLS 验证。
- 覆盖安装与冷启动后的自定义证书指纹一致，HTTPS 元数据返回映射端口 443。手机设置页面确认「Web 域名」文案及 Root 路由已启用；未更改用户域名、访问 IP、端口、HTTPS 开关或证书配置。
- 强制停止 App 后，其 NAT / 策略路由与访问 IP 已清理，系统热点规则保留；重新启动后映射和 HTTPS 恢复。发行包签名、原签名指纹、16 KiB 对齐、仅 arm64 ABI、认证资产及网页资源校验通过，个人 HTTPS 私钥未进入 APK。
- 上述访问测试来自手机自身，未验证另一台热点客户端的入站连接及 Tesla 浏览器；热点关闭 / 重开和第三方 VPN 并存仍待复测。

## 同步上游 main 后的回归（2026-10-10）

将单笔功能提交重放到上游 `1dc11aa`，保留认证资源导入、认证校验和浏览器全屏启动功能。合并启动流程时，缺少有效认证资源仍不能开启监听器或获取唤醒锁；TLS 测试复用上游合成认证资源，不读取个人私钥。

- Common 194 项、Shared 239 项完整回归通过，共 433 项，0 失败 / 错误 / 跳过；网页测试 64 项及 JavaScript 语法检查通过。
- `:mobile:lintDebug`：0 错误、18 项警告；`:mobile:assembleDebug` 构建通过。Debug APK 中的两份认证资源与本机已有配置一致，未包含额外凭据，网页资源与当前源码一致；本地认证资源及 APK 未提交。
- Robolectric 原生渲染检查授权后的 Web 监听及 Tesla 兼容设置；上游未配置资源时的启动限制、认证导入和全屏启动均由合并后的完整回归覆盖。
- 此轮未重新安装到手机；此前真机验证对应同步上游前的版本。另一台热点客户端和 Tesla 浏览器仍待验证。

## PR Review：启动清理保护与 EC 参数块（2026-10-10）

- 启动失败且回滚清理未确认时保存独立阻塞状态，本次进程内的手动重试、地址/端口/热点配置变化及 Web 服务停止/重启均不会安装新规则，也不会发布共享入口。正常拒绝授权或已确认回滚的失败仍可重试。
- 传统 EC PEM 私钥支持一个前置 `EC PARAMETERS` 块，兼容命名曲线及显式参数；保持单把私钥与无额外尾随对象的校验。
- 新增 3 项回归：生成的真实事务 shell 同时模拟启动失败与回滚失败；参数块前置的 EC 私钥经 Android Conscrypt KeyManager 完成可信 TLS 握手、保存与重载；缺失/多把私钥或额外对象被拒绝且原身份保持有效。前两项在旧代码上失败，修复后通过。
- Common 197 项、Shared 239 项完整回归通过，共 436 项，0 失败 / 错误 / 跳过；Lint 0 错误、18 项警告；Debug 构建通过。网页代码未变，沿用此前 64 项网页测试及语法检查结果；此轮未重新安装到手机。

## WebSocket 空关闭帧 NPE 修复（2026-10-15）

浏览器（真实 Chrome）关闭 WebSocket 时，App 端 NanoHTTPD 抛出 `NullPointerException: Parameter specified as non-null is null: method …LanWebServer$Client.close, parameter code`（栈 `NanoWSD$WebSocket.handleCloseFrame:186`），异常离开处理线程导致未发送 close 回复、未释放 viewer。模拟器实测复现 3 次（同一「断开」点击路径）。

根因（读 NanoWSD 2.3.1 真实源码，sources jar sha256 `0852067223ffa2c6cbf50536964296651f454c43f9bde4536615c7f8d65baa2b`）：`handleCloseFrame` 对无状态码关闭帧传入 `code=null/reason=null`；`CloseFrame.generatePayload(code==null)` 生成空 payload，回帧本就应为「无状态码 close」，不得合成 1000/1005。本项目 Kotlin 覆写把 Java 平台参数声明为非空，触发编译器 null 检查。

修复：`LanWebServer.Client.close` 与 `onClose` 的平台参数放宽为可空并原样透传，保留「异步写出 close 回复 → afterSend → cleanup」设计；未 catch/suppress、未硬编码状态码，未改前端重连、协议校验、权限、telemetry 或依赖版本。

公开线协议回归（真实 socket，非反射 / 源码文本断言）：新增 `LanWebServerTest.emptyCloseFrameRepliesProtocolCorrectlyReleasesViewerAndAllowsReconnect` —— masked 空 Close → 断言服务端回 `OpCode.Close` 且 payload 为空、连接被服务端收尾（read=-1）、`WebSession.hasViewer=false`、随后重连首条帧为 `audio-route`（排除「101 但 busy」假通过）。用例绑定独立空闲端口并在 finally 还原端口偏好，避免默认端口占用影响该协议边界的判定。

- RED（修复前）：`java.io.EOFException`（LanWebServerTest.kt:367）；GREEN（修复后）：`tests=1 failures=0`。
- 如实说明：整类 `LanWebServerTest` 未在本机通过 —— 主机默认端口 8080 由其它服务（com.docker）占用且未停用该服务，依赖 8080 的既有用例报 `BindException: Address already in use` 并级联失败；本次只确认目标回归用例通过，不声称完整套件通过，也未做修复前后整类对比。

模拟器复验（API 36）：刷新后「已自动恢复配对」，真实点击「断开 / 启动显示」多轮全部干净恢复；观测窗口内 `NanoHTTPD|NullPointerException`=0、`FATAL EXCEPTION`=0、ANR=0；断开后设备侧事后核实出现新 ESTAB 连接，证明重连真实建立。窗口与计数只针对实际留存的日志，不宣称所有 buffer 绝对无丢失。

未测边界：无真实 iPhone、无实车、未在手机端验证；CarPlay 会话建立、画面 / 音频投送、触控回传、USB / Wi‑Fi Direct 硬件通路均未验证。运行时权限状态与修复前一致（`NEARBY_WIFI_DEVICES` / `BLUETOOTH_CONNECT` 由系统权限界面授予，其余未触发）。

## 产物

以下发行包为同步上游前用于真机验证的本地产物，本轮仅重新构建 Debug。

`mobile/release/wheelplay-0.2.0-arm64-root-fix-092bba0b.apk`（53.9 MiB）

SHA-256：

```text
092bba0b333bd17e3d045a41eaee512a6c05b3708aadd8a8ed656bdf877aca56
```

包名 `moe.feng.wheelplay`，发行签名，此本地产物包含上述 CarPlay 认证资产。

## WebRTC 三种渲染方式（2026-10-21）

连接页改为原生 Video（默认）、兼容 Canvas、直接 Canvas 三张单选卡片。直接 Canvas 使用主线程 MediaStreamTrackProcessor 与 Canvas 2D，不创建 Video 元素；不支持或运行失败直接使用 JPEG。旧 Canvas 偏好迁移，用户选择、自动重连及失败后的选择均保留。

- Node 自动化：94 项通过，覆盖三个适配器、旧模式回归、单选与迁移、存储错误、逐帧释放、reader 取消及迟到任务、首帧等待、三档恢复时间、解码继续而绘制停滞、静态画面、JPEG 回退、横竖触控、迟到 JPEG 加载 / 错误和重连隔离。新增检查确保网页模板及 JS 消息在 Android BrowserStrings 中导出，避免桌面夹具正常而 APK 缺少文案映射。
- `npm run check`、`git diff --check` 通过。Android `:mobile:assembleDebug --no-configuration-cache` 构建成功；BrowserStringsTest 的 1 项和 LocalizationTest 的 7 项 Robolectric 测试通过，后者使用 APK 网页模板检查英文、简体、繁体资源。构建需显式设置本机 ANDROID_HOME；首次未配置 SDK 的尝试失败后已按现有 SDK 路径完成构建。
- 真实 Chromium + 合成 WebRTC/H.264：三个模式均收到解码画面，首帧 ready 每次仅一次；两种 Canvas 的绿色测试区域像素正确。直接 Canvas 在非安全 HTTP origin（isSecureContext=false）及 HTTPS origin 均绘制成功，1280×720 改为 720×1280 后尺寸正确，Video 元素数量始终为 0。两个旧模式播放时各有一个 Video，断开后均移除，无页面 JS 错误。
- HTTPS / 非安全 HTTP origin 由浏览器 QA 路由代理提供资源与合成信令，用于验证页面环境下的 API 和真实浏览器解码，不验证 Android HTTPS 证书、服务端媒体发送或真实热点网络。另以真实本地 demo WebSocket 验证禁用 Processor 后接收 1280×720 JPEG、显示回退提示且 Video 数量为 0。
- 已检查 1280px / 390px 页面截图、卡片选中状态、键盘方向键切换和窄屏无横向溢出。前台动态夹具观察到约 30 次 / 秒绘制；后台 QA 页会受调度限制，以上结果不作为性能保证。截图及浏览器夹具保存在 Git 忽略的 `.local/renderer-qa/`。
- 待实机验收：Android / iPhone / 目标车机端到端、真实 HTTP / HTTPS 入口与证书、静态一分钟后恢复、UDP 短暂断流及超时回退、HEVC、功耗和 60 fps。未安装 APK 到设备，未宣称绕过车机系统遮罩或后台解码限制。

## 真机链路验证：三种渲染方式（2026-10-21）

设备为 Pixel 3（Android 15）和无线连接的 iPhone XS Max，显示端为 macOS Chromium / Chrome，经真实局域网 HTTP 访问 Android Web 服务；没有代理配对、信令或视频数据。设备 APK 与本地 Debug APK 的 SHA-256 一致。浏览器固定 1280×800，对应 1280×720 CarPlay 画面。以下是该组合的观测结果，不代表其他车机或设备的性能。

| 检查 | 结果 |
| --- | --- |
| 原生 Video / 兼容 Canvas / 直接 Canvas | 均通过真实 H.264 出画；三次连接首帧分别约 549 / 406 / 599 ms；采样未见丢包 |
| 直接 Canvas 的元素与绘制 | Video 数量始终为 0；动态画面曾观测到约 29 fps，源端静态降帧时同步下降 |
| 静置一分钟 | 60 次采样均保持 track-canvas，无回退、无 Video；包含源端 0 fps 和绘制 0 fps 的时段，未误判停滞 |
| 真实触控 | 浏览器点击 CarPlay 主屏按钮后，iPhone 输出切换动画；JPEG 回退后仍可触控并收到更新图像 |
| 短暂渲染停顿 | 包装原生 Processor reader，仅阻塞应用读取约 3 秒；实际 WebRTC 仍解码约 26–30 fps。恢复读取后继续直接 Canvas，无回退 |
| 持续渲染停顿 | 在持续触控动画输入下阻塞读取，默认 10 秒容忍策略约 12.1 秒后触发 render-stalled；真实 JPEG 恢复为 1280×720，Video 仍为 0 |
| 控制连接重连 | 主动关闭真实客户端 WebSocket，约 1.76 秒自动恢复直接 Canvas，模式选择保留、Video 为 0 |
| HEVC | 临时开启 iPhone HEVC 并重新连接；真实 HEVC → WebRTC → 直接 Canvas 出画，1280×720，动态采样约 24–30 fps，Video 为 0、未见丢包 |

故障注入只阻塞应用层对原生 Processor 的读取，未替换发送端或浏览器解码器；该项不等同于 UDP 丢包测试。记录与脚本保存在 Git 忽略的 `.local/device-renderer-qa/`，截图可能包含真实 CarPlay 内容，不纳入版本库。

额外观察：兼容 Canvas 在静态低帧率时可能触发动画帧补偿，同一解码帧重复绘制约 120 次 / 秒；这不是源视频帧率，也未测量功耗。直接 Canvas 在没有新帧时不提交绘制。开启「自适应浏览器尺寸」时，窗口变化会触发整个无线 CarPlay 重协商；本次曾等待约 40–50 秒才恢复源画面，因此固定窗口完成模式对比。

测试后关闭临时 HEVC 设置，保持原有 30 fps、10 秒恢复策略及尺寸自适应设置，并恢复用户原 Chrome 的直接 Canvas 会话。尚未覆盖目标车机浏览器、真实 UDP 阻断、设备 HTTPS 证书、60 fps 与功耗。

## 真机补测：直接 Canvas 的 60 fps 协商与稳定性（2026-10-21）

沿用 Pixel 3 / Android 15、无线 iPhone XS Max 和 macOS Chromium，真实 LAN HTTP / H.264 链路；临时将协商帧率设为 60 fps，设备日志确认请求生效。固定浏览器视口，等待尺寸重协商完成后，持续交替滑动 CarPlay 应用主屏，制造真实动态输入。1280×720 对应 1280×800 浏览器视口；2544×1576 对应 2544×1656、DPR 1 的测试视口，与用户原 Chrome 的源画面像素尺寸一致，但 CSS 尺寸及 DPR 不同。

| 指标 | 1280×720 | 2544×1576 |
| --- | ---: | ---: |
| 连续动态采样时长 | 45.39 秒 | 45.28 秒 |
| 源端输入平均帧率 | 54.81 fps | 40.75 fps |
| 浏览器解码平均帧率 | 55.03 fps | 41.12 fps |
| 成功绘制平均帧率 | 54.43 fps | 41.06 fps |
| 约 1 秒采样窗口的最高绘制帧率 | 59.01 fps | 55.98 fps |
| 成功绘制帧数 / 不同帧时间戳数 | 2471 / 2471 | 1859 / 1859 |
| 绘制间隔中位数 / P95 / 最大值 | 15.7 / 37.2 / 126.5 ms | 18.3 / 50.4 / 161.0 ms |
| WebRTC 报告丢包数 / framesDropped 增量 | 0 / 0 | 0 / 0 |

额外一次 1280×720 的 25.46 秒连续滑动采样，成功绘制平均 53.49 fps、约 1 秒窗口峰值 56.98 fps。上述三个动态采样块均保持 track-canvas 和连接状态，未回退 JPEG，Video 元素数始终为 0；720p 两块动态观察合计约 71 秒，高分辨率观察约 45 秒。没有小时级稳定性、功耗或温升结论。

绘制计数在调用原生 `CanvasRenderingContext2D.drawImage` 成功返回后递增，并按 `VideoFrame.timestamp` 核对不同帧；原生 PeerConnection `getStats` 提供解码与网络计数。仅增加观测包装，未替换源视频、传输、Processor 或解码器。这些数值是应用提交 Canvas 绘制的速率，不是屏幕实际呈现或刷新率。WebRTC 的 framesDropped 为 0 不代表 Processor 消费端绝对不丢帧；例如 720p 主采样中，解码增量比实际绘制多 27 帧。

本轮未观察到持续满 60 fps。绘制基本跟上解码，但源端平均输入本身低于 60 fps，且主屏滑动不是恒定 60 fps 测试片，因此不能单独据此判定 Canvas 的吞吐上限或瓶颈位置。源端静态降帧、窗口 / 设置变化引起的无线 CarPlay 重协商均与稳定采样分开；一次截图重置测试视口导致的重协商数据，以及最初混入静置时段的试采样，未纳入表格。

测完已恢复原有 30 fps / H.264，保留 10 秒恢复策略与尺寸自适应设置，并关闭测试浏览器。设备日志确认恢复后的 2544×1576 / 30 fps 协商及首帧；用户原 Chrome 恢复「H.264 / WebRTC / 直接 Canvas · 触控已连接」，Android 显示「CarPlay 画面正在串流」。

原始采样及设备日志保存在 Git 忽略的 `.local/device-60fps-qa/`。目标车机浏览器的 60 fps、恒定帧率输入、设备 HTTPS、真实 UDP 阻断及功耗仍待验证。

## 真机分段排查：高分辨率不足 60 fps（2026-10-21）

继续使用 Pixel 3 / Android 15、无线 iPhone XS Max 和 macOS Chromium，协商上限 60 fps。高分辨率固定为 2544×1576，半尺寸对照为 1272×788。主屏重复左右滑动，每组约 30 秒；额外使用一次不松手的连续往返拖动。都是实际 CarPlay 输入，未生成或替换媒体数据。窗口和编码重协商期间不计入稳定帧率。

本轮首先核对计数位置：`wheelplayStats.source.counts.input` 在 `WebVideoSource.frame` 内递增，是 Android 收齐 AirPlay 视频包并解密后的输入数，**不是 iPhone 内部生成帧数**。`ScreenStream` 以 TCP 顺序读取完整包；源 PTS 来自 AirPlay 包头，`ptsInterval` 在 Android 交给转发器前计算。`transport.sender.counts.accepted` 是原生发送调用成功次数；浏览器 `framesReceived` 才是接收端证据。设备心跳与浏览器 getStats 不完全同步，表中小幅速率差异包含采样边界偏差，不能当作某一级多生成或丢弃了帧。

| 对照组 | Android 输入 fps | Android 成功发送 fps | 浏览器接收 / 解码 fps | Canvas 成功绘制 fps |
| --- | ---: | ---: | ---: | ---: |
| 高分辨率 H.264，直接 Canvas A | 48.33 | 48.33 | 48.43 / 48.43 | 48.40 |
| 同尺寸 H.264，跳过 drawImage B | 48.79 | 48.79 | 49.32 / 49.22 | —（消费 49.22） |
| 同尺寸 H.264，原生 Video C | 45.84 | 45.84 | 46.04 / 45.91 | — |
| 同尺寸 H.264，不松手往返拖动 D | 38.85 | 38.85 | 39.25 / 39.25 | 39.15 |
| 半尺寸 H.264，直接 Canvas E | 52.36 | 52.36 | 52.59 / 52.55 | 52.32 |
| 高分辨率 HEVC，重连接收器后 I | 50.56 | 50.56 | 50.62 / 50.62 | 50.56 |
| 高分辨率 H.264，恢复编码后的复测 J | 48.89 | 48.86 | 49.26 / 49.19 | 49.19 |

B 组只在页面内临时跳过 `drawImage`，持续读取并释放原生 VideoFrame；它是排除绘制负载的诊断，消费速率不能称为实际绘制帧率。C 组额外观察到约 44.41 次 / 秒的原生视频帧回调及 VideoPlaybackQuality dropped 增量 14，回调可能合并，不能与 Canvas 提交次数等同。不同组不是恒定内容的逐帧回放；尤其连续拖动会受页面边界和内容更新策略影响，D 不能单独作为设备吞吐上限。半尺寸改变了 CarPlay 布局及物理尺寸通告，也不是严格的单变量编码性能基准。

分段证据及结论：

- **源端 / 无线输入边界：** 高分辨率 H.264 的源 PTS 已包含 33.3 / 50 ms 等间隔，A 组稳定后最近 240 帧窗口的平均 PTS 间隔约 20.22 ms；浏览器消费到的 PTS 间隔平均 20.48 ms、P95 50 ms，绘制间隔平均 20.48 ms、P95 50.70 ms。缺口在 Canvas 之前已存在。项目通过 AirPlay `/info` 的 `maxFPS` 通告 60，是上限能力，不会强迫 iPhone 每秒生成 60 个不同画面。本轮把主要缺口限定在 Android 完整收帧之前，仍不能进一步区分 iPhone 内容更新 / 合成 / 编码和无线 TCP 回压；未取得 iPhone 内部计数，也未做 USB 对照。
- **Android 转发：** A、B、C、D、E、I、J 稳定采样均无转发队列过期、溢出或发送失败；A / J 的 1 Hz 队列快照为 0，B / C 最大为 1。A 组排除前 7 秒旧窗口后，窗口 meanMs 的平均值为解密 1.36 ms、收包后至交接 1.40 ms、排队 0.69 ms、发送调用 4.54 ms；交接已包含解密，不能重复相加。完整动态接收日志的处理最大耗时约 3–6 ms，读取等待可达约 50–80 ms；未见持续队列积压。没有证据表明本次帧率主要被 Android 转发器截断，仍不代表其无限吞吐或无线输入链路没有压力。
- **浏览器解码与绘制：** A / J 解码平均约 5.67 / 5.62 ms，Canvas drawImage 调用平均约 0.186 / 0.192 ms、P95 均约 0.3 ms，无观测到的主线程长任务。跳过绘制后没有升到 60，原生 Video 也没有升到 60；H.264 各组实际输出基本跟上解码，直接 Canvas 的 Video 元素数始终为 0。drawImage 调用耗时不包含全部异步 GPU / 屏幕呈现工作，本轮没有测量物理显示帧率，也没有用恒定 60 fps 输入确认浏览器极限。

HEVC 单独发现间歇性解码失败：第一次直接 Canvas 接收器出画后停止解码，动态采样前 9 秒 Android 输入与成功发送各增加 392 帧，浏览器接收增加 410、解码增加 0、framesDropped 增加 409、packetsLost 增加 0，约第 10 秒回退 `decode-stalled`。第一次改用原生 Video 也观察到同一回退原因；该次未及时捕获回退前完整时序，不能据其计算动态性能。随后重新建立原生 Video 接收器的 18.56 秒采样恢复约 51.18 fps 解码；再重新建立直接 Canvas 的 I 组 30.06 秒采样正常，约 50.56 fps 绘制，无回退。故该问题不依赖 Canvas，尚不能区分 HEVC 码流 / 打包参考链和浏览器解码器状态；不把它解释为固定 40–50 fps 性能上限，也不以重连成功宣称已修复。

HEVC 正常组码率约 14.59 Mbps，H.264 A / J 约 29.85 / 28.18 Mbps，但输入只从约 49 提升到约 51 fps；本次顺序采样不能证明无线带宽是唯一瓶颈。Android 热状态从测试前 0 变为后期 1；没有频率及 iPhone 温度证据，未排除热与调度影响。一次辅助 top 采样未可靠对齐动态窗口，不用于 CPU 饱和结论。

下一步若要继续收敛源端原因，应使用同分辨率、同场景的 USB / 无线对照并取得 iPhone 侧帧时间或稳定帧率内容；若要定位 HEVC 间歇失败，应在失败前保存压缩访问单元及 PTS、关键帧 / 参数集信息，与浏览器解码错误对齐，区分码流及打包问题和解码器状态。当前证据不支持优先重写 Canvas 绘制器或增加转发缓冲。

采样、观测脚本及设备日志保存在忽略的 `.local/device-bottleneck-qa/`，未修改产品代码、未安装新 APK。首次 HEVC 回退使汇总脚本遇到空 transport，已保留原始采样并单独分析；该错误不计为产品异常。截图和原始内容不进入版本库。

测试后 UI 确认恢复 30 fps、HEVC 关闭，原始分辨率、自适应和 10 秒恢复时间保留；日志确认 2544×1576 / 30 fps / H.264 重新协商及首帧，直接 Canvas 恢复并可触控。随后撤销页面观测包装、断开并关闭专用测试浏览器，偏好恢复直接 Canvas。用户原 Chrome 在本轮开始时即为「已配对 · 等待启动」，本轮没有修改它的地址、选项或连接状态。

## HEVC 间歇性 decode-stalled：长度前缀被误识别（2026-10-21）

本轮在同一真机链路、2544×1576 / HEVC / 60 fps 下，分别用原生 Video 和直接 Canvas 复现停滞，确认了 Android Native 码流转换的确定性缺陷。产品代码及设备 APK 均未修改；已安装 APK 与本地 Debug APK SHA-256 一致（`fd24d487512022b9b9100ea9aa7cbd6b96fbb2e65d50abff6e91a69172de4ed2`）。浏览器为 Chromium 156.0.8078.47，revision `1d17a2e2ee147bb7d6ae2ab6abdb3a2510c17775`。

**观测方法与证据。** 临时为测试 PeerConnection 启用 encoded insertable streams，在接收器解包后、解码前复制真实 RTCEncodedVideoFrame 数据与元信息，并立即原样送入 writable；没有改变帧内容或插入媒体。保存 Annex B 数据、每帧边界 / RTP 时间戳 / NAL 类型及 1 Hz getStats，另用本机 FFmpeg 软件解码。新增观测路径会影响时序，因此性能数值不作基准；本轮目的是捕获已损坏的字节，而两种渲染器都确实发生了同类回退。

| 样本 | 解包后捕获帧数 | 末次有效 getStats：收到 / 解码 / 丢弃 | 结果 |
| --- | ---: | ---: | --- |
| run-0，直接 Canvas 正常基线 | 导出码流离线解出 2790 帧 | 持续随输入解码，未回退 | 约 100 秒静置后动态恢复正常；FFmpeg 无警告 |
| run-1，原生 Video 重连 | 248 | 231 / 11 / 210 | 首帧后停止解码，decode-stalled；末次有效 stats 丢包 0 |
| run-2，直接 Canvas 重连 | 193 | 125 / 7 / 108 | 同类异常及回退；末次有效 stats 丢包 0 |

表中捕获总数包含末次有效 stats 之后、关闭前到达的帧，不能用三项计数直接相减核算全部丢弃。失败样本的元信息长度总和与二进制文件长度完全一致。run-1 / run-2 的 FFprobe 分别只能离线解出 88 / 84 帧，报告 `Failed to parse header of NALU`、`Could not find ref with POC` 和 `Error constructing the frame RPS`；FFmpeg 可能跳过错误后继续解码，不能把进程退出成功误称为码流有效。浏览器 Media 域未提供有用的 decoder-specific 错误，本轮未取得硬件解码器的私有日志。

**确定性原因。** `common/src/main/cpp/video_buffer.hpp:38` 的 `annexB()` 同时用 `prefix()` 判断是否已经为 Annex B；`prefix()` 将 `00 00 01` 识别为三字节起始码。AirPlay 的四字节大端 NAL 长度在 256–511 范围内恰为 `00 00 01 xx`，因此 `if (!prefix(0))` 跳过整个长度前缀转换，后续扫描把 `xx` 当成 NAL 头。`video_packetizer.hpp` 按已声明的 Annex B 输入扫描，于是把这个多余字节连同原 NAL 一起封进 RTP。问题位于打包前的格式转换，不是已确认的 FU 位布局或浏览器绘制问题。

run-1 捕获的第 12 个访问单元（索引 11）为：

```text
推导出的长度前缀输入：00 00 01 7c 02 01 d0 0b ...
                     长度 380 └─真实 HEVC NAL 头
应得到的 Annex B：   00 00 00 01 02 01 d0 0b ...
浏览器实际收到：     00 00 00 01 7c 02 01 d0 0b ...
```

抓包不是 Android 转换前的直接采样，第一行明确是根据接收数据推导：长度字段为 380，剩余 payload 恰好 380 字节。将此输入送入**未修改的生产转换器与打包器**，得到 385 字节，与浏览器捕获结果逐字节相同。字节 `7c` 令该 NAL 被识别为 type 62 / tid_plus1 2；原本的 `02 01` 则是 type 1 / tid_plus1 1。run-2 也在同一字节位置出现相同偏移模式。

另外用隔离的 C++ 诊断程序扫描首个 NAL 长度 2–1100，恰有 256 个长度失败，范围精确为 256–511；255 和 512 正常。生产的 `test/native/video-packetizer.cpp` 原有 MTU / FU / marker / 参数集 roundtrip 测试仍通过，它只给 packetizer 输入正确 Annex B，没有覆盖 Native 长度前缀转换。Native 随后的关键帧扫描还可能把长度末字节误认成 IRAP，导致错误插入参数集和虚假的关键帧分类。静态或低变化画面更容易产生小 NAL，符合间歇性触发特征，但并非所有静态画面都必然触发。

**与浏览器的区分。** 浏览器解码前数据已损坏，独立软件解码也出现语法与参考链错误；两种渲染器都复现，且页内捕获没有修改帧。因此本轮复现的首要原因是 Android Native 格式误判，不能再把该现象仅归为浏览器 HEVC 性能或随机解码器状态。正常样本包含 VPS / SPS / PPS / IDR，RTP FU、时间戳及末包 marker 的实现和已有测试未暴露同类问题；协议核对依据 [RFC 7798](https://www.rfc-editor.org/rfc/rfc7798.html)。这不代表已排除所有 HEVC 或浏览器兼容问题，也未声称修复后验证完成。

**建议修复与验证。** 将“输入格式判定”和“Annex B NAL 扫描”分开；AirPlay 长度前缀入口按已知格式验证完整访问单元后转换，不能用前三个字节推断格式。若需保留 Annex B 输入支持，应明确输入契约并与 JVM 路径对齐，避免破坏合法三 / 四字节起始码。补充 Native / JVM 一致性及 255、256、380、511、512 边界、多 NAL、畸形长度、不误判关键帧的测试；使用本次推导输入及不含私人画面的等价夹具验证转换和 RTP 往返字节一致。修复后再做同 APK 真机 HEVC 两种渲染器重连、静置 / 动态恢复、H.264 回归，核对 encoded 数据及 decode-stalled，而非只确认首次出画。风险主要是既有 Annex B 兼容分支；问题代码为 AVC / HEVC 共用，H.264 也应补测。

原始媒体、元信息、离线解码日志及隔离复现程序保存在 Git 忽略的 `.local/hevc-diagnosis/`，包含真实 CarPlay 内容，不提交版本库。诊断阶段未修改产品实现、未安装 APK；尚未实施上述修复。

测试后已关闭临时 HEVC、恢复 30 fps，保留原始分辨率、自适应与 10 秒恢复策略。设备日志确认恢复后的 2544×1576 / 30 fps / H.264 协商及首帧，网页恢复直接 Canvas 绘制、触控及 Video 数量 0；撤销观测包装后断开并关闭专用测试浏览器，原 Chrome 未操作。`git diff --check` 通过。

## HEVC 长度前缀修复及真机复验（2026-10-21）

已实施上一节定位的修复：Native 转换器通过 `VideoInputFormat` 显式区分长度前缀与 Annex B；两个 AirPlay 解密入口均指定 LengthPrefixed，完整验证访问单元后再改写长度。Annex B 的三 / 四字节起始码扫描独立保留，不再通过前三个字节推断输入格式。每次扫描先清除关键帧标记，畸形长度不产生虚假的关键帧。没有改变 RTP 分片、浏览器解码策略或恢复时间。

**回归检查。** 新增 `test/native/video-buffer.cpp`，覆盖 AVC / HEVC 的 2–1101 字节 NAL（含完整 256–511 缺陷区间）、单 / 多 NAL、关键帧分类、65536 字节分片、显式三 / 四字节 Annex B、歧义输入与畸形长度，并经过生产 packetizer / FU 重组逐字节校验。该测试在旧转换器上先失败，修复后以 ASan / UBSan 编译运行通过；已有 packetizer 测试也通过。上一节推导的真实 380 字节输入经修复后得到正确 384 字节访问单元，移除多余长度尾字节。`npm test` 94 项通过，`npm run check`、`git diff --check` 及 `:mobile:assembleDebug --no-configuration-cache` 通过。Native 新测试已注册至 CMake；本轮运行的是上述两个独立 Native 可执行文件，未重跑完整 CMake 套件。

**同设备实测。** 安装修复版 Debug APK，并核对设备上 APK 的 SHA-256 与本地产物一致：`4fb3f33a988ee417897fe644b7a513ffc7357d81a1aa1fb46876b6d9a696c3a3`。沿用 Pixel 3 / Android 15、无线 iPhone XS Max、Chromium 156 的真实链路；设置 HEVC、协商上限 60 fps、自适应 2544×1576，恢复容忍时间仍为 10 秒。五轮通过网页断开后重新连接，使用真实触控滑动应用页产生动态画面；没有合成输入或替换压缩帧。

| 轮次 / 模式 | 观测时长 | 末次收到 / 解码 / 丢弃 | 结果 |
| --- | ---: | ---: | --- |
| 0，直接 Canvas，捕获开启 | 142 秒 | 3026 / 3023 / 0 | 静止超过一分钟后恢复滑动，持续出画 |
| 1，原生 Video，捕获开启 | 50 秒 | 1278 / 1278 / 0 | 重连后正常解码与显示 |
| 2，直接 Canvas，捕获开启 | 35 秒 | 1044 / 1044 / 0 | 再次重连后正常绘制 |
| 3，直接 Canvas，捕获关闭 | 38 秒 | 1138 / 1138 / 0 | 排除 encoded insertable streams 观测路径的影响 |
| 4，兼容 Canvas，捕获关闭 | 31 秒 | 1160 / 1160 / 0 | 旧 Canvas 路径回归通过 |

所有轮次都未记录 `decode-stalled`、其他 JPEG 回退或丢包；轮次 0 终止采样时有 3 帧尚未解码，当时 framesDropped 为 0，不能将该瞬间差额算作解码丢弃。直接 Canvas 的各轮 Video 元素数均为 0。动态窗口排除开始 2 秒后，轮次 0 / 2 / 3 成功绘制约 50.46 / 51.00 / 52.20 fps，原生轮次 1 解码约 51.60 fps；这些顺序触控样本用于稳定性回归，不是恒定内容的性能基准，也未证明已达到持续 60 fps。兼容 Canvas 保留动画帧补偿，绘制次数不能作为不同视频帧的数量。

前三轮共捕获 5348 个完整访问单元，元信息与导出二进制边界一致，没有超过保存容量。包含 **32 个 256–511 字节 NAL**，其头部均为正确的 HEVC type 1 / layer 0 / tid_plus1 1；所有捕获 NAL 均未再出现上一节多余长度字节形成的异常头部。三份码流经 FFmpeg `-xerror` 解码及 FFprobe 逐帧计数，分别完整解出 3026 / 1278 / 1044 帧，警告和错误日志均为空。结合旧版本的确定性失败用例、同长度区间实测和独立解码结果，本轮确认已修复此前定位的格式误判；短时复验不代表穷尽所有 HEVC 设备兼容性或长时间运行问题。

**恢复与清理。** UI 再次确认恢复 30 fps、HEVC 关闭、原始分辨率、自适应开启及 10 秒容忍时间。重新连接后的网页实际协商 H.264，直接 Canvas 首轮恢复约 30 fps，继续滑动可见画面更新，累计绘制 629 帧，未回退，Video 数量 0。随后释放触点、撤销页面观测包装、恢复直接 Canvas 偏好并断开关闭专用测试浏览器；用户原 Chrome 的地址、选项和连接状态未操作。保留修复版 APK，未恢复旧缺陷版本。原始码流、元信息、构建与设备日志保存在忽略的 `.local/hevc-fix-qa/`，真实内容不提交版本库。

## Rebase 上游后的集成与真机复验（2026-10-21）

分支已 rebase 至 `upstream/main` 的 `03a5934`。保留上游媒体 sessionId / videoEpoch、画面与输入就绪状态、两秒保留画面、浏览器缩放补偿、v3 JPEG / WebP 流水线和有界退避重试，三种渲染器接入同一呈现控制器。关闭渲染器前通过 held-frame 保存当前画面，旧轨道回调仍由代次隔离；直接 Canvas 在重建、图像回退与自动重试期间不创建 Video。明确不支持的编码、Processor / Canvas 或播放权限错误使用 `retryable: false`，临时绘制错误保留上游重试行为。补齐上游新增的两条 Android 浏览器状态文案导出。

**工程检查。** 前端 136 项测试通过，包含新增的直接 Canvas 媒体版本重建及 v3 WebP 回退后重试；Android `:common:testDebugUnitTest` 57 个套件、324 项通过，无失败或跳过。Native 长度前缀测试通过 ASan / UBSan，已有 packetizer 测试通过。`npm run check`、`git diff --check` 和 Android Debug 构建通过。真实 Chromium 媒体测试验证 WebP 探测、缩放下触控、损坏 WebP 后 JPEG 回退、两帧流水线精确 ACK、旧帧拒绝、恢复期限、旋转及 WebSocket 重连；这些合成图像测试与下述真实 CarPlay 验证分别记录。

**真机链路与 HEVC。** 沿用 Pixel 3 / Android 15、无线 iPhone XS Max → Android 转发 → Mac Chromium 的链路，实际 WebRTC 尺寸为 2544×1576。临时协商上限 60 fps / HEVC，容忍时间 10 秒，真实应用页滑动驱动画面。首个 rebase APK SHA-256 为 `b945cf72f401b1d990554afdd0a178e547d11265c160c4c79ff8b2e65f848afe`，核对设备安装包一致。

| 轮次 | 收到 / 解码 / 丢弃 | 观测结果 |
| --- | ---: | --- |
| H.264 / 30 fps / 直接 Canvas | 402 / 402 / 0 | 动态触控正常，动态窗口约 26.67 fps 绘制 |
| HEVC / 直接 Canvas，捕获开启 | 2496 / 2496 / 0 | 约 135 秒，静置约 80 秒后动态恢复；动态窗口约 51.12 fps 绘制 |
| HEVC / 原生 Video，捕获开启 | 999 / 999 / 0 | 重连后正常，动态窗口约 52.73 fps 解码 |
| HEVC / 兼容 Canvas，捕获关闭 | 843 / 843 / 0 | 重连与滑动正常，动态窗口约 51.99 fps 解码 |
| HEVC / 直接 Canvas，捕获关闭 | 1089 / 1089 / 0 | 正常运行后注入一次临时绘制异常，JPEG 回退后自动重试恢复 |

上表正常运行期间没有 decode-stalled 或非预期回退，末次统计丢包均为 0。两份真实 HEVC 捕获共 3495 帧，含 15 个 256–511 字节 NAL，头部均正确；FFmpeg / FFprobe 分别完整解出 2496 / 999 帧，无警告与错误。Native NAL 修复在 rebase 后继续有效。静态画面的零 fps、Chrome freeze / PLI 计数以及兼容 Canvas 的重复补绘不作为独立故障或显示刷新率证据；顺序触控样本未证明持续达到 60 fps。

**真机额外发现并修复 WebP 探测问题。** 上游探测图片使用 `data:` URL，但 Android 实际 CSP 为 `img-src 'self' blob:`，因此真实页面探测失败并一直协商 JPEG；原浏览器测试服务没有同一 CSP，未暴露问题。改用相同 68 字节、2×2 WebP 的 Blob URL，在成功、失败和超时后均释放 URL，不放宽 CSP。浏览器测试改为读取 Android 服务端实际 CSP：旧探测先因等待 WebP 超时失败，修复后全部通过；单元测试补充 Blob 类型、尺寸及幂等释放断言。

最终 APK SHA-256 为 `b3adefe53f82aa1b2268ba4ad02d270bbaf8534d6d7b8bce54283a443b852bfb`，再次安装并核对设备哈希。真机页面实际 WebP 探测成功，状态统计为 protocol 3 / image/webp。最终 APK 的直接 Canvas 动态窗口约 50.67 fps；注入一次只影响 drawImage 的异常后，真实 WebP 画面成功加载，2544×1576 图像可见且输入就绪；约 5.8 秒自动恢复 HEVC / 直接 Canvas，恢复后动态窗口约 50.99 fps，末次收到 / 解码均为 964 帧，丢弃和丢包为 0。故障观测全过程 Video DOM 数、Video 创建次数和视频 play 调用次数均为 0。该故障注入未改写压缩码流或协议，仅用于验证回退与恢复。

1280px 连接页三列、390px 单列，均无横向溢出且 Video 数为 0。测试后 UI 确认恢复 30 fps、HEVC 关闭、原始分辨率、自适应开启和 10 秒容忍时间；新增加的图片格式 WebP / 质量 75 沿用安装后的设置，测试未修改。实际重新协商 H.264 后，触控与直接 Canvas 正常，末次收到 / 解码均为 1038 帧，丢弃为 0，未回退；Video 创建和 play 调用均为 0。随后释放触点，撤销页面观测包装，恢复直接 Canvas 偏好，断开并关闭专用测试浏览器。设备保留最终修复版 APK。

记录、真实码流、设备与构建日志保存至忽略的 `.local/rebase-qa/`；不提交私人画面。目标车机型号及长时间运行仍需单独验证，本轮未操作用户原 Chrome。

## 浏览器 Media Session（2026-10-23）

将 iAP2 Now Playing 状态经已配对 WebSocket 推送到车机网页，再通过 Web Media Session API 发布歌曲、歌手、专辑、封面、播放状态和进度。播放／暂停／切歌回传复用原生媒体桥的音频焦点及暂停逻辑；未启用尚未验证的 seek。协议及兼容性边界见 [MEDIA_SESSION.md](MEDIA_SESSION.md#browser--head-unit-media-session)。

- `npm test`：150 项通过；`npm run check` 通过。覆盖 API 缺失／部分支持、暂停进度、过期状态、旧按键回调、WebSocket 背压与重连清理。
- Android 定向回归累计覆盖 43 个独立测试：`BrowserMediaBridgeTest` 7 项、`CarPlayMediaSessionBridgeTest` 12 项、`LanWebServerTest` 24 项，均通过。最后补充的命令排队后撤销权限、未完成命令限制及旧 controller 完成回调隔离已单独复测。
- 真实 HTTP / WebSocket 测试验证状态推送、控制结果、封面读取与撤销配对后返回 404；既有不声明 `nowPlaying=1` 的客户端保持原协议。
- `npm run test:browser:media`：真实 Chromium 在生产 CSP 下通过元数据、JPEG 封面、控制回传、iPhone 回报后暂停、禁用 seek、断线清理及旧回调隔离，同时保留画面／触控回归。媒体按键回调由测试脚本触发，不代表物理按键验收。
- `:mobile:lintDebug` 通过：0 错误、18 警告。`git diff --check` 通过。云环境使用可写 Android／Gradle／Robolectric 缓存及会话代理运行检查，未修改仓库工具链设置。
- 未安装到车机或 iPhone。车机系统媒体卡片、方向盘按键、Web Audio 会话激活，以及具体浏览器在 HTTP／HTTPS 下的支持情况仍需实机验收。


## 浏览器与原生 Media Session 进度跳转（2026-10-23）

本节取代此前“seek 未实现／等待协议依据”的当前状态描述。根据 LIVI 的实际发送代码、Nocturne 的协议定义和 MacPlay 的字节编码测试，接通 `seekto → positionMs → iAP2 0x5003 / 参数 0`，按大端 uint32 毫秒发送。具体上游版本和链接见 [绝对跳转说明](MEDIA_SESSION.md#absolute-seeking)。有线／无线 identification 均声明发送该消息。

浏览器和原生 Android 仅在手机允许跳转且时长、位置有效时启用控制。支持绝对拖动与前后跳转；连续拖动合并至最后一个目标，等待中的最终请求不会因前一个控制尚未完成而丢失。请求绑定 controller、连接与曲目，iAP2 队列在接收和发送时复核有效性。暂停时跳转保持暂停，发送成功不冒充手机已更新播放位置。

- `npm test`：154 项通过，含拖动合并、秒／毫秒转换、相对进度推算、边界钳制、无效输入、超时和回调失效。
- `:shared:testDebugUnitTest --tests '*Iap2*' --tests '*CarPlayMediaCoordinatorTest'`：66 项通过。覆盖 42 秒的完整消息字节、真实 iAP2 链路模拟收包、队列背压下丢弃过期跳转、连接复用、切歌／断线隔离，以及已有协议回归。
- `:common:testDebugUnitTest` 定向运行 `BrowserMediaBridgeTest`、`CarPlayMediaSessionBridgeTest`、`LanWebServerTest`：46 项通过。真实 HTTP／WebSocket 验证 seek 能力、命令字段和结果；Robolectric 验证暂停跳转、配对撤销、能力变化和最终请求保留。
- `npm run test:browser:media`：真实 Chromium 在生产 CSP 下通过 seek 能力切换、最后一次拖动位置（42.123 秒 → 42123 毫秒）、暂停状态保持、切歌后的旧 seek 回调隔离，以及既有画面／触控／重连检查。回调由测试脚本触发，未验证车机的实体按键或系统进度条 UI。
- `:mobile:lintDebug`：0 错误、18 警告；`git diff --check` 通过。
- 未安装或操作真实车机／iPhone。手机音乐应用是否执行跳转、进度回报及本地／浏览器输出的声音连续性仍需实机验收。本次复用现有 CarPlay 音频链路，未根据猜测注入 AirPlay 缓冲清空命令。
