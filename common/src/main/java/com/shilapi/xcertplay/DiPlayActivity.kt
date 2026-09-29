// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.LanAddresses
import com.shilapi.xcertplay.web.BrowserExperienceLink
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Connection and preference pages in the shared server navigation. */
class DiPlayActivity : AppCompatActivity() {
    private val ui by lazy { ServerUi(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var page = "service"
    private lateinit var shell: ServerUi.Shell
    private var webAddress: TextView? = null
    private var webInterface: TextView? = null
    private var otherAddresses: LinearLayout? = null
    private var otherAddressesToggle: ServerUi.DisclosureRow? = null
    private var addressesExpanded = false
    private var displayedAddresses: List<LanAddresses.Entry>? = null
    private var webCode: TextView? = null
    private var webPairingControls: LinearLayout? = null
    private var quickBrowserButton: Button? = null
    private var webViewer: TextView? = null
    private var webStage: TextView? = null
    private var sessionConnectButton: Button? = null
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: com.google.android.material.button.MaterialButton? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); refreshService(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp("附近设备", "请允许访问附近设备，以连接已配对的 iPhone。")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private val scan = registerForActivityResult(ScanQRCode()) { result ->
        val payload = when (result) {
            is QRResult.QRSuccess -> result.content.rawValue?.take(257)
            QRResult.QRUserCanceled -> null
            QRResult.QRMissingPermission -> {
                permissionHelp("相机", "扫码配对需要相机权限，也可以在车机网页手动输入配对码。")
                null
            }
            is QRResult.QRError -> {
                toast("扫码失败，请检查相机权限或关闭正在使用相机的其他应用。")
                null
            }
        } ?: return@registerForActivityResult
        val request = WebSession.inspectPairing(payload)
        if (request == null) {
            toast("二维码无效或已过期，请刷新此服务端的车机网页后重试。")
        } else {
            MaterialAlertDialogBuilder(this).setTitle("允许这台车机连接？")
                .setMessage("浏览器地址：${request.peer}\n授权后，该浏览器可以显示 CarPlay 并发送触控操作。")
                .setNegativeButton("取消", null)
                .setPositiveButton("允许连接") { _, _ ->
                    toast(if (WebSession.approvePairing(payload)) "已授权，车机网页将自动连接" else "请求已过期，请刷新网页重新扫码")
                }.show()
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanPairing() else permissionHelp("相机", "扫码配对需要相机权限，也可以在车机网页手动输入配对码。")
    }
    private fun scanPairing() {
        if (!WebSession.running) { toast("请等待 Web 服务启动后再扫码"); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast("此设备没有相机，请在车机网页输入配对码"); return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        scan.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        ServerWindow.showSystemBars(window)
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            "认证资产未就绪，请安装包含认证资产的完整 APK 后重试。"
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "service"
        createShell()
        startForegroundService(Intent(this, DiPlaySessionService::class.java))
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "service") navigate("service") else moveTaskToBack(true)
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        navigate(intent.getStringExtra("page") ?: "service")
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); createShell() }
    override fun onResume() {
        super.onResume(); ServerWindow.showSystemBars(window); handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch) { refreshPhone(); refreshService() }
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun normalizedPage(value: String) = when (value) {
        "home", "wireless-recovery" -> "phone"
        "about" -> "settings"
        "phone", "settings" -> value
        else -> "service"
    }

    private fun navigate(destination: String) {
        val target = normalizedPage(destination)
        shell.select(target)
        page = target
        if (destination == "wireless-recovery") confirmWirelessReset()
    }

    private fun createShell() {
        val initial = normalizedPage(page)
        shell = ui.Shell(initial) { page = it }
        page = initial
        shell.put("service", ui.content(::service))
        refreshPhone()
        shell.put("settings", ui.content(::settings))
        setContentView(shell.root)
        refreshService()
    }

    private fun refreshPhone() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        shell.put("phone", ui.content(::home))
        refreshStatus()
    }

    private fun render() {
        refreshPhone()
        exportButton = null
        shell.put("settings", ui.content(::settings))
    }

    private fun service(content: LinearLayout) {
        content.addView(label(getString(R.string.app_name), 21, TEXT, true).apply { setPadding(0, 0, 0, dp(24)) })
        content.addView(label("在车机浏览器中使用 CarPlay", 28, TEXT, true))
        content.addView(space(24))
        ui.card(content, "车机访问", R.drawable.ic_server_server) { body ->
            body.addView(label("局域网地址", 14, MUTED))
            webAddress = label("正在启动服务…", 21, ACCENT, true).apply {
                setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(8))
            }
            body.addView(webAddress)
            webInterface = label("", 14, MUTED)
            body.addView(webInterface)
            quickBrowserButton = button("在本机浏览器中体验", false, R.drawable.ic_server_launch) {
                openLocalBrowserExperience()
            }.apply { isEnabled = false }
            body.addView(quickBrowserButton, ui.secondaryButtonLayout(12))
            otherAddressesToggle = ui.DisclosureRow("其他地址") {
                addressesExpanded = !addressesExpanded
                updateAddressExpansion(animate = true)
            }.apply { visibility = View.GONE }
            body.addView(otherAddressesToggle, ui.secondaryButtonLayout(4))
            otherAddresses = column().apply { visibility = View.GONE }
            body.addView(otherAddresses)
            displayedAddresses = null
        }
        ui.card(content, "浏览器配对", R.drawable.ic_server_qr) { body ->
            webPairingControls = column().apply {
                addView(label("扫描车机网页上的二维码，即可授权显示与触控。", 16, MUTED))
                addView(button("扫描二维码配对", true, R.drawable.ic_server_scan) { scanPairing() }, matchButton(12))
                addView(label("或在网页输入配对码", 14, MUTED).apply { setPadding(0, dp(16), 0, 0) })
                webCode = label("— — — — — —", 32, TEXT, true).apply {
                    letterSpacing = .12f; setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(16))
                }
                addView(webCode)
            }
            body.addView(webPairingControls)
            webViewer = label("等待车机浏览器连接", 16, MUTED)
            body.addView(webViewer)
        }
        ui.card(content, "iPhone 会话", R.drawable.ic_server_phone) { body ->
            webStage = label("等待连接 iPhone", 16, TEXT)
            body.addView(webStage)
            sessionConnectButton = button("连接", false, R.drawable.ic_server_phone) {
                if (!CarPlayBackgroundSession.hasSession()) connect(true)
            }.apply { visibility = View.GONE }
            body.addView(sessionConnectButton, ui.secondaryButtonLayout(12))
            body.addView(button("管理 iPhone 连接", false) { navigate("phone") }, ui.secondaryButtonLayout(16))
        }
        content.addView(label("音频与麦克风使用此设备，网页传输画面与触摸。", 14, MUTED))
        content.addView(button("停止服务", false) {
            startService(Intent(this, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP))
            finishAndRemoveTask()
        }, ui.secondaryButtonLayout(24))
    }

    private fun refreshService() {
        val addresses = if (WebSession.running && WebSession.error == null) WebSession.addresses(this) else emptyList()
        val primary = addresses.firstOrNull()
        val addressText = WebSession.error ?: if (WebSession.running) primary?.url
            ?: "未找到局域网地址，请连接 Wi-Fi" else "正在启动服务…"
        if (webAddress?.text?.toString() != addressText) webAddress?.text = addressText
        webInterface?.text = primary?.description.orEmpty()
        webInterface?.visibility = if (primary == null) View.GONE else View.VISIBLE
        if (displayedAddresses != addresses) {
            displayedAddresses = addresses
            otherAddresses?.removeAllViews()
            addresses.drop(1).forEachIndexed { index, entry ->
                otherAddresses?.addView(label(entry.description, 14, MUTED))
                otherAddresses?.addView(label(entry.url, 16, TEXT).apply {
                    setTextIsSelectable(true); setPadding(0, dp(4), 0, if (index < addresses.size - 2) dp(16) else 0)
                })
            }
            updateAddressExpansion()
        }
        webCode?.text = if (WebSession.running) WebSession.code else "— — — — — —"
        quickBrowserButton?.isEnabled = WebSession.running && BrowserExperienceLink.local(WebSession.code) != null
        val viewerConnected = WebSession.hasViewer
        webPairingControls?.visibility = if (viewerConnected) View.GONE else View.VISIBLE
        webViewer?.text = if (viewerConnected) "车机浏览器已连接" else "等待车机浏览器连接"
        webStage?.text = setupError ?: if (WebSession.videoActive) "CarPlay 画面正在串流" else WebSession.stage
    }

    private fun openLocalBrowserExperience() {
        val url = BrowserExperienceLink.local(WebSession.code)
        if (!WebSession.running || url == null) {
            toast("请等待 Web 服务启动后再打开")
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("未找到可打开网页的浏览器") }
    }

    private fun updateAddressExpansion(animate: Boolean = false) {
        val count = ((displayedAddresses?.size ?: 0) - 1).coerceAtLeast(0)
        otherAddressesToggle?.visibility = if (count > 0) View.VISIBLE else View.GONE
        otherAddressesToggle?.setExpanded(addressesExpanded, animate)
        otherAddresses?.visibility = if (count > 0 && addressesExpanded) View.VISIBLE else View.GONE
    }

    private fun home(content: LinearLayout) {
        content.addView(label("连接 iPhone", 30, TEXT, true))
        content.addView(label("此设备接收 CarPlay，车机浏览器负责显示与触控。", 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        status = label("等待连接", 20, ACCENT, true)
        ui.card(content, "连接状态", R.drawable.ic_server_phone) { it.addView(status) }
        ui.card(content, "无线连接", R.drawable.ic_server_wifi) { body ->
            body.addView(label("先将 iPhone 与这台 Android 设备完成蓝牙配对，并保持双方蓝牙和 Wi-Fi 开启。", 15, MUTED))
            body.addView(ui.preference("iPhone", selectedPhoneName()) { choosePhone() }, matchButton())
            connectButton = button("连接 iPhone", true, R.drawable.ic_server_phone) { connect(true) }
            body.addView(connectButton, matchButton(12))
            disconnectButton = button("断开 iPhone", false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread {
                    startForegroundService(Intent(this, DiPlaySessionService::class.java))
                    WebSession.stage = "iPhone 已断开"
                    refreshStatus(); refreshService()
                } }
            }.apply { visibility = View.GONE }
            body.addView(disconnectButton, ui.secondaryButtonLayout(12))
            if (carHotspotOff()) {
                body.addView(label("热点“${AirPlayPersistence.loadManualHotspotSsid(this)}”尚未开启，请先在系统设置中打开。", 15, WARNING).apply { setPadding(0, dp(16), 0, 0) })
                body.addView(button("打开热点设置", false) { openCarWifiSettings() }, ui.secondaryButtonLayout(12))
            }
        }
        ui.card(content, "USB 连接", R.drawable.ic_server_usb) { body ->
            body.addView(label("使用数据线连接 iPhone 与此设备，并在 iPhone 上允许 CarPlay。", 15, MUTED))
            body.addView(button("通过 USB 连接", false) { connect(false) }, ui.secondaryButtonLayout(16))
        }
        setupError?.let { content.addView(label(it, 16, WARNING)) }
    }

    private fun settings(content: LinearLayout) {
        content.addView(label("设置", 30, TEXT, true))
        content.addView(label("修改画面尺寸、分辨率、音频缓冲、帧率或串流技术时会重新连接。其他设置在下次连接生效。", 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "自动连接") { card ->
            toggle(card, "打开应用时自动连接", "使用上次的连接方式和已选择的 iPhone。", DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, "设备启动后打开", "需要系统允许应用自启动。", AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            card.addView(ui.preference("iPhone", selectedPhoneName()) { choosePhone() }, matchButton())
        }
        section(content, "无线连接") { card -> wirelessLinkControls(card) }
        section(content, "画面与音频") { card ->
            carPlaySizeControl(card)
            toggle(card, "自适应浏览器尺寸", "按车机浏览器的实际画面比例协商 CarPlay 分辨率；浏览器尺寸变化时会重新连接。", AirPlayPersistence.loadAdaptiveBrowserSize(this)) {
                AirPlayPersistence.saveAdaptiveBrowserSize(this, it)
                WebSession.setAdaptiveBrowserSize(it)
            }
            choice(card, "分辨率", listOf("原始分辨率", "80% · 降低负载", "60% · 最低负载"), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, "音频缓冲", listOf("300 ms · 默认", "500 ms", "1000 ms · 更稳定"),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, "协商帧率", listOf("30 fps · 较低负载", "60 fps · 更流畅"), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            val technologies = com.shilapi.xcertplay.web.StreamTechnology.entries
            choice(card, "串流技术", technologies.map { it.label },
                technologies.indexOf(AirPlayPersistence.loadStreamTechnology(this))) {
                AirPlayPersistence.saveStreamTechnology(this, technologies[it])
            }
            card.addView(label("WebRTC 支持 H.264 / HEVC 直通，浏览器不支持时自动回退 JPEG（约 15 fps）。追求 60 fps 请将协商帧率设为 60；实际帧率以网页显示为准。", 14, MUTED))
            toggle(card, "允许 iPhone 使用 HEVC", "下次连接生效。需要浏览器支持 HEVC / WebRTC，否则回退 JPEG；关闭时使用 H.264。", AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, "右舵布局", "将 CarPlay 控件移到右侧。", AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }

        }
        if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) section(content, "BYD 导航输出") { card ->
            toggle(card, "抬头显示与仪表导航",
                "在兼容的 BYD 设备上显示导航箭头、距离与路名，支持情况取决于车型。",
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
        }
        section(content, "权限与连接帮助") { card ->
            card.addView(label("附近设备权限用于连接 iPhone，麦克风用于 Siri 和通话。旧版 Android 的无线连接还需要定位权限，USB 模式可能请求本地 VPN 授权。", 16, MUTED))
            card.addView(button("应用权限", false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, ui.secondaryButtonLayout(16))
            card.addView(button("系统蓝牙设置", false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, ui.secondaryButtonLayout(12))
            card.addView(button("无线连接帮助", false) { wirelessHelp() }, ui.secondaryButtonLayout(12))
        }
        section(content, "关于与诊断") { card ->
            card.addView(label("${getString(R.string.app_name)} · ${version()}", 17, TEXT, true))
            exportButton = button(if (exportInProgress) "正在保存报告…" else "保存诊断报告", false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, ui.secondaryButtonLayout(12))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "报告保存到 Downloads/WheelPlay。" else "请选择报告保存位置。"
            card.addView(label(destination + "不会自动上传，报告排除协议载荷和凭据。", 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
            card.addView(label("基于 xcertplay / DiPlay，保留 GPL-3.0 与 AGPL-3.0 许可声明。CarPlay 与 NIO 图标属于各自权利人。", 13, MUTED).apply { setPadding(0, dp(20), 0, 0) })
        }
        section(content, "开源") { card ->
            card.addView(ui.preference("源码地址", "cnb.cool/siubeng/wheelplay-android") {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cnb.cool/siubeng/wheelplay-android")))
                }.onFailure { toast("未找到可打开网页的浏览器") }
            })
        }
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        MaterialAlertDialogBuilder(this).setTitle("热点尚未开启")
            .setMessage("当前使用热点“${AirPlayPersistence.loadManualHotspotSsid(this)}”，请先在系统设置中开启后再连接。")
            .setPositiveButton("打开系统设置") { _, _ -> openCarWifiSettings() }
            .setNeutralButton("连接") { _, _ -> connect(true) }
            .setNegativeButton("取消", null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    // Wi-Fi Direct is the default link. The car's own hotspot is an alternative when Wi-Fi Direct is unstable.
    // The runtime config rejects manual mode without valid credentials, so it is only saved together with them.
    private fun wirelessLinkControls(parent: LinearLayout) {
        val carHotspot = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL
        val options = arrayOf("Wi-Fi Direct · 默认", "设备热点")
        val control = ui.preference("连接网络", if (carHotspot) "设备热点" else "Wi-Fi Direct") {}
        control.setOnClickListener {
            var selection = if (carHotspot) 1 else 0
            MaterialAlertDialogBuilder(this).setTitle("连接网络")
                .setSingleChoiceItems(options, selection) { _, index -> selection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "应用并重新连接" else "保存") { _, _ ->
                    when {
                        (selection == 1) == carHotspot -> Unit
                        selection == 0 -> applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
                        hotspotError(storedSsid(), storedPassword()) == null -> applyWirelessLink(WirelessHotspotMode.MANUAL)
                        else -> askHotspotCredentials { ssid, password ->
                            saveHotspotCredentials(ssid, password)
                            applyWirelessLink(WirelessHotspotMode.MANUAL)
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }
        parent.addView(control, matchButton(0))
        if (!carHotspot) {
            parent.addView(label("由此设备创建 Wi-Fi Direct 网络供 iPhone 连接。", 14, MUTED))
            return
        }
        val ssid = storedSsid()
        val password = storedPassword()
        parent.addView(ui.preference("热点名称", ssid) {
            textInput("热点名称", ssid, secret = false) { value ->
                hotspotError(value, password)?.let { toast(it); return@textInput }
                saveHotspotCredentials(value, password)
                render()
            }
        }, matchButton(0))
        parent.addView(ui.preference("热点密码", if (password.isEmpty()) "未设置" else "•".repeat(8)) {
            textInput("热点密码", password, secret = true) { value ->
                hotspotError(ssid, value)?.let { toast(it); return@textInput }
                saveHotspotCredentials(ssid, value)
                render()
            }
        }, matchButton(0))
        parent.addView(label("先在系统设置中开启热点，在此填写相同名称与密码。iPhone 会连接该网络，修改在下次连接生效。", 14, MUTED).apply {
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun selectedPhoneName() = if (DiPlayPreferences.phoneAddress(this) == null) "未选择" else DiPlayPreferences.phoneName(this)

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.validate(ssid, password)

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        textInput("热点名称", storedSsid(), secret = false) { ssid ->
            textInput("热点密码", storedPassword(), secret = true) { password ->
                val error = hotspotError(ssid, password)
                if (error != null) toast(error) else done(ssid, password)
            }
        }
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        if (CarPlayBackgroundSession.hasSession()) connect(true)
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val field = TextInputLayout(this).apply {
            hint = title
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(field.context).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        field.addView(input, ViewGroup.LayoutParams(-1, -2))
        val container = column().apply { setPadding(dp(24), dp(8), dp(24), 0); addView(field) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(container)
            .setPositiveButton("保存") { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton("取消", null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, "CarPlay 显示尺寸", sizes.map { it.label }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label("调整 CarPlay 图标和文字大小，应用后会重新连接。", 14, MUTED).apply {
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun connect(wireless: Boolean) {
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        navigate("service")
        startActivity(Intent(this, CarPlayHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(0, 0)
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            MaterialAlertDialogBuilder(this).setTitle("请开启蓝牙")
                .setMessage("请开启这台 Android 设备的蓝牙，并先与 iPhone 配对。")
                .setPositiveButton("打开蓝牙设置") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("稍后", null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("请先配对 iPhone")
                .setMessage("在 iPhone 的“设置 → 蓝牙”中与这台 Android 设备配对，然后返回选择 iPhone。")
                .setPositiveButton("打开蓝牙设置") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("知道了", null).show(); return
        }
        MaterialAlertDialogBuilder(this).setTitle("选择 iPhone")
            .setItems(devices.map { device ->
                val name = device.name ?: "已配对设备"
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton("配对其他设备") { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton("取消") { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        MaterialAlertDialogBuilder(this).setTitle("无线连接帮助")
            .setMessage("将 iPhone 与此设备完成蓝牙配对，保持 Wi-Fi 开启，并在 iPhone 上允许 CarPlay。请关闭其他投屏应用。\n\n如果之前的连接仍占用网络，可重置 CarPlay Wi-Fi 后重连。此操作不会关闭普通上网 Wi-Fi。")
            .setPositiveButton("知道了", null)
            .setNeutralButton("重置 CarPlay Wi-Fi") { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (intent.getStringExtra("page") != "wireless-recovery") return
        intent.removeExtra("page")
        navigate("phone")
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        MaterialAlertDialogBuilder(this).setTitle("重置 CarPlay Wi-Fi？")
            .setMessage("这会结束当前 Wi-Fi Direct 连接，包括重新安装前遗留的连接。请先关闭其他投屏应用，普通上网 Wi-Fi 不受影响。")
            .setPositiveButton("重置并连接") { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton("取消", null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast("此设备不支持 Wi-Fi Direct。"); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast("Wi-Fi Direct 仍被占用，请关闭其他投屏应用后重试。")
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast("无法重置 Wi-Fi Direct，请关闭其他投屏应用后重试。") }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp("无线连接权限", "请先允许附近设备权限；旧版 Android 还需定位权限。")
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> "认证配置需要处理"
            CarPlayBackgroundSession.active -> "CarPlay 已连接"
            running -> "正在连接 iPhone…"
            DiPlayPreferences.phoneAddress(this) != null -> "已选择 ${DiPlayPreferences.phoneName(this)}"
            else -> "尚未选择 iPhone"
        }
        if (lastRunning != running) {
            connectButton?.visibility = if (running) View.GONE else View.VISIBLE
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
        sessionConnectButton?.visibility = if (!running && !CarPlayBackgroundSession.active &&
            DiPlayPreferences.phoneAddress(this) != null) View.VISIBLE else View.GONE
        sessionConnectButton?.isEnabled = setupError == null
    }
    private fun reportFileName() = "WheelPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                "无法打开保存位置，请重试保存到下载目录。"
                else "此设备没有可用的文件选择器。")
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = "正在保存报告…" }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("WheelPlay ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = "保存诊断报告" }
                if (result.isSuccess) {
                    MaterialAlertDialogBuilder(this).setTitle("诊断报告已保存")
                        .setMessage(if (uri == null) "Downloads/WheelPlay/$fileName" else "报告已保存到所选位置。")
                        .setPositiveButton("完成", null).show()
                } else {
                    MaterialAlertDialogBuilder(this).setTitle("无法保存报告")
                        .setMessage("请检查存储空间，或选择其他保存位置。")
                        .setPositiveButton("选择位置") { _, _ -> chooseReportDestination() }
                        .setNegativeButton("关闭", null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(body).setPositiveButton("应用设置") { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton("稍后", null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast("请从此设备的系统设置中打开该选项。") } }
    private fun toast(message: String) { com.google.android.material.snackbar.Snackbar.make(shell.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) =
        ui.section(parent, title, content = build)
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        line.addView(MaterialSwitch(this).apply { contentDescription = title; isChecked = value; minHeight = dp(48); setOnCheckedChangeListener { _, checked -> save(checked) } })
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, save: (Int) -> Unit) {
        var selection = current
        val control = ui.preference(title, options[selection].substringBefore(" · ")) {}
        control.setOnClickListener {
            var pendingSelection = selection
            MaterialAlertDialogBuilder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) "应用并重新连接" else "保存") { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        control.setValue(options[selection].substringBefore(" · "))
                        if (CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }
        parent.addView(control, matchButton(0))
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = ui.text(value, size, color, bold)
    private fun button(title: String, primary: Boolean, @androidx.annotation.DrawableRes iconRes: Int = 0, click: () -> Unit) = ui.button(title, primary, iconRes, click)
    private fun matchButton(top: Int = 0) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = ServerUi.BG
        private val SURFACE = ServerUi.SURFACE
        private val BORDER = ServerUi.BORDER
        private val ACCENT = ServerUi.ACCENT
        private val TEXT = ServerUi.TEXT
        private val MUTED = ServerUi.MUTED
        private val WARNING = ServerUi.WARNING
    }
}
