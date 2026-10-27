package moe.feng.wheelplay

import com.shilapi.xcertplay.projection.ProjectionSourceIds
import com.shilapi.xcertplay.projection.CarPlaySourceSession
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.projectionSessions

import com.shilapi.xcertplay.shared.R as UiR
import com.shilapi.xcertplay.i18n.*

import com.shilapi.xcertplay.web.WebSession
import com.shilapi.xcertplay.web.OffscreenVideoOutput
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import moe.feng.wheelplay.ui.CarPlayConnectionDialog
import moe.feng.wheelplay.ui.CarPlayConnectionUiState
import moe.feng.wheelplay.ui.theme.WheelPlayTheme
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay protocol and permission host. A transparent window presents Compose connection dialogs
 * over the console; video stays in the offscreen server output. The controller outlives the dialog.
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private var connectionUi by mutableStateOf(CarPlayConnectionUiState())
    private var connectionDialogVisible by mutableStateOf(true)
    private var reconnectAttempts = 0
    private lateinit var airPlayIdentity: AirPlayIdentity

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "WheelPlay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + WheelPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            // Advertise the channel so a browser can opt in during an existing session.
            // No fixes are supplied until browser consent or the Android GPS setting enables a source.
            locationInformationEnabled = true,
        ),
        label = "WheelPlay",
        hostName = "wheelplay-" + WheelPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = WheelPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = WheelPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = true,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setLocalizedConnectionStage(UiText(UiR.string.host_vpn_consent_was_denied))
                connectionUi = connectionUi.copy(failed = true)
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )

            if (!wirelessPermissionsReady) {
                setLocalizedConnectionStage(UiText(UiR.string.host_allow_the_connection_permissions_to_continue))
                connectionUi = connectionUi.copy(failed = true)
            }
            maybeStartCarPlay()
        }
    private val manualHotspotChannelPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                hasFineLocationPermission()
            appendLog(
                if (granted) {
                    "Hotspot channel scan permission granted"
                } else {
                    "Hotspot channel scan permission denied; continuing with Auto"
                },
            )
            if (!granted) {
                AirPlayPersistence.saveManualHotspotChannelPermissionPromptDismissed(this, true)
            }

            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                AirPlayPersistence.saveLocationReportingEnabled(this, false)
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            if (!locationPermissionAvailable &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                wirelessHotspotMode == WirelessHotspotMode.MANUAL
            ) {
                // Don't immediately ask for the same denied permission again for optional channel detection.
                manualHotspotChannelPermissionAsked = true
                AirPlayPersistence.saveManualHotspotChannelPermissionPromptDismissed(this, true)
            }

            requestStartupPrerequisites()
        }

    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var simulatedManufacturerName = AirPlayPersistence.DEFAULT_SIMULATED_MANUFACTURER_NAME
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var streamTechnology = com.shilapi.xcertplay.web.StreamTechnology.WEBRTC
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var microphoneSource = CarPlayMicrophoneSource.ANDROID
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotChannelPermissionAsked = false
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var automaticReconnectAllowed = true
    private var sessionLog: SessionLogFile? = null
    private val shuttingDown = AtomicBoolean(false)
    private var shutdownComplete = false
    private val shutdownCompletions = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSystemBars()
        if (projectionSessions.hasSession() &&
            projectionSessions.snapshot()?.descriptor?.id != ProjectionSourceIds.CARPLAY) {
            showWheelPlayHome("home"); finish(); return
        }
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        val authenticationReady = runCatching { WheelPlayBootstrap.ensure(this) }.isSuccess
        if (!authenticationReady) {
            // Do not retain a partially initialized host after USB/other external launches.
            showWheelPlayHome("settings")
            finish()
            return
        }
        startForegroundService(Intent(this, WheelPlaySessionService::class.java))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializeSessionLog()
        darkMode = isDarkMode(resources.configuration.uiMode)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContent {
            WheelPlayTheme {
                if (connectionDialogVisible) {
                    CarPlayConnectionDialog(
                        state = connectionUi,
                        onCancel = ::cancelConnection,
                        onBackground = ::returnToDashboard,
                        onSettings = { showWheelPlayHome("settings") },
                        onResetWifi = { showWheelPlayHome("wireless-recovery") },
                        onChannelPermission = ::resolveChannelPermissionPrompt,
                    )
                }
            }
        }
        activeDisplaySize = DisplaySize(1280, 720)
        WebSession.setBrowserViewportListener { width, height ->
            mainHandler.post { scheduleBrowserDisplaySize(width, height) }
        }
        showSystemBars()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    cancelConnection()
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            dismissConnectedDialog()
        } else if (AirPlayPersistence.loadMicrophoneSource(this) == CarPlayMicrophoneSource.DISABLED ||
            microphonePermissionResolved || WebSession.audio.microphoneRequested) {
            microphonePermissionResolved = true
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        microphoneSource = AirPlayPersistence.loadMicrophoneSource(this)
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        model = AirPlayPersistence.loadModel(this)
        simulatedManufacturerName = AirPlayPersistence.loadSimulatedManufacturerName(this)
        fps = AirPlayPersistence.loadFps(this)
        streamTechnology = AirPlayPersistence.loadStreamTechnology(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        loadPersistedWirelessSettings()
        manualHotspotChannelPermissionAsked =
            AirPlayPersistence.loadManualHotspotChannelPermissionPromptDismissed(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun loadPersistedWirelessSettings() {
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
    }

    private fun requestStartupPrerequisites() {
        if (isFinishing || shuttingDown.get()) return
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Precise location is optional on Android 13+: it only enables Wi-Fi scan-result channel detection. */
    private fun requestManualHotspotChannelPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            wirelessHotspotMode != WirelessHotspotMode.MANUAL ||
            hasFineLocationPermission() || manualHotspotChannelPermissionAsked
        ) return false

        manualHotspotChannelPermissionAsked = true
        connectionUi = connectionUi.copy(requestChannelPermission = true)
        return true
    }

    private fun resolveChannelPermissionPrompt(allow: Boolean) {
        if (!connectionUi.requestChannelPermission || shuttingDown.get()) return
        connectionUi = connectionUi.copy(requestChannelPermission = false)
        if (allow) {
            manualHotspotChannelPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        } else {
            AirPlayPersistence.saveManualHotspotChannelPermissionPromptDismissed(this, true)
            appendLog("Hotspot channel scan skipped; continuing with Auto")

            maybeStartCarPlay()
        }
    }

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true

            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false

        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (shuttingDown.get()) {
            // A failed startup may leave its error dialog in the history after cleanup. Its
            // executor and generation are closed; a new connection needs a fresh host.
            finish()
            shutdown(false, "Replacing stopped connection host") {
                startActivity(Intent(intent).setClass(this, CarPlayHostActivity::class.java))
            }
            return
        }
        setIntent(intent)
        connectionDialogVisible = true
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED" && wirelessEnabled) {
            shutdown(false, "switching to USB") {
                AirPlayPersistence.saveWirelessEnabled(this, false)
                startActivity(Intent(this, CarPlayHostActivity::class.java))
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || shuttingDown.get()) return
        var transportChanged = false
        if (!isFinishing && !shuttingDown.get() && controller == null &&
            !handshakeResetInProgress
        ) {
            // The retained connection host can return from settings before a controller exists.
            // Refresh the backend and its credentials before any startup/permission checks.
            val previousWireless = wirelessEnabled
            loadPersistedWirelessSettings()
            transportChanged = previousWireless != wirelessEnabled
            locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
            val selectedMicrophone = AirPlayPersistence.loadMicrophoneSource(this)
            if (microphoneSource != selectedMicrophone) {
                microphoneSource = selectedMicrophone
                microphonePermissionResolved = selectedMicrophone == CarPlayMicrophoneSource.DISABLED ||
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ||
                    WebSession.audio.microphoneRequested
                if (!microphonePermissionResolved) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()

        if (transportChanged && microphonePermissionResolved && !awaitingVpnConsent &&
            !awaitingWirelessPermissions && !awaitingLocationPermission
        ) {
            requestStartupPrerequisites()
        } else {
            maybeStartCarPlay()
        }
        showSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showSystemBars()
    }

    override fun onStop() {
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nextDarkMode = isDarkMode(newConfig.uiMode)
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
        }
        showSystemBars()
    }

    override fun onDestroy() {
        WebSession.setBrowserViewportListener(null)
        mainHandler.removeCallbacks(applyDisplaySize)
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    override fun onPostResume() {
        super.onPostResume()
        dismissConnectedDialog()
    }

    private fun dismissConnectedDialog() {
        if (connectionDialogVisible && !connectionUi.stopping && CarPlayBackgroundSession.active &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            returnToDashboard()
        }
    }

    private fun returnToDashboard() = showWheelPlayHome(intent.getStringExtra("return_page") ?: "service")

    private fun cancelConnection() {
        if (connectionUi.stopping || isFinishing) return
        connectionUi = connectionUi.copy(stopping = true, requestChannelPermission = false)
        automaticReconnectAllowed = false
        val done = {
            returnToDashboard()
            finish()
        }
        if (!shuttingDown.get() && controller == null && !CarPlayBackgroundSession.isOwner(this)) {
            // Permission/setup cancellation must not clear another input source's reservation.
            shuttingDown.set(true)
            restartGeneration += 1
            teardownExecutor.shutdown()
            shutdownComplete = true
            done()
        } else {
            shutdown(false, "Connection cancelled", done)
        }
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(UiR.string.host_local_offline)
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> getString(UiR.string.host_remote)
    }

    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport = try {
        val mime = if (hevcEnabled) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Match MediaCodec.createDecoderByType's first suitable decoder; do not silently force
        // an enlarged stream through a software decoder on a slower head unit.
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (Build.VERSION.SDK_INT >= 29) decoder.isHardwareAccelerated
                else !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels, display.heightPixels, display.fps.toDouble()) == true
            val reason = when {
                !hardware -> "software_decoder"
                hevcEnabled && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            CanvasSupport(reason == "supported", reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                    "widths=${video?.supportedWidths} heights=${video?.supportedHeights} alignment=${video?.widthAlignment}x${video?.heightAlignment} " +
                    "fpsRange=${video?.supportedFrameRates} result=$reason")
        }
    } catch (error: Exception) {
        CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}")
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val resolutionDisplay = CarPlayDisplayScale.apply(baseDisplay, displayScaleTenths)
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        val support = when {
            uiScalePercent >= CarPlayUiScale.DEFAULT -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
            scaledDisplay === resolutionDisplay -> CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            else -> largerCanvasSupport(scaledDisplay)
        }
        if (!support.supported) {
            scaledDisplay = resolutionDisplay
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            appendLog("Larger CarPlay canvas unavailable reason=${support.reason}; using Default icon and text size")
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(UiR.string.host_this_head_unit_cannot_use_the_smaller_size_at),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = if (AirPlayPersistence.loadSafeMargins(this)?.isFullScreen == false) false else safeAreaDrawOutside,
        )
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent surface=${size.width}x${size.height} resolution=${displayScaleTenths * 10}% " +
            "base=${resolutionDisplay.widthPixels}x${resolutionDisplay.heightPixels} candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (hevcEnabled) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        return AirPlayConfig(
            deviceName = "WheelPlay",
            deviceId = WheelPlayBootstrap.deviceId(airPlayIdentity),
            btMac = WheelPlayBluetooth.localAddress(this) ?: WheelPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = display,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = AirPlayPersistence.loadMicrophoneSource(this) != CarPlayMicrophoneSource.DISABLED,
            microphoneOpus = com.shilapi.xcertplay.media.OpusEncoderSupport.isAvailable(),
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = normalizedManufacturer(),
            icons = listOf(loadAirPlayIcon()),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            // The browser canvas fills its display at every pixel resolution. Treating it as
            // a fraction of a previous viewport shrinks the reported screen and enlarges iOS UI.
            maximumWidthPixels = size.width,
            maximumHeightPixels = size.height,
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun normalizedManufacturer(): String =
        simulatedManufacturerName.trim().ifBlank { AirPlayPersistence.DEFAULT_SIMULATED_MANUFACTURER_NAME }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        val videoGeneration = WebSession.beginVideo(videoWidth, videoHeight)
        val source = com.shilapi.xcertplay.web.WebVideoSource(streamTechnology)
        WebSession.attachVideoSource(videoGeneration, source)
        val output = OffscreenVideoOutput(videoWidth, videoHeight,
            wanted = { WebSession.hasViewer && source.jpegNeeded },
            encoding = { AirPlayPersistence.imageEncoding(this, WebSession.imageFormat) },
            publish = { bytes, format, quality -> WebSession.publish(videoGeneration, bytes, format, quality) })
        return AndroidMediaSink(
            surface = output.surface,
            encodedVideo = source,
            frameDecoderFactory = { key ->
                try {
                    com.shilapi.xcertplay.web.NativeVideoDecoder(key, platform = true)
                        .also { source.setProcessorName(it.name) }
                } catch (_: LinkageError) { null }
            },
            remoteAudio = WebSession.audio.createRoute(),
            onClosed = { output.closeAndAwait(); WebSession.endVideo(videoGeneration) },
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = { message ->
                diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
            },
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = AirPlayPersistence.loadMicrophoneSource(this) != CarPlayMicrophoneSource.DISABLED,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    reconnectAttempts = 0
                    syncAirPlayDarkMode()
                    appendLog("AirPlay session active")
                    dismissConnectedDialog()
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    if (activeAirPlaySession === session) activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    WebSession.videoActive = false
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    WebSession.videoActive = false
                    setLocalizedConnectionStage(UiText(UiR.string.host_carplay_session_ended_reconnecting))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    WebSession.videoActive = false
                    setLocalizedConnectionStage(UiText(UiR.string.host_transport_error_reconnecting))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                if (DiagnosticRedactor.redact(message) == null) return
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        runOnUiThread {
            if (!shuttingDown.get() && controllerGeneration == restartGeneration) {
                val failed = status is CarPlayStatus.Failed && (!status.automaticRetry || status.wifiResetRequired)
                if (failed) automaticReconnectAllowed = false
                val description = status.describe()
                setConnectionStage(description)
                connectionUi = connectionUi.copy(
                    failed = failed,
                    wifiResetRequired = status is CarPlayStatus.Failed && status.wifiResetRequired,
                )
                if (failed && connectionUi.stage == UiText(UiR.string.host_connection_interrupted_retrying)) {
                    setLocalizedConnectionStage(UiText(UiR.string.carplay_connection_failed_hint))
                }
                if (status is CarPlayStatus.Failed && status.automaticRetry && !status.wifiResetRequired) {
                    reconnectAfterLoss(description)
                }
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        sink = snapshot.sink
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height, this) { completion ->
            runOnUiThread {
                shutdown(false, "WheelPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        if (snapshot.controller.isClosed()) {
            restartCarPlay("Replacing closed background CarPlay controller")
            return true
        }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(UiR.string.host_carplay_service_already_running)
            } else {
                getString(UiR.string.host_carplay_session_already_running)
            },
        )
        dismissConnectedDialog()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || handshakeResetInProgress || controller != null) return
        automaticReconnectAllowed = true
        if (WheelPlayBootstrap.importing) {
            setLocalizedConnectionStage(UiText(UiR.string.ui_authentication_resources_are_being_imported_reconnect_when_finished))
            connectionUi = connectionUi.copy(failed = true)
            return
        }
        if (runCatching { WheelPlayBootstrap.ensure(this) }.isFailure) {
            setLocalizedConnectionStage(UiText(UiR.string.ui_authentication_resources_are_not_ready_import_files_or_a))
            connectionUi = connectionUi.copy(failed = true)
            return
        }
        if (!CarPlayBackgroundSession.reserve(this, { WebSession.stageDetail(this) }) { completion ->
                runOnUiThread { shutdown(false, "WheelPlay disconnect", completion); finish() }
            }) {
            showWheelPlayHome("home")
            finish()
            return
        }
        val controllerGeneration = restartGeneration
        // The settings page can change branding while this activity remains in the back stack.
        simulatedManufacturerName = AirPlayPersistence.loadSimulatedManufacturerName(this)
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider = WebSession.location.createProvider(
            if (locationReportingEnabled) {
                AndroidCarPlayLocationProvider(this)
            } else {
                null
            },
        )
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> ${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} microphone=${airPlayConfig.microphone} " +
                "androidLocation=${if (locationReportingEnabled) "enabled" else "disabled"} browserLocation=opt-in mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} microphone=${airPlayConfig.microphone} " +
                "androidLocation=$locationReportingEnabled browserLocation=opt-in mfi=${config.mfiTarget}",
        )
        val renderer = try {
            createMediaSink(
                videoWidth = airPlayConfig.main.widthPixels,
                videoHeight = airPlayConfig.main.heightPixels,
                controllerGeneration = controllerGeneration,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Offscreen video initialization failed", error)
            if (error is com.shilapi.xcertplay.projection.ProjectionCleanupException)
                CarPlayBackgroundSession.teardownFinished(false)
            setLocalizedConnectionStage(UiText(UiR.string.host_this_device_cannot_create_an_offscreen_decoder_surface_check))
            connectionUi = connectionUi.copy(failed = true)
            return
        }
        sink = renderer
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
        )
        controller = next
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this) { completion ->
            runOnUiThread {
                shutdown(terminateProcess = false, reason = "WheelPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            startForegroundService(Intent(this, WheelPlaySessionService::class.java))
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setLocalizedConnectionStage(UiText(UiR.string.host_could_not_start_carplay_return_to_wheelplay_and_check))
            connectionUi = connectionUi.copy(failed = true)
        }
    }

    private fun syncAirPlayDarkMode() {
        if (activeAirPlaySession != null) WebSession.refreshAppearance(this)
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleBrowserDisplaySize(width: Int?, height: Int?) {
        if (shuttingDown.get()) return
        val size = if (width == null || height == null) {
            DisplaySize(1280, 720)
        } else {
            val scale = minOf(1.0, 3840.0 / width, 2160.0 / height)
            fun even(value: Double) = (value.toInt().coerceAtLeast(2) / 2) * 2
            DisplaySize(even(width * scale), even(height * scale))
        }
        if (size == activeDisplaySize || size == pendingDisplaySize) return
        activeDisplaySize?.let { active ->
            if (kotlin.math.abs(size.width - active.width) <= 8 &&
                kotlin.math.abs(size.height - active.height) <= 8 &&
                kotlin.math.abs(size.width.toDouble() / size.height - active.width.toDouble() / active.height) < 0.01
            ) return
        }
        appendLog("Adaptive browser display requested: ${size.width}x${size.height}")
        pendingDisplaySize = size
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.postDelayed(applyDisplaySize, BROWSER_DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        if (shuttingDown.get() || size == activeDisplaySize) return
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)

        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (controller == null) {
            maybeStartCarPlay()
        } else if (handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get()) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            handshakeResetInProgress ||
            controller != null ||
            connectionUi.requestChannelPermission
        ) {
            return
        }
        if (requestManualHotspotChannelPermissionIfNeeded()) return
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String) {
        if (!automaticReconnectAllowed) return
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || handshakeResetInProgress) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (
                    !automaticReconnectAllowed || shuttingDown.get() ||
                    handshakeResetInProgress ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        sink = null
        teardownExecutor.execute {
            val clean = runCatching {
                oldController?.close()
                val transportStopped = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
                if (transportStopped) oldSink?.close(awaitWorkers = true)
                transportStopped && CarPlayBackgroundSession.teardownClean
            }.getOrDefault(false)
            if (!clean) {
                CarPlayBackgroundSession.teardownFinished(false)
                return@execute
            }
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) {
                    handshakeResetInProgress = false
                    startCarPlay(size)
                }
            }
        }
    }

    private fun showWheelPlayHome(page: String = "home") {
        connectionDialogVisible = false
        controller?.sendTouch(emptyList())
        startActivity(Intent(this, WheelPlayActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(0, 0)
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (shutdownComplete) { completion(); return }
        shutdownCompletions.add(completion)
        if (!shuttingDown.compareAndSet(false, true)) return
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            val clean = runCatching {
                oldController?.close()
                val transportStopped = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
                if (transportStopped) oldSink?.close(awaitWorkers = true)
                transportStopped && CarPlayBackgroundSession.teardownClean
            }.getOrDefault(false)
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            if (terminateProcess) applicationContext.stopService(Intent(applicationContext, WheelPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post {
                CarPlayBackgroundSession.teardownFinished(clean)
                shutdownComplete = true
                val callbacks = shutdownCompletions.toList()
                shutdownCompletions.clear()
                callbacks.forEach { it() }
            }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            WebSession.videoActive = activeScreenStreamTypes.isNotEmpty()
            dismissConnectedDialog()
        }
    }

    private fun setConnectionStage(message: String) {
        WebSession.setStage(friendlyStage(message))
        connectionUi = connectionUi.copy(stage = friendlyStage(message), failed = false, wifiResetRequired = false)
        dismissConnectedDialog()
    }

    private fun setLocalizedConnectionStage(message: UiText) {
        WebSession.setStage(message)
        connectionUi = connectionUi.copy(stage = message)
        dismissConnectedDialog()
    }

    private fun friendlyStage(message: String): UiText = when {
        message.contains("Turn on Wi-Fi", true) -> UiText(UiR.string.host_turn_on_wi_fi_in_this_device_s_system)
        message.contains("Allow precise Location", true) -> UiText(UiR.string.host_allow_precise_location_in_the_app_permissions)
        message.contains("Allow Nearby devices", true) -> UiText(UiR.string.host_allow_access_to_nearby_devices_in_the_app_permissions)
        message.contains("createGroup failed", true) -> UiText(UiR.string.host_cannot_start_carplay_wi_fi_check_wi_fi_and)
        message.contains("needs a reset", true) -> UiText(UiR.string.host_the_previous_wi_fi_direct_connection_is_still_running)
        message.contains("socket", true) || message.contains("RFCOMM", true) -> UiText(UiR.string.host_cannot_connect_to_the_iphone_yet_unlock_it_and)
        message.contains("unsupported", true) || message.contains("not supported", true) -> UiText(UiR.string.host_this_device_may_not_support_wireless_carplay_try_a)
        message.contains("denied", true) || message.contains("permission", true) -> UiText(UiR.string.host_allow_the_connection_permissions_to_continue)
        message.contains("Failed", true) || message.contains("error", true) -> UiText(UiR.string.host_connection_interrupted_retrying)
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) -> UiText(UiR.string.host_connect_the_iphone_using_a_usb_data_cable)
        message.contains("paired", true) -> UiText(UiR.string.host_looking_for_a_paired_iphone)
        message.contains("Bluetooth", true) -> UiText(UiR.string.ui_connecting_to_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) -> UiText(UiR.string.host_reconnecting_to_iphone)
        message.contains("active", true) || message.contains("running", true) -> UiText(UiR.string.host_carplay_session_started_waiting_for_video)
        else -> UiText(UiR.string.host_preparing_carplay)
    }

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "WheelPlay log started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun showSystemBars() = ServerWindow.showSystemBars(window)

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "Preparing MFi authentication"
        CarPlayStatus.WaitingForMfi -> "Waiting for MFi coprocessor"
        CarPlayStatus.RequestingMfiPermission -> "Requesting MFi USB permission"
        CarPlayStatus.MfiReady -> "MFi authentication ready"
        CarPlayStatus.StartingHotspot -> "Starting wireless hotspot"
        is CarPlayStatus.HotspotReady ->
            "Hotspot ready: $backend, $ssid, $band, channel ${if (channel == 0) "auto" else channel}"
        CarPlayStatus.WaitingForPairedIphone -> "Waiting for paired iPhone"
        CarPlayStatus.ConnectingBluetooth -> "Connecting Bluetooth"
        CarPlayStatus.RunningWireless -> "Wireless CarPlay control running"
        CarPlayStatus.WirelessActiveFallback -> "Wireless CarPlay active with Bluetooth control"
        CarPlayStatus.WirelessActive -> "Wireless CarPlay active"
        CarPlayStatus.DiscoveringIphone -> "Discovering iPhone"
        CarPlayStatus.WaitingForIphone -> "Waiting for iPhone over USB"
        CarPlayStatus.RequestingIphonePermission -> "Requesting iPhone USB permission"
        CarPlayStatus.WaitingForReenumeration -> "Waiting for iPhone re-enumeration"
        CarPlayStatus.SelectingConfiguration -> "Selecting CarPlay configuration"
        CarPlayStatus.OpeningDataPaths -> "Opening USB data paths"
        CarPlayStatus.Pairing -> "Pairing with iPhone"
        CarPlayStatus.ConnectingControl -> "Connecting iAP2 control"
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) "Starting AirPlay service" else "Attaching NCM/AirPlay network"
        CarPlayStatus.RunningControl -> "CarPlay control running"
        CarPlayStatus.ControlEnded -> "CarPlay control window ended"
        is CarPlayStatus.Failed -> "Failed: ${message}"
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val BROWSER_DISPLAY_CHANGE_DEBOUNCE_MILLIS = 900L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
    }

    private data class DisplaySize(val width: Int, val height: Int)
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
    )

    @Volatile var teardownClean = true
        private set

    @Synchronized fun reserve(owner: Any, status: () -> String?, stop: (() -> Unit) -> Unit): Boolean {
        if (projection != null) return this.owner === owner
        val adapter = CarPlaySourceSession(status)
        if (!projectionSessions.adopt(adapter,
                ProjectionResource.entries.toSet())) return false
        projection = adapter
        this.owner = owner
        stopAction = stop
        teardownClean = true
        return true
    }

    fun teardownFinished(clean: Boolean) {
        val adapter = synchronized(this) {
            teardownClean = clean
            projection.also { if (clean) projection = null }
        }
        adapter?.completed(clean)
        if (clean) adapter?.let { projectionSessions.ended(it) }
    }

    private var projection: CarPlaySourceSession? = null
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        return Snapshot(currentController, currentSink, width, height)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot?) -> Unit>()
    fun subscribe(listener: (Snapshot?) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(snapshot())
        return AutoCloseable { listeners.remove(listener) }
    }

    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int, owner: Any, stop: (() -> Unit) -> Unit) {
        synchronized(this) {
            this.stopAction = stop
            this.owner = owner
            this.controller = controller
            this.sink = sink
            projection?.bind(controller, sink)
            this.width = width
            this.height = height
        }
        projection?.let { projectionSessions.changed(it) }
        listeners.forEach { it(snapshot()) }
    }

    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        synchronized(this) {
            if (expected != null && controller !== expected) return
            controller = null
            sink = null
            projection?.bind(null, null)
            if (!keepOwner) { stopAction = null; owner = null }
            active = false
            width = 0
            height = 0
        }
        listeners.forEach { it(snapshot()) }
    }
}
