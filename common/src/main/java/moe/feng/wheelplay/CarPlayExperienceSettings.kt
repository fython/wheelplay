package moe.feng.wheelplay

import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.i18n.*
import com.shilapi.xcertplay.shared.R as UiR
import kotlin.math.roundToInt

enum class CarPlayAppearance(@param:androidx.annotation.StringRes val labelRes: Int) {
    SYSTEM(UiR.string.ui_follow_android),
    DAY(UiR.string.ui_always_day),
    NIGHT(UiR.string.ui_always_night);

    fun night(systemNight: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemNight
            DAY -> false
            NIGHT -> true
        }
}

enum class CarPlayMicrophoneSource(@param:androidx.annotation.StringRes val labelRes: Int) {
    ANDROID(UiR.string.ui_android_microphone),
    BROWSER(UiR.string.ui_browser_microphone),
    DISABLED(UiR.string.ui_disable_microphone),
}

enum class CarPlayAudioOutput(@param:androidx.annotation.StringRes val labelRes: Int) {
    ANDROID(UiR.string.ui_android_device),
    BROWSER(UiR.string.ui_head_unit_browser),
}

enum class BrowserOfflinePolicy(
    @param:androidx.annotation.StringRes val labelRes: Int,
    val timeoutMillis: Long?,
) {
    KEEP(UiR.string.ui_keep_carplay_connected, null),
    ONE_MINUTE(UiR.string.ui_disconnect_iphone_after_1_minute_offline, 60_000),
    FIVE_MINUTES(UiR.string.ui_disconnect_iphone_after_5_minutes_offline, 300_000),
}

/** Percentages follow the negotiated display size, including adaptive browser resolutions. */
data class CarPlaySafeMargins(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
) {
    init {
        requireUi(listOf(top, bottom, left, right).all { it in 0..40 }) {
            UiText(UiR.string.ui_each_margin_must_be_between_0_and_40)
        }
    }

    val isFullScreen
        get() = top == 0 && bottom == 0 && left == 0 && right == 0

    fun rectangle(width: Int, height: Int): SafeAreaRect? {
        require(width > 0 && height > 0)
        if (isFullScreen) return null
        val x = (width * left / 100.0).roundToInt().coerceIn(0, width - 1)
        val y = (height * top / 100.0).roundToInt().coerceIn(0, height - 1)
        return SafeAreaRect(
            x,
            y,
            (width * (100 - right) / 100.0).roundToInt().coerceIn(x + 1, width),
            (height * (100 - bottom) / 100.0).roundToInt().coerceIn(y + 1, height),
        )
    }

    fun encode() = "$top,$bottom,$left,$right"

    companion object {
        fun decode(value: String?): CarPlaySafeMargins? =
            runCatching {
                    val parts = value?.split(',') ?: return null
                    if (parts.size != 4) return null
                    CarPlaySafeMargins(
                        parts[0].toInt(),
                        parts[1].toInt(),
                        parts[2].toInt(),
                        parts[3].toInt(),
                    )
                }
                .getOrNull()
    }
}
