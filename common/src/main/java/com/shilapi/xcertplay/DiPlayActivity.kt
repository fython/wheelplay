// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import com.shilapi.xcertplay.projection.projectionSessions
import com.shilapi.xcertplay.projection.plugin.projectionPlugins
import com.shilapi.xcertplay.projection.plugin.InstalledProjectionPlugin
import com.shilapi.xcertplay.projection.plugin.RemotePluginSession
import com.shilapi.xcertplay.projection.ProjectionSession
import moe.feng.wheelplay.plugin.ProjectionPlugin
import com.shilapi.xcertplay.projection.registerProjectionSources
import com.shilapi.xcertplay.projection.androidauto.LocalAndroidAuto
import com.shilapi.xcertplay.projection.vnc.VncSource
import com.shilapi.xcertplay.projection.vnc.VncEndpoint

import com.shilapi.xcertplay.shared.R as UiR
import com.shilapi.xcertplay.i18n.*

import android.Manifest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.RootAccess
import com.shilapi.xcertplay.web.LanAddresses
import com.shilapi.xcertplay.web.TeslaHttpCompatibility
import com.shilapi.xcertplay.web.LanTls
import com.shilapi.xcertplay.web.BrowserExperienceLink
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.TeslaHttpMode
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.compose.ui.platform.ComposeView
import com.shilapi.xcertplay.settings.*
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Connection and preference pages in the shared server navigation. */
class DiPlayActivity : AppCompatActivity() {
    private class CertificateDocument : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
    }
    private val ui by lazy { ServerUi(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var page = "service"
    private val sourceDetailPages = setOf("source-carplay", "source-android-auto", "source-iccoa", "source-vnc", "source-diagnostics", "source-plugins")
    private val sourceStatuses = mutableMapOf<String, TextView>()
    private val sourceStopButtons = mutableMapOf<String, Button>()
    private val settingsModel by lazy { ViewModelProvider(this)[SettingsViewModel::class.java] }
    private var settingsView: ComposeView? = null
    private val settingsDialogs = SettingsDialogHost()
    private lateinit var shell: ServerUi.Shell
    private var webAddress: TextView? = null
    private var webInterface: TextView? = null
    private var webBusy = false
    private var awaitingTeslaVpnConsent = false
    private var vncDialog: android.app.AlertDialog? = null
    private var localAndroidAutoDialog: android.app.AlertDialog? = null
    private val localAndroidAutoSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!isFinishing && !isDestroyed) showLocalAndroidAutoSetup()
    }
    private val teslaVpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requested = awaitingTeslaVpnConsent
        awaitingTeslaVpnConsent = false
        if (requested && TeslaHttpCompatibility.config(this).mode == TeslaHttpMode.VPN) {
            applyTeslaHttpEnabled(result.resultCode == RESULT_OK)
            if (result.resultCode != RESULT_OK) toast(getString(UiR.string.ui_vpn_permission_canceled_rootless_mode_remains_disabled))
        }
    }
    private var pendingPemCertificate: ByteArray? = null
    private val pemCertificatePicker = registerForActivityResult(CertificateDocument()) { uri ->
        if (uri != null) {
            webBusy = true
            Thread({
                val result = runCatching { readCertificateFile(uri) }
                runOnUiThread {
                    webBusy = false
                    if (isFinishing || isDestroyed) { result.getOrNull()?.fill(0); return@runOnUiThread }
                    result.onSuccess { pendingPemCertificate = it; pemKeyPicker.launch(arrayOf("*/*")) }
                        .onFailure { toast(getString(UiR.string.error_certificate_read, it.userMessage(this))) }
                }
            }, "https-certificate-read").start()
        }
    }
    private val pemKeyPicker = registerForActivityResult(CertificateDocument()) { uri ->
        val certificate = pendingPemCertificate
        pendingPemCertificate = null
        if (uri == null || certificate == null) certificate?.fill(0)
        else textInput(getString(UiR.string.ui_pem_private_key_password_leave_blank_if_unencrypted), "", true, onCancel = { certificate.fill(0) }) { value ->
            val password = value.toCharArray()
            configureWeb(getString(UiR.string.ui_https_certificate_imported_reconnect_your_browser)) {
                try {
                    val key = readCertificateFile(uri)
                    try { LanTls.preparePem(certificate, key, password).use { WebSession.installCertificate(this, it) } }
                    finally { key.fill(0) }
                } finally { certificate.fill(0); password.fill('\u0000') }
            }
        }
    }
    private val pkcs12Picker = registerForActivityResult(CertificateDocument()) { uri ->
        if (uri != null) textInput(getString(UiR.string.ui_pkcs_12_file_password_optional), "", true) { value ->
            choosePkcs12Identity(uri, value.toCharArray())
        }
    }
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
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); refreshService(); refreshSettingsRuntime(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(UiR.string.ui_nearby_devices), getString(UiR.string.ui_allow_access_to_nearby_devices_to_connect_to_a))
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private val oemImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            oemImageCrop.launch(Intent(this, ImageCropActivity::class.java)
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast(getString(UiR.string.ui_could_not_open_the_icon_editor_try_again)) }
    }
    private val oemImageCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            render()
            toast(getString(UiR.string.ui_vehicle_icon_saved_applies_on_the_next_iphone_connection))
        }
    }

    private val authenticationImport by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))[AuthenticationImportModel::class.java]
    }
    private var selectedPrivateKey: Uri? = null
    private val selectAuthenticationKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedPrivateKey = uri
            launchAuthenticationPicker { selectAuthenticationCertificate.launch(arrayOf("*/*")) }
        }
    }
    private val selectAuthenticationCertificate = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = selectedPrivateKey
        selectedPrivateKey = null
        if (uri != null && key != null) authenticationImport.import(key = key, certificate = uri)
    }
    private val selectAuthenticationApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) authenticationImport.import(apk = uri)
    }

    private val scan = registerForActivityResult(ScanQRCode()) { result ->
        val payload = when (result) {
            is QRResult.QRSuccess -> result.content.rawValue?.take(257)
            QRResult.QRUserCanceled -> null
            QRResult.QRMissingPermission -> {
                permissionHelp(getString(UiR.string.ui_camera), getString(UiR.string.ui_qr_pairing_requires_camera_permission_you_can_also_enter))
                null
            }
            is QRResult.QRError -> {
                toast(getString(UiR.string.ui_scan_failed_check_camera_permission_or_close_other_apps))
                null
            }
        } ?: return@registerForActivityResult
        val request = WebSession.inspectPairing(payload)
        if (request == null) {
            toast(getString(UiR.string.ui_invalid_or_expired_qr_code_refresh_this_server_s))
        } else {
            MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_allow_this_head_unit_to_connect))
                .setMessage(getString(UiR.string.pairing_permission_message, request.peer))
                .setNegativeButton(getString(UiR.string.ui_cancel), null)
                .setPositiveButton(getString(UiR.string.ui_allow_connection)) { _, _ ->
                    toast(if (WebSession.approvePairing(payload)) getString(UiR.string.ui_authorized_the_head_unit_web_page_will_connect_automatically) else getString(UiR.string.ui_request_expired_refresh_the_web_page_and_scan_again))
                }.show()
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanPairing() else permissionHelp(getString(UiR.string.ui_camera), getString(UiR.string.ui_qr_pairing_requires_camera_permission_you_can_also_enter))
    }
    private fun scanPairing() {
        if (!WebSession.running) { toast(getString(UiR.string.ui_wait_for_the_web_service_to_start_before_scanning)); return }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast(getString(UiR.string.ui_this_device_has_no_camera_enter_the_pairing_code)); return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA); return
        }
        scan.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TeslaHttpCompatibility.config(this).let { it.enabled && it.mode == TeslaHttpMode.ROOT }) RootAccess.checkOnStartup()
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        ServerWindow.showSystemBars(window)
        refreshAuthentication()
        selectedPrivateKey = savedInstanceState?.getString("selectedPrivateKey")?.let(Uri::parse)
        awaitingTeslaVpnConsent = savedInstanceState?.getBoolean("awaitingTeslaVpnConsent") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page")
            ?: if (setupError == null) "service" else "settings"
        if (savedInstanceState == null && page == "settings" && setupError != null) {
            settingsModel.open(SettingsCategory.AUTHENTICATION)
        }
        createShell()
        startWebServiceIfReady()
        handleWirelessRecovery()
        authenticationImport.state.observe(this) { state ->
            refreshAuthentication()
            render(); refreshService()
            state.result?.let {
                startWebServiceIfReady()
                toast(it); authenticationImport.consumeResult()
            }
        }
        if (savedInstanceState == null) handleAuthenticationIntent(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    page == "settings" && settingsModel.state.value.category != null -> settingsModel.open(null)
                    page in sourceDetailPages -> navigate("phone")
                    page != "service" -> navigate("service")
                    else -> moveTaskToBack(true)
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (!handleAuthenticationIntent(intent)) navigate(intent.getStringExtra("page") ?: "service")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("selectedPrivateKey", selectedPrivateKey?.toString())
        outState.putBoolean("awaitingTeslaVpnConsent", awaitingTeslaVpnConsent)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        settingsView?.disposeComposition()
        super.onConfigurationChanged(newConfig)
        createShell()
    }
    override fun onResume() {
        super.onResume(); projectionPlugins(this).refresh(); refreshAuthentication(); render(); ServerWindow.showSystemBars(window); handler.removeCallbacks(tick); handler.post(tick)
        WebSession.setPhoneConnectHandler {
            if (setupError != null) setupError!!
            else {
                connect(AirPlayPersistence.loadWirelessEnabled(this))
                getString(UiR.string.ui_iphone_connection_requested_complete_any_device_selection_or_permission)
            }
        }
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch) { refreshPhone(); refreshService() }
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !DiPlayBootstrap.importing && selectedPrivateKey == null &&
                intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW &&
                !projectionSessions.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { WebSession.setPhoneConnectHandler(null); handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { vncDialog?.dismiss(); localAndroidAutoDialog?.dismiss(); settingsDialogs.cancel(); pendingPemCertificate?.fill(0); pendingPemCertificate = null; super.onDestroy() }

    private fun normalizedPage(value: String) = when (value) {
        "home" -> "phone"
        "wireless-recovery" -> "source-carplay"
        "about" -> "settings"
        "phone", "settings" -> value
        in sourceDetailPages -> value
        else -> "service"
    }

    private fun navigate(destination: String) {
        val target = normalizedPage(destination)
        if (target == "settings") {
            settingsModel.open(if (setupError != null) SettingsCategory.AUTHENTICATION else null)
        }
        shell.select(target)
        page = target
        if (destination == "wireless-recovery") confirmWirelessReset()
    }

    private fun createShell() {
        val initial = normalizedPage(page)
        shell = ui.Shell(initial, { page = it }, sourceDetailPages.associateWith { "phone" })
        page = initial
        shell.put("service", ui.content(::service))
        refreshPhone()
        settingsView = ComposeView(this).apply {
            id = R.id.settings_compose
            setContent { SettingsRoute(settingsModel, settingsActions, settingsDialogs) { shell.setPageScroll("settings", it) } }
        }.also { shell.put("settings", it) }
        refreshSettings()
        setContentView(shell.root)
        refreshService()
    }

    private fun refreshPhone() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        sourceStatuses.clear(); sourceStopButtons.clear()
        shell.put("phone", ui.content(::sourceIndex))
        shell.put("source-carplay", ui.content(::carPlaySource))
        shell.put("source-android-auto", ui.content(::androidAutoSource))
        shell.put("source-iccoa", ui.content(::iccoaSource))
        shell.put("source-vnc", ui.content(::vncSource))
        shell.put("source-diagnostics", ui.content(::diagnosticSources))
        shell.put("source-plugins", ui.content(::pluginSources))
        refreshStatus()
    }

    private fun render() {
        refreshPhone()
        refreshSettings()
    }

    private fun refreshSettings() {
        settingsModel.reload()
        refreshSettingsRuntime()
    }

    private fun refreshSettingsRuntime() {
        val session = CarPlayBackgroundSession.snapshot()
        val audioStatus = when {
            session == null -> getString(UiR.string.ui_current_no_iphone_connected)
            AirPlayPersistence.loadBrowserAudioPlayback(this) && WebSession.audio.playbackReady && session.sink.isRemoteMediaAvailable() -> getString(UiR.string.ui_current_output_head_unit_browser)
            AirPlayPersistence.loadBrowserAudioPlayback(this) -> getString(UiR.string.ui_current_output_android_browser_not_ready_or_offline)
            else -> getString(UiR.string.ui_current_output_android)
        }
        val source = AirPlayPersistence.loadMicrophoneSource(this)
        val microphoneStatus = when {
            source == CarPlayMicrophoneSource.DISABLED -> getString(UiR.string.ui_current_microphone_disabled)
            session == null -> getString(UiR.string.ui_current_no_iphone_connected)
            source == CarPlayMicrophoneSource.BROWSER && WebSession.audio.microphoneReady -> getString(UiR.string.ui_input_browser_captured_on_demand)
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> getString(UiR.string.ui_android_microphone_permission_is_missing_voice_input_is_unavailable)
            source == CarPlayMicrophoneSource.BROWSER -> getString(UiR.string.ui_input_android_browser_microphone_not_ready)
            else -> getString(UiR.string.ui_input_android_captured_on_demand)
        }
        val compatibility = TeslaHttpCompatibility.status
        val teslaConfig = TeslaHttpCompatibility.config(this)
        val teslaMessage = if (!teslaConfig.enabled) getString(UiR.string.ui_disabled)
            else if (!WebSession.running && setupError != null) getString(UiR.string.ui_starts_after_authentication_resources_are_imported)
            else compatibility.error ?: compatibility.address?.let {
                if (compatibility.mode == TeslaHttpMode.VPN) getString(UiR.string.status_rootless_address_configured, it)
                else getString(UiR.string.status_root_routing_enabled, it, compatibility.downstreams.joinToString())
            } ?: getString(UiR.string.status_preparing_mode, getString(teslaConfig.mode.labelRes))
        settingsModel.updateRuntime(SettingsRuntime(
            authenticationError = setupError, importing = DiPlayBootstrap.importing, webBusy = webBusy,
            awaitingVpnConsent = awaitingTeslaVpnConsent, exporting = exportInProgress,
            rootGranted = RootAccess.granted, rootChecking = RootAccess.checking, rootStatus = RootAccess.status.resolve(this),
            teslaStatus = teslaMessage, audioStatus = audioStatus, microphoneStatus = microphoneStatus,
            version = version(), fingerprint = WebSession.tls?.fingerprint
        ))
    }

    private val settingsActions by lazy {
        SettingsActions(
            choice = { setting, index -> changeSettings { applySettingsEffect(settingsModel.choose(setting, index)) }?.let(::toast) },
            toggle = { setting, checked -> changeSettings { applySettingsEffect(settingsModel.toggle(setting, checked)) }?.let(::toast) },
            text = { setting, value -> changeSettings { applySettingsEffect(settingsModel.edit(setting, value)) } },
            credentials = { ssid, password -> changeSettings { settingsModel.saveCredentials(ssid, password) } },
            margins = { margins -> changeSettings { applySettingsEffect(settingsModel.saveMargins(margins)) }?.let(::toast) },
            resetBranding = { includeName -> changeSettings { settingsModel.resetBranding(includeName) }?.let(::toast) },
            command = { command -> changeSettings { settingsCommand(command) }?.let(::toast) },
            renameBrowser = { id, name -> changeSettings { settingsModel.renameBrowser(id, name) }?.let(::toast) },
            removeBrowser = { id -> changeSettings { settingsModel.removeBrowser(id) }?.let(::toast) }
        )
    }

    private fun changeSettings(action: () -> Unit): String? = runCatching {
        action(); refreshSettings(); refreshService()
    }.exceptionOrNull()?.let { it.userMessage(this) }

    private fun applySettingsEffect(effect: SettingsEffect) {
        when (effect) {
            SettingsEffect.None -> Unit
            is SettingsEffect.Reconnect -> if (CarPlayBackgroundSession.hasSession()) {
                val wireless = effect.wireless ?: AirPlayPersistence.loadWirelessEnabled(this)
                if (effect.stopBeforeConnect) {
                    // Release the old backend before checking the new hotspot: its P2P group
                    // can prevent the user from enabling the system hotspot.
                    CarPlayBackgroundSession.stop { runOnUiThread { connect(wireless) } }
                } else connect(wireless)
            }
            is SettingsEffect.WebUpdate -> configureWeb(effect.message, effect.operation)
            is SettingsEffect.TeslaEnabled -> setTeslaHttpEnabled(effect.enabled)
        }
    }

    private fun settingsCommand(command: SettingsCommand) {
        when (command) {
            SettingsCommand.SELECT_PHONE -> choosePhone()
            SettingsCommand.HOTSPOT_SETTINGS -> openCarWifiSettings()
            SettingsCommand.REQUEST_ROOT -> configureWeb(getString(UiR.string.ui_root_access_granted)) { RootAccess.request()?.resolve(this).also { TeslaHttpCompatibility.retry() } }
            SettingsCommand.RETRY_ROOT -> TeslaHttpCompatibility.retry()
            SettingsCommand.ENABLE_VPN -> setTeslaHttpEnabled(true)
            SettingsCommand.SELECT_ICON -> oemImagePicker.launch("image/*")
            SettingsCommand.IMPORT_APK -> if (canImportAuthentication()) launchAuthenticationPicker { selectAuthenticationApk.launch(arrayOf("*/*")) }
            SettingsCommand.IMPORT_FILES -> if (canImportAuthentication()) launchAuthenticationPicker { selectAuthenticationKey.launch(arrayOf("*/*")) }
            SettingsCommand.SELECT_PEM -> pemCertificatePicker.launch(arrayOf("*/*"))
            SettingsCommand.SELECT_PKCS12 -> pkcs12Picker.launch(arrayOf("*/*"))
            SettingsCommand.APP_PERMISSIONS -> openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            SettingsCommand.BLUETOOTH_SETTINGS -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            SettingsCommand.WIRELESS_HELP -> wirelessHelp()
            SettingsCommand.EXPORT_REPORT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics() else chooseReportDestination()
            SettingsCommand.DIPLAY -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shihabal3amri/DiPlay")))
            SettingsCommand.SOURCE -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fython/wheelplay")))
            SettingsCommand.DOWNLOAD -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://shihabal3amri.github.io/DiPlay/")))
        }
    }

    private fun service(content: LinearLayout) {
        content.addView(label(getString(R.string.app_name), 21, TEXT, true).apply { setPadding(0, 0, 0, dp(24)) })
        content.addView(label(getString(UiR.string.ui_use_carplay_in_your_head_unit_browser), 28, TEXT, true))
        content.addView(space(24))
        ui.card(content, getString(UiR.string.ui_head_unit_access), R.drawable.ic_server_server) { body ->
            body.addView(label(getString(UiR.string.ui_local_network_address), 14, MUTED))
            webAddress = label(getString(UiR.string.ui_starting_service), 21, ACCENT, true).apply {
                setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(8))
            }
            body.addView(webAddress)
            webInterface = label("", 14, MUTED)
            body.addView(webInterface)
            quickBrowserButton = button(getString(UiR.string.ui_try_in_this_device_s_browser), false, R.drawable.ic_server_launch) {
                openLocalBrowserExperience()
            }.apply { isEnabled = false }
            body.addView(quickBrowserButton, ui.secondaryButtonLayout(12))
            otherAddressesToggle = ui.DisclosureRow(getString(UiR.string.ui_other_addresses)) {
                addressesExpanded = !addressesExpanded
                updateAddressExpansion(animate = true)
            }.apply { visibility = View.GONE }
            body.addView(otherAddressesToggle, ui.secondaryButtonLayout(4))
            otherAddresses = column().apply { visibility = View.GONE }
            body.addView(otherAddresses)
            displayedAddresses = null
        }
        ui.card(content, getString(UiR.string.ui_browser_pairing), R.drawable.ic_server_qr) { body ->
            webPairingControls = column().apply {
                addView(label(getString(UiR.string.ui_scan_the_qr_code_on_the_head_unit_web), 16, MUTED))
                addView(button(getString(UiR.string.ui_scan_qr_code_to_pair), true, R.drawable.ic_server_scan) { scanPairing() }, matchButton(12))
                addView(label(getString(UiR.string.ui_or_enter_the_pairing_code_on_the_web_page), 14, MUTED).apply { setPadding(0, dp(16), 0, 0) })
                webCode = label("— — — — — —", 32, TEXT, true).apply {
                    letterSpacing = .12f; setTextIsSelectable(true); setPadding(0, dp(8), 0, dp(16))
                }
                addView(webCode)
            }
            body.addView(webPairingControls)
            webViewer = label(getString(UiR.string.ui_waiting_for_head_unit_browser), 16, MUTED)
            body.addView(webViewer)
        }
        ui.card(content, getString(UiR.string.ui_iphone_session), R.drawable.ic_server_phone) { body ->
            webStage = label(getString(UiR.string.ui_waiting_to_connect_to_iphone), 16, TEXT)
            body.addView(webStage)
            sessionConnectButton = button(getString(UiR.string.ui_connect), false, R.drawable.ic_server_phone) {
                if (!CarPlayBackgroundSession.hasSession()) connect(true)
            }.apply { visibility = View.GONE }
            body.addView(sessionConnectButton, ui.secondaryButtonLayout(12))
            body.addView(button(getString(UiR.string.ui_manage_iphone_connection), false) { navigate("source-carplay") }, ui.secondaryButtonLayout(16))
        }
        content.addView(label(getString(UiR.string.ui_audio_and_microphone_use_this_device_by_default_enable), 14, MUTED))
        content.addView(button(getString(UiR.string.ui_stop_service), false) {
            startService(Intent(this, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP))
            finishAndRemoveTask()
        }, ui.secondaryButtonLayout(24))
    }

    private fun refreshService() {
        val addresses = if (WebSession.running && WebSession.error == null) WebSession.addresses(this) else emptyList()
        val primary = addresses.firstOrNull()
        val addressText = WebSession.error ?: if (WebSession.running) primary?.url
                ?: getString(UiR.string.ui_no_local_network_address_found_connect_to_wi_fi) else getString(UiR.string.ui_starting_service)
        if (webAddress?.text?.toString() != addressText) webAddress?.text = addressText
        webInterface?.text = primary?.let { it.description(this) + if (it.hostname.isNotEmpty()) getString(UiR.string.address_direct_ip, it.ipUrl) else "" }.orEmpty()
        webInterface?.visibility = if (primary == null) View.GONE else View.VISIBLE
        if (displayedAddresses != addresses) {
            displayedAddresses = addresses
            otherAddresses?.removeAllViews()
            addresses.drop(1).forEachIndexed { index, entry ->
                otherAddresses?.addView(label(entry.description(this), 14, MUTED))
                otherAddresses?.addView(label(entry.url, 16, TEXT).apply {
                    setTextIsSelectable(true); setPadding(0, dp(4), 0, if (index < addresses.size - 2) dp(16) else 0)
                })
            }
            updateAddressExpansion()
        }
        webCode?.text = if (WebSession.running) WebSession.code else "— — — — — —"
        quickBrowserButton?.isEnabled = WebSession.running && BrowserExperienceLink.local(WebSession.code, WebSession.httpPort) != null
        val viewerConnected = WebSession.hasViewer
        webPairingControls?.visibility = if (viewerConnected) View.GONE else View.VISIBLE
        webViewer?.text = if (viewerConnected) getString(UiR.string.ui_head_unit_browser_connected) else getString(UiR.string.ui_waiting_for_head_unit_browser)
        webStage?.text = WebSession.stage(this)
    }

    private fun openLocalBrowserExperience() {
        val url = BrowserExperienceLink.local(WebSession.code, WebSession.httpPort)
        if (!WebSession.running || url == null) {
            toast(getString(UiR.string.ui_wait_for_the_web_service_to_start_before_opening))
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast(getString(UiR.string.ui_no_browser_available_to_open_this_page)) }
    }

    private fun updateAddressExpansion(animate: Boolean = false) {
        val count = ((displayedAddresses?.size ?: 0) - 1).coerceAtLeast(0)
        otherAddressesToggle?.visibility = if (count > 0) View.VISIBLE else View.GONE
        otherAddressesToggle?.setExpanded(addressesExpanded, animate)
        otherAddresses?.visibility = if (count > 0 && addressesExpanded) View.VISIBLE else View.GONE
    }

    private fun sourceIndex(content: LinearLayout) {
        content.addView(label(getString(UiR.string.source_tab), 30, TEXT, true).apply { setPadding(0, 0, 0, dp(24)) })
        ui.sourceCard(content, getString(UiR.string.source_carplay), getString(UiR.string.source_carplay_summary), R.drawable.ic_server_phone) { navigate("source-carplay") }
        ui.sourceCard(content, "Android Auto", getString(UiR.string.source_aa_summary), R.drawable.ic_server_launch) { navigate("source-android-auto") }
        val carLinkPlugins = projectionPlugins(this).entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK)
        val carLinkSummary = if (carLinkPlugins.isEmpty()) getString(UiR.string.source_iccoa_summary)
            else getString(UiR.string.plugin_iccoa_installed, carLinkPlugins.joinToString { it.name })
        ui.sourceCard(content, "ICCOA CarLink", carLinkSummary, R.drawable.ic_server_wifi) { navigate("source-iccoa") }
        ui.sourceCard(content, "VNC", getString(UiR.string.vnc_summary), R.drawable.ic_server_server) { navigate("source-vnc") }
        ui.sourceCard(content, getString(UiR.string.plugin_sources), getString(UiR.string.plugin_summary), R.drawable.ic_server_launch) { navigate("source-plugins") }
        ui.sourceCard(content, getString(UiR.string.source_diagnostics), getString(UiR.string.source_diagnostics_summary), R.drawable.ic_server_server) { navigate("source-diagnostics") }
    }

    private fun pluginSources(content: LinearLayout) {
        sourceHeading(content, getString(UiR.string.plugin_sources))
        val plugins = projectionPlugins(this)
        content.addView(label(getString(UiR.string.plugin_install_hint), 15, MUTED))
        content.addView(button(getString(UiR.string.plugin_refresh), false) {
            plugins.refresh(); refreshPhone()
        }, matchButton(12))
        sourceSessionControls(content, "plugin")
        if (plugins.entries().isEmpty()) content.addView(label(getString(UiR.string.plugin_none), 16, MUTED))
        plugins.entries().forEach { pluginCard(content, it) }
    }

    private fun pluginCard(content: LinearLayout, plugin: InstalledProjectionPlugin) {
        val plugins = projectionPlugins(this)
        ui.card(content, plugin.name, R.drawable.ic_server_launch) { body ->
            body.addView(label(plugin.component.packageName, 13, MUTED))
            val enabled = plugins.enabled(plugin)
            body.addView(label(plugin.problem ?: getString(if (enabled) UiR.string.plugin_enabled else UiR.string.plugin_disabled), 15, MUTED))
            if (plugin.problem == null || enabled) body.addView(button(getString(if (enabled) UiR.string.plugin_disable else UiR.string.plugin_enable), false) {
                if (enabled) { plugins.setEnabled(plugin, false); refreshPhone() }
                else android.app.AlertDialog.Builder(this)
                    .setTitle(getString(UiR.string.plugin_enable))
                    .setMessage(getString(UiR.string.plugin_enable_prompt, plugin.name, plugin.component.packageName))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runCatching { plugins.setEnabled(plugin, true) }.onFailure { toast(getString(UiR.string.plugin_unavailable)) }
                        refreshPhone()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }, matchButton(12))
            if (plugin.settings != null) body.addView(button(getString(UiR.string.plugin_settings), false) {
                runCatching { startActivity(Intent().setComponent(plugin.settings)) }
                    .onFailure { toast(getString(UiR.string.plugin_unavailable)) }
            }, matchButton(12))
            body.addView(button(getString(UiR.string.plugin_connect), true) { startProjectionSource(plugin.id) }.apply {
                isEnabled = enabled && plugin.problem == null
            }, matchButton(12))
        }
    }

    private fun sourceHeading(content: LinearLayout, title: String) {
        content.addView(button(getString(UiR.string.source_back), false, R.drawable.ic_settings_back) { navigate("phone") },
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16) })
        content.addView(label(title, 30, TEXT, true).apply { setPadding(0, 0, 0, dp(24)) })
    }

    private fun carPlaySource(content: LinearLayout) {
        sourceHeading(content, getString(UiR.string.source_carplay))
        content.addView(label(getString(UiR.string.ui_this_device_receives_carplay_the_head_unit_browser_provides), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        status = label(getString(UiR.string.ui_waiting_for_connection), 20, ACCENT, true)
        ui.card(content, getString(UiR.string.ui_connection_status), R.drawable.ic_server_phone) { it.addView(status) }
        ui.card(content, getString(UiR.string.ui_wireless_connection), R.drawable.ic_server_wifi) { body ->
            body.addView(label(getString(UiR.string.ui_pair_the_iphone_with_this_android_device_over_bluetooth), 15, MUTED))
            body.addView(ui.preference("iPhone", selectedPhoneName()) { choosePhone() }, matchButton())
            connectButton = button(getString(UiR.string.ui_connect_iphone), true, R.drawable.ic_server_phone) { connect(true) }
            body.addView(connectButton, matchButton(12))
            disconnectButton = button(getString(UiR.string.ui_disconnect_iphone), false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread {
                    startWebServiceIfReady()
                    WebSession.setStage(UiText(UiR.string.ui_iphone_disconnected))
                    refreshStatus(); refreshService()
                } }
            }.apply { visibility = View.GONE }
            body.addView(disconnectButton, ui.secondaryButtonLayout(12))
            if (carHotspotOff()) {
                body.addView(label(getString(UiR.string.hotspot_disabled_hint, AirPlayPersistence.loadManualHotspotSsid(this)), 15, WARNING).apply { setPadding(0, dp(16), 0, 0) })
                body.addView(button(getString(UiR.string.ui_open_hotspot_settings), false) { openCarWifiSettings() }, ui.secondaryButtonLayout(12))
            }
        }
        ui.card(content, getString(UiR.string.ui_usb_connection), R.drawable.ic_server_usb) { body ->
            body.addView(label(getString(UiR.string.ui_connect_the_iphone_to_this_device_with_a_data), 15, MUTED))
            body.addView(button(getString(UiR.string.ui_connect_over_usb), false) { connect(false) }, ui.secondaryButtonLayout(16))
        }
        setupError?.let { content.addView(label(it, 16, WARNING)) }
    }

    private fun androidAutoSource(content: LinearLayout) {
        sourceHeading(content, "Android Auto")
        sourceSessionControls(content, "android-auto")
        ui.card(content, getString(UiR.string.aa_local_title), R.drawable.ic_server_phone) { body ->
            body.addView(label(getString(UiR.string.aa_local_summary), 15, MUTED))
            body.addView(button(getString(UiR.string.aa_local_connect), true) { connectLocalAndroidAuto() }, matchButton(12))
            body.addView(button(getString(UiR.string.aa_local_help), false) { showLocalAndroidAutoSetup() }, ui.secondaryButtonLayout(12))
        }
        ui.card(content, getString(UiR.string.ui_usb_connection), R.drawable.ic_server_usb) { body ->
            body.addView(label(getString(UiR.string.aa_usb_hint), 15, MUTED))
            body.addView(button(getString(UiR.string.aa_usb_connect), false) { startProjectionSource("android-auto-usb") }, matchButton(12))
        }
        ui.card(content, getString(UiR.string.aa_remote_title), R.drawable.ic_server_wifi) { body ->
            body.addView(label(getString(UiR.string.aa_tcp_hint), 15, MUTED))
            body.addView(button(getString(UiR.string.aa_remote_connect), false) { configureAndroidAutoTcp() }, matchButton(12))
        }
    }

    private fun iccoaSource(content: LinearLayout) {
        sourceHeading(content, "ICCOA CarLink")
        val plugins = projectionPlugins(this).entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK)
        if (plugins.isEmpty()) {
            ui.card(content, getString(UiR.string.plugin_iccoa_missing), R.drawable.ic_server_phone) { body ->
                body.addView(label(getString(UiR.string.plugin_iccoa_hint), 16, MUTED))
                body.addView(button(getString(UiR.string.plugin_refresh), false) {
                    projectionPlugins(this).refresh(); refreshPhone()
                }, matchButton(12))
                body.addView(button(getString(UiR.string.plugin_sources), false) { navigate("source-plugins") }, matchButton(12))
            }
        }
        // Keep stop/status available if the APK disappears while its session is being cleaned up.
        if (plugins.isNotEmpty() || projectionSessions.snapshot()?.let { sessionBelongsToSource(it, "iccoa") } == true) {
            sourceSessionControls(content, "iccoa")
        }
        plugins.forEach { pluginCard(content, it) }
    }

    private fun vncSource(content: LinearLayout) {
        sourceHeading(content, "VNC")
        sourceSessionControls(content, "vnc")
        ui.card(content, getString(UiR.string.vnc_desktop), R.drawable.ic_server_server) { body ->
            body.addView(label(getString(UiR.string.vnc_hint), 15, MUTED))
            body.addView(button(getString(UiR.string.vnc_connect), true) { configureVnc() }, matchButton(12))
        }
    }

    private fun configureVnc() {
        registerProjectionSources(this)
        val source = projectionSessions.sources().filterIsInstance<VncSource>().single()
        val endpoint = source.endpoint()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        fun field(title: Int, value: String, type: Int) = EditText(this).apply {
            hint = getString(title); contentDescription = hint
            inputType = type; setSingleLine(true); setText(value); fields.addView(this)
        }
        val host = field(UiR.string.vnc_host, endpoint.host, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val port = field(UiR.string.vnc_port, endpoint.port.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        val password = field(UiR.string.vnc_password, "", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val readOnly = CheckBox(this).apply { text = getString(UiR.string.vnc_read_only); isChecked = endpoint.readOnly; fields.addView(this) }
        vncDialog?.dismiss()
        val dialog = android.app.AlertDialog.Builder(this).setTitle(getString(UiR.string.vnc_connect))
            .setMessage(getString(UiR.string.vnc_security_hint)).setView(fields)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener { password.text.clear(); if (vncDialog === dialog) vncDialog = null }
        vncDialog = dialog
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val target = VncEndpoint(host.text.toString().trim(), port.text.toString().toIntOrNull() ?: 0, readOnly.isChecked)
            if (!target.valid()) { host.error = getString(UiR.string.vnc_invalid_address); return@setOnClickListener }
            val secret = CharArray(password.length()) { password.text[it] }
            try {
                if (secret.size > 8 || secret.any { it.code !in 1..255 }) {
                    password.error = getString(UiR.string.vnc_password_hint); return@setOnClickListener
                }
                source.configure(target, secret)
                dialog.dismiss()
                startProjectionSource("vnc-tcp")
            } finally { secret.fill('\u0000') }
        }
    }

    private fun diagnosticSources(content: LinearLayout) {
        sourceHeading(content, getString(UiR.string.source_diagnostics))
        sourceSessionControls(content, "demo")
        ui.card(content, getString(UiR.string.source_diagnostics), R.drawable.ic_server_server) { body ->
            body.addView(label(getString(UiR.string.projection_demo_hint), 15, MUTED))
            body.addView(button(getString(UiR.string.projection_choose_demo), false) { chooseDiagnosticSource() }, matchButton(12))
        }
    }

    private fun belongsToSource(id: String, group: String) = id.startsWith("$group-")

    private fun sessionBelongsToSource(session: ProjectionSession, group: String): Boolean =
        if (group == "iccoa") (session as? RemotePluginSession)?.plugin?.sourceId == ProjectionPlugin.SOURCE_ICCOA_CARLINK
        else belongsToSource(session.descriptor.id, group)

    private fun sourceSessionControls(content: LinearLayout, group: String) {
        ui.card(content, getString(UiR.string.ui_connection_status), R.drawable.ic_server_phone) { body ->
            sourceStatuses[group] = label(getString(UiR.string.projection_waiting), 16, TEXT).also { body.addView(it) }
            sourceStopButtons[group] = button(getString(UiR.string.projection_stop), false) {
                val session = projectionSessions.snapshot()?.takeIf { sessionBelongsToSource(it, group) }
                if (session != null) projectionSessions.stop(session).whenComplete { _, failure -> runOnUiThread {
                    if (failure != null) toast(getString(UiR.string.projection_failed, failure.cause?.message ?: failure.message.orEmpty()))
                    refreshStatus(); refreshService()
                } }
            }.also { body.addView(it, ui.secondaryButtonLayout(12)) }
        }
    }

    private fun chooseDiagnosticSource() {
        registerProjectionSources(this)
        val sources = projectionSessions.sources().filter { belongsToSource(it.descriptor.id, "demo") }
        android.app.AlertDialog.Builder(this).setTitle(getString(UiR.string.projection_choose_demo))
            .setItems(sources.map { it.descriptor.displayName }.toTypedArray()) { _, index ->
                startProjectionSource(sources[index].descriptor.id)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun configureAndroidAutoTcp() {
        val host = android.widget.EditText(this).apply {
            setSingleLine(true)
            hint = "192.168.1.2"
            setText(getSharedPreferences("projection", 0).getString("aa_host", ""))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(UiR.string.aa_tcp_title))
            .setMessage(getString(UiR.string.aa_tcp_hint))
            .setView(host)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val address = host.text.toString().trim()
                if (!com.shilapi.xcertplay.projection.androidauto.AapTcpConnection.validAddress(address)) {
                    toast(getString(UiR.string.aa_invalid_address))
                } else {
                    getSharedPreferences("projection", 0).edit().putString("aa_host", address).putInt("aa_port", 5277).apply()
                    startProjectionSource("android-auto-tcp")
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun connectLocalAndroidAuto() {
        projectionSessions.snapshot()?.takeIf { it.descriptor.id == LocalAndroidAuto.SOURCE_ID }?.let {
            toast(it.statusText ?: getString(UiR.string.projection_active, it.descriptor.displayName))
            return
        }
        if (!LocalAndroidAuto.isInstalledAndEnabled(this) ||
            !getSharedPreferences("projection", 0).getBoolean(LocalAndroidAuto.SETUP_SEEN, false)) {
            showLocalAndroidAutoSetup()
            return
        }
        registerProjectionSources(this)
        startProjectionSource(LocalAndroidAuto.SOURCE_ID)
    }

    private fun showLocalAndroidAutoSetup() {
        localAndroidAutoDialog?.dismiss()
        val available = LocalAndroidAuto.isInstalledAndEnabled(this)
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle(getString(UiR.string.aa_local_title))
            .setMessage(getString(if (available) UiR.string.aa_local_setup else UiR.string.aa_local_unavailable))
            .setNeutralButton(UiR.string.aa_local_settings) { _, _ -> openLocalAndroidAutoSettings() }
            .setNegativeButton(android.R.string.cancel, null)
        if (available) builder.setPositiveButton(UiR.string.aa_local_connect) { _, _ ->
            getSharedPreferences("projection", 0).edit().putBoolean(LocalAndroidAuto.SETUP_SEEN, true).apply()
            connectLocalAndroidAuto()
        }
        localAndroidAutoDialog = builder.show()
    }

    private fun openLocalAndroidAutoSettings() {
        for (intent in LocalAndroidAuto.settingsIntents()) {
            try {
                localAndroidAutoSettings.launch(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // AA versions expose different settings entry points.
            } catch (_: SecurityException) {
                // Fall back when an AA activity is not exported.
            }
        }
        toast(getString(UiR.string.aa_local_settings_unavailable))
    }

    private fun startProjectionSource(id: String) {
        registerProjectionSources(this)
        startWebServiceIfReady()
        projectionSessions.switchTo(id).whenComplete { _, failure -> runOnUiThread {
            if (failure != null) toast(getString(UiR.string.projection_failed, failure.cause?.message ?: failure.message.orEmpty()))
            refreshStatus(); refreshService()
        } }
    }

    private fun refreshAuthentication() {
        setupError = if (DiPlayBootstrap.importing) getString(UiR.string.ui_importing_authentication_resources_please_wait)
        else runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            getString(UiR.string.ui_authentication_resources_are_not_ready_import_files_or_a_2)
        }
    }

    private fun startWebServiceIfReady() {
        startForegroundService(Intent(this, DiPlaySessionService::class.java))
    }

    private fun canImportAuthentication(): Boolean {
        if (DiPlayBootstrap.importing) { toast(getString(UiR.string.ui_importing_authentication_resources_please_wait)); return false }
        if (CarPlayBackgroundSession.hasSession()) { toast(getString(UiR.string.ui_disconnect_the_iphone_before_importing_authentication_resources)); return false }
        return true
    }

    private fun launchAuthenticationPicker(launch: () -> Unit) {
        runCatching(launch).onFailure {
            selectedPrivateKey = null
            toast(getString(UiR.string.ui_could_not_open_the_file_picker_enable_the_system))
        }
    }

    private fun handleAuthenticationIntent(incoming: Intent): Boolean {
        if (incoming.action != Intent.ACTION_SEND && incoming.action != Intent.ACTION_VIEW) return false
        settingsModel.open(SettingsCategory.AUTHENTICATION)
        shell.select("settings")
        page = "settings"
        val uri = AuthenticationImportIntents.apk(incoming)
        if (uri == null) { toast(getString(UiR.string.ui_could_not_read_apk_import_it_using_the_file)); return true }
        if (!canImportAuthentication()) return true
        settingsDialogs.confirm(getString(UiR.string.ui_import_authentication_resources_from_this_apk), getString(UiR.string.ui_wheelplay_reads_only_the_two_authentication_resources_from_the)) {
            authenticationImport.import(apk = uri)
        }
        return true
    }

    private fun setTeslaHttpEnabled(enabled: Boolean) {
        if (enabled && TeslaHttpCompatibility.config(this).mode == TeslaHttpMode.VPN) {
            if (awaitingTeslaVpnConsent) return
            try {
                val consent = CarPlayVpnService.prepare(this)
                if (consent != null) {
                    awaitingTeslaVpnConsent = true
                    teslaVpnConsent.launch(consent)
                    render()
                    return
                }
            } catch (error: Exception) {
                awaitingTeslaVpnConsent = false
                toast(getString(UiR.string.error_vpn_permission_request, error.userMessage(this)))
                render()
                return
            }
        }
        applyTeslaHttpEnabled(enabled)
    }

    private fun applyTeslaHttpEnabled(enabled: Boolean) {
        TeslaHttpCompatibility.save(this, TeslaHttpCompatibility.config(this).copy(enabled = enabled))
        if (!WebSession.running) startWebServiceIfReady()
        handler.post { render(); refreshService() }
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_hotspot_is_off))
            .setMessage(getString(UiR.string.hotspot_enable_message, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(UiR.string.ui_open_system_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(UiR.string.ui_connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(UiR.string.ui_cancel), null).show()
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

    private fun selectedPhoneName() = if (DiPlayPreferences.phoneAddress(this) == null) getString(UiR.string.ui_not_selected) else DiPlayPreferences.phoneName(this)

    private fun readCertificateFile(uri: Uri): ByteArray = contentResolver.openInputStream(uri)?.use(LanTls::readImport)
        ?: error(getString(UiR.string.ui_could_not_open_the_selected_file))

    private fun configureWeb(success: String, action: () -> String?) {
        if (webBusy) { toast(getString(UiR.string.ui_updating_settings_please_wait)); return }
        webBusy = true
        render()
        Thread({
            val failure = runCatching(action).getOrElse { getString(UiR.string.error_web_settings_update, it.userMessage(this)) }
            runOnUiThread {
                webBusy = false
                if (!isFinishing && !isDestroyed) { toast(failure ?: success); render(); refreshService() }
            }
        }, "web-configure").start()
    }

    private fun choosePkcs12Identity(uri: Uri, password: CharArray) {
        if (webBusy) { password.fill('\u0000'); toast(getString(UiR.string.ui_updating_https_please_wait)); return }
        webBusy = true
        Thread({
            var bytes: ByteArray? = null
            val result = runCatching {
                readCertificateFile(uri).also { bytes = it }.let { LanTls.pkcs12Aliases(it, password) }
            }
            runOnUiThread {
                webBusy = false
                fun clear() { bytes?.fill(0); password.fill('\u0000') }
                if (isFinishing || isDestroyed) { clear(); return@runOnUiThread }
                result.onFailure { clear(); toast(getString(UiR.string.ui_could_not_read_pkcs_12_check_the_file_and)) }
                    .onSuccess { aliases ->
                        val choose = { alias: String ->
                            textInput(getString(UiR.string.ui_private_key_password_leave_blank_to_use_file_password), "", true, onCancel = ::clear) { value ->
                                val keyPassword = if (value.isEmpty()) password.copyOf() else value.toCharArray()
                                configureWeb(getString(UiR.string.ui_pkcs_12_certificate_imported_reconnect_your_browser)) {
                                    try { LanTls.preparePkcs12(bytes!!, password, alias, keyPassword).use { WebSession.installCertificate(this, it) } }
                                    finally { clear(); keyPassword.fill('\u0000') }
                                }
                            }
                        }
                        if (aliases.size == 1) choose(aliases.single())
                        else settingsDialogs.select(getString(UiR.string.ui_choose_a_pkcs_12_private_key_entry), aliases, ::clear) { index -> choose(aliases[index]) }
                    }
            }
        }, "https-pkcs12-read").start()
    }

    private fun textInput(title: String, current: String, secret: Boolean, preserveWhitespace: Boolean = false,
        onCancel: () -> Unit = {}, save: (String) -> Unit) {
        settingsDialogs.input(title, current, secret, preserveWhitespace, onCancel, save)
    }

    private fun connect(wireless: Boolean) {
        refreshAuthentication()
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
        if (projectionSessions.hasSession()) projectionSessions.stop().whenComplete { _, failure -> runOnUiThread {
            if (failure == null) open()
            else toast(getString(UiR.string.projection_failed, failure.cause?.message ?: failure.message.orEmpty()))
        } } else open()
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
            MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_turn_on_bluetooth))
                .setMessage(getString(UiR.string.ui_turn_on_bluetooth_on_this_android_device_and_pair))
                .setPositiveButton(getString(UiR.string.ui_open_bluetooth_settings)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(UiR.string.ui_later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_pair_your_iphone_first))
                .setMessage(getString(UiR.string.ui_pair_this_android_device_in_settings_bluetooth_on_your))
                .setPositiveButton(getString(UiR.string.ui_open_bluetooth_settings)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(UiR.string.ui_ok), null).show(); return
        }
        MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_choose_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(UiR.string.ui_paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(UiR.string.ui_pair_another_device)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(UiR.string.ui_cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_wireless_connection_help))
            .setMessage(getString(UiR.string.ui_pair_the_iphone_with_this_device_over_bluetooth_keep))
            .setPositiveButton(getString(UiR.string.ui_ok), null)
            .setNeutralButton(getString(UiR.string.ui_reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (intent.getStringExtra("page") != "wireless-recovery") return
        intent.removeExtra("page")
        navigate("source-carplay")
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_reset_carplay_wi_fi_2))
            .setMessage(getString(UiR.string.ui_this_ends_the_current_wi_fi_direct_connection_including))
            .setPositiveButton(getString(UiR.string.ui_reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(UiR.string.ui_cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(UiR.string.ui_this_device_does_not_support_wi_fi_direct)); return }
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
                                        channel.close(); toast(getString(UiR.string.ui_wi_fi_direct_is_still_in_use_close_other))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(UiR.string.ui_could_not_reset_wi_fi_direct_close_other_casting)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(UiR.string.ui_wireless_connection_permissions), getString(UiR.string.ui_allow_nearby_devices_permission_first_older_android_versions_also))
        }
    }

    private fun refreshStatus() {
        val projection = projectionSessions.snapshot()
        sourceStatuses.forEach { (group, label) ->
            val session = projection?.takeIf { sessionBelongsToSource(it, group) }
            label.text = if (session == null) getString(UiR.string.projection_waiting)
                else session.statusText ?: getString(UiR.string.projection_active, session.descriptor.displayName)
            sourceStopButtons[group]?.visibility = if (session != null) View.VISIBLE else View.GONE
        }
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            projectionSessions.snapshot()?.descriptor?.id?.let { it != "carplay" } == true ->
                projectionSessions.snapshot()?.descriptor?.displayName
            setupError != null -> getString(UiR.string.ui_authentication_setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(UiR.string.ui_carplay_connected)
            running -> getString(UiR.string.ui_connecting_to_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> getString(UiR.string.status_selected_phone, DiPlayPreferences.phoneName(this))
            else -> getString(UiR.string.ui_no_iphone_selected)
        }
        if (lastRunning != running) {
            connectButton?.visibility = if (running) View.GONE else View.VISIBLE
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null && !DiPlayBootstrap.importing
        sessionConnectButton?.visibility = if (!running && !CarPlayBackgroundSession.active &&
            DiPlayPreferences.phoneAddress(this) != null) View.VISIBLE else View.GONE
        sessionConnectButton?.isEnabled = setupError == null && !DiPlayBootstrap.importing
    }
    private fun reportFileName() = "WheelPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(UiR.string.ui_could_not_open_the_save_location_try_saving_to)
                else getString(UiR.string.ui_no_file_picker_is_available_on_this_device))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        refreshSettingsRuntime()
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
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).name}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Tesla compatibility: enabled=${TeslaHttpCompatibility.config(appContext).enabled}; mode=${TeslaHttpCompatibility.config(appContext).mode}; addressConfigured=${TeslaHttpCompatibility.status.address != null}; downstreams=${TeslaHttpCompatibility.status.downstreams.joinToString()}")
                    TeslaHttpCompatibility.status.error?.let { appendLine("Tesla compatibility status: $it") }
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
                refreshSettingsRuntime()
                if (result.isSuccess) {
                    MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_diagnostic_report_saved))
                        .setMessage(if (uri == null) "Downloads/WheelPlay/$fileName" else getString(UiR.string.ui_report_saved_to_the_selected_location))
                        .setPositiveButton(getString(UiR.string.ui_done), null).show()
                } else {
                    MaterialAlertDialogBuilder(this).setTitle(getString(UiR.string.ui_could_not_save_report))
                        .setMessage(getString(UiR.string.ui_check_storage_space_or_choose_another_save_location))
                        .setPositiveButton(getString(UiR.string.ui_choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(UiR.string.ui_close), null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(body).setPositiveButton(getString(UiR.string.ui_app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(UiR.string.ui_later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(UiR.string.ui_open_this_option_in_the_device_s_system_settings)) } }
    private fun toast(message: String) { com.google.android.material.snackbar.Snackbar.make(shell.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = ui.text(value, size, color, bold)
    private fun button(title: String, primary: Boolean, @androidx.annotation.DrawableRes iconRes: Int = 0, click: () -> Unit) = ui.button(title, primary, iconRes, click)
    private fun matchButton(top: Int = 0) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val ACCENT = ServerUi.ACCENT
        private val TEXT = ServerUi.TEXT
        private val MUTED = ServerUi.MUTED
        private val WARNING = ServerUi.WARNING
    }
}
