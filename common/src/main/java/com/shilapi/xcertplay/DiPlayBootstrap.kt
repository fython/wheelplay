package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.security.MessageDigest

/** Uses user-imported resources, with optional privately provisioned APK assets as a fallback. */
internal object DiPlayBootstrap {
    private val importInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    val importing: Boolean get() = importInProgress.get()
    @Synchronized fun beginImport(): Boolean = importInProgress.compareAndSet(false, true)
    fun finishImport() { importInProgress.set(false) }

    @Synchronized fun ensure(context: Context) {
        check(!importing) { "Authentication import in progress" }
        val store = MfiAssetStore(context.noBackupFilesDir)
        if (!store.exists()) {
            store.installFiles(
                { context.assets.open("offline-mfi/identity.pk8") },
                { context.assets.open("offline-mfi/certificate.p7b") },
            )
        }
        store.load()
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}
