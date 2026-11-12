// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package moe.feng.wheelplay

import com.shilapi.xcertplay.projection.message
import com.shilapi.xcertplay.projection.projectionFailure
import com.shilapi.xcertplay.projection.ProjectionSourceIds
import com.shilapi.xcertplay.projection.projectionSessions
import com.shilapi.xcertplay.projection.plugin.projectionPlugins
import com.shilapi.xcertplay.projection.plugin.RemotePluginSession
import com.shilapi.xcertplay.projection.ProjectionSession
import moe.feng.wheelplay.plugin.ProjectionPlugin
import com.shilapi.xcertplay.projection.registerProjectionSources
import com.shilapi.xcertplay.projection.androidauto.LocalAndroidAuto
import moe.feng.wheelplay.projection.desktop.*
import com.shilapi.xcertplay.projection.SourceAvailability
import rikka.shizuku.Shizuku
import com.shilapi.xcertplay.projection.vnc.VncSource
import com.shilapi.xcertplay.projection.vnc.VncEndpoint

import com.shilapi.xcertplay.shared.R as UiR
import com.shilapi.xcertplay.i18n.*

import android.Manifest
import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.RootAccess
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
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import moe.feng.wheelplay.ui.*
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
    private val handler = Handler(Looper.getMainLooper())
    private var page by mutableStateOf("service")
    private val sourceDetailPages = SourcePage.entries.map { it.id }.toSet()
    private var aaConnection by mutableStateOf(AaConnection.LOCAL)
    private val settingsModel by lazy { ViewModelProvider(this)[SettingsViewModel::class.java] }
    private val settingsDialogs = SettingsDialogHost()
    private var webBusy = false
    private var awaitingTeslaVpnConsent = false
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
    private data class HomeSource(val id: String, val name: String, val page: SourcePage)
    private var setupError: String? = null
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
        override fun run() { refreshUiState(); refreshSettingsRuntime(); handler.postDelayed(this, 1000) }
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
            dialogs.message(getString(UiR.string.ui_allow_this_head_unit_to_connect), getString(UiR.string.pairing_permission_message, request.peer), listOf(
                WheelPlayDialogs.Action(getString(UiR.string.ui_allow_connection)) {
                    toast(if (WebSession.approvePairing(payload)) getString(UiR.string.ui_authorized_the_head_unit_web_page_will_connect_automatically) else getString(UiR.string.ui_request_expired_refresh_the_web_page_and_scan_again))
                },
            ))
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
        createComposeContent()
        startWebServiceIfReady()
        handleWirelessRecovery()
        authenticationImport.state.observe(this) { state ->
            refreshAuthentication()
            render()
            state.result?.let {
                startWebServiceIfReady()
                toast(it); authenticationImport.consumeResult()
            }
        }
        if (savedInstanceState == null && !handleUsbConnectionIntent(intent)) handleAuthenticationIntent(intent)
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
        if (!handleUsbConnectionIntent(intent) && !handleAuthenticationIntent(intent)) navigate(intent.getStringExtra("page") ?: "service")
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
        super.onConfigurationChanged(newConfig)
        render()
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
        if (!initialLaunch) { refreshUiState() }
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
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(desktopPermissionResult); dialogs.cancel(); settingsDialogs.cancel(); pendingPemCertificate?.fill(0); pendingPemCertificate = null; super.onDestroy() }

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
        page = target
        if (destination == "wireless-recovery") confirmWirelessReset()
    }

    private var uiState by mutableStateOf(WheelPlayUiState())
    private val snackbar = SnackbarHostState()
    private val dialogs = WheelPlayDialogs()

    private fun createComposeContent() {
        page = normalizedPage(page)
        refreshUiState()
        refreshSettings()
        setContent {
            WheelPlayScreen(page, aaConnection, uiState, settingsModel, settingsActions, settingsDialogs, dialogs, snackbar, ::dispatch)
        }
    }


    private fun dispatch(action: UiAction, argument: String?) {
        when (action) {
            UiAction.NAVIGATE -> {
                // Reselecting a tab preserves its scroll and settings subpage.
                if (page != argument) page = normalizedPage(argument ?: "service")
            }
            UiAction.AA_MODE -> aaConnection = AaConnection.valueOf(requireNotNull(argument))
            UiAction.BROWSER -> openLocalBrowserExperience()
            UiAction.SCAN -> scanPairing()
            UiAction.CHOOSE_HOME -> chooseHomeSource()
            UiAction.HOME_SETTINGS -> navigate(selectedHomeSource().page.id)
            UiAction.HOME_CONNECT -> {
                val source = selectedHomeSource()
                when (source.id) {
                    ProjectionSourceIds.CARPLAY -> connect(AirPlayPersistence.loadWirelessEnabled(this))
                    ProjectionSourceIds.ANDROID_AUTO, ProjectionSourceIds.ANDROID_DESKTOP -> navigate(source.page.id)
                    ProjectionSourceIds.VNC -> configureVnc()
                    else -> startProjectionSource(source.id)
                }
            }
            UiAction.HOME_STOP -> {
                val active = projectionSessions.snapshot()
                if (projectionSessions.hasSession()) projectionSessions.stop(active).whenComplete { _, failure -> runOnUiThread {
                    if (failure != null) toast(getString(UiR.string.projection_failed, projectionFailure(failure).message(this)))
                    refreshUiState()
                } } else CarPlayBackgroundSession.stop { runOnUiThread { refreshUiState() } }
            }
            UiAction.STOP_SERVICE -> {
                startService(Intent(this, WheelPlaySessionService::class.java).setAction(WheelPlaySessionService.ACTION_STOP))
                finishAndRemoveTask()
            }
            UiAction.CHOOSE_PHONE -> choosePhone()
            UiAction.CARPLAY_CONNECT -> connect(true)
            UiAction.CARPLAY_USB -> connect(false)
            UiAction.CARPLAY_STOP -> CarPlayBackgroundSession.stop { runOnUiThread {
                startWebServiceIfReady(); WebSession.setStage(UiText(UiR.string.ui_iphone_disconnected)); refreshUiState()
            } }
            UiAction.HOTSPOT -> openCarWifiSettings()
            UiAction.AA_LOCAL -> connectLocalAndroidAuto()
            UiAction.AA_USB -> startProjectionSource(ProjectionSourceIds.ANDROID_AUTO_USB)
            UiAction.AA_NETWORK -> configureAndroidAutoTcp()
            UiAction.AA_HELP -> showLocalAndroidAutoSetup()
            UiAction.VNC -> configureVnc()
            UiAction.STOP_SOURCE -> stopSource(requireNotNull(argument))
            UiAction.PLUGIN_REFRESH -> { projectionPlugins(this).refresh(); refreshUiState() }
            UiAction.PLUGIN_ENABLE, UiAction.PLUGIN_DISABLE, UiAction.PLUGIN_SETTINGS, UiAction.PLUGIN_CONNECT -> {
                val registry = projectionPlugins(this)
                val plugin = registry.entries().find { it.id == argument }
                if (plugin == null) { toast(getString(UiR.string.plugin_unavailable)); return }
                when (action) {
                    UiAction.PLUGIN_ENABLE -> settingsDialogs.confirm(getString(UiR.string.plugin_enable),
                        getString(UiR.string.plugin_enable_prompt, plugin.name, plugin.component.packageName)) {
                        runCatching { registry.setEnabled(plugin, true) }.onFailure { toast(getString(UiR.string.plugin_unavailable)) }
                        refreshUiState()
                    }
                    UiAction.PLUGIN_DISABLE -> { registry.setEnabled(plugin, false); refreshUiState() }
                    UiAction.PLUGIN_SETTINGS -> plugin.settings?.let { component ->
                        runCatching { startActivity(Intent().setComponent(component)) }.onFailure { toast(getString(UiR.string.plugin_unavailable)) }
                    }
                    UiAction.PLUGIN_CONNECT -> startProjectionSource(plugin.id)
                    else -> Unit
                }
            }
            UiAction.DESKTOP_DISPLAY -> configureDesktopDisplay()
            UiAction.DESKTOP_SHIZUKU -> openSystem(packageManager.getLaunchIntentForPackage(ShizukuDesktopBackend.PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            UiAction.DESKTOP_AUTHORIZE -> runCatching {
                if (Shizuku.pingBinder() && Shizuku.getVersion() >= 13) Shizuku.requestPermission(40)
                else toast((desktopProvider().dependencyStatus() as? SourceAvailability.Unavailable)?.reason.orEmpty())
            }.onFailure { toast(getString(UiR.string.desktop_not_authorized)) }.let { Unit }
            UiAction.DESKTOP_APP -> chooseDesktopApp()
            UiAction.DESKTOP_START -> startProjectionSource(ProjectionSourceIds.ANDROID_DESKTOP)
            UiAction.DEMO -> chooseDiagnosticSource()
        }
        refreshUiState()
    }

    private fun stopSource(group: String) {
        val session = projectionSessions.snapshot()?.takeIf { sessionBelongsToSource(it, group) }
        val state = projectionSessions.stateSnapshot()
        val pendingHere = state.source?.id?.let { sourcePageFor(it)?.group == group || it == group || belongsToSource(it, group) } == true && projectionSessions.hasSession()
        if (session != null || pendingHere) projectionSessions.stop(session).whenComplete { _, failure -> runOnUiThread {
            if (failure != null) toast(getString(UiR.string.projection_failed, projectionFailure(failure).message(this)))
            refreshUiState()
        } }
    }

    /** Read backend state on the main thread; composition only consumes this immutable snapshot. */
    private fun refreshUiState() {
        val desktop = desktopProvider()
        val desktopState = desktop.probe()
        val state = projectionSessions.stateSnapshot()
        val active = projectionSessions.snapshot()
        val running = projectionSessions.hasSession()
        val carPlayRunning = CarPlayBackgroundSession.hasSession()
        val registry = projectionPlugins(this)
        val selected = selectedHomeSource()
        val currentId = state.source?.id?.takeIf { running } ?: if (carPlayRunning) ProjectionSourceIds.CARPLAY else null
        val currentPage = currentId?.let(::sourcePageFor)
        val phase = when {
            running -> state.message(this)
            CarPlayBackgroundSession.active -> getString(UiR.string.projection_state_running)
            else -> getString(UiR.string.projection_state_connecting)
        }
        val overview = buildString {
            append(getString(UiR.string.source_home_selection, selected.name))
            if (currentId != null) append("\n" + getString(UiR.string.source_current_session,
                state.source?.displayName?.takeIf { running } ?: getString(UiR.string.source_carplay), phase))
        }
        val flags = SourcePage.entries.associateWith { sourcePage ->
            val labels = mutableListOf<String>()
            if (selected.page == sourcePage) labels += getString(UiR.string.source_home_default)
            if (sourcePage == currentPage) labels += if (state.failure != null) getString(UiR.string.source_needs_attention) else phase
            else if (state.failure != null && state.source?.id?.let(::sourcePageFor) == sourcePage) labels += getString(UiR.string.source_needs_attention)
            else when (sourcePage) {
                SourcePage.ICCOA -> {
                    val plugins = registry.entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK)
                    labels += getString(when {
                        plugins.isEmpty() -> UiR.string.source_install_required
                        plugins.none { it.problem == null } -> UiR.string.source_update_required
                        plugins.none { registry.available(it) } -> UiR.string.source_enable_required
                        plugins.any { registry.lastFailure(it.id) != null } -> UiR.string.source_needs_attention
                        else -> UiR.string.source_ready
                    })
                }
                SourcePage.DESKTOP -> if (desktopState !is SourceAvailability.Ready) labels += getString(UiR.string.source_setup_required)
                else -> Unit
            }
            labels.joinToString(" · ")
        }
        val sessions = SourcePage.entries.filter { it != SourcePage.CARPLAY }.mapNotNull { sourcePage ->
            val group = sourcePage.group
            val session = active?.takeIf { sessionBelongsToSource(it, group) }
            val belongs = state.source?.id?.let { sourcePageFor(it)?.group == group || it == group || belongsToSource(it, group) } == true
            val hasPlugins = if (sourcePage == SourcePage.ICCOA) registry.entriesForSource(ProjectionPlugin.SOURCE_ICCOA_CARLINK).isNotEmpty() else registry.entries().isNotEmpty()
            if (sourcePage in setOf(SourcePage.ICCOA, SourcePage.PLUGINS) && !hasPlugins && session == null && !(belongs && (running || state.failure != null))) null
            else group to SessionUi(when {
                belongs && (state.failure != null || (session == null && running)) -> state.message(this)
                session != null -> session.statusText ?: state.message(this)
                else -> getString(UiR.string.source_idle)
            }, session != null || (belongs && running))
        }.toMap()
        val plugins = registry.entries().map { plugin -> PluginUi(
            plugin.id, plugin.name, plugin.component.packageName, plugin.sourceId == ProjectionPlugin.SOURCE_ICCOA_CARLINK,
            registry.enabled(plugin), plugin.problem, registry.lastFailure(plugin.id)?.let { getString(UiR.string.projection_failed, it) },
            plugin.settings != null, !(running && (state.source?.id == plugin.id || active?.descriptor?.id == plugin.id)),
        ) }
        val addresses = if (WebSession.running && WebSession.error == null) WebSession.addresses(this) else emptyList()
        val primary = addresses.firstOrNull()
        val activeName = active?.descriptor?.displayName ?: if (carPlayRunning) getString(UiR.string.source_carplay) else state.source?.displayName?.takeIf { running }
        val selectedActive = (selected.id == ProjectionSourceIds.CARPLAY && carPlayRunning) || active?.let {
            it.descriptor.id == selected.id || (selected.id in setOf(ProjectionSourceIds.ANDROID_AUTO, ProjectionSourceIds.VNC) && belongsToSource(it.descriptor.id, selected.id))
        } == true
        val config = desktop.displayConfig()
        uiState = WheelPlayUiState(
            home = HomeUi(
                address = WebSession.error ?: if (WebSession.running) primary?.url ?: getString(UiR.string.ui_no_local_network_address_found_connect_to_wi_fi) else getString(UiR.string.ui_starting_service),
                addressDescription = primary?.let { it.description(this) + if (it.hostname.isNotEmpty()) getString(UiR.string.address_direct_ip, it.ipUrl) else "" }.orEmpty(),
                otherAddresses = addresses.drop(1).map { AddressUi(it.description(this), it.url) },
                browserEnabled = WebSession.running && BrowserExperienceLink.local(WebSession.code, WebSession.httpPort) != null,
                code = if (WebSession.running) WebSession.code else "— — — — — —", viewerConnected = WebSession.hasViewer,
                sourceName = getString(UiR.string.home_switch_source, selected.name),
                stage = when {
                    active != null -> getString(UiR.string.home_active_source, active.descriptor.displayName, state.failure?.message(this) ?: active.statusText ?: getString(UiR.string.projection_active, active.descriptor.displayName))
                    carPlayRunning -> getString(UiR.string.home_active_source, getString(UiR.string.source_carplay), WebSession.stage(this))
                    selected.id == ProjectionSourceIds.CARPLAY -> setupError ?: if (WheelPlayPreferences.phoneAddress(this) != null) getString(UiR.string.status_selected_phone, WheelPlayPreferences.phoneName(this)) else getString(UiR.string.ui_waiting_to_connect_to_iphone)
                    registry.lastFailure(selected.id) != null -> getString(UiR.string.projection_failed, registry.lastFailure(selected.id))
                    state.source?.id == selected.id -> state.message(this)
                    else -> getString(UiR.string.projection_waiting)
                },
                connectLabel = getString(if (selected.id in setOf(ProjectionSourceIds.ANDROID_AUTO, ProjectionSourceIds.VNC, ProjectionSourceIds.ANDROID_DESKTOP)) UiR.string.home_configure_connect else UiR.string.ui_connect),
                showConnect = !selectedActive && !(selected.id == ProjectionSourceIds.CARPLAY && WheelPlayPreferences.phoneAddress(this) == null),
                connectEnabled = selected.id != ProjectionSourceIds.CARPLAY || (setupError == null && !WheelPlayBootstrap.importing),
                stopLabel = activeName?.let { getString(UiR.string.home_stop_source, it) }, showAudioHint = selected.id == ProjectionSourceIds.CARPLAY,
            ),
            sources = SourcesUi(
                overview, flags, sessions, plugins,
                carPlayStatus = when {
                    active?.descriptor?.id?.let { it != ProjectionSourceIds.CARPLAY } == true -> active.descriptor.displayName
                    setupError != null -> getString(UiR.string.ui_authentication_setup_needs_attention)
                    CarPlayBackgroundSession.active -> getString(UiR.string.ui_carplay_connected)
                    carPlayRunning -> getString(UiR.string.ui_connecting_to_iphone)
                    WheelPlayPreferences.phoneAddress(this) != null -> getString(UiR.string.status_selected_phone, WheelPlayPreferences.phoneName(this))
                    else -> getString(UiR.string.ui_no_iphone_selected)
                }, carPlayRunning = carPlayRunning, carPlayEnabled = setupError == null && !WheelPlayBootstrap.importing,
                phone = selectedPhoneName(), setupError = setupError,
                hotspotHint = if (carHotspotOff()) getString(UiR.string.hotspot_disabled_hint, AirPlayPersistence.loadManualHotspotSsid(this)) else null,
                desktopStatus = if (desktop.preparing) getString(UiR.string.desktop_preparing) else (desktopState as? SourceAvailability.Unavailable)?.reason ?: desktop.lastFailure ?: getString(UiR.string.desktop_ready),
                desktopEnabled = !desktop.preparing && desktopState is SourceAvailability.Ready && active?.descriptor?.id != ProjectionSourceIds.ANDROID_DESKTOP,
                desktopDisplay = getString(UiR.string.desktop_display_summary, config.width, config.height, config.dpi), desktopApp = desktop.selectedApp(),
            ),
        )
    }

    private fun render() {
        refreshUiState()
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
            margins = { slot, margins -> changeSettings { applySettingsEffect(settingsModel.saveMargins(slot, margins)) }?.let(::toast) },
            resetBranding = { includeName -> changeSettings { settingsModel.resetBranding(includeName) }?.let(::toast) },
            command = { command -> changeSettings { settingsCommand(command) }?.let(::toast) },
            renameBrowser = { id, name -> changeSettings { settingsModel.renameBrowser(id, name) }?.let(::toast) },
            removeBrowser = { id -> changeSettings { settingsModel.removeBrowser(id) }?.let(::toast) }
        )
    }

    private fun changeSettings(action: () -> Unit): String? = runCatching {
        action(); refreshSettings(); refreshUiState()
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
        val sources = homeSources()
        dialogs.choices(getString(UiR.string.home_choose_source), sources.map { it.name }, sources.indexOf(selectedHomeSource())) { index ->
            WheelPlayPreferences.saveHomeSource(this, sources[index].id)
            refreshUiState()
        }
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

    private val desktopPermissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == 40) runOnUiThread { if (!isDestroyed) refreshUiState() }
    }
    private fun desktopProvider(): AndroidDesktopSource {
        registerProjectionSources(this)
        return projectionSessions.sources().filterIsInstance<AndroidDesktopSource>().single()
    }
    private fun configureDesktopDisplay() {
        val source = desktopProvider()
        val config = source.displayConfig()
        val defaults = DesktopDisplayConfig()
        dialogs.form(getString(UiR.string.desktop_display_settings), getString(UiR.string.desktop_display_hint), listOf(
            WheelPlayDialogs.Field("width", getString(UiR.string.desktop_width), config.width.toString(), numeric = true),
            WheelPlayDialogs.Field("height", getString(UiR.string.desktop_height), config.height.toString(), numeric = true),
            WheelPlayDialogs.Field("dpi", getString(UiR.string.desktop_dpi), config.dpi.toString(), numeric = true),
        ), resetLabel = getString(UiR.string.desktop_display_reset), defaults = listOf(defaults.width, defaults.height, defaults.dpi).map { it.toString() }) { values, _ ->
            val selected = runCatching { DesktopDisplayConfig(values[0].toInt(), values[1].toInt(), values[2].toInt()) }.getOrNull()
            if (selected == null) getString(UiR.string.desktop_display_invalid, DesktopDisplayConfig.MIN_SIZE, DesktopDisplayConfig.MAX_SIZE,
                DesktopDisplayConfig.MAX_PIXELS, DesktopDisplayConfig.MIN_DPI, DesktopDisplayConfig.MAX_DPI)
            else { source.configureDisplay(selected); refreshUiState(); null }
        }
    }

    private fun chooseDesktopApp() {
        val apps = packageManager.queryIntentActivities(AndroidDesktopSource.launcherIntent(), 0)
            .filter { it.activityInfo.isDesktopLaunchable }.distinctBy { it.activityInfo.desktopComponent }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (apps.isEmpty()) { toast(getString(UiR.string.desktop_no_apps)); return }
        dialogs.choices(getString(UiR.string.desktop_choose_app), apps.map { "${it.loadLabel(packageManager)} (${it.activityInfo.packageName})" }) { index ->
            runCatching { desktopProvider().selectApp(apps[index].activityInfo.desktopComponent.flattenToString()) }
                .onFailure { toast(getString(UiR.string.desktop_choose_app)) }
            refreshUiState()
        }
    }

    private fun configureVnc() {
        registerProjectionSources(this)
        val source = projectionSessions.sources().filterIsInstance<VncSource>().single()
        val endpoint = source.endpoint()
        dialogs.form(getString(UiR.string.vnc_connect), getString(UiR.string.vnc_security_hint), listOf(
            WheelPlayDialogs.Field("host", getString(UiR.string.vnc_host), endpoint.host),
            WheelPlayDialogs.Field("port", getString(UiR.string.vnc_port), endpoint.port.toString(), numeric = true),
            WheelPlayDialogs.Field("password", getString(UiR.string.vnc_password), "", secret = true),
        ), getString(UiR.string.vnc_read_only), endpoint.readOnly) { values, readOnly ->
            val target = VncEndpoint(values[0].trim(), values[1].toIntOrNull() ?: 0, readOnly)
            val secret = values[2].toCharArray()
            try {
                when {
                    !target.valid() -> getString(UiR.string.vnc_invalid_address)
                    secret.size > 8 || secret.any { it.code !in 1..255 } -> getString(UiR.string.vnc_password_hint)
                    else -> { source.configure(target, secret); startProjectionSource(ProjectionSourceIds.VNC_TCP); null }
                }
            } finally { secret.fill('\u0000') }
        }
    }

    private fun belongsToSource(id: String, group: String) = id.startsWith("$group-")

    private fun sessionBelongsToSource(session: ProjectionSession, group: String): Boolean =
        if (group == ProjectionSourceIds.ICCOA) (session as? RemotePluginSession)?.plugin?.sourceId == ProjectionPlugin.SOURCE_ICCOA_CARLINK
        else session.descriptor.id == group || belongsToSource(session.descriptor.id, group)

    private fun chooseDiagnosticSource() {
        registerProjectionSources(this)
        val sources = projectionSessions.sources().filter { belongsToSource(it.descriptor.id, ProjectionSourceIds.DEMO) }
        dialogs.choices(getString(UiR.string.projection_choose_demo), sources.map { it.descriptor.displayName }) { index ->
            startProjectionSource(sources[index].descriptor.id)
        }
    }

    private fun configureAndroidAutoTcp() {
        dialogs.form(getString(UiR.string.aa_tcp_title), getString(UiR.string.aa_tcp_hint), listOf(
            WheelPlayDialogs.Field("host", "IP", getSharedPreferences("projection", 0).getString("aa_host", "").orEmpty()),
        )) { values, _ ->
            val address = values.single().trim()
            if (!com.shilapi.xcertplay.projection.androidauto.AapTcpConnection.validAddress(address)) getString(UiR.string.aa_invalid_address)
            else {
                getSharedPreferences("projection", 0).edit().putString("aa_host", address).putInt("aa_port", 5277).apply()
                startProjectionSource(ProjectionSourceIds.ANDROID_AUTO_TCP); null
            }
        }
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
        val available = LocalAndroidAuto.isInstalledAndEnabled(this)
        val actions = mutableListOf(WheelPlayDialogs.Action(getString(UiR.string.aa_local_settings), ::openLocalAndroidAutoSettings))
        if (available) actions.add(0, WheelPlayDialogs.Action(getString(UiR.string.aa_local_connect)) {
            getSharedPreferences("projection", 0).edit().putBoolean(LocalAndroidAuto.SETUP_SEEN, true).apply()
            connectLocalAndroidAuto()
        })
        dialogs.message(getString(UiR.string.aa_local_title), getString(if (available) UiR.string.aa_local_setup else UiR.string.aa_local_unavailable), actions)
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
            refreshUiState()
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
        handler.post { render() }
    }

    // The car hotspot link needs the hotspot on; WheelPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        dialogs.message(getString(UiR.string.ui_hotspot_is_off), getString(UiR.string.hotspot_enable_message, AirPlayPersistence.loadManualHotspotSsid(this)), listOf(
            WheelPlayDialogs.Action(getString(UiR.string.ui_open_system_settings), ::openCarWifiSettings),
            WheelPlayDialogs.Action(getString(UiR.string.ui_connect)) { connect(true) },
        ))
    }

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
                if (!isFinishing && !isDestroyed) { toast(failure ?: success); render() }
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
        refreshUiState()
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
    private fun handleUsbConnectionIntent(launch: Intent): Boolean {
        if (launch.action != "android.hardware.usb.action.USB_DEVICE_ATTACHED") return false
        // Route USB attachment through the console so the dialog always has a real backdrop.
        // Consume it before onResume's optional auto-connect and before Activity recreation.
        launch.action = null
        launch.putExtra("page", page)
        if (projectionSessions.hasSession() &&
            projectionSessions.snapshot()?.descriptor?.id != ProjectionSourceIds.CARPLAY) return true
        refreshAuthentication()
        if (setupError != null) {
            navigate("settings")
            settingsModel.open(SettingsCategory.AUTHENTICATION)
        } else if (!CarPlayBackgroundSession.hasSession() || AirPlayPersistence.loadWirelessEnabled(this)) {
            connect(false)
        }
        return true
    }

    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java)
            .putExtra("return_page", page)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(0, 0)
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val settings = WheelPlayDialogs.Action(getString(UiR.string.ui_open_bluetooth_settings)) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        if (adapter == null || !adapter.isEnabled) {
            dialogs.message(getString(UiR.string.ui_turn_on_bluetooth), getString(UiR.string.ui_turn_on_bluetooth_on_this_android_device_and_pair), listOf(settings)); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            dialogs.message(getString(UiR.string.ui_pair_your_iphone_first), getString(UiR.string.ui_pair_this_android_device_in_settings_bluetooth_on_your), listOf(settings)); return
        }
        dialogs.choices(getString(UiR.string.ui_choose_iphone), devices.map { device ->
            val name = device.name ?: getString(UiR.string.ui_paired_device)
            if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
        }, extra = WheelPlayDialogs.Action(getString(UiR.string.ui_pair_another_device), settings.run), onCancel = { pendingWireless = false }) { index ->
            val device = devices[index]
            WheelPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
            val start = pendingWireless; pendingWireless = false
            render()
            if (start) connect(true)
        }
    }

    private fun wirelessHelp() {
        dialogs.message(getString(UiR.string.ui_wireless_connection_help), getString(UiR.string.ui_pair_the_iphone_with_this_device_over_bluetooth_keep), listOf(
            WheelPlayDialogs.Action(getString(UiR.string.ui_ok)) {},
            WheelPlayDialogs.Action(getString(UiR.string.ui_reset_carplay_wi_fi), ::confirmWirelessReset),
        ))
    }

    private fun handleWirelessRecovery() {
        if (intent.getStringExtra("page") != "wireless-recovery") return
        intent.removeExtra("page")
        navigate(SourcePage.CARPLAY.id)
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        dialogs.message(getString(UiR.string.ui_reset_carplay_wi_fi_2), getString(UiR.string.ui_this_ends_the_current_wi_fi_direct_connection_including), listOf(
            WheelPlayDialogs.Action(getString(UiR.string.ui_reset_and_connect)) { CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } } },
        ))
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
                    dialogs.message(getString(UiR.string.ui_diagnostic_report_saved),
                        if (uri == null) "Downloads/WheelPlay/$fileName" else getString(UiR.string.ui_report_saved_to_the_selected_location),
                        listOf(WheelPlayDialogs.Action(getString(UiR.string.ui_done)) {}))
                } else {
                    dialogs.message(getString(UiR.string.ui_could_not_save_report), getString(UiR.string.ui_check_storage_space_or_choose_another_save_location),
                        listOf(WheelPlayDialogs.Action(getString(UiR.string.ui_choose_location), ::chooseReportDestination)))
                }
            }
        }, "wheelplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        dialogs.message(title, body, listOf(WheelPlayDialogs.Action(getString(UiR.string.ui_app_settings)) {
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }))
    }

    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(UiR.string.ui_open_this_option_in_the_device_s_system_settings)) } }
    private fun toast(message: String) { lifecycleScope.launch { snackbar.showSnackbar(message) } }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"




}
