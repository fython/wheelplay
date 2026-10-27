// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package moe.feng.wheelplay

import com.shilapi.xcertplay.projection.message
import com.shilapi.xcertplay.projection.projectionFailure
import com.shilapi.xcertplay.projection.ProjectionSourceIds
import com.shilapi.xcertplay.projection.projectionSessions
import com.shilapi.xcertplay.projection.plugin.projectionPlugins
import com.shilapi.xcertplay.projection.plugin.InstalledProjectionPlugin
import com.shilapi.xcertplay.projection.plugin.RemotePluginSession
import com.shilapi.xcertplay.projection.ProjectionSession
import moe.feng.wheelplay.plugin.ProjectionPlugin
import com.shilapi.xcertplay.projection.registerProjectionSources
import com.shilapi.xcertplay.projection.androidauto.LocalAndroidAuto
import moe.feng.wheelplay.projection.desktop.*
import androidx.core.view.isVisible
import com.shilapi.xcertplay.projection.SourceAvailability
import rikka.shizuku.Shizuku
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
import moe.feng.wheelplay.settings.*
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
class WheelPlayActivity : AppCompatActivity() {
    private class CertificateDocument : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
    }
    private val ui by lazy { ServerUi(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var page = "service"
    private val sourceDetailPages = SourcePage.entries.map { it.id }.toSet()
    private val pluginFailureLabels = mutableMapOf<String, MutableList<TextView>>()
    private val sourceRows = mutableMapOf<SourcePage, ServerUi.SourceRow>()
    private val pluginConnectButtons = mutableMapOf<String, MutableList<Button>>()
    private var sourceOverview: TextView? = null
    private enum class AaConnection { LOCAL, USB, NETWORK }
    private var aaConnection = AaConnection.LOCAL
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
    private var desktopDialog: android.app.AlertDialog? = null
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
    private var homeSourceButton: Button? = null
    private var homeStopButton: Button? = null
    private var homeAudioHint: TextView? = null
    private enum class SourcePage(val id: String) {
        CARPLAY("source-carplay"),
        ANDROID_AUTO("source-android-auto"),
        ICCOA("source-iccoa"),
        VNC("source-vnc"),
        DESKTOP("source-desktop"),
        DIAGNOSTICS("source-diagnostics"),
        PLUGINS("source-plugins"),
    }
    private data class HomeSource(val id: String, val name: String, val page: SourcePage)
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: com.google.android.material.button.MaterialButton? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var pendingAndroidAutoSourceId: String? = null
    private val androidAutoMicrophonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val sourceId = pendingAndroidAutoSourceId ?: return@registerForActivityResult
            pendingAndroidAutoSourceId = null
            if (!granted) toast(getString(R.string.aa_microphone_permission_denied))
            startProjectionSourceNow(sourceId)
        }
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
        Shizuku.addRequestPermissionResultListener(desktopPermissionResult)
        if (TeslaHttpCompatibility.config(this).let { it.enabled && it.mode == TeslaHttpMode.ROOT }) RootAccess.checkOnStartup()
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        ServerWindow.showSystemBars(window)
        refreshAuthentication()
        selectedPrivateKey = savedInstanceState?.getString("selectedPrivateKey")?.let(Uri::parse)
        pendingAndroidAutoSourceId = savedInstanceState?.getString("pendingAndroidAutoSourceId")
        aaConnection = AaConnection.entries.firstOrNull { it.name == savedInstanceState?.getString("aaConnection") } ?: AaConnection.LOCAL
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
        outState.putString("pendingAndroidAutoSourceId", pendingAndroidAutoSourceId)
        outState.putString("aaConnection", aaConnection.name)
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
            if (setupError == null && !WheelPlayBootstrap.importing && selectedPrivateKey == null &&
                intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW &&
                !projectionSessions.hasSession() &&
                selectedHomeSource().id == ProjectionSourceIds.CARPLAY && WheelPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { WebSession.setPhoneConnectHandler(null); handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(desktopPermissionResult); desktopDialog?.dismiss(); vncDialog?.dismiss(); localAndroidAutoDialog?.dismiss(); settingsDialogs.cancel(); pendingPemCertificate?.fill(0); pendingPemCertificate = null; super.onDestroy() }

    private fun normalizedPage(value: String) = when (value) {
        "home" -> "phone"
        "wireless-recovery" -> SourcePage.CARPLAY.id
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
        sourceStatuses.clear(); sourceStopButtons.clear(); pluginFailureLabels.clear()
        sourceRows.clear(); pluginConnectButtons.clear(); sourceOverview = null
        shell.put("phone", ui.content(::sourceIndex))
        shell.put(SourcePage.CARPLAY.id, ui.content(::carPlaySource))
        shell.put(SourcePage.ANDROID_AUTO.id, ui.content(::androidAutoSource))
        shell.put(SourcePage.ICCOA.id, ui.content(::iccoaSource))
        shell.put(SourcePage.VNC.id, ui.content(::vncSource))
        shell.put(SourcePage.DESKTOP.id, ui.content(::desktopSource))
        shell.put(SourcePage.DIAGNOSTICS.id, ui.content(::diagnosticSources))
        shell.put(SourcePage.PLUGINS.id, ui.content(::pluginSources))
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
            authenticationError = setupError, importing = WheelPlayBootstrap.importing, webBusy = webBusy,
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
            SettingsCommand.UPSTREAM_PROJECT -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shihabal3amri/DiPlay")))
            SettingsCommand.SOURCE -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fython/wheelplay")))
            SettingsCommand.DOWNLOAD -> openSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://shihabal3amri.github.io/DiPlay/")))
        }
    }

    private fun service(content: LinearLayout) {
        content.addView(label(getString(R.string.app_name), 21, TEXT, true).apply { setPadding(0, 0, 0, dp(24)) })
        content.addView(label(getString(UiR.string.home_projection_headline), 28, TEXT, true))
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
        ui.card(content, getString(UiR.string.home_source_title), R.drawable.ic_server_phone) { body ->
            homeSourceButton = button("", false) { chooseHomeSource() }
            body.addView(homeSourceButton, ui.secondaryButtonLayout())
            webStage = label("", 16, TEXT).apply { setPadding(0, dp(12), 0, 0) }
            body.addView(webStage)
            sessionConnectButton = button(getString(UiR.string.ui_connect), false, R.drawable.ic_server_phone) {
                val source = selectedHomeSource()
                when (source.id) {
                    ProjectionSourceIds.CARPLAY -> connect(AirPlayPersistence.loadWirelessEnabled(this))
                    ProjectionSourceIds.ANDROID_AUTO, ProjectionSourceIds.ANDROID_DESKTOP -> navigate(source.page.id)
                    ProjectionSourceIds.VNC -> configureVnc()
                    else -> startProjectionSource(source.id)
                }
            }
            body.addView(sessionConnectButton, ui.secondaryButtonLayout(12))
            homeStopButton = button("", false) {
                homeStopButton?.isEnabled = false
                val active = projectionSessions.snapshot()
                if (projectionSessions.hasSession()) projectionSessions.stop(active).whenComplete { _, failure -> runOnUiThread {
                    if (failure != null) toast(getString(UiR.string.projection_failed, projectionFailure(failure).message(this@WheelPlayActivity)))
                    refreshStatus(); refreshService()
                } } else CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus(); refreshService() } }
            }
            body.addView(homeStopButton, ui.secondaryButtonLayout(12))
            body.addView(button(getString(UiR.string.home_source_settings), false) {
                navigate(selectedHomeSource().page.id)
            }, ui.secondaryButtonLayout(16))
        }
        homeAudioHint = label(getString(UiR.string.ui_audio_and_microphone_use_this_device_by_default_enable), 14, MUTED)
        content.addView(homeAudioHint)
        content.addView(button(getString(UiR.string.ui_stop_service), false) {
            startService(Intent(this, WheelPlaySessionService::class.java).setAction(WheelPlaySessionService.ACTION_STOP))
            finishAndRemoveTask()
        }, ui.secondaryButtonLayout(24))
    }

    private fun homeSources(): List<HomeSource> = listOf(
        HomeSource(ProjectionSourceIds.CARPLAY, getString(UiR.string.source_carplay), SourcePage.CARPLAY),
        HomeSource(ProjectionSourceIds.ANDROID_AUTO, "Android Auto", SourcePage.ANDROID_AUTO),
        HomeSource(ProjectionSourceIds.VNC, "VNC", SourcePage.VNC),
        HomeSource(ProjectionSourceIds.ANDROID_DESKTOP, getString(UiR.string.desktop_title), SourcePage.DESKTOP),
    ) + projectionPlugins(this).let { registry ->
        registry.entries().filter { registry.available(it) }.map { plugin ->
            HomeSource(plugin.id, plugin.name,
                if (plugin.sourceId == ProjectionPlugin.SOURCE_ICCOA_CARLINK) SourcePage.ICCOA else SourcePage.PLUGINS)
        }
    }

    private fun selectedHomeSource(): HomeSource {
        val sources = homeSources()
        val saved = WheelPlayPreferences.homeSource(this)
        return sources.find { it.id == saved } ?: sources.first().also {
            WheelPlayPreferences.saveHomeSource(this, it.id)
        }
    }

    private fun chooseHomeSource() {
        projectionPlugins(this).refresh()
        val selected = selectedHomeSource()
        val sources = homeSources()
        android.app.AlertDialog.Builder(this).setTitle(getString(UiR.string.home_choose_source))
            .setSingleChoiceItems(sources.map { it.name }.toTypedArray(), sources.indexOf(selected)) { dialog, index ->
                // Selecting a target never starts or interrupts a projection session.
                WheelPlayPreferences.saveHomeSource(this, sources[index].id)
                dialog.dismiss(); refreshService()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun refreshHomeSource() {
        val source = selectedHomeSource()
        val active = projectionSessions.snapshot()
        val carPlayRunning = CarPlayBackgroundSession.hasSession()
        val runtimeState = projectionSessions.stateSnapshot()
        val activeName = active?.descriptor?.displayName ?: if (carPlayRunning) getString(UiR.string.source_carplay)
            else runtimeState.source?.displayName?.takeIf { projectionSessions.hasSession() }
        val selectedActive = (source.id == ProjectionSourceIds.CARPLAY && carPlayRunning) || active?.let {
            it.descriptor.id == source.id || (source.id in setOf(ProjectionSourceIds.ANDROID_AUTO, ProjectionSourceIds.VNC) && belongsToSource(it.descriptor.id, source.id))
        } == true
        homeSourceButton?.text = getString(UiR.string.home_switch_source, source.name)
        webStage?.text = when {
            active != null -> getString(UiR.string.home_active_source, active.descriptor.displayName,
                runtimeState.failure?.message(this) ?: active.statusText ?: getString(UiR.string.projection_active, active.descriptor.displayName))
            carPlayRunning -> getString(UiR.string.home_active_source, getString(UiR.string.source_carplay), WebSession.stage(this))
            source.id == ProjectionSourceIds.CARPLAY -> setupError ?: when {
                WheelPlayPreferences.phoneAddress(this) != null -> getString(UiR.string.status_selected_phone, WheelPlayPreferences.phoneName(this))
                else -> getString(UiR.string.ui_waiting_to_connect_to_iphone)
            }
            projectionPlugins(this).lastFailure(source.id) != null ->
                getString(UiR.string.projection_failed, projectionPlugins(this).lastFailure(source.id))
            runtimeState.source?.id == source.id -> runtimeState.message(this)
            else -> getString(UiR.string.projection_waiting)
        }
        sessionConnectButton?.apply {
            text = getString(if (source.id in setOf(ProjectionSourceIds.ANDROID_AUTO, ProjectionSourceIds.VNC, ProjectionSourceIds.ANDROID_DESKTOP)) UiR.string.home_configure_connect else UiR.string.ui_connect)
            visibility = if (selectedActive || (source.id == ProjectionSourceIds.CARPLAY && WheelPlayPreferences.phoneAddress(this@WheelPlayActivity) == null)) View.GONE else View.VISIBLE
            isEnabled = source.id != ProjectionSourceIds.CARPLAY || (setupError == null && !WheelPlayBootstrap.importing)
        }
        homeStopButton?.apply {
            visibility = if (activeName != null) View.VISIBLE else View.GONE
            text = getString(UiR.string.home_stop_source, activeName.orEmpty())
            isEnabled = activeName != null
        }
        homeAudioHint?.visibility = if (source.id == ProjectionSourceIds.CARPLAY) View.VISIBLE else View.GONE
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
        refreshHomeSource()
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
        content.addView(label(getString(UiR.string.source_tab), 30, TEXT, true))
        sourceOverview = label("", 15, MUTED).apply {
            setPadding(0, dp(8), 0, dp(16))
        }.also { content.addView(it) }
        fun row(page: SourcePage, title: String, summary: String, icon: Int) =
            ui.SourceRow(title, summary, icon) { navigate(page.id) }.also { sourceRows[page] = it }
        ui.sourceRows(content, listOf(
            row(SourcePage.CARPLAY, getString(UiR.string.source_carplay), getString(UiR.string.source_carplay_summary), R.drawable.ic_server_phone),
            row(SourcePage.ANDROID_AUTO, "Android Auto", getString(UiR.string.source_aa_summary), R.drawable.ic_server_phone),
            row(SourcePage.ICCOA, "ICCOA CarLink", getString(UiR.string.source_carlink_brief), R.drawable.ic_server_wifi),
            row(SourcePage.VNC, "VNC", getString(UiR.string.vnc_summary), R.drawable.ic_server_server),
            row(SourcePage.DESKTOP, getString(UiR.string.desktop_title), getString(UiR.string.source_desktop_brief), R.drawable.ic_server_phone),
        ))
        content.addView(label(getString(UiR.string.source_tools), 18, TEXT, true).apply {
            setPadding(0, dp(16), 0, dp(12))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        ui.sourceRows(content, listOf(
            row(SourcePage.PLUGINS, getString(UiR.string.plugin_sources), getString(UiR.string.plugin_summary), R.drawable.ic_server_settings),
            row(SourcePage.DIAGNOSTICS, getString(UiR.string.source_diagnostics), getString(UiR.string.source_diagnostics_summary), R.drawable.ic_server_server),
        ))
    }

    private fun sourcePageFor(id: String): SourcePage? = when {
        id == ProjectionSourceIds.CARPLAY -> SourcePage.CARPLAY
        id == ProjectionSourceIds.ANDROID_DESKTOP -> SourcePage.DESKTOP
        id == ProjectionSourceIds.ANDROID_AUTO || belongsToSource(id, ProjectionSourceIds.ANDROID_AUTO) -> SourcePage.ANDROID_AUTO
        id == ProjectionSourceIds.VNC || belongsToSource(id, ProjectionSourceIds.VNC) -> SourcePage.VNC
        belongsToSource(id, ProjectionSourceIds.DEMO) -> SourcePage.DIAGNOSTICS
        id.startsWith(ProjectionSourceIds.PLUGIN_PREFIX) -> {
            val plugin = (projectionSessions.snapshot() as? RemotePluginSession)?.plugin?.takeIf { it.id == id }
                ?: projectionPlugins(this).entries().find { it.id == id }
            if (plugin?.sourceId == ProjectionPlugin.SOURCE_ICCOA_CARLINK) SourcePage.ICCOA else SourcePage.PLUGINS
        }
        else -> null
    }

    private fun refreshSourceIndex() {
        val state = projectionSessions.stateSnapshot()
        val active = projectionSessions.snapshot()
        val running = projectionSessions.hasSession()
        val selected = selectedHomeSource()
        val carPlayRunning = CarPlayBackgroundSession.hasSession()
        val currentId = state.source?.id?.takeIf { running } ?: if (carPlayRunning) ProjectionSourceIds.CARPLAY else null
        val currentPage = currentId?.let(::sourcePageFor)
        val phase = when {
            running -> state.message(this)
            CarPlayBackgroundSession.active -> getString(UiR.string.projection_state_running)
            else -> getString(UiR.string.projection_state_connecting)
        }
        sourceOverview?.text = buildString {
            append(getString(UiR.string.source_home_selection, selected.name))
            if (currentId != null) {
                append("\n")
                append(getString(UiR.string.source_current_session,
                    state.source?.displayName?.takeIf { running } ?: getString(UiR.string.source_carplay), phase))
            }
        }
        val registry = projectionPlugins(this)
        sourceRows.forEach { (page, row) ->
            val flags = mutableListOf<String>()
            if (selected.page == page) flags += getString(UiR.string.source_home_default)
            if (page == currentPage) flags += if (state.failure != null) getString(UiR.string.source_needs_attention) else phase
            else if (state.failure != null && state.source?.id?.let(::sourcePageFor) == page) flags += getString(UiR.string.source_needs_attention)
            else when (page) {
                SourcePage.ICCOA -> {
                    val plugins = registry.entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK)
                    flags += getString(when {
                        plugins.isEmpty() -> UiR.string.source_install_required
                        plugins.none { it.problem == null } -> UiR.string.source_update_required
                        plugins.none { registry.available(it) } -> UiR.string.source_enable_required
                        plugins.any { registry.lastFailure(it.id) != null } -> UiR.string.source_needs_attention
                        else -> UiR.string.source_ready
                    })
                }
                SourcePage.DESKTOP -> if (desktopProvider().probe() !is SourceAvailability.Ready) flags += getString(UiR.string.source_setup_required)
                else -> Unit
            }
            row.setStatus(flags.joinToString(" · "))
        }
        pluginConnectButtons.forEach { (id, buttons) ->
            buttons.forEach { button ->
                // The summary above owns Stop, including acquisition and cleanup before/after a session.
                button.visibility = if (running && (state.source?.id == id || active?.descriptor?.id == id)) View.GONE else View.VISIBLE
            }
        }
    }

    private fun pluginInstallHelp(content: LinearLayout, carLink: Boolean) {
        content.addView(label(getString(if (carLink) UiR.string.plugin_iccoa_missing else UiR.string.source_no_plugins), 21, TEXT, true))
        content.addView(label(getString(UiR.string.source_plugin_empty_hint), 15, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
        ui.details(content, getString(UiR.string.source_install_help)) { body ->
            body.addView(label(getString(if (carLink) UiR.string.plugin_iccoa_hint else UiR.string.source_plugin_install_steps), 15, MUTED))
        }
    }

    private fun pluginSources(content: LinearLayout) {
        sourceHeading(content, getString(UiR.string.plugin_sources))
        val plugins = projectionPlugins(this)
        val installed = plugins.entries()
        if (installed.isEmpty()) pluginInstallHelp(content, false)
        // A disappearing APK may still own resources; keep its stop/error summary accessible.
        if (installed.isNotEmpty() || projectionSessions.stateSnapshot().source?.id?.startsWith(ProjectionSourceIds.PLUGIN_PREFIX) == true) {
            sourceSessionControls(content, ProjectionSourceIds.PLUGIN)
        }
        installed.forEach { pluginCard(content, it) }
        content.addView(ui.textButton(getString(UiR.string.plugin_refresh)) {
            plugins.refresh(); refreshPhone()
        }, ui.secondaryButtonLayout(8))
        ui.details(content, getString(UiR.string.source_plugin_capabilities)) { body ->
            body.addView(label(getString(UiR.string.plugin_install_hint), 15, MUTED))
        }
    }

    private fun pluginCard(content: LinearLayout, plugin: InstalledProjectionPlugin) {
        val plugins = projectionPlugins(this)
        ui.card(content, plugin.name, R.drawable.ic_server_phone) { body ->
            val failureLabel = label("", 15, WARNING).apply { visibility = View.GONE }
            pluginFailureLabels.getOrPut(plugin.id) { mutableListOf() }.add(failureLabel)
            body.addView(failureLabel)
            val enabled = plugins.enabled(plugin)
            body.addView(label(plugin.problem ?: getString(if (enabled) UiR.string.plugin_enabled else UiR.string.source_plugin_enable_hint), 15, MUTED))
            if (plugin.problem == null) {
                if (enabled) {
                    val connect = button(getString(UiR.string.plugin_connect), true) { startProjectionSource(plugin.id) }
                    pluginConnectButtons.getOrPut(plugin.id) { mutableListOf() }.add(connect)
                    body.addView(connect, matchButton(12))
                } else body.addView(button(getString(UiR.string.plugin_enable), true) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(getString(UiR.string.plugin_enable))
                        .setMessage(getString(UiR.string.plugin_enable_prompt, plugin.name, plugin.component.packageName))
                        .setPositiveButton(UiR.string.plugin_enable) { _, _ ->
                            runCatching { plugins.setEnabled(plugin, true) }.onFailure { toast(getString(UiR.string.plugin_unavailable)) }
                            refreshPhone()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }, matchButton(12))
            }
            if (plugin.settings != null) body.addView(ui.textButton(getString(UiR.string.plugin_settings)) {
                runCatching { startActivity(Intent().setComponent(plugin.settings)) }
                    .onFailure { toast(getString(UiR.string.plugin_unavailable)) }
            }, ui.secondaryButtonLayout(8))
            ui.details(body, getString(UiR.string.source_plugin_manage)) { details ->
                details.addView(label(plugin.component.packageName, 14, MUTED).apply { setTextIsSelectable(true) })
                if (enabled) details.addView(ui.textButton(getString(UiR.string.plugin_disable)) {
                    plugins.setEnabled(plugin, false); refreshPhone()
                }, ui.secondaryButtonLayout())
            }
        }
    }

    private fun sourceHeading(content: LinearLayout, title: String) {
        ui.sourceToolbar(content, title) { navigate("phone") }
    }

    private fun carPlaySource(content: LinearLayout) {
        sourceHeading(content, getString(UiR.string.source_carplay))
        status = label(getString(UiR.string.source_idle), 16, MUTED).apply { setPadding(0, 0, 0, dp(16)) }
        content.addView(status)
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
        ui.details(content, getString(UiR.string.source_connection_help)) { body ->
            body.addView(label(getString(UiR.string.ui_this_device_receives_carplay_the_head_unit_browser_provides), 16, MUTED))
        }
    }

    private fun androidAutoSource(content: LinearLayout) {
        sourceHeading(content, "Android Auto")
        sourceSessionControls(content, ProjectionSourceIds.ANDROID_AUTO)
        content.addView(label(getString(UiR.string.source_connection_method), 18, TEXT, true).apply { setPadding(0, 0, 0, dp(8)) })
        val modes = listOf(UiR.string.source_aa_local, UiR.string.source_aa_usb, UiR.string.source_aa_network)
        val selector = com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
        }
        val ids = modes.map { title ->
            val choice = button(getString(title), false) {}.apply {
                id = View.generateViewId(); minWidth = 0; minimumWidth = 0
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            selector.addView(choice, LinearLayout.LayoutParams(0, -2, 1f))
            choice.id
        }
        selector.check(ids[aaConnection.ordinal])
        content.addView(selector, matchButton())
        val panel = column()
        fun showConnection() {
            panel.removeAllViews()
            val mode = aaConnection
            val hint = when (mode) {
                AaConnection.LOCAL -> UiR.string.source_aa_local_brief
                AaConnection.USB -> UiR.string.aa_usb_hint
                AaConnection.NETWORK -> UiR.string.source_aa_network_brief
            }
            panel.addView(label(getString(hint), 16, MUTED).apply { setPadding(0, dp(16), 0, 0) })
            val action = when (mode) {
                AaConnection.LOCAL -> UiR.string.aa_local_connect
                AaConnection.USB -> UiR.string.aa_usb_connect
                AaConnection.NETWORK -> UiR.string.aa_remote_connect
            }
            panel.addView(button(getString(action), true) {
                when (mode) {
                    AaConnection.LOCAL -> connectLocalAndroidAuto()
                    AaConnection.USB -> startProjectionSource(ProjectionSourceIds.ANDROID_AUTO_USB)
                    AaConnection.NETWORK -> configureAndroidAutoTcp()
                }
            }, matchButton(16))
            if (mode == AaConnection.LOCAL) panel.addView(ui.textButton(getString(UiR.string.aa_local_help)) {
                showLocalAndroidAutoSetup()
            }, ui.secondaryButtonLayout(8))
            ui.details(panel, getString(UiR.string.source_connection_help)) { body ->
                body.addView(label(getString(when (mode) { AaConnection.LOCAL -> UiR.string.aa_local_summary; AaConnection.USB -> UiR.string.source_aa_usb_help; AaConnection.NETWORK -> UiR.string.aa_tcp_hint }), 15, MUTED))
            }
        }
        selector.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                aaConnection = AaConnection.entries[ids.indexOf(checkedId)]
                showConnection()
            }
        }
        showConnection()
        content.addView(panel)
    }

    private fun iccoaSource(content: LinearLayout) {
        sourceHeading(content, "ICCOA CarLink")
        val plugins = projectionPlugins(this).entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK)
        if (plugins.isEmpty()) {
            pluginInstallHelp(content, true)
            content.addView(ui.textButton(getString(UiR.string.plugin_refresh)) {
                projectionPlugins(this).refresh(); refreshPhone()
            }, ui.secondaryButtonLayout(8))
            content.addView(ui.textButton(getString(UiR.string.plugin_sources)) { navigate(SourcePage.PLUGINS.id) }, ui.secondaryButtonLayout())
        }
        if (plugins.isNotEmpty() || projectionSessions.snapshot()?.let { sessionBelongsToSource(it, ProjectionSourceIds.ICCOA) } == true) {
            sourceSessionControls(content, ProjectionSourceIds.ICCOA)
        }
        plugins.forEach { pluginCard(content, it) }
    }

    private var desktopStatus: TextView? = null
    private var desktopStart: Button? = null
    private val desktopPermissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == 40) runOnUiThread { if (!isDestroyed) refreshPhone() }
    }
    private fun desktopProvider(): AndroidDesktopSource {
        registerProjectionSources(this)
        return projectionSessions.sources().filterIsInstance<AndroidDesktopSource>().single()
    }
    private fun desktopSource(content: LinearLayout) {
        val source = desktopProvider()
        sourceHeading(content, getString(UiR.string.desktop_title))
        sourceSessionControls(content, ProjectionSourceIds.ANDROID_DESKTOP)
        ui.card(content, getString(UiR.string.desktop_title), R.drawable.ic_server_phone) { body ->
            body.addView(label(getString(UiR.string.desktop_hint), 15, MUTED))
            val config = source.displayConfig()
            body.addView(button(getString(UiR.string.desktop_display_summary, config.width, config.height, config.dpi), false) {
                configureDesktopDisplay()
            }, matchButton(12))
            desktopStatus = label("", 15, MUTED).also { body.addView(it) }
            body.addView(button(getString(UiR.string.desktop_open_shizuku), false) {
                openSystem(packageManager.getLaunchIntentForPackage(ShizukuDesktopBackend.PACKAGE)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }, matchButton(12))
            body.addView(button(getString(UiR.string.desktop_authorize), false) {
                runCatching {
                    if (Shizuku.pingBinder() && Shizuku.getVersion() >= 13) Shizuku.requestPermission(40)
                    else toast((source.dependencyStatus() as? SourceAvailability.Unavailable)?.reason.orEmpty())
                }.onFailure { toast(getString(UiR.string.desktop_not_authorized)) }
            }, matchButton(12))
            body.addView(button(getString(UiR.string.desktop_choose_app), false) { chooseDesktopApp() }, matchButton(12))
            if (source.selectedApp().isNotEmpty()) body.addView(label(source.selectedApp(), 13, MUTED))
            desktopStart = button(getString(UiR.string.desktop_start), true) {
                startProjectionSource(ProjectionSourceIds.ANDROID_DESKTOP)
            }.also { body.addView(it, matchButton(12)) }
        }
    }
    private fun configureDesktopDisplay() {
        val source = desktopProvider()
        val config = source.displayConfig()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0) }
        fun field(title: Int, value: Int) = EditText(this).apply {
            fields.addView(label(getString(title), 14, MUTED))
            contentDescription = getString(title)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true); setText(value.toString()); fields.addView(this)
        }
        val width = field(UiR.string.desktop_width, config.width)
        val height = field(UiR.string.desktop_height, config.height)
        val dpi = field(UiR.string.desktop_dpi, config.dpi)
        val error = label(getString(UiR.string.desktop_display_invalid, DesktopDisplayConfig.MIN_SIZE,
            DesktopDisplayConfig.MAX_SIZE, DesktopDisplayConfig.MAX_PIXELS, DesktopDisplayConfig.MIN_DPI,
            DesktopDisplayConfig.MAX_DPI), 14, MUTED).apply { isVisible = false; fields.addView(this) }
        desktopDialog?.dismiss()
        val dialog = android.app.AlertDialog.Builder(this).setTitle(UiR.string.desktop_display_settings)
            .setMessage(UiR.string.desktop_display_hint).setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(UiR.string.desktop_display_reset, null).create()
        dialog.setOnDismissListener { if (desktopDialog === dialog) desktopDialog = null }
        desktopDialog = dialog
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val defaults = DesktopDisplayConfig()
            width.setText(defaults.width.toString()); height.setText(defaults.height.toString()); dpi.setText(defaults.dpi.toString())
            error.isVisible = false
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selected = runCatching {
                DesktopDisplayConfig(
                    width = width.text.toString().toInt(),
                    height = height.text.toString().toInt(),
                    dpi = dpi.text.toString().toInt(),
                )
            }.getOrNull()
            if (selected == null) { error.isVisible = true; return@setOnClickListener }
            source.configureDisplay(selected)
            dialog.dismiss()
            refreshPhone()
        }
    }

    private fun chooseDesktopApp() {
        val apps = packageManager.queryIntentActivities(AndroidDesktopSource.launcherIntent(), 0)
            .filter { it.activityInfo.isDesktopLaunchable }
            .distinctBy { it.activityInfo.desktopComponent }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (apps.isEmpty()) { toast(getString(UiR.string.desktop_no_apps)); return }
        android.app.AlertDialog.Builder(this).setTitle(UiR.string.desktop_choose_app)
            .setItems(apps.map { "${it.loadLabel(packageManager)} (${it.activityInfo.packageName})" }.toTypedArray()) { _, index ->
                val info = apps[index].activityInfo
                runCatching { desktopProvider().selectApp(info.desktopComponent.flattenToString()) }
                    .onFailure { toast(getString(UiR.string.desktop_choose_app)) }
                refreshPhone()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun vncSource(content: LinearLayout) {
        sourceHeading(content, "VNC")
        sourceSessionControls(content, ProjectionSourceIds.VNC)
        content.addView(label(getString(UiR.string.source_vnc_brief), 16, MUTED))
        content.addView(button(getString(UiR.string.vnc_connect), true) { configureVnc() }, matchButton(16))
        ui.details(content, getString(UiR.string.source_vnc_help)) { body ->
            body.addView(label(getString(UiR.string.vnc_hint), 15, MUTED))
            body.addView(label(getString(UiR.string.vnc_security_hint), 15, MUTED).apply { setPadding(0, dp(12), 0, 0) })
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
                startProjectionSource(ProjectionSourceIds.VNC_TCP)
            } finally { secret.fill('\u0000') }
        }
    }

    private fun diagnosticSources(content: LinearLayout) {
        sourceHeading(content, getString(UiR.string.source_diagnostics))
        sourceSessionControls(content, ProjectionSourceIds.DEMO)
        ui.card(content, getString(UiR.string.source_diagnostics), R.drawable.ic_server_server) { body ->
            body.addView(label(getString(UiR.string.projection_demo_hint), 15, MUTED))
            body.addView(button(getString(UiR.string.projection_choose_demo), false) { chooseDiagnosticSource() }, matchButton(12))
        }
    }

    private fun belongsToSource(id: String, group: String) = id.startsWith("$group-")

    private fun sessionBelongsToSource(session: ProjectionSession, group: String): Boolean =
        if (group == ProjectionSourceIds.ICCOA) (session as? RemotePluginSession)?.plugin?.sourceId == ProjectionPlugin.SOURCE_ICCOA_CARLINK
        else session.descriptor.id == group || belongsToSource(session.descriptor.id, group)

    private fun sourceSessionControls(content: LinearLayout, group: String) {
        val body = column().apply { setPadding(0, 0, 0, dp(16)) }
        content.addView(body)
        sourceStatuses[group] = label(getString(UiR.string.source_idle), 16, MUTED).also { body.addView(it) }
        sourceStopButtons[group] = button(getString(UiR.string.projection_stop), false) {
            val session = projectionSessions.snapshot()?.takeIf { sessionBelongsToSource(it, group) }
            val state = projectionSessions.stateSnapshot()
            val pendingHere = state.source?.id?.let { it == group || belongsToSource(it, group) } == true && projectionSessions.hasSession()
            if (session != null || pendingHere) projectionSessions.stop(session).whenComplete { _, failure -> runOnUiThread {
                if (failure != null) toast(getString(UiR.string.projection_failed, projectionFailure(failure).message(this@WheelPlayActivity)))
                refreshStatus(); refreshService()
            } }
        }.also { body.addView(it, ui.secondaryButtonLayout(12)) }
    }

    private fun chooseDiagnosticSource() {
        registerProjectionSources(this)
        val sources = projectionSessions.sources().filter { belongsToSource(it.descriptor.id, ProjectionSourceIds.DEMO) }
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
                    startProjectionSource(ProjectionSourceIds.ANDROID_AUTO_TCP)
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
        val homeId = when {
            belongsToSource(id, ProjectionSourceIds.ANDROID_AUTO) -> ProjectionSourceIds.ANDROID_AUTO
            belongsToSource(id, ProjectionSourceIds.VNC) -> ProjectionSourceIds.VNC
            else -> id
        }
        if (homeSources().any { it.id == homeId }) WheelPlayPreferences.saveHomeSource(this, homeId)
        if (isAndroidAutoSource(id) &&
            AirPlayPersistence.loadMicrophoneSource(this) == CarPlayMicrophoneSource.ANDROID &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAndroidAutoSourceId = id
            androidAutoMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startProjectionSourceNow(id)
    }

    private fun isAndroidAutoSource(id: String) = belongsToSource(id, ProjectionSourceIds.ANDROID_AUTO)

    private fun startProjectionSourceNow(id: String) {
        registerProjectionSources(this)
        startWebServiceIfReady()
        projectionSessions.switchTo(id).whenComplete { _, failure -> runOnUiThread {
            if (failure != null) toast(getString(UiR.string.projection_failed, projectionFailure(failure).message(this@WheelPlayActivity)))
            refreshStatus(); refreshService()
        } }
    }

    private fun refreshAuthentication() {
        setupError = if (WheelPlayBootstrap.importing) getString(UiR.string.ui_importing_authentication_resources_please_wait)
        else runCatching { WheelPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            getString(UiR.string.ui_authentication_resources_are_not_ready_import_files_or_a_2)
        }
    }

    private fun startWebServiceIfReady() {
        startForegroundService(Intent(this, WheelPlaySessionService::class.java))
    }

    private fun canImportAuthentication(): Boolean {
        if (WheelPlayBootstrap.importing) { toast(getString(UiR.string.ui_importing_authentication_resources_please_wait)); return false }
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

    // The car hotspot link needs the hotspot on; WheelPlay only checks it (turning it on needs ADB-only permission).
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

    private fun selectedPhoneName() = if (WheelPlayPreferences.phoneAddress(this) == null) getString(UiR.string.ui_not_selected) else WheelPlayPreferences.phoneName(this)

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
        WheelPlayPreferences.saveHomeSource(this, ProjectionSourceIds.CARPLAY)
        refreshHomeSource()
        refreshAuthentication()
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && WheelPlayPreferences.phoneAddress(this) == null) {
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
            else toast(getString(UiR.string.projection_failed, projectionFailure(failure).message(this@WheelPlayActivity)))
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
                WheelPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
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
        navigate(SourcePage.CARPLAY.id)
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
        val desktop = desktopProvider()
        val desktopState = desktop.probe()
        desktopStatus?.text = if (desktop.preparing) getString(UiR.string.desktop_preparing)
            else (desktopState as? SourceAvailability.Unavailable)?.reason
                ?: desktop.lastFailure ?: getString(UiR.string.desktop_ready)
        desktopStart?.isEnabled = !desktop.preparing && desktopState is SourceAvailability.Ready &&
            projectionSessions.snapshot()?.descriptor?.id != ProjectionSourceIds.ANDROID_DESKTOP

        pluginFailureLabels.forEach { (id, labels) ->
            val failure = projectionPlugins(this).lastFailure(id)
            labels.forEach { label ->
                label.text = failure?.let { getString(UiR.string.projection_failed, it) }.orEmpty()
                label.visibility = if (failure == null) View.GONE else View.VISIBLE
            }
        }
        val projection = projectionSessions.snapshot()
        sourceStatuses.forEach { (group, label) ->
            val session = projection?.takeIf { sessionBelongsToSource(it, group) }
            val state = projectionSessions.stateSnapshot()
            val belongs = state.source?.id?.let { it == group || belongsToSource(it, group) } == true
            label.text = when {
                belongs && (state.failure != null || (session == null && projectionSessions.hasSession())) -> state.message(this)
                session != null -> session.statusText ?: state.message(this)
                else -> getString(UiR.string.source_idle)
            }
            sourceStopButtons[group]?.visibility = if (session != null || (belongs && projectionSessions.hasSession())) View.VISIBLE else View.GONE
        }
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            projectionSessions.snapshot()?.descriptor?.id?.let { it != ProjectionSourceIds.CARPLAY } == true ->
                projectionSessions.snapshot()?.descriptor?.displayName
            setupError != null -> getString(UiR.string.ui_authentication_setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(UiR.string.ui_carplay_connected)
            running -> getString(UiR.string.ui_connecting_to_iphone)
            WheelPlayPreferences.phoneAddress(this) != null -> getString(UiR.string.status_selected_phone, WheelPlayPreferences.phoneName(this))
            else -> getString(UiR.string.ui_no_iphone_selected)
        }
        if (lastRunning != running) {
            connectButton?.visibility = if (running) View.GONE else View.VISIBLE
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null && !WheelPlayBootstrap.importing
        refreshHomeSource()
        refreshSourceIndex()
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
        }, "wheelplay-export").start()
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
