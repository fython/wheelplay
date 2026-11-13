# APK 输入源插件（API v2）

WheelPlay 可以发现独立安装的第三方插件 APK，通过显式绑定的 Android Service 接入投影运行时。插件使用自己的 UID、进程和权限，不向宿主加载 dex 或 native 库。内置 CarPlay、Android Auto、VNC 和诊断源保持原路径；一次只允许一个活动投影源。

## 安装与使用

1. 使用 Android 安装插件 APK。WheelPlay 不需要“安装未知应用”权限，也不提供插件下载市场。
2. 打开 **输入源 → 更多工具 → 插件输入源**；需要时点击“刷新已安装插件”。
3. 打开插件设置，完成插件自己的设备选择、SDK 配置、USB/蓝牙等授权。
4. 核对插件名称和启用确认中的包名，点击“启用插件”，再点击“连接插件”。包名和停用操作也可在“管理插件”中查看。
5. 使用原有 WheelPlay 浏览器地址配对并观看。切换源仍会断开旧观看连接，需要重新连接。

首页的「投影输入源」卡片默认选择 CarPlay (iPhone)，可切换到 Android Auto、VNC 或已启用且兼容的插件，并记住选择。设置入口跟随所选源；切换选项不会启动或停止投影，连接动作仍经过原有资源释放流程。卡片同时显示实际运行的源并提供停止操作。所选插件被停用、卸载或变得不兼容时，首页退回 CarPlay；已有 CarPlay 自动连接设置只在首页选择 CarPlay 时生效。

尚未安装插件时，页面提供安装说明；不会显示等待连接状态或提供未经核实的下载链接。每个插件只突出当前可用的主要操作：未启用时显示启用，启用后显示连接；当前会话的停止操作保留在页面状态区域。设置与能力说明可按需展开。

安装本身不会绑定或执行插件代码。启用状态绑定到 Service 组件、APK 当前签名证书和首次安装时间。签名变化或卸载重装会使授权失效（包括宿主未运行时发生的重装）；普通同签名升级保留启用状态，但会终止旧会话，需要重新连接。证书轮换目前保守地要求重新启用。

插件更新、停用、卸载或异常断连会撤销当前输入。只有插件明确确认其所有生产者和平台资源均已释放，宿主才释放运行时占用。停止超时、进程死亡或 SDK 清理失败不能证明 Wi-Fi/USB/子服务已经恢复，当前实现会隔离故障会话；按界面提示强行停止插件并重启 WheelPlay。宿主会关闭自己的视频消费者，不能将这一步误认为远端 SDK 已停止。

## 本版能力

| 能力 | API v2 |
| --- | --- |
| 视频 | 默认 Surface；协商压缩视频扩展 v1 后可传递 H.264 access unit |
| 输出 | Surface 使用 JPEG/WebP；压缩视频扩展直通 WebRTC，不进行 Surface→WebRTC 重编码 |
| 分辨率 | 握手声明，单边 1–4096 像素，总像素不超过 8,388,608；变更需重连 |
| 触控 | 0–10 点，稳定 ID 0–31，内容区域归一化坐标；空快照释放全部触点 |
| 夜间模式 | 可选，必须实际实现后声明 |
| 设置 | 同一 APK 内可导出的设置 Activity |
| 音频/麦克风 | 可选 audio v1，独立双向管道，宿主管理播放与录音路由 |
| 媒体信息与播放控制 | 可选 media v1；有界完整快照，播放/暂停/上一首/下一首 |
| 桌面键盘/鼠标 | 暂未开放插件端口 |
| 编码帧 IPC | 可选压缩视频扩展 v1；独立可靠管道、大小与队列边界检查 |
| framebuffer IPC、多显示器 | 暂不支持 |

API v2 对插件保守预留现有全部资源类型（USB、无线、麦克风、显示）。这是合作组件间的协调，不是 Android 权限授予，也不能阻止其他 APK 自行改网络。不要在发现、绑定或握手阶段启动 SDK、广播、抢占网络或申请设备资源。

## 开发独立插件

`projection-plugin-api` 是独立 Android Library，仅依赖 Android 与 Kotlin 标准库，不依赖 `common`、`shared`、AAP、CarPlay 或浏览器代码。`projection-plugin-sample` 是可独立安装的示例应用，不会合并到宿主 APK。

```bash
./gradlew :projection-plugin-api:assembleRelease :projection-plugin-sample:assembleDebug :mobile:assembleDebug
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb install -r projection-plugin-sample/build/outputs/apk/debug/projection-plugin-sample-debug.apk
```

外部项目可使用生成的 `projection-plugin-api/build/outputs/aar/projection-plugin-api-release.aar`，或将该独立模块作为源码依赖。当前尚未发布 Maven 坐标。使用 Kotlin 的项目应正常依赖 Kotlin 标准库。

一个 Service 代表一个源；一个 APK 可以声明多个 Service。源 ID 由宿主从包名与组件名构造，插件不能冒用 `carplay` 等内置 ID。Manifest 示例：

```xml
<service android:name=".CarLinkService" android:exported="true" android:label="CarLink">
    <intent-filter>
        <action android:name="moe.feng.wheelplay.action.PROJECTION_SOURCE" />
    </intent-filter>
    <meta-data android:name="moe.feng.wheelplay.plugin.API_VERSION" android:value="2" />
    <meta-data android:name="moe.feng.wheelplay.plugin.SOURCE_ID" android:value="iccoa-carlink" />
    <meta-data android:name="moe.feng.wheelplay.plugin.SETTINGS_ACTIVITY" android:value=".SettingsActivity" />
</service>
<activity android:name=".SettingsActivity" android:exported="true" />
```

可选元数据 `SOURCE_ID` 用于关联内置输入源页面。ICCOA CarLink 插件声明 `iccoa-carlink`（SDK 常量 `SOURCE_ICCOA_CARLINK`）后，内置 ICCOA 卡片会显示已安装插件，其详情页提供插件设置、启用、连接和会话停止操作；未安装时显示安装引导。安装或从插件设置返回后会重新发现。多个插件声明同一 ID 时逐个列出，由用户选择；不兼容插件显示具体原因。缺省或未知 ID 仍可在通用插件页使用。

该 ID 仅用于界面关联，不替代组件生成的运行时 ID，不授予权限或自动启用，也不按名称或包名猜测匹配。设置入口仍只接受插件同包内可访问的 Activity，浏览页面不会执行插件代码。

建议继承 `ProjectionPluginService`，实现：

- `videoInfo()`：只返回当前配置，不能启动投影；返回 `PluginVideoInfo(width, height, maxTouchContacts, nightMode)`。
- `onStartProjection(surface, events)`：异步启动 SDK；准备好画面与输入后报告 `events.state(READY)`；失败或自然断线调用 `events.ended(reason)`。
- `onTouch(contacts)`：将通用快照转为 SDK 的 DOWN/MOVE/UP 或对应触控接口；须短时返回。
- `onNightMode(night)`：可选。
- `onStopProjection()`：立即封闭输入，停止传输、音视频生产者及所有 SDK 子服务，返回完成于真正释放之后的 `CompletableFuture<Unit>`。清理不确定必须异常完成，不能只调用 SDK.stop 就立即完成。

插件主动断开、上报 `ERROR` 或启动钩子抛出异常时，基类先通过 `onState(ERROR, reason)` 通知宿主，再调用清理钩子。宿主立即撤销输入并启动 15 秒清理截止时间；`onStopped(clean=true)` 仍只表示资源清理完成，不代表启动成功。手动停止不会制造错误通知。已有插件需使用更新后的基类重新构建，AIDL 与 API v2 版本号不变。

启动或运行失败的原因保留在当前宿主进程内，显示在对应插件卡片和首页所选输入源中；页面刷新和 Activity 重建不会丢失。显式重试会清除旧错误，APK 更新或卸载也会清除，旧 APK 的迟到回调不能覆盖新记录。清理成功后仍释放占用，不会把可重试的启动失败当成资源释放失败。

这些钩子在 SDK 基类的串行工作线程调用。较长操作必须使用自有工作线程，并在停止 future 中等待退出。普通模式的 Surface 在停止成功后由基类释放；专用进程模式由进程退出回收，宿主在观察到退出前保留消费者。生产者不得继续写入已释放的 Surface。基类监听宿主 Binder 死亡和解绑以请求清理，清理失败后不再接纳新会话。

对于保存进程单例、线程或 native 状态的 SDK，可覆盖 `terminateProcessAfterStop = true`，并将投影 Service 和该 SDK 的全部子服务配置到同一个专用 `android:process=":projection"`。设置 Activity 留在应用主进程，其他源不要共享该专用进程。基类拒绝在应用主进程启用此模式。

此模式仍要求 `onStopProjection()` 先封闭异步回调，解绑并停止 SDK 子服务，等待广播、网络等系统资源完成清理。成功回调表示这部分清理完成；宿主随后解绑，基类退出专用进程，宿主等待该会话 Binder 真正死亡后才释放 Surface 和资源占用。进程退出用于回收残留线程、单例和 native 内存，不能代替 Wi-Fi/P2P/热点的显式清理。SDK 清理失败仍会退出专用进程，但宿主保持隔离，不能将一次崩溃当成成功停止。宿主解绑后若清理钩子一直卡住，独立于 SDK 工作线程的 8 秒兜底会退出该专用进程，不会伪造成功确认。

默认基类只接受安装包名为 `moe.feng.wheelplay` 或 `moe.feng.wheelplay.debug` 的调用 UID，并对后续命令校验该 UID 与会话 token。包名校验不是发布者认证；需要特定宿主发行者时覆盖 `isHostAllowed` 检查其签名证书。宿主按用户启用的插件签名、明确组件和调用 UID 校验插件身份；双方仍应将对端参数视为不可信。

## 线协议和生命周期

所有 AIDL 方法均为 `oneway`，没有在 UI 或运行时锁内等待远端返回的同步 RPC。

1. 宿主 `connect(token, apiVersion, hostCallback)`；插件回调 `onInfo(token, Bundle)`。握手超时为 15 秒。Bundle 仅包含公开的基础字段，不能塞入自定义 Parcelable。
2. 宿主验证版本和尺寸、创建 Surface，调用 `start(token, surface)`。准备就绪超时为 60 秒；设置和权限流程应提前在插件 Activity 完成。
3. 插件回调 `onState(token, CONNECTING/READY/ERROR, message)`；宿主缓存能力和就绪状态。READY 之前不接收普通触控。
4. 触控和夜间模式通过宿主容量 32 的发送队列执行。过载会停止会话，不能无界积压或静默丢失抬手后继续运行。
5. 停止时撤销输入，发送 `stop(token)`，等待 `onStopped(token, clean, message)`；15 秒未确认即失败。重复停止不延长截止时间。
6. 声明 `processExitRequired` 的会话还必须等到原 Binder 死亡；收到成功确认后仅解绑，不能立即释放占用。只收到断连通知、未退出或清理失败均不足以释放。
7. 宿主关闭自己的 Surface 消费者并等待输出清理完成。仅成功确认、必要的进程退出及成功本地清理允许打开下一个源。

每个会话使用新 token，所有回调都校验 token 和调用 UID。旧会话不能更新新会话状态。`connect` 被拒绝且未启动生产者时可以返回 `onStopped(clean=true)`；已启动后必须执行完整清理。

API v2 使用精确版本匹配。宿主与插件须一起更新；v1 插件须重新编译并更新 Manifest 版本，以免旧宿主将清理确认误当成专用进程已经退出。更改现有 AIDL 方法顺序/含义或加入需要宿主理解的能力时，应升级版本并定义兼容策略，不能将内部 Kotlin 对象布局视为稳定协议。

## ICCOA CarLink 接入边界

实验性 `projection-plugin-carlink` 模块将 CarLink 运行库、固定名称的服务、native 库、初始化代码及权限配置放在独立插件 APK 中，详见[本地构建与验收记录](CARLINK.md)。WheelPlay 宿主不包含、下载或加载 `icar-sdk.dex`，也不接受只替换 dex 的注入流程。插件需本地提供完整且校验通过的依赖，可用本协议将宿主 Surface 交给 SDK，并回传状态、接收触控；不代表真实协议闭环已验收。

仍必须在持有适用 SDK 的设备上确认：离屏 Surface 是否被 SDK 接受；能否停止生产者和网络服务；授权及后台/前台服务限制；设备是否需要系统签名；宿主浏览器 LAN 与 SDK 的无线链路是否共存。音频通过下述可选 audio v1 扩展协商；具体发送手机的播放、通话和语音行为仍需实机验证。

## 验收

自动化覆盖元数据发现、默认禁用、签名变化、升级/卸载、版本拒绝、停止前后竞态、迟到回调、插件失联、停止超时隔离和 SDK 清理确认。测试中的本地 Binder 替身不等价于真机跨进程性能验证。

`mobile` 的 `PluginSurfaceIntegrationTest` 需同时安装宿主、示例和 instrumentation APK。它验证不同 UID、真实 Binder/Surface 图像传输、触控圆点出现与释放、夜间背景颜色和停止确认；已加入现有 Android 28/36 CI 模拟器任务。可手动执行：

```bash
./gradlew :mobile:assembleDebugAndroidTest
adb install -r mobile/build/outputs/apk/androidTest/debug/mobile-debug-androidTest.apk
adb shell am instrument -w -r -e class moe.feng.wheelplay.plugin.PluginSurfaceIntegrationTest \
  moe.feng.wheelplay.debug.test/androidx.test.runner.AndroidJUnitRunner
```

设备验收需同时安装宿主和示例 APK：看到动态画面、多个触点反馈和夜间模式；与 CarPlay/AA/VNC 来回切换至少 30 次；连接时更新/卸载/强停插件；禁用后不能继续输入；错误版本和缺失权限有可恢复提示；后台与锁屏时检查 OEM 行为。示例 APK 的独立 UID 和真实跨进程 Surface 链路必须通过设备测试确认。


### 可选原生取消扩展

API v2 的 `PluginVideoInfo` 新增可选 `nativeCancelInput`（缺省 false）。明确声明 true 的插件可以实现 `ProjectionPluginService.onCancelInput()`，宿主才会调用追加在 AIDL 末尾的 `cancelInput(token)`；该调用与触控在同一 oneway Binder/SDK 队列中排序，并沿用 UID/token 检查。只有确实取消手势而非正常抬手的实现才应声明此能力。旧插件未携带此字段，继续收到空触控快照，旧事务号保持不变。新插件也可被旧宿主使用，旧宿主忽略此可选字段。SDK 状态消息在宿主映射到公共生命周期/错误模型，停止确认与专属进程退出要求保持不变。

### Optional browser display hint

API v2 hosts may include `ProjectionPlugin.EXTRA_DISPLAY_WIDTH` and `EXTRA_DISPLAY_HEIGHT`
in the explicit service bind Intent. These are a snapshot of the browser pixel canvas (1280×720
when adaptation is disabled or unavailable). `ProjectionPluginService.requestedDisplaySize`
exposes a validated optional hint to `videoInfo()`. Plugins constrain it to their protocol limits
and return the actual size through the existing `onInfo` handshake. Older hosts/plugins remain
compatible; the base API version is unchanged. The hint alone does not resize an active Surface.

### Optional display-size updates v1

The host advertises `EXTRA_DISPLAY_SIZE_VERSION=1` at bind time. Plugins opt in with
`PluginVideoInfo.displaySizeVersion=1`. Only after both sides negotiate and startup has been sent,
the host calls the appended `updateDisplaySize(token, requestId, width, height)` transaction.
`ProjectionPluginService.onDisplaySizeChanged()` returns whether a clean restart is required;
the base service replies through the appended `onDisplaySizeResult(token, requestId, restartRequired)`.
Do not advertise this extension unless the hook handles it. Old transaction numbers remain stable;
old hosts/plugins keep the bind-time-only behavior and receive no new transactions.

The service validates UID/token, positive monotonic request IDs, dimensions and lifecycle state.
The host coalesces changes for 800 ms, ignores stale replies, and honors the adaptive-size setting
(disabled means 1280×720). Plugins compare their constrained effective size, so a viewport change
that does not alter output need not restart. A restart releases input and awaits the existing
cleanup acknowledgement, dedicated-process death when required, and output teardown before
binding a fresh session with the latest viewport. A user stop or source switch cancels automatic
reopening; a cleanup failure retains quarantine. Surface and encoded outputs are recreated together
with touch mapping rather than changing dimensions underneath an active producer.

## 编码视频扩展调研

ICCOA 的独立实验已验证压缩 H.264 可经现有 WebRTC 在浏览器出画，且无需本机解码即可完成普通触控。CarLink 设置现已提供可选 WebRTC 模式，默认仍为 Surface；关键帧请求与重连恢复仍有实验性限制。实测记录、失败边界和能力协商／有界媒体管道／关键帧控制设计见 [ICCOA WebRTC 验证与协议草案](ICCOA_WEBRTC_PROBE.md)。

## 压缩视频扩展 v1

基础 API 保持 v2；现有 AIDL 方法顺序不变，末尾追加 `startEncoded(token, ParcelFileDescriptor)`。
宿主绑定时声明 `EXTRA_ENCODED_VIDEO_VERSION=1`，插件握手返回 `PluginVideoInfo.encodedVideoVersion=1`
才允许调用新方法。缺省 0 继续调用 `start(token, Surface)`。插件选择 WebRTC 但宿主未声明支持时，
在握手阶段拒绝并提示更新或切回 Surface。两条启动路径互斥，同样校验调用 UID、token 与会话状态。

宿主创建 reliable pipe，将写端通过 Binder 传给插件；宿主封送后关闭自己的写端副本。
插件所有写入在独立线程完成，SDK 回调只放入有界队列。管道绑定到本次会话，不能复用到其他 token。
停止后关闭两端；管道 EOF 或异常不等于 SDK 已释放资源，原有 `onStopped` 清理屏障仍有效。

`EncodedVideoWire` 定义大端格式：`WPE1` magic、宽、高，随后每条记录为 `sequence:int64`、
`ptsUs:int64`、`size:int32` 和 `size` 字节。序号从 0 连续递增，单条不超过 2 MiB，先校验长度再分配。
目前仅支持完整 H.264 Annex-B access unit、单一固定格式和非重排视频；同帧多 slice 可以存在，
但不能把多个 picture 合为一条。参数集在首张图片前提供，可重复；参数集变化、B 帧、缺片、PTS
倒退或重复均要求重新连接。读取完整 AU 后立即交付，不等待下一帧时间戳，因此静态画面的最后一帧也能显示。

视频宽高取编码格式，触控仍使用握手画布上的归一化坐标。此扩展未声明按需关键帧能力；
宿主不伪造请求成功。每个新 WebRTC 消费者可使用有界完整 GOP 首播，同一消费者不回放旧时间戳。
模式切换需要停止并重连，不在活动会话中改配置或同时运行两种输出。视频扩展不提供 framebuffer IPC；音频使用独立的 audio v1 扩展。


## Browser source controls

The web display uses `status.sourceControl` (version 1) to distinguish selected and active sources,
lifecycle phase, cleanup state, foreground requirements and the currently allowed action.
`source-action` carries a monotonically increasing per-WebSocket `requestId`, the advertised
`revision`, `sourceId`, and `action` (`connect`, `configure`, or `stop`). The host validates the
current selection/session again before execution; replies use `source-action-result` with the
same request ID. Duplicate request IDs are ignored. Stop is also guarded by the runtime session
generation, including during asynchronous acquisition. Missing or disabled default plugins fall back to CarPlay with a visible notice, without
starting a connection (including startup auto-connect and Activity recreation). The fallback
changes the control revision; requests still targeting the old plugin are rejected rather than
executed as CarPlay. Native home and browser startup share the same source dispatcher;
configuration/device permission UI stays on Android.

The browser distinguishes source readiness from local media presentation. A ready source with
no current frame offers a transport-only retry and stop, never another source connect. The retry
reopens the browser WebSocket without stopping Android projection. Connecting sources offer
cancel; stopping/failed-cleanup states suppress startup. During display-size reconfiguration,
stop cancels automatic reopening. Raw SDK status text is not interpreted as capabilities.

Old `phone-connect` requests are accepted only for an idle, explicitly selected CarPlay source
with a foreground connection handler. Other selections receive a refresh message. New pages
connected to old hosts do not guess a source action and ask for an update/refresh instead.


## 连接准备与本机交互状态

插件可在服务元数据中声明 `moe.feng.wheelplay.plugin.PREPARE_ACTIVITY`，指向同包可访问的 Activity。
宿主连接前通过 Activity Result 启动它，并携带 `ProjectionPlugin.EXTRA_PREPARE_REQUEST`。
插件负责申请自己的权限和引导系统设置；准备完成返回 `RESULT_OK` 并原样回传该请求值，取消返回 `RESULT_CANCELED`。
宿主重新核对插件身份、启用状态、所选输入源及会话代次，确认会话空闲后才继续。未声明准备入口的旧插件沿用原流程。

结构化交互通过 `PluginVideoInfo.interactionVersion` 与绑定 Intent 的 `EXTRA_INTERACTION_VERSION` 双向协商。
双方支持版本 1 时，插件可调用 `events.interaction(PluginInteraction(...))`，由追加的 `IProjectionHost.onInteraction` 回调传递。
阶段支持 `preparing`、`waiting-device`、`pairing`、`waiting-video`、`ready`、`failed`；六位数字配对码只允许出现在 `pairing` 阶段。
配对码仅供本机 UI 使用，不得拼入普通状态、浏览器数据、日志或诊断。宿主在就绪、失败、停止及超时后清除展示；旧宿主不会收到新回调。


## 宿主界面请求 v1

宿主通过绑定参数 `EXTRA_HOST_UI_VERSION=1` 声明支持，插件通过 `PluginVideoInfo.hostUiVersion=1` 选择启用。
投影就绪后，插件可调用 `events.requestHostUi()`，经追加的 `IProjectionHost.onHostUiRequested(token)` 回调请求浏览器设置。
宿主校验 UID、token、能力协商、插件仍可用且属于当前就绪会话，再调用 `WebSession.requestSettings()`，复用已授权浏览器的 `open-settings` 消息和设置对话框。
该事件不会停止或重启投影；旧宿主或旧插件未协商时不会发送新回调。
CarLink 将 SDK `onGoToDesktop` 对应的状态 6 映射到此事件，状态 7（`onBackToForeground`）不触发弹窗。该转发交互已通过 Pixel 接收端实机验证。

## 可选音频扩展 v1

宿主绑定时声明 AUDIO_VERSION=1；插件在 PluginVideoInfo 中声明 audioVersion 和 microphone。
双方支持后，宿主必须先调用 attachAudio，再启动视频。两个独立可靠管道分别承载
插件播放/录音请求与宿主 PCM 麦克风数据；Binder 不承载音频样本。旧宿主继续使用插件的旧音频行为。

WPA1 帧包含连续序号、正数流代次和有界载荷（每包最多 64 KiB）；播放支持 PCM16 LE
及完整 AAC-LC ADTS 访问单元，单/双声道。开始、暂停/恢复、停止和麦克风请求具有独立代次，
过期录音丢弃。队列溢出、格式错误或断管终止会话，不积累无界延迟。停止确认包含音频线程与描述符清理。

宿主统一管理浏览器播放与 Android 回退；插件不同时启动 SDK 本机播放器。
麦克风按现有“Android / 浏览器 / 禁用”设置工作，仅在 SDK 请求期间采集。
Android 录音权限由宿主申请；浏览器使用现有安全上下文和 getUserMedia 授权。
浏览器不可用时可回退 Android，缺少 Android 权限时不录音；禁用设置阻止两种来源。
音频样本不写入日志或文件。摄像头、语音唤醒及媒体元数据不在此扩展范围内。

## 可选媒体扩展 v1

独立协商 MEDIA_VERSION=1 后，插件通过 onMediaState 发布有界完整快照：
歌名、歌手、专辑、播放器标识、播放状态、时长和当前进度（毫秒）。
每个文本字段最多 1024 字符；不经 Binder 传封面，也不下载 SDK 提供的封面地址。
播放器销毁及会话结束清空状态。宿主通过 MediaControlSource 复用 Web now-playing 和原生媒体卡片。

mediaCommand 带递增请求编号，支持播放、暂停、上一首、下一首；控制仅对已就绪的当前会话有效。
回执只表示 SDK 接受发送，不能修改手机权威的播放状态。宿主限制单个在途请求并在 3 秒后超时；
旧 token、重放、停止后回调及不支持该扩展的插件均不能操作当前播放。
CarLink 使用 SDK IPhoneDataListener.onMusicInfoReceived 和 sendKeyEvent 的媒体按键。
尚未验证 seek 接口，故 canSeek=false；有进度展示，但不注册浏览器 seek 动作。
插件音频播放器保留音频焦点所有权，Media Session 不重复申请焦点。
