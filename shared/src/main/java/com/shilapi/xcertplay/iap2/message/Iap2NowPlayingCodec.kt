package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.PhonePlaybackStatus

data class NowPlayingUpdate(
    val persistentId: Long? = null, val title: String? = null, val durationMs: Long? = null,
    val album: String? = null, val artist: String? = null, val artworkTransferId: Int? = null,
    val status: PhonePlaybackStatus? = null, val positionMs: Long? = null,
    val queueIndex: Long? = null, val appName: String? = null, val appBundleId: String? = null,
    val speed: Float? = null, val setElapsedTimeAvailable: Boolean? = null,
)

/** Field mapping: Accessory Interface Specification R32, sections 95.12.5 and 95.13. */
object Iap2NowPlayingCodec {
    const val UPDATE = 0x5001
    const val SET_INFORMATION = 0x5003

    fun subscription(): Iap2Frame = Iap2Messages.buildRaw(0x5000) {
        group(0) { listOf(0, 1, 4, 6, 12, 26).forEach(::void) }
        group(1) { listOf(0, 1, 2, 7, 12, 13, 16).forEach(::void) }
    }

    fun decode(frame: Iap2Frame): NowPlayingUpdate {
        require(frame.messageId == UPDATE)
        val body = Iap2BodyReader.of(frame)
        val item = body.optionalGroup(0)
        val playback = body.optionalGroup(1)
        return NowPlayingUpdate(
            persistentId = item?.optionalU64(0), title = item?.optionalString(1),
            durationMs = item?.optionalU32(4), album = item?.optionalString(6),
            artist = item?.optionalString(12), artworkTransferId = item?.optionalU8(26)?.also {
                if (it < 128) throw com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException("Invalid device artwork transfer identifier")
            },
            status = playback?.optionalU8(0)?.let {
                when (it) {
                    0 -> PhonePlaybackStatus.STOPPED; 1 -> PhonePlaybackStatus.PLAYING
                    2 -> PhonePlaybackStatus.PAUSED; 3 -> PhonePlaybackStatus.SEEK_FORWARD
                    4 -> PhonePlaybackStatus.SEEK_BACKWARD
                    else -> throw com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException("Invalid playback status")
                }
            },
            positionMs = playback?.optionalU32(1), queueIndex = playback?.optionalU32(2),
            appName = playback?.optionalString(7), speed = playback?.optionalU16(12)?.div(100f),
            setElapsedTimeAvailable = playback?.optionalU8(13)?.let {
                if (it !in 0..1) throw com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException("Invalid seek capability")
                it == 1
            }, appBundleId = playback?.optionalString(16),
        )
    }

    /** Raw wire value, deliberately not a millisecond API until the outbound unit is verified. */
    fun setElapsedTime(wireValue: Long): Iap2Frame = Iap2Messages.buildRaw(SET_INFORMATION) { u32(0, wireValue) }
}
