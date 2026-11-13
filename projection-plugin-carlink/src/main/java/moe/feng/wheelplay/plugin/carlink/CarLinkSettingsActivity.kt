package moe.feng.wheelplay.plugin.carlink

import moe.feng.wheelplay.plugin.forRemoteProcess
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.saveable.rememberSaveable
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import moe.feng.wheelplay.plugin.ProjectionPlugin
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import moe.feng.wheelplay.ui.WheelPlaySettingsTheme

class CarLinkSettingsActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var state by mutableStateOf(Bundle())
    private var config by mutableStateOf(Bundle())
    private var host by mutableStateOf(Bundle())
    private var bridge: ResultReceiver? = null
    private val reply = object : ResultReceiver(main) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { host = resultData ?: Bundle() }
    }
    private fun hostCall(values: Bundle? = null) {
        bridge?.send(if (values == null) 0 else 1, Bundle(values ?: Bundle()).apply { putParcelable("reply", reply.forRemoteProcess()) })
    }
    private var problems by mutableStateOf(emptyList<String>())
    private val refresh = object : Runnable {
        override fun run() {
            state = settingsCall("status")
            hostCall()
            problems = CarLinkEnvironment.problems(this@CarLinkSettingsActivity, CarLinkConnection.fromKey(config.getString("connection")))
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = settingsCall("config")
        bridge = intent.getParcelableExtra(ProjectionPlugin.EXTRA_SETTINGS_BRIDGE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            WheelPlaySettingsTheme {
                CarLinkSettingsScreen(state, config, host, { values ->
                    settingsCall("save", values); config = settingsCall("config")
                    problems = CarLinkEnvironment.problems(this, CarLinkConnection.fromKey(config.getString("connection")))
                }, { hostCall(it) }, problems, { finish() },
                    { CarLinkEnvironment.permissions(CarLinkConnection.fromKey(config.getString("connection"))).takeIf { it.isNotEmpty() }?.let { requestPermissions(it, 1) } },
                    { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
                    { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    { openHotspotSettings() },
                    { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                    { report ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CarLink 诊断", report))
                        Toast.makeText(this, "已复制诊断信息", Toast.LENGTH_SHORT).show()
                    }, runCatching { packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("未知"), { openWheelPlay() })
            }
        }
    }
    private fun openWheelPlay() {
        if (bridge != null) { finish(); return }
        val launch = listOf("moe.feng.wheelplay", "moe.feng.wheelplay.debug")
            .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "请先安装 WheelPlay，再从输入源中连接插件", Toast.LENGTH_LONG).show()
    }
    private fun openHotspotSettings() {
        val intents = listOf("com.android.settings.WIFI_TETHER_SETTINGS", "android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS)
        for (action in intents) if (runCatching { startActivity(Intent(action)) }.isSuccess) return
    }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarLinkSettingsScreen(
    state: Bundle, config: Bundle, host: Bundle,
    save: (Bundle) -> Unit, saveHost: (Bundle) -> Unit,
    problems: List<String>, onBack: () -> Unit,
    onPermissions: () -> Unit, onWireless: () -> Unit, onLocation: () -> Unit, onHotspot: () -> Unit,
    onAppSettings: () -> Unit = {}, onCopy: (String) -> Unit = {}, version: String = "未知", onHost: () -> Unit = onBack,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("ICCOA CarLink") }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = "返回")
            }
        })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val gutter = maxOf(if (maxWidth < 360.dp) 16.dp else 24.dp, (maxWidth - 760.dp) / 2)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = gutter).padding(top = 24.dp, bottom = 32.dp)) {
                val connection = CarLinkConnection.fromKey(config.getString("connection"))
                var advanced by rememberSaveable { mutableStateOf(false) }
                var recovery by rememberSaveable { mutableStateOf(false) }
                var savedNotice by rememberSaveable { mutableStateOf(false) }
                val saveNext: (Bundle) -> Unit = { save(it); savedNotice = true }
                val phase = CarLinkPhase.from(state)
                Description("1. 准备手机连接　2. 返回 WheelPlay 连接　3. 在车机浏览器启动显示")
                if (savedNotice) Text("已保存，下次连接生效", color = MaterialTheme.colorScheme.primary)
                Group("连接状态") {
                    Text(phase.label, style = MaterialTheme.typography.headlineSmall)
                    state.getString("pin")?.let { Text("配对码：$it", style = MaterialTheme.typography.titleLarge) }
                    state.getString("message")?.takeIf { it.isNotBlank() && it != "尚未连接" }?.let { Description(it) }
                    if (phase == CarLinkPhase.FAILED) {
                        val failedConnection = CarLinkConnection.fromKey(state.getString("connection", connection.key))
                        if (failedConnection == CarLinkConnection.USB) Description("请先拔下两部手机之间的 USB 线，再插回；解锁发送手机后返回 WheelPlay 重连，并允许授权和车联提示。")
                        else {
                            TextButton(onClick = onWireless) { Text("检查无线设置") }
                            if (failedConnection == CarLinkConnection.SOFT_AP) TextButton(onClick = onHotspot) { Text("检查系统热点") }
                        }
                        OutlinedButton(onClick = onHost) { Text("返回 WheelPlay 重连") }
                    } else if (!state.getBoolean("active")) {
                        OutlinedButton(onClick = onHost) { Text("返回 WheelPlay 连接") }
                    }
                    if (phase == CarLinkPhase.USB_PERMISSION) Description("请在接收手机的系统对话框中选择允许；拒绝后需重新发起连接。")
                }
                Group("连接方式") {
                    Choice("手机连接", connection.key, CarLinkConnection.entries.map { it.key to it.label }) {
                        saveNext(Bundle().apply { putString("connection", it) })
                    }
                    Description(when (connection) {
                        CarLinkConnection.P2P -> "开启蓝牙、Wi-Fi 和定位，在发送手机的车联功能中选择接收设备，并确认配对码。"
                        CarLinkConnection.SOFT_AP -> "先在系统设置中手动开启接收手机的热点，再填写相同的热点名称和密码。建议使用 WPA2；插件只复用热点，不会修改或关闭它。"
                        CarLinkConnection.USB -> "接收设备需支持 USB Host / OTG。使用数据线连接发送手机，发起连接后允许 USB 访问；切换到配件模式时可能需要再次授权。请只连接一台手机。"
                    })
                    if (connection == CarLinkConnection.SOFT_AP) {
                        HotspotCredentials(config, saveNext)
                        TextButton(onClick = onHotspot, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开系统热点设置") }
                        Description("开启蓝牙，在发送手机的车联功能中选择接收设备。车机浏览器也可连接此热点。密码仅保存在本机插件中。")
                    }
                    Description("下次连接生效。切换后请停止当前投影，再重新连接。USB 停止后若重连超时，请拔插数据线。")
                    state.getString("connection")?.let {
                        Description("${if (state.getBoolean("active")) "当前" else "上次"}会话：${CarLinkConnection.fromKey(it).label}" +
                            if (state.getBoolean("active") && it != connection.key) "；已保存的新连接方式尚未生效" else "")
                    }
                }
                TextButton(onClick = { recovery = !recovery }) { Text(if (recovery) "收起故障恢复" else "需要帮助？查看故障恢复") }
                if (recovery) Group("故障恢复") {
                    Description(when (connection) {
                        CarLinkConnection.USB -> "出现 10007 或 USB 重连超时：断开两部手机之间的 USB 线，再重新插入；保持发送手机解锁，在 WheelPlay 点击连接，并允许 USB 访问与车联提示。"
                        CarLinkConnection.SOFT_AP -> "找不到设备或连接超时：确认系统热点已开启，插件中的名称和密码与系统一致，并开启蓝牙及所需权限。修改热点信息后重新连接。"
                        CarLinkConnection.P2P -> "找不到设备或连接超时：确认两部手机的 Wi-Fi、蓝牙和定位已开启，并在发送手机的车联功能中重新选择接收设备。"
                    })
                    Description("浏览器无法连接：检查浏览器与接收手机的局域网连接，并关闭其他已连接的显示页面。持续黑屏可停止投影后重连，或选择 Surface 兼容模式。")
                    Description("若 WheelPlay 提示插件清理失败或已隔离：先断开 USB；无线连接请关闭再开启系统 Wi-Fi，确认旧连接已断开，再重启 WheelPlay。")
                }
                Group("权限与设备") {
                    Description(if (problems.isEmpty()) "设备准备完成" else problems.joinToString("\n"))
                    if (problems.isNotEmpty()) {
                        TextButton(onClick = onAppSettings) { Text("打开应用权限设置") }
                        if (connection == CarLinkConnection.SOFT_AP) TextButton(onClick = onHotspot) { Text("打开系统热点设置") }
                    }
                    if (connection.wireless && problems.isNotEmpty()) {
                    Button(onClick = onPermissions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("授予附近设备与定位权限")
                    }
                    TextButton(onClick = onWireless, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开无线设置") }
                    TextButton(onClick = onLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开定位设置") }
                    } else if (connection == CarLinkConnection.USB) Description("USB 授权会在 WheelPlay 发起连接后弹出，无需开启蓝牙或 Wi-Fi。")
                }
                Group("视频输出") {
                    Choice("输出方式", config.getString("videoOutput", "surface")!!,
                        listOf("surface" to "兼容模式", "webrtc" to "低延迟视频模式")) {
                        saveNext(Bundle().apply { putString("videoOutput", it) })
                    }
                    Description("下次连接生效。更改后请先停止投影，再重新连接。")
                    state.getString("videoOutput")?.let {
                        Description("${if (state.getBoolean("active")) "当前" else "上次"}会话：${videoOutputLabel(it)}" +
                            if (state.getBoolean("active") && it != config.getString("videoOutput", "surface")) "；已保存的新模式尚未生效" else "")
                    }
                    Description(if (config.getString("videoOutput", "surface") == "webrtc")
                        "WebRTC / H.264：浏览器直接解码，减少接收手机处理开销；实际延迟取决于设备与网络。首次播放或重连可能等待关键帧；若持续黑屏，请重新连接或切回 Surface。需配套新版 WheelPlay。"
                    else "Surface / JPEG / WebP：接收手机解码后输出图片，兼容性更好；画质与帧率可在高级设置调整。")
                }
                Group("车机浏览器") {
                    Description(if (phase == CarLinkPhase.READY) "手机画面已就绪，浏览器显示需要单独启动。" else "手机连接与浏览器连接是两个步骤。")
                    Description("在 WheelPlay 查看局域网地址，用车机浏览器打开并配对，然后点击“启动显示”。浏览器与接收手机需在同一局域网，只保留一个已连接的显示页面。")
                }
                OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (advanced) "收起高级设置与诊断" else "高级设置与诊断")
                }
                if (advanced) {
                if (config.getString("videoOutput", "surface") == "surface") {
                Group("画质与浏览器输出") {
                    Description("复用 WheelPlay 的图片格式和质量配置，保存后立即生效。帧率是图片发布上限，实际速度取决于设备与网络。")
                    if (host.isEmpty) {
                        Description("请从新版 WheelPlay 的插件设置入口打开，以调整宿主画质和查看输出诊断。")
                    } else {
                        Choice("图片格式", host.getString("format", "WEBP")!!, listOf("WEBP" to "WebP", "JPEG" to "JPEG")) {
                            saveHost(Bundle(host).apply { putString("format", it) })
                        }
                        Choice("图片质量", host.getInt("quality", 75).toString(), listOf(60, 75, 85, 92, 100).map { it.toString() to it.toString() }) {
                            saveHost(Bundle(host).apply { putInt("quality", it.toInt()) })
                        }
                        Choice("浏览器输出上限", host.getInt("fps", 15).toString(), listOf(10, 15, 20, 30).map { it.toString() to "$it FPS" }) {
                            saveHost(Bundle(host).apply { putInt("fps", it.toInt()) })
                        }
                        host.getString("error")?.let { Description(it) }
                    }
                }
                }
                Group("投影画布") {
                    Description("保持浏览器画布比例。清晰度和文字大小在下次连接时生效。默认均衡模式保持原有行为。")
                    Choice("渲染清晰度", config.getString("mode", "balanced")!!,
                        listOf("balanced" to "均衡", "native" to "原生像素", "custom" to "自定义倍率")) {
                        saveNext(Bundle().apply { putString("mode", it) })
                    }
                    if (config.getString("mode") == "custom") {
                        var scale by remember(config) { mutableFloatStateOf(config.getFloat("scale", 1f)) }
                        Text("像素倍率：${"%.2f".format(scale)}×")
                        Slider(value = scale, onValueChange = { scale = it }, valueRange = .5f..2f, steps = 5,
                            onValueChangeFinished = { saveNext(Bundle().apply { putFloat("scale", scale) }) })
                    }
                    Description("均衡限制为 1920 × 1080；原生与自定义最多 4096 边长、约 838 万像素。这是内存保护限制，高分辨率是否可用取决于发送手机。")
                    Choice("文字与图标大小（DPI）", config.getInt("dpi", 160).toString(),
                        listOf(120, 160, 200, 240, 280, 320, 400, 480).map { it.toString() to it.toString() }) {
                        saveNext(Bundle().apply { putInt("dpi", it.toInt()) })
                    }
                    Description("DPI 越大，文字与图标通常越大；它不等于视频清晰度。未获取浏览器尺寸时，以 1280 × 720 为基准。")
                }
                Group("画面诊断") {
                    Description("插件：$version\nWheelPlay：${host.getString("hostVersion") ?: "请从 WheelPlay 打开以获取"}")
                    if (state.containsKey("errorCode")) Description("错误码：${state.getInt("errorCode")}")
                    OutlinedButton(onClick = { onCopy(carLinkDiagnosticReport(version, state, config, host)) }) { Text("复制诊断信息") }
                    Description("只复制版本、连接与输出方式、画面尺寸、阶段和错误码；不包含热点名称、密码、配对码或设备标识。")
                    if (state.getInt("width") > 0) {
                    Description("${if (state.getBoolean("active")) "当前" else "上次"}会话")
                    Description("宿主请求：${state.getInt("requestWidth")} × ${state.getInt("requestHeight")} px\n输出画布：${state.getInt("width")} × ${state.getInt("height")} px\n会话 DPI：${state.getInt("activeDpi")}")
                    } else Description("尚无会话数据，连接后显示请求尺寸、输出画布和 DPI。")
                    if (!host.isEmpty) {
                        Description("浏览器上报像素：${host.getString("viewport")}")
                        if (state.getString("videoOutput") == "webrtc") Description("压缩视频直接发送至浏览器；显示帧率请查看浏览器顶部状态。")
                        else Description(host.getString("output").orEmpty())
                    }
                    state.getString("videoOutput")?.let { Description("会话输出：${if (it == "webrtc") "WebRTC" else "Surface"}") }
                    if (state.getInt("encodedWidth") > 0) Description("源视频：${state.getInt("encodedWidth")} × ${state.getInt("encodedHeight")} px")
                    Description("浏览器上报尺寸已包含 DPR；输出画布与源视频尺寸可能不同。")
                }
                }
                Group("关于此插件") {
                    Description("支持单指触控\n麦克风关闭。音频可能在接收手机播放，暂不提供浏览器音频。")
                    Description("MiAuto 0.1.3 · ICCOA SDK 1.5.3\n适用于 arm64、4 KB 页设备。")
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        content()
    }
}

@Composable
private fun Description(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Choice(title: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 48.dp)) {
        Text("$title：${options.firstOrNull { it.first == value }?.second ?: value}")
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { (key, label) ->
                TextButton(onClick = { change(key); open = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (key == value) "✓ $label" else label)
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } })
}

@Composable
private fun HotspotCredentials(config: Bundle, save: (Bundle) -> Unit) {
    val savedSsid = config.getString("hotspotSsid", "")!!
    val savedPassword = config.getString("hotspotPassword", "")!!
    var ssid by remember(savedSsid) { mutableStateOf(savedSsid) }
    var password by remember(savedPassword) { mutableStateOf(savedPassword) }
    var showPassword by remember { mutableStateOf(false) }
    val credentials = CarLinkSoftAp.Credentials(ssid, password)
    val ssidError = credentials.ssidProblem()
    val passwordError = credentials.passwordProblem()
    val problem = ssidError ?: passwordError
    val count = ssid.codePointCount(0, ssid.length)
    val changed = ssid != savedSsid || password != savedPassword
    OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("热点名称（SSID）") },
        isError = ssid.isNotEmpty() && ssidError != null,
        supportingText = { Text("$count / 32 字符 · ${ssid.toByteArray(Charsets.UTF_8).size} / 32 字节\n" +
            (ssidError ?: "名称有效，须与系统热点完全一致")) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("热点密码") },
        isError = password.isNotEmpty() && passwordError != null,
        supportingText = { Text(passwordError ?: "密码格式有效，请确认与系统热点一致") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "隐藏" else "显示") } })
    if (problem != null) Description("名称和密码均符合要求后即可保存。")
    Button(onClick = { save(Bundle().apply { putString("hotspotSsid", ssid); putString("hotspotPassword", password) }) },
        enabled = changed && problem == null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
        Text(if (!changed && problem == null) "热点信息已保存" else "保存热点信息")
    }
}
