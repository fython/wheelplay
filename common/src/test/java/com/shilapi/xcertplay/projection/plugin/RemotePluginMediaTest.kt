package com.shilapi.xcertplay.projection.plugin

import android.os.Looper
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.web.BrowserMediaBridge
import moe.feng.wheelplay.plugin.PluginMediaState
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
class RemotePluginMediaTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun song(playing: Boolean = true) = PluginMediaState(true, playing, "Song", "Artist", "Album", "42", 120000, 1000)

    @Test fun browserGetsMetadataPhoneStateAndNoUnverifiedSeek() {
        val commands = mutableListOf<Pair<Long, Int>>()
        val source = RemotePluginMedia({ true }, { id, command -> commands.add(id to command); true })
        val messages = mutableListOf<JSONObject>()
        val bridge = BrowserMediaBridge({ messages += JSONObject(it) }, { source }, { AutoCloseable {} },
            dispatch = { current, generation, command -> current.sendMediaCommand(command, generation) })
        try {
            bridge.start(); idle()
            source.update(song()); idle()
            val wire = messages.last()
            assertTrue(wire.getBoolean("available"))
            assertEquals("Song", wire.getString("title"))
            assertEquals("playing", wire.getString("status"))
            assertFalse(wire.getBoolean("canSeek"))
            val revision = source.mediaSnapshot().trackRevision
            val result = source.sendMediaCommand(MediaCommand.PAUSE, 1); idle()
            assertEquals(PluginMediaState.PAUSE, commands.single().second)
            source.result(commands.single().first, true)
            assertEquals(MediaCommandResult.SENT, result.get())
            assertTrue(source.mediaSnapshot().playing)
            source.update(song(false)); idle()
            assertEquals(revision, source.mediaSnapshot().trackRevision)
            assertEquals("paused", messages.last().getString("status"))
            source.update(song().copy(title = "Next")); idle()
            assertTrue(source.mediaSnapshot().trackRevision > revision)
            source.update(PluginMediaState()); idle()
            assertFalse(messages.last().getBoolean("available"))
        } finally { bridge.close(); source.close(); idle() }
    }

    @Test fun commandsRejectWrongGenerationTimeoutAndStopWithoutOptimisticState() {
        var active = true
        val commands = mutableListOf<Pair<Long, Int>>()
        val source = RemotePluginMedia({ active }, { id, command -> commands.add(id to command); true })
        source.update(song())
        fun send(generation: Long = 1) = source.sendMediaCommand(MediaCommand.NEXT, generation).also { idle() }
        assertEquals(MediaCommandResult.UNAVAILABLE, send(99).get())
        val pending = send()
        assertEquals(MediaCommandResult.SUPERSEDED, send().get())
        source.result(commands.last().first + 1, true)
        assertFalse(pending.isDone)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(MediaCommandResult.FAILED, pending.get())
        active = false
        assertEquals(MediaCommandResult.UNAVAILABLE, send().get())
        active = true
        val stopped = send()
        source.close()
        assertEquals(MediaCommandResult.UNAVAILABLE, stopped.get())
        source.update(song())
        assertFalse(source.mediaSnapshot().available)
        assertEquals(MediaCommandResult.UNAVAILABLE, send().get())
        assertEquals(MediaCommandResult.UNSUPPORTED, source.seekTo(1000, 1, 1).get())
    }
}
