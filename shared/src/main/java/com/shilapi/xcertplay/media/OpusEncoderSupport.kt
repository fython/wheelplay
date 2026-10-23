package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * Advertise Opus only when the platform lists an encoder. Otherwise the phone is offered PCM input
 * formats during negotiation, without opening the microphone or changing the audio mode.
 */
object OpusEncoderSupport {
    fun isAvailable(): Boolean = isAvailable(
        platformAvailable = ::platformAvailable,
    )

    internal fun isAvailable(
        platformAvailable: () -> Boolean,
    ): Boolean = runCatching(platformAvailable).getOrDefault(false)

    private fun platformAvailable(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }
}
