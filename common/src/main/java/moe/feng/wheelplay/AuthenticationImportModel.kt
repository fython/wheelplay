// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.shilapi.xcertplay.shared.R as UiR
import kotlin.concurrent.thread

/** Retains an import across Activity recreation; holds no Activity or credential bytes. */
internal class AuthenticationImportModel(application: Application) : AndroidViewModel(application) {
    data class State(val busy: Boolean = false, val result: String? = null)

    val state = MutableLiveData(State())

    fun import(apk: Uri? = null, key: Uri? = null, certificate: Uri? = null) {
        if (state.value?.busy == true) return
        if (CarPlayBackgroundSession.hasSession()) {
            state.value =
                State(
                    result =
                        getApplication<Application>()
                            .getString(
                                UiR.string
                                    .ui_disconnect_the_iphone_before_importing_authentication_resources
                            )
                )
            return
        }
        if (!WheelPlayBootstrap.beginImport()) return
        state.value = State(busy = true)
        val context = getApplication<Application>()
        thread(name = "authentication-import") {
            val result =
                try {
                    val store = MfiAssetStore(context.noBackupFilesDir)
                    fun open(uri: Uri): java.io.InputStream {
                        require(uri.scheme == "content") { "Expected a selected local document" }
                        return requireNotNull(context.contentResolver.openInputStream(uri))
                    }
                    val disconnected = { !CarPlayBackgroundSession.hasSession() }
                    if (apk != null) store.installApk({ open(apk) }, disconnected)
                    else
                        store.installFiles(
                            { open(requireNotNull(key)) },
                            { open(requireNotNull(certificate)) },
                            disconnected,
                        )
                    getApplication<Application>()
                        .getString(
                            UiR.string
                                .ui_authentication_resources_imported_and_verified_you_can_now_connect
                        )
                } catch (_: Exception) {
                    if (CarPlayBackgroundSession.hasSession())
                        getApplication<Application>()
                            .getString(
                                UiR.string
                                    .ui_disconnect_the_iphone_before_importing_authentication_resources
                            )
                    else if (apk != null)
                        getApplication<Application>()
                            .getString(
                                UiR.string.ui_import_failed_use_an_upstream_apk_containing_identity_pk8
                            )
                    else
                        getApplication<Application>()
                            .getString(
                                UiR.string.ui_import_failed_choose_a_valid_matching_pkcs_8_private
                            )
                } finally {
                    WheelPlayBootstrap.finishImport()
                }
            state.postValue(State(result = result))
        }
    }

    fun consumeResult() {
        state.value = State()
    }
}

/** Restrict exported import entry points to local files explicitly sent by the user. */
internal object AuthenticationImportIntents {
    fun apk(intent: Intent): Uri? {
        val uri =
            try {
                when (intent.action) {
                    Intent.ACTION_SEND -> {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                            ?: intent.clipData?.getItemAt(0)?.uri
                    }
                    Intent.ACTION_VIEW -> intent.data
                    else -> null
                }
            } catch (_: RuntimeException) {
                null
            }
        return uri?.takeIf { it.scheme == "content" }
    }
}
