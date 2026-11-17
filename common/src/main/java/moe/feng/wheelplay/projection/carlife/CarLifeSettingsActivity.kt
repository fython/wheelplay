package moe.feng.wheelplay.projection.carlife

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.projection.projectionSessions
import com.shilapi.xcertplay.projection.ProjectionSourceIds
import moe.feng.wheelplay.ui.WheelPlaySettingsTheme

class CarLifeSettingsActivity : ComponentActivity() {
    companion object { const val EXTRA_PREPARE_REQUEST = "carlife.prepareRequest" }
    private var directLocalPreparation = false
    private val systemConnection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (directLocalPreparation) {
            directLocalPreparation = false
            val prefs = getSharedPreferences("carlife_connection", MODE_PRIVATE)
            val editor = prefs.edit().putBoolean("remoteMode", false).putString("host", "")
            prefs.getString("host", "").orEmpty().takeIf { it.isNotEmpty() }?.let {
                editor.putString("lastRemoteHost", it)
            }
            if (editor.commit()) continueConnection(intent.getStringExtra(EXTRA_PREPARE_REQUEST))
            else {
                Toast.makeText(this, "连接设置保存失败", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    private val ui = Handler(Looper.getMainLooper())
    private var history by mutableStateOf("")
    private var discoveryVisible by mutableStateOf(false)
    private val refresh = object : Runnable {
        override fun run() {
            history = status().getString("history").orEmpty()
            ui.postDelayed(this, 1500)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val profile = CarLifeEnvironment.detect(this)
        val prefs = getSharedPreferences("carlife_connection", MODE_PRIVATE)
        val request = intent.getStringExtra(EXTRA_PREPARE_REQUEST)
        directLocalPreparation = savedInstanceState?.getBoolean("directLocalPreparation") == true
        if (directLocalPreparation) return
        val remoteMode = prefs.getBoolean("remoteMode", prefs.getString("host", "").orEmpty().isNotEmpty())
        if (savedInstanceState == null && request != null && !remoteMode) {
            val entry = CarLifeEnvironment.entry(this, profile)
            if (entry != null) {
                directLocalPreparation = true
                val launched = runCatching {
                    entry.flags = entry.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                    systemConnection.launch(entry)
                }.isSuccess
                if (launched) return
                directLocalPreparation = false
            }
            Toast.makeText(this, "无法打开系统连接设置，请手动准备连接。", Toast.LENGTH_LONG).show()
        }
        setContent {
            val remote = remember { prefs.getBoolean("remoteMode", prefs.getString("host", "").orEmpty().isNotEmpty()) }
            var host by remember { mutableStateOf(prefs.getString("host", "").orEmpty().ifEmpty { prefs.getString("lastRemoteHost", "").orEmpty() }) }
            var remoteProtocol by remember { mutableStateOf(CarLifeConnection.protocolSelection(prefs.getString("remoteProtocolVersion", null), prefs.getString("brand", null))) }
            var localProtocol by remember { mutableStateOf(CarLifeConnection.protocolSelection(prefs.getString("localProtocolVersion", null), prefs.getString("localProtocol", null), profile.id)) }
            var manual by remember { mutableStateOf(false) }
            var searchGeneration by remember { mutableStateOf(0) }
            var discovered by remember { mutableStateOf(emptyList<String>()) }
            var searchStatus by remember { mutableStateOf("") }
            DisposableEffect(remote, discoveryVisible, searchGeneration) {
                var disposed = false
                val discovery = if (remote && discoveryVisible) {
                    discovered = emptyList()
                    searchStatus = "正在搜索附近的 CarLife 手机…"
                    CarLifeDiscovery({ address -> ui.post { if (!disposed) discovered = (discovered + address).distinct() } },
                        { message -> ui.post { if (!disposed) searchStatus = message } }).also { it.start() }
                } else null
                onDispose { disposed = true; discovery?.close() }
            }
            val valid = !remote || CarLifeConnection.validAddress(host.trim())
            fun saveAndContinue() {
                if (prefs.edit().putBoolean("remoteMode", remote).putString("lastRemoteHost", host.trim()).putString("host", if (remote) host.trim() else "").putString("remoteProtocolVersion", remoteProtocol).putString("localProtocolVersion", localProtocol).commit()) continueConnection(request)
                else Toast.makeText(this, "连接设置保存失败", Toast.LENGTH_LONG).show()
            }
            WheelPlaySettingsTheme {
                var moreExpanded by remember { mutableStateOf(false) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("CarLife") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(painterResource(R.drawable.ic_carlife_back), contentDescription = "返回")
                                }
                            },
                            actions = {
                                Box {
                                    IconButton(onClick = { moreExpanded = true }) {
                                        Icon(painterResource(R.drawable.ic_carlife_more), contentDescription = "更多选项")
                                    }
                                    DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                                        DropdownMenuItem(text = { Text("停止 CarLife 接收") }, onClick = {
                                            moreExpanded = false
                                            projectionSessions.snapshot()?.takeIf { it.descriptor.id == ProjectionSourceIds.CARLIFE }?.let { projectionSessions.stop(it) }
                                        })
                                    }
                                }
                            },
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text("将手机画面带到车机浏览器", style = MaterialTheme.typography.headlineSmall)
                            Text("保持手机解锁，按以下步骤准备连接。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (remote) {
                                Section("连接手机") {
                                    Text(searchStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("可连接地址（${discovered.size} 个已发现）", style = MaterialTheme.typography.titleSmall)
                                    val candidates = (discovered + listOf(host.trim()).filter { CarLifeConnection.validAddress(it) }).distinct()
                                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                                        candidates.forEach { address ->
                                            val selected = host.trim() == address
                                            Row(
                                                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                                    .selectable(selected = selected, role = Role.RadioButton,
                                                        onClick = { host = address; manual = false })
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                RadioButton(selected = selected, onClick = null)
                                                Column(Modifier.weight(1f)) {
                                                    Text(address, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                                                    Text(if (address in discovered) "本轮发现" else "已保存或手动指定 · 本轮未发现",
                                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if (selected) Text("已选择", style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                    if (candidates.isEmpty()) Text("发现地址后会显示在此处，请选择要连接的地址。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { searchGeneration++ }) { Text("重新搜索") }
                                        TextButton(onClick = { manual = !manual }) { Text(if (manual) "收起手动地址" else "手动输入 IP（可选）") }
                                    }
                                    if (manual) OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("源手机 IPv4 地址") },
                                        placeholder = { Text("例如 192.168.1.100") }, singleLine = true,
                                        isError = host.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth())
                                    Text("两台手机连接同一 Wi-Fi。在源手机开始 CarLife 无线连接，再从列表选择手机。广播无法到达时可手动输入 IP；连接中的手机可能不再广播。")
                                }
                            }
                            var protocolDialogVisible by remember { mutableStateOf(false) }
                            val selectedProtocol = if (remote) remoteProtocol else localProtocol
                            val protocolOptions = listOf(
                                Triple("auto", "自动（推荐）", "优先 4.1；仅在源手机明确拒绝版本时尝试 3.0。"),
                                Triple("3.0", "3.0 · 明文兼容", "使用传统 CarLife 握手与明文传输。"),
                                Triple("4.1", "4.1 · 加密兼容", "使用认证与加密协商。"),
                            )
                            Section("协议兼容模式") {
                                Text("按源手机支持的协议选择，手机品牌不限定可用模式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { protocolDialogVisible = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(protocolOptions.first { it.first == selectedProtocol }.second)
                                }
                                Text("保存后在下次连接生效。自动模式不会因认证失败、解密失败或网络超时降级。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (protocolDialogVisible) {
                                AlertDialog(
                                    onDismissRequest = { protocolDialogVisible = false },
                                    title = { Text("协议兼容模式") },
                                    text = {
                                        Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                                            protocolOptions.forEach { (value, title, description) ->
                                                ProtocolOption(value, title, description, selectedProtocol) {
                                                    if (remote) remoteProtocol = it else localProtocol = it
                                                    protocolDialogVisible = false
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {},
                                    dismissButton = {
                                        TextButton(onClick = { protocolDialogVisible = false }) { Text("取消") }
                                    },
                                )
                            }
                            if (remote) {
                                Section("连接准备") {
                                    Step("1", "准备源手机", "在另一台手机开启 CarLife 无线连接。vivo 可在蓝牙检查弹窗中选择取消。")
                                    Step("2", "开始接收", "选择发现的源手机，点击继续，然后在 WheelPlay 浏览器观看和操作。")
                                    Button(onClick = { saveAndContinue() }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (request != null) "源手机已准备，继续" else "保存并返回 WheelPlay")
                                    }
                                }
                            } else {
                            Text("本机系统入口：${profile.label}", style = MaterialTheme.typography.titleMedium)
                            if (!profile.tcpVerified) Text("此环境尚未实机验证。请手动开启系统车载功能的 CarLife 连接；发现应用不代表已经兼容。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Section("连接准备") {
                                Step("1", "打开系统车载功能", "在本机系统连接页开始 CarLife 连接；若其他接收应用正在连接，请先停止。")
                                if (profile.id == "xiaomi") {
                                    Text("小米实验适配：先点击「启动 CarWith 连接」，随后返回此页继续。旧版若没有公开入口，请从系统连接设置手动准备。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = {
                                        val entry = CarLifeEnvironment.publicConnectionEntry(this@CarLifeSettingsActivity, profile)
                                        if (entry == null) Toast.makeText(this@CarLifeSettingsActivity, "此版本没有可用的公开启动入口，请使用系统连接设置手动连接。", Toast.LENGTH_LONG).show()
                                        else runCatching { startActivity(entry) }.onFailure {
                                            Toast.makeText(this@CarLifeSettingsActivity, "CarWith 启动请求失败，请使用系统连接设置。", Toast.LENGTH_LONG).show()
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("启动 CarWith 连接") }
                                }
                                if (profile.id == "vivo") Step("2", "跳过蓝牙检查", "在「检查车机蓝牙」弹窗点击「取消」，继续准备投影。")
                                else Step("2", "完成系统准备", "按本机系统提示进入无线连接等待状态，再返回此页。")
                                Step("3", "返回并继续", "及时返回此页，点击下方按钮，再到 WheelPlay 浏览器观看和操作。")
                                Button(onClick = { openConnectionPage() }, modifier = Modifier.fillMaxWidth()) { Text("打开系统连接设置") }
                                OutlinedButton(onClick = { saveAndContinue() }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (request != null) "准备完成，保存并连接" else "保存并返回 WheelPlay")
                                }
                            }
                            }
                            Section("使用说明") {
                                Text("WebUI 尺寸协商（实验） · 支持双指缩放", style = MaterialTheme.typography.titleSmall)
                                Text("已在 CarWith、jovi InCar 上通过验证。开启自适应显示尺寸后，连接时会请求 WebUI 尺寸。调整窗口尺寸后，请在 WheelPlay 停止投影并重新连接；若尺寸不符，旧车辆的分辨率设置可能仍在生效。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (remote) "断线后等待源手机恢复两分钟。请在源手机重新开始 CarLife 连接；源手机 IP 改变时，需更新地址并重新连接。" else "断线后会等待系统源恢复两分钟。在系统车载功能重新开始连接并返回 WheelPlay，可自动接回原尺寸的投影。若看到用户协议提示页，请重新完成上面的手动连接步骤。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            var expanded by remember { mutableStateOf(false) }
                            Section("连接诊断") {
                                Text(if (history.isBlank()) "尚无连接记录" else "已记录连接过程，可展开查看或复制。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起诊断" else "查看诊断") }
                                    TextButton(enabled = history.isNotBlank(), onClick = {
                                        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CarLife", history))
                                        Toast.makeText(this@CarLifeSettingsActivity, "诊断已复制", Toast.LENGTH_SHORT).show()
                                    }) { Text("复制诊断") }
                                }
                                if (expanded) SelectionContainer {
                                    Text(history.ifBlank { "尚无连接记录" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openConnectionPage() {
        runCatching {
            val profile = CarLifeEnvironment.detect(this)
            startActivity(checkNotNull(CarLifeEnvironment.entry(this, profile)))
        }.onFailure { Toast.makeText(this, "无法打开连接设置，请手动进入本机系统车载功能。", Toast.LENGTH_LONG).show() }
    }

    private fun continueConnection(request: String?) {
        if (request != null) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PREPARE_REQUEST, request))
            finish()
        } else {
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("directLocalPreparation", directLocalPreparation)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); discoveryVisible = true; ui.post(refresh) }
    override fun onPause() { discoveryVisible = false; ui.removeCallbacks(refresh); super.onPause() }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable private fun Step(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(number, style = MaterialTheme.typography.labelLarge) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ProtocolOption(value: String, title: String, description: String, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .selectable(selected = selected == value, role = Role.RadioButton, onClick = { onSelect(value) })
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected == value, onClick = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
