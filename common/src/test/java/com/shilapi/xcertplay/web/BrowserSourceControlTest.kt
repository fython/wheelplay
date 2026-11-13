package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.projection.*
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrowserSourceControlTest {
    private class Session(val id: String) : ProjectionSession {
        override val descriptor = SourceDescriptor(id, id)
        override val capabilities = ProjectionCapabilities(emptySet())
        override val inputReady = true
        val done = CompletableFuture<Unit>()
        var stops = 0
        override fun touch(contacts: List<ProjectionContact>) = false
        override fun stop(): CompletableFuture<Unit> { stops++; return done }
    }
    @Test fun legacyPhoneConnectCannotStartSelectedPluginAsCarPlay() {
        val control = WebSession.sourceControl
        var calls = 0
        control.select(BrowserSourceControl.Selection("plugin-carlink", "CarLink"))
        control.foreground { calls++ }
        try {
            var reply = ""
            WebSession.requestPhoneConnection(org.robolectric.RuntimeEnvironment.getApplication()) { reply = it }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, calls); assertTrue(reply.isNotBlank())
            control.select(BrowserSourceControl.Selection(ProjectionSourceIds.CARPLAY, "CarPlay", notice = "Fallback"))
            WebSession.requestPhoneConnection(org.robolectric.RuntimeEnvironment.getApplication()) { reply = it }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(0, calls) // A legacy plugin page cannot use automatic selection fallback.
            control.select(BrowserSourceControl.Selection(ProjectionSourceIds.CARPLAY, "CarPlay"))
            WebSession.requestPhoneConnection(org.robolectric.RuntimeEnvironment.getApplication()) { reply = it }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, calls)
        } finally { control.foreground(null) }
    }
    @Test fun selectedPluginRoutesToItsOwnActionAndRejectsStaleSelection() {
        val runtime = ProjectionRuntime(); val control = BrowserSourceControl(runtime)
        val started = mutableListOf<String>(); val results = mutableListOf<String>()
        control.foreground { started.add(it) }
        control.select(BrowserSourceControl.Selection("plugin-carlink", "CarLink"))
        val before = control.snapshot()
        control.execute("connect", "carplay", before.getLong("revision"), 1000, results::add)
        assertEquals(listOf("stale"), results); assertTrue(started.isEmpty())
        control.execute("connect", "plugin-carlink", before.getLong("revision"), 1000, results::add)
        control.execute("connect", "plugin-carlink", before.getLong("revision"), 1001, results::add)
        assertEquals(listOf("plugin-carlink"), started); assertEquals("pending", results.last())
        control.select(BrowserSourceControl.Selection("carplay", "CarPlay"))
        control.execute("connect", "plugin-carlink", before.getLong("revision"), 5000, results::add)
        assertEquals("stale", results.last()); assertEquals(1, started.size)
    }
    @Test fun foregroundConfigurationAndMissingPluginAreExplicit() {
        val control = BrowserSourceControl(ProjectionRuntime())
        control.select(BrowserSourceControl.Selection("desktop", "Desktop", "configure"))
        assertTrue(control.snapshot().getBoolean("needsForeground")); assertEquals("", control.snapshot().getString("action"))
        control.foreground { }
        assertEquals("configure", control.snapshot().getString("action"))
        control.select(BrowserSourceControl.Selection("missing", "Missing", "unavailable"))
        assertEquals("missing", control.snapshot().getString("selectedId"))
        assertTrue(control.snapshot().getBoolean("unavailable")); assertEquals("", control.snapshot().getString("action"))
    }
    @Test fun stopTargetsActiveSourceAndCleanupFailureCannotReconnect() {
        val runtime = ProjectionRuntime(); val control = BrowserSourceControl(runtime)
        control.select(BrowserSourceControl.Selection("carplay", "CarPlay")); control.foreground { error("Must not connect") }
        val active = Session("plugin-carlink"); runtime.adopt(active, setOf(ProjectionResource.DISPLAY))
        val state = control.snapshot(); assertEquals("stop", state.getString("action"))
        val results = mutableListOf<String>()
        control.execute("connect", "carplay", state.getLong("revision"), 1000, results::add)
        assertEquals("stale", results.last())
        control.execute("stop", "plugin-carlink", state.getLong("revision"), 1000, results::add)
        assertEquals(1, active.stops); assertEquals("", control.snapshot().getString("action"))
        active.done.completeExceptionally(IllegalStateException("Still producing"))
        assertEquals("failed", results.last()); assertEquals("unconfirmed", control.snapshot().getString("cleanup"))
        assertEquals("", control.snapshot().getString("action"))
    }
    @Test fun replacementOfSameSourceInvalidatesOldStop() {
        val runtime = ProjectionRuntime(); val control = BrowserSourceControl(runtime)
        val first = Session("same"); runtime.adopt(first, emptySet()); val old = control.snapshot()
        runtime.stop(); first.done.complete(Unit)
        val replacement = Session("same"); runtime.adopt(replacement, emptySet())
        var result = ""
        control.execute("stop", "same", old.getLong("revision"), 1000) { result = it }
        assertEquals("stale", result); assertEquals(0, replacement.stops)
        assertTrue(runtime.stopIfGeneration(old.getLong("generation")).isCompletedExceptionally)
        assertEquals(0, replacement.stops)
    }
}
