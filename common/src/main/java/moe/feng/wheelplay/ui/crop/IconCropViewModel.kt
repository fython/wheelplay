// SPDX-License-Identifier: AGPL-3.0-only
package moe.feng.wheelplay.ui.crop

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.shilapi.xcertplay.shared.R as UiR
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.feng.wheelplay.AirPlayPersistence

/** Retains decoding and a single save operation across Activity recreation. */
internal class IconCropViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set

    var error by mutableStateOf<Int?>(null)
        private set

    var saving by mutableStateOf(false)
        private set

    var complete by mutableStateOf(false)
        private set

    private var started = false

    var selection by
        mutableStateOf(
            savedState.get<FloatArray>("selection")?.let { IconCropSelection(it[0], it[1], it[2]) }
                ?: IconCropSelection()
        )
        private set

    fun select(update: (IconCropSelection) -> IconCropSelection) {
        if (saving) return
        val value = update(selection)
        selection = value
        savedState["selection"] = floatArrayOf(value.centerX, value.centerY, value.zoom)
    }

    fun load(uri: Uri) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                bitmap =
                    withContext(Dispatchers.IO) {
                        val source =
                            ImageDecoder.createSource(
                                getApplication<Application>().contentResolver,
                                uri,
                            )
                        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                            // ImageDecoder applies EXIF orientation before reporting/displaying the
                            // image.
                            val edge = maxOf(info.size.width, info.size.height)
                            decoder.setTargetSampleSize(
                                ((edge.toLong() + MAX_DECODE_EDGE - 1) / MAX_DECODE_EDGE).toInt()
                            )
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = UiR.string.ui_could_not_read_image_go_back_and_choose_a
            }
        }
    }

    fun save() {
        val source = bitmap ?: return
        if (saving || complete) return
        val crop = selection
        saving = true
        error = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = crop.render(source)
                    val encoded =
                        try {
                            ByteArrayOutputStream().use { bytes ->
                                check(output.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                                bytes.toByteArray()
                            }
                        } finally {
                            output.recycle()
                        }
                    AirPlayPersistence.saveCustomAirPlayIcon(getApplication(), encoded)
                }
                complete = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = UiR.string.ui_could_not_save_icon_try_again
            } finally {
                saving = false
            }
        }
    }

    // The source bitmap can still be referenced by a Compose draw frame or an IO job after
    // onCleared. Let Android reclaim it when those references disappear instead of recycling it.
    private companion object {
        const val MAX_DECODE_EDGE = 2048
    }
}
