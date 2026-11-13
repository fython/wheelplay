package moe.feng.wheelplay.plugin.carlink

import com.ucar.vehiclesdk.UCarCommon
import moe.feng.wheelplay.plugin.PluginMediaState
import org.junit.Assert.*
import org.junit.Test

class CarLinkMediaTest {
    private class Info(private val destroyed: Boolean = false) : UCarCommon.MusicInfo() {
        override fun isDestroyed() = destroyed
        override fun isPlaying() = true
        override fun getTitle() = "x".repeat(2000)
        override fun getArtistName(): String? = null
        override fun getAlbumName() = "Album"
        override fun getAppId() = 42
        override fun getTotalTimesMs() = -1L
        override fun getPlayingCurrentTimeMs() = -2
    }
    @Test fun snapshotBoundsVendorFieldsAndClearsDestroyedPlayer() {
        val value = CarLinkMedia.snapshot(Info())
        assertTrue(value.available); assertTrue(value.playing)
        assertEquals(1024, value.title.length)
        assertEquals("", value.artist)
        assertEquals("42", value.player)
        assertEquals(-1L, value.durationMs); assertEquals(-1L, value.positionMs)
        assertEquals(PluginMediaState(), CarLinkMedia.snapshot(Info(true)))
    }
    @Test fun commandsUseExplicitMediaKeysAndRejectUnknownActions() {
        assertEquals(UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PLAY, CarLinkMedia.key(PluginMediaState.PLAY))
        assertEquals(UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PAUSE, CarLinkMedia.key(PluginMediaState.PAUSE))
        assertEquals(UCarCommon.KeyCodeType.KEY_CODE_MEDIA_NEXT, CarLinkMedia.key(PluginMediaState.NEXT))
        assertEquals(UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PREVIOUS, CarLinkMedia.key(PluginMediaState.PREVIOUS))
        assertNull(CarLinkMedia.key(99))
    }
}
