// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import kotlin.concurrent.thread

/** Retains an import across Activity recreation; holds no Activity or credential bytes. */
internal class AuthenticationImportModel(application: Application) : AndroidViewModel(application) {
    data class State(val busy: Boolean = false, val result: String? = null)
    val state = MutableLiveData(State())

    fun import(apk: Uri? = null, key: Uri? = null, certificate: Uri? = null) {
        if (state.value?.busy == true) return
        if (CarPlayBackgroundSession.hasSession()) {
            state.value = State(result = "请先断开 iPhone，再导入认证资源")
            return
        }
        if (!DiPlayBootstrap.beginImport()) return
        state.value = State(busy = true)
        val context = getApplication<Application>()
        thread(name = "authentication-import") {
            val result = try {
                val store = MfiAssetStore(context.noBackupFilesDir)
                fun open(uri: Uri): java.io.InputStream {
                    require(uri.scheme == "content") { "Expected a selected local document" }
                    return requireNotNull(context.contentResolver.openInputStream(uri))
                }
                val disconnected = { !CarPlayBackgroundSession.hasSession() }
                if (apk != null) store.installApk({ open(apk) }, disconnected)
                else store.installFiles({ open(requireNotNull(key)) }, { open(requireNotNull(certificate)) }, disconnected)
                "认证资源已导入并通过校验，现在可以连接 iPhone"
            } catch (_: Exception) {
                if (CarPlayBackgroundSession.hasSession()) "请先断开 iPhone，再导入认证资源"
                else if (apk != null) "导入失败：请使用包含 identity.pk8 和 certificate.p7b 的 DiPlay APK（不超过 256 MB）。原有资源已保留。"
                else "导入失败：请选择有效且匹配的 PKCS#8 私钥和 P-256 证书（各不超过 16 KB）。原有资源已保留。"
            } finally {
                DiPlayBootstrap.finishImport()
            }
            state.postValue(State(result = result))
        }
    }

    fun consumeResult() { state.value = State() }
}

/** Restrict exported import entry points to local files explicitly sent by the user. */
internal object AuthenticationImportIntents {
    fun apk(intent: Intent): Uri? {
        val uri = try {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.clipData?.getItemAt(0)?.uri
                }
                Intent.ACTION_VIEW -> intent.data
                else -> null
            }
        } catch (_: RuntimeException) { null }
        return uri?.takeIf { it.scheme == "content" }
    }
}
