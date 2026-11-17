package moe.feng.wheelplay.carlife

import android.os.Looper
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.web.BrowserMediaBridge
import moe.feng.wheelplay.projection.carlife.CarLifeMediaSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NativeCarLifeMediaTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun song(playing: Boolean = true) = CarLifeMedia.Snapshot(true, playing,
        "歌曲", "歌手", "专辑", 120000, 1000, ByteArray(0))

    @Test fun nativeLocalSourcePublishesBrowserMetadataAndDispatchesProtocolKeys() {
        val commands = mutableListOf<Pair<Long, Int>>()
        val source = CarLifeMediaSource({ true }, { id, key -> commands.add(id to key); true }, publishNativeSession = false)
        val messages = mutableListOf<JSONObject>()
        val bridge = BrowserMediaBridge({ messages += JSONObject(it) }, { source }, { AutoCloseable {} },
            dispatch = { current, generation, command -> current.sendMediaCommand(command, generation) })
        try {
            bridge.start(); idle(); source.update(song()); idle()
            assertFalse(source.publishNativeSession)
            assertEquals("歌曲", messages.last().getString("title"))
            assertEquals("playing", messages.last().getString("status"))
            assertFalse(messages.last().getBoolean("canSeek"))
            for ((command, key) in listOf(MediaCommand.PLAY to 31, MediaCommand.PAUSE to 32,
                MediaCommand.NEXT to 16, MediaCommand.PREVIOUS to 15)) {
                val result = source.sendMediaCommand(command, 1); idle()
                assertEquals(key, commands.last().second)
                source.result(commands.last().first, true)
                assertEquals(MediaCommandResult.SENT, result.get())
            }
            assertTrue(source.mediaSnapshot().playing) // A transport acknowledgement is not a playback update.
            source.update(song(false)); idle()
            assertEquals("paused", messages.last().getString("status"))
            source.reset(); idle()
            assertFalse(messages.last().getBoolean("available"))
            assertEquals(MediaCommandResult.UNAVAILABLE, source.sendMediaCommand(MediaCommand.PLAY, 1).also { idle() }.get())
        } finally { bridge.close(); source.close(); idle() }
    }

    @Test fun recoveryAndCloseInvalidatePendingCommandsAndLateArtwork() {
        val source = CarLifeMediaSource({ true }, { _, _ -> true }, publishNativeSession = true)
        source.update(song()); assertTrue(source.publishNativeSession)
        val pending = source.sendMediaCommand(MediaCommand.NEXT, 1); idle()
        source.reset(); assertEquals(MediaCommandResult.UNAVAILABLE, pending.get())
        source.result(1, true); assertFalse(source.mediaSnapshot().available)
        source.update(song())
        val timedOut = source.sendMediaCommand(MediaCommand.NEXT, 1); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(MediaCommandResult.FAILED, timedOut.get())
        val stopped = source.sendMediaCommand(MediaCommand.PAUSE, 1); idle()
        source.close(); assertEquals(MediaCommandResult.UNAVAILABLE, stopped.get())
        source.update(song()); assertFalse(source.mediaSnapshot().available)
    }
}
