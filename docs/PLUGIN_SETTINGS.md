# 插件内嵌设置：schema 与 Jetpack Remote Compose

输入源插件可以将设置显示在 **WheelPlay 的 Activity 内**。配置仍由插件 APK 保存和处理，宿主不加载插件 dex、Composable 或自定义 View。提供两种呈现方式：

| 方式 | 数据 | 宿主呈现 | 适用场景 |
| --- | --- | --- | --- |
| Schema | 版本化 JSON schema 和当前值 | WheelPlay 的 Compose 设置控件 | 开关、文本、整数、选项、操作按钮 |
| Remote Compose | Jetpack Remote Compose 二进制文档 | 固定版本的官方播放器 | 插件定制布局、绘制和交互 |

两种方式共用 ContentProvider、配置修订号和变更通知。不使用 RemoteViews。现有设置 Activity 保留为独立页面入口；仅声明 Activity 的旧插件继续按原方式打开。投影 Binder API 仍为 v2，设置协议独立为 v1。

## 声明与开发

在投影 Service 上声明 Provider authority：

```xml
<meta-data
    android:name="moe.feng.wheelplay.plugin.SETTINGS_PROVIDER"
    android:value="${applicationId}.settings" />
```

在 application 内注册插件自己的 Provider：

```xml
<provider
    android:name=".SettingsProvider"
    android:authorities="${applicationId}.settings"
    android:exported="true"
    android:grantUriPermissions="false" />
```

继承独立 SDK 中的 `ProjectionSettingsProvider`：

```kotlin
class SettingsProvider : ProjectionSettingsProvider() {
    override fun readSettings(source: ComponentName) = PluginSettingsSchema(
        revision = loadRevision(),
        title = "插件设置",
        fields = listOf(
            PluginSetting("enabled", "启用功能", SettingType.BOOLEAN,
                loadEnabled(), restartRequired = true)
        )
    )

    override fun writeSetting(source: ComponentName, key: String, value: Any?) {
        // 保存值，并在同一事务中递增 revision。
    }
}
```

上面存储函数由插件实现。一个 Provider 可服务同一 APK 的多个输入源，`source` 为投影 Service 的 `ComponentName`。`readSettings()` 不得启动投影、申请设备权限或占用设备资源。

SDK 在写入前检查字段存在、可编辑、类型、范围、字符串长度和选项有效性。插件仍负责业务校验和持久化。字段种类为 `BOOLEAN`、`TEXT`、`INTEGER`、`CHOICE`、`ACTION`；操作按钮的值为 JSON `null`。`enabled=false` 的字段不可写，`restartRequired=true` 在宿主显示“下次连接时生效”。

最小 schema 的线格式示例：

```json
{
  "version": 1,
  "revision": 12,
  "title": "插件设置",
  "fields": [
    {
      "key": "enabled",
      "title": "启用功能",
      "type": "boolean",
      "value": true,
      "enabled": true,
      "restartRequired": true
    }
  ]
}
```

`choices` 为 `{ "value": "stable-id", "label": "显示文字" }` 数组。整数使用 `minimum` / `maximum`，文本使用 `maxLength`，各字段可有 `summary`。字段 key 在当前 schema 中唯一，最多 64 个字段，schema UTF-8 总大小不超过 64 KiB。

## ContentProvider 协议 v1

所有设置 RPC 使用 `ContentResolver.call()`；查询游标和直接 SQL 更新不属于此协议。变更通知 URI 固定为 `content://<authority>/wheelplay-settings`。宿主只在用户打开设置页后访问 Provider，列表发现阶段仅读取安装元数据。

每次调用的 `arg` 是投影 Service 的 `ComponentName.flattenToString()`。`extras.version` 必须为 `1`，`include_remote` 表示是否需要 Remote Compose 文档。

| Method | 额外请求字段 | 行为 |
| --- | --- | --- |
| `wheelplay.settings.get` | 无 | 返回当前快照 |
| `wheelplay.settings.set` | `revision`、`key`、`value` | 写入一个字段；value 是 JSON 标量的字符串编码 |
| `wheelplay.settings.action` | `revision`、`action_id`、可选 `action_value` | 执行 Remote Compose ID action，value 是可选字符串 |
| 同上 | `revision`、`action_name`、`action_json` | 执行 named action，action_json 是 JSON 标量编码 |

响应为仅含基础值的 Bundle：`version`、`status`、`schema`、`supports_remote`，必要时包含 `message`。成功状态为 `ok`；写入修订号过期时返回 `conflict` 及最新快照，并且不执行修改；其他状态为 `invalid`、`unsupported` 或 `error`。鉴权和来源验证失败会抛出异常。

成功写入/动作后，插件应返回新的 revision。SDK 串行处理其调用，并在修改后发送 `notifyChange()`。插件通过其他页面更改配置时，应调用 `notifySettingsChanged()`。编辑框使用**打开编辑时**的修订号，外部更新不会让旧输入悄悄覆盖新值。

## Remote Compose 接入

宿主固定使用官方 `androidx.compose.remote` **`1.0.0-alpha23`**。复杂 UI 的插件需使用匹配版本的 creation 库生成文档；仅使用 schema 的插件无需依赖 Remote Compose。

```kotlin
override val supportsRemoteCompose = true

override fun remoteDocument(source: ComponentName): PluginSettingsDocument {
    // 使用 Jetpack Remote Compose creation API / Remote Compose DSL 生成文档。
    return PluginSettingsDocument(documentBytes, width = 720, height = 360)
}

override fun onRemoteAction(source: ComponentName, actionId: Int, value: String?) {
    // 验证 action，并修改插件配置及 revision。
}

override fun onNamedRemoteAction(source: ComponentName, name: String, value: Any?) {
    // 验证名称和 JSON 标量；不接受任意 Parcelable 或插件对象。
}
```

RPC 的远程快照额外包含 `remote_version`、`remote_document`、`remote_width`、`remote_height`。二进制文档最多 256 KiB，声明尺寸单边为 1–4096 且总像素不超过 8,388,608，须与解码文档一致。宿主拒绝不匹配的 renderer 版本；仍可切回标准 schema 或打开独立 Activity。

宿主在自己的 Activity 中通过 `AndroidView` 承载官方 `RemoteComposePlayer`，并转发 ID 和 named action。返回快照后重新播放新的文档，使复杂布局和标准表单保持同一配置状态。插件负责 action 的业务验证，不能依赖普通字段校验来保护另一个 action 路径。

Remote Compose 当前是 alpha，部分播放器 API 标为 `RestrictedApi`；宿主将相关引用和 lint 抑制集中在 `RemoteComposeSettings.kt`，升级时应一起更新版本目录、SDK renderer 版本、生产端和渲染测试。文档只能使用播放器支持的远程操作，不能直接传入普通 Compose 函数或任意自定义 Android View。

播放器限制解压大小、操作数、位图尺寸、位图内存和帧率；禁用外部图片 URL、文件加载、文档自动触发的 host action 和自定义 shader。资源应随文档提供。

## 身份与生命周期

- Provider 必须属于投影插件同一 APK、同一 UID，已启用、可导出，并满足宿主的权限要求。宿主在请求前后再次核对安装时间、版本、更新时间和签名；APK 更新或移除后丢弃旧页面与旧响应。
- SDK 默认只接受插件自身 UID，以及 `moe.feng.wheelplay` / `moe.feng.wheelplay.debug` 的 UID。插件需要限定特定宿主发行者时，可覆盖 `isHostAllowed()` 校验签名，规则与现有投影 Service 相同。
- RPC 在数量受限的后台线程执行，UI 请求有五秒截止时间。超时只表示宿主停止等待，不保证 Android 已取消插件中的 Binder 方法；因此 Provider 实现必须短时返回。
- 页面停止可见时移除 ContentObserver、撤销等待并丢弃排队修改；关闭时销毁播放器。恢复可见后重新读取当前值，不复用旧快照执行修改。
- 配置更新不自动重启正在进行的投影。设备权限继续在插件自己的 Activity 中申请；跨进程存储同步仍由插件负责。

## 示例与验证

`projection-plugin-sample` 同时提供标准表单和真实 Remote Compose 文档，两者操作相同的帧计数开关、帧率配置。schema 还演示字幕、分辨率和恢复默认操作。示例的绘图和 click-area 文档使用官方 AndroidX profile，由当前播放器解码；宿主按文档原始尺寸等比缩放，并保留 Android 的触控坐标变换，像素测试覆盖实际背景及按钮区域。投影采用专用进程，在停止退出并重新连接后读取最新偏好，示例中的配置均标明下次连接生效。

```bash
./gradlew :projection-plugin-api:testDebugUnitTest \
  :common:testDebugUnitTest \
  :projection-plugin-sample:testDebugUnitTest \
  :mobile:assembleDebug :projection-plugin-sample:assembleDebug
```

安装宿主和示例后，在输入源插件卡片点击“设置”，可在宿主 Activity 内切换“标准设置”和“插件布局”；“在插件中打开”继续打开旧 Activity。

本次验证通过 58 项 JVM/Robolectric 测试：SDK 16 项，宿主插件设置、注册表、投影会话、生命周期与输入源导航 41 项，示例 1 项。覆盖真实 Remote Compose 解码、像素绘制、点击回写、并发编辑冲突、外部通知、前后台恢复和远程布局失败后的 schema 回退。宿主与示例 Debug APK 已构建。Robolectric 中的 Provider 不代替不同 UID 的车机真机验收；安装两个 APK 后仍需检查目标设备上的跨进程调用和输入行为。

`mobile:lintDebug` 和 `projection-plugin-sample:lintDebug` 通过。`common:lintDebug` 报告 27 个错误，位置均在本次未修改的代码行，涉及既有权限声明、常量和字符串格式；新增设置实现没有 lint 错误，common 全模块 lint 尚未通过。

官方参考：[Remote Compose 发布说明](https://developer.android.com/jetpack/androidx/releases/compose-remote)、[播放器源码](https://github.com/androidx/androidx/tree/androidx-main/compose/remote/remote-player-view)、[生成 API 源码](https://github.com/androidx/androidx/tree/androidx-main/compose/remote/remote-creation-core)。
