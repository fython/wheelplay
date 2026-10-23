package com.shilapi.xcertplay.projection

import com.shilapi.xcertplay.web.WebListenSettings
import com.shilapi.xcertplay.web.WebSession
import java.util.concurrent.CompletableFuture
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectionRoutingTest {
    private class Source(val name: String, val touches: Int, val desktop: Boolean = false) : ProjectionSession {
        override val descriptor = SourceDescriptor(name, name)
        override val capabilities = ProjectionCapabilities(setOf(VideoIngressKind.FRAMEBUFFER), touches, pointerInput = desktop, keyboardInput = desktop)
        override val inputReady = touches > 0
        val inputs = mutableListOf<ProjectionInput>()
        override fun input(event: ProjectionInput): Boolean { inputs += event; return true }
        val reports = mutableListOf<List<ProjectionContact>>()
        override fun touch(contacts: List<ProjectionContact>): Boolean { reports += contacts; return true }
        override fun stop() = CompletableFuture.completedFuture(Unit)
    }
    @After fun cleanup() { projectionSessions.stop().join(); WebSession.stop() }

    @Test fun oldVideoAndInputCannotCrossSourceSwitchIncludingEmptyReleases() {
        val app = RuntimeEnvironment.getApplication()
        WebListenSettings.saveHttpsEnabled(app, false)
        WebSession.start(app)
        val old = Source("first", 2)
        projectionSessions.adopt(old, emptySet())
        val first = WebSession.beginVideo(320, 180)
        WebSession.setVideoActive(first, true)
        val oldVersion = WebSession.mediaSnapshot()
        val client = Any()
        assertTrue(WebSession.touch.claim(client))
        val contact = listOf(ProjectionContact(0, .25, .75, true))
        assertTrue(WebSession.mediaTouch(client, oldVersion.sessionId, first, contact, 1))

        projectionSessions.stop().join()
        val next = Source("second", 5)
        projectionSessions.adopt(next, emptySet())
        val second = WebSession.beginVideo(640, 480)
        WebSession.setVideoActive(second, true)
        val version = WebSession.mediaSnapshot()
        assertEquals("second", version.sourceId)
        assertEquals(5, version.maxTouchContacts)
        assertFalse(WebSession.touch.touch(client, contact, 2)) // Old browser lease was revoked.
        assertTrue(WebSession.touch.claim(client))
        val count = next.reports.size
        assertFalse(WebSession.mediaTouch(client, oldVersion.sessionId, first, contact, 3))
        assertFalse(WebSession.mediaTouch(client, oldVersion.sessionId, first, emptyList(), 4))
        assertEquals(count, next.reports.size)
        assertTrue(WebSession.mediaTouch(client, version.sessionId, second, contact, 5))
        WebSession.publish(first, byteArrayOf(9))
        WebSession.setVideoActive(first, false)
        WebSession.endVideo(first)
        assertEquals(second, WebSession.mediaSnapshot().videoEpoch)
        assertEquals("ready", WebSession.mediaSnapshot().sourceState)
        assertNull(WebSession.frame)
        WebSession.publish(second, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), WebSession.frame!!.jpeg)
        assertTrue(old.reports.last().isEmpty())
    }
    @Test fun readOnlySourceAdvertisesZeroTouchesWithoutHidingVideo() {
        val app = RuntimeEnvironment.getApplication()
        WebListenSettings.saveHttpsEnabled(app, false)
        WebSession.start(app)
        projectionSessions.adopt(Source("read-only", 0), emptySet())
        val epoch = WebSession.beginVideo(320, 180)
        WebSession.setVideoActive(epoch, true)
        val status = WebSession.mediaSnapshot()
        assertEquals("ready", status.sourceState)
        assertEquals(0, status.maxTouchContacts)
        assertFalse(status.inputReady)
        val viewer = Any()
        WebSession.audio.attach(viewer, true, { _, _ -> }, {})
        try {
            WebSession.audio.ready(viewer, playback = true, capture = true)
            assertFalse(WebSession.audio.playbackReady)
            assertFalse(WebSession.audio.microphoneReady)
            WebSession.audio.createRoute().use { assertFalse(it.microphoneEnabled) }
        } finally { WebSession.audio.detach(viewer) }
    }
    @Test fun desktopInputChecksLeaseBoundsCapabilitiesAndVideoEpoch() {
        val app = RuntimeEnvironment.getApplication()
        WebListenSettings.saveHttpsEnabled(app, false); WebSession.start(app)
        val source = Source("desktop", 1, true)
        projectionSessions.adopt(source, emptySet())
        val epoch = WebSession.beginVideo(320,180); WebSession.setVideoActive(epoch,true)
        val version = WebSession.mediaSnapshot()
        assertTrue(version.pointerInput); assertTrue(version.keyboardInput)
        val owner = Any(); val stranger = Any(); WebSession.touch.claim(owner)
        val key = ProjectionInput.Key("a",true)
        assertFalse(WebSession.mediaInput(stranger,version.sessionId,epoch,key,0))
        assertTrue(WebSession.mediaInput(owner,version.sessionId,epoch,key,0))
        assertFalse(WebSession.mediaInput(owner,version.sessionId,epoch,ProjectionInput.Pointer(Double.NaN,0.0,0),0))
        assertFalse(WebSession.mediaInput(owner,version.sessionId,epoch,ProjectionInput.Scroll(0.0,0.0,100,0),0))
        WebSession.touch.expire(1600)
        assertEquals(ProjectionInput.Release,source.inputs.last())
        WebSession.mediaInput(owner,version.sessionId,epoch,key,1700)
        val resized = WebSession.beginVideo(640,480); WebSession.setVideoActive(resized,true)
        assertEquals(ProjectionInput.Release,source.inputs.last())
        WebSession.touch.claim(owner)
        val count = source.inputs.size
        assertFalse(WebSession.mediaInput(owner,version.sessionId,epoch,key,1800))
        assertFalse(WebSession.mediaInput(owner,version.sessionId,epoch,ProjectionInput.Release,1800))
        assertEquals(count,source.inputs.size)
        projectionSessions.stop().join()
        val car = Source("car",2)
        projectionSessions.adopt(car,emptySet())
        assertFalse(projectionSessions.input(car,key))
    }

}
