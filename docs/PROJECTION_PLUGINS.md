# APK 输入源插件（API v2）

WheelPlay 可以发现独立安装的第三方插件 APK，通过显式绑定的 Android Service 接入投影运行时。插件使用自己的 UID、进程和权限，不向宿主加载 dex 或 native 库。内置 CarPlay、Android Auto、VNC 和诊断源保持原路径；一次只允许一个活动投影源。

## 安装与使用

1. 使用 Android 安装插件 APK。WheelPlay 不需要“安装未知应用”权限，也不提供插件下载市场。
2. 打开 **输入源 → 插件输入源 → 刷新已安装插件**。
3. 打开插件设置，完成插件自己的设备选择、SDK 配置、USB/蓝牙等授权。
4. 核对插件名称及包名，点击启用，再点击连接插件。
5. 使用原有 WheelPlay 浏览器地址配对并观看。切换源仍会断开旧观看连接，需要重新连接。

首页的「投影输入源」卡片默认选择 CarPlay (iPhone)，可切换到 Android Auto、VNC 或已启用且兼容的插件，并记住选择。设置入口跟随所选源；切换选项不会启动或停止投影，连接动作仍经过原有资源释放流程。卡片同时显示实际运行的源并提供停止操作。所选插件被停用、卸载或变得不兼容时，首页退回 CarPlay；已有 CarPlay 自动连接设置只在首页选择 CarPlay 时生效。

安装本身不会绑定或执行插件代码。启用状态绑定到 Service 组件、APK 当前签名证书和首次安装时间。签名变化或卸载重装会使授权失效（包括宿主未运行时发生的重装）；普通同签名升级保留启用状态，但会终止旧会话，需要重新连接。证书轮换目前保守地要求重新启用。

插件更新、停用、卸载或异常断连会撤销当前输入。只有插件明确确认其所有生产者和平台资源均已释放，宿主才释放运行时占用。停止超时、进程死亡或 SDK 清理失败不能证明 Wi-Fi/USB/子服务已经恢复，当前实现会隔离故障会话；按界面提示强行停止插件并重启 WheelPlay。宿主会关闭自己的视频消费者，不能将这一步误认为远端 SDK 已停止。

## 本版能力

| 能力 | API v2 |
| --- | --- |
| 视频 | 宿主创建 Surface，通过 Binder 传递给插件；插件绘制或交给 SDK/MediaCodec |
| 输出 | 现有 JPEG/WebP 浏览器链路；没有 Surface→WebRTC 重编码 |
| 分辨率 | 握手声明，单边 1–4096 像素，总像素不超过 8,388,608；变更需重连 |
| 触控 | 0–10 点，稳定 ID 0–31，内容区域归一化坐标；空快照释放全部触点 |
| 夜间模式 | 可选，必须实际实现后声明 |
| 设置 | 同一 APK 内可导出的设置 Activity |
| 音频/麦克风、媒体信息、桌面键盘/鼠标 | 暂未开放插件端口；不能声明浏览器音频能力 |
| 编码帧/framebuffer IPC、多显示器 | 暂不支持；需要后续版本协商及有界媒体通道 |

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

CarLink 运行库、固定名称的服务、native 库、初始化代码及权限配置放在未来的 **CarLink 插件 APK**。WheelPlay 不包含、下载或加载 `icar-sdk.dex`，也不接受只替换 dex 的注入流程。插件可用本协议将宿主 Surface 交给 SDK，并回传状态、接收触控。

仍必须在持有适用 SDK 的设备上确认：离屏 Surface 是否被 SDK 接受；能否停止生产者和网络服务；授权及后台/前台服务限制；设备是否需要系统签名；宿主浏览器 LAN 与 SDK 的无线链路是否共存。需要麦克风和音频时，必须先明确插件本机处理与宿主路由的责任，再扩展 SDK；v2 不承诺完整 CarLink 音频闭环。

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
