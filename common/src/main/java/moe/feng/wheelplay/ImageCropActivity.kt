// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import moe.feng.wheelplay.ui.crop.IconCropScreen
import moe.feng.wheelplay.ui.crop.IconCropViewModel
import moe.feng.wheelplay.ui.theme.WheelPlayTheme

/** Loads one local image and writes a user-positioned 1:1 PNG into the AirPlay icon slot. */
class ImageCropActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri =
            intent.data
                ?: run {
                    finish()
                    return
                }
        val model = ViewModelProvider(this)[IconCropViewModel::class.java]
        model.load(uri)
        ServerWindow.showSystemBars(window)
        setContent {
            WheelPlayTheme {
                // Once Save is accepted, wait for the atomic write; Cancel never races that write.
                BackHandler(enabled = model.saving || model.complete) {}
                LaunchedEffect(model.complete) {
                    if (model.complete) {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_CROP_COMPLETE, true))
                        finish()
                    }
                }
                IconCropScreen(
                    bitmap = model.bitmap,
                    selection = model.selection,
                    error = model.error,
                    saving = model.saving || model.complete,
                    onSelectionChange = model::select,
                    onSave = model::save,
                    onCancel = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CROP_COMPLETE = "crop_complete"
    }
}
