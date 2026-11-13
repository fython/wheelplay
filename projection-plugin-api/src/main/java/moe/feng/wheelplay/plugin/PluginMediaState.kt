package moe.feng.wheelplay.plugin

import android.os.Bundle

/** Complete, bounded snapshot. Position/duration are milliseconds, -1 means unknown. No artwork URLs. */
data class PluginMediaState(
    val available: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val player: String = "",
    val durationMs: Long = -1,
    val positionMs: Long = -1,
) {
    init {
        require(listOf(title, artist, album, player).all { it.length <= 1024 })
        require(durationMs in -1..MAX_TIME && positionMs in -1..MAX_TIME)
    }
    fun toBundle() = Bundle().apply {
        putBoolean("available", available); putBoolean("playing", playing)
        putString("title", title); putString("artist", artist); putString("album", album); putString("player", player)
        putLong("durationMs", durationMs); putLong("positionMs", positionMs)
    }
    companion object {
        const val MAX_TIME = 365L * 24 * 60 * 60 * 1000
        const val PLAY = 1
        const val PAUSE = 2
        const val NEXT = 3
        const val PREVIOUS = 4
        fun validCommand(value: Int) = value in PLAY..PREVIOUS
        fun fromBundle(value: Bundle) = PluginMediaState(value.getBoolean("available"), value.getBoolean("playing"),
            value.getString("title").orEmpty(), value.getString("artist").orEmpty(),
            value.getString("album").orEmpty(), value.getString("player").orEmpty(),
            value.getLong("durationMs", -1), value.getLong("positionMs", -1))
    }
}
