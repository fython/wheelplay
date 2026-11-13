package moe.feng.wheelplay.plugin.carlink

import com.ucar.vehiclesdk.UCarCommon
import moe.feng.wheelplay.plugin.PluginMediaState

internal object CarLinkMedia {
    fun snapshot(info: UCarCommon.MusicInfo): PluginMediaState {
        if (info.isDestroyed) return PluginMediaState()
        return PluginMediaState(true, info.isPlaying, info.title.orEmpty().take(1024),
            info.artistName.orEmpty().take(1024), info.albumName.orEmpty().take(1024), info.appId.toString(),
            info.totalTimesMs.takeIf { it in 1..PluginMediaState.MAX_TIME } ?: -1,
            info.playingCurrentTimeMs.toLong().takeIf { it >= 0 } ?: -1)
    }
    fun key(command: Int): UCarCommon.KeyCodeType? = when (command) {
        PluginMediaState.PLAY -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PLAY
        PluginMediaState.PAUSE -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PAUSE
        PluginMediaState.NEXT -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA_NEXT
        PluginMediaState.PREVIOUS -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PREVIOUS
        else -> null
    }
}
