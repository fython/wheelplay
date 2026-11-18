package moe.feng.wheelplay.projection.carlife

import android.os.Looper
import com.shilapi.xcertplay.projection.*
import moe.feng.wheelplay.carlife.CarLifeProtocolMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarLifeSourceTest {
    @Test fun admissionDoesNotStartProtocolAndStopBeforeActivationReleasesCleanly() {
        val source = CarLifeSource(RuntimeEnvironment.getApplication())
        val session = source.open().get()
        assertTrue(session is CarLifeProjectionSession)
        assertEquals(ProjectionSourceIds.CARLIFE, session.descriptor.id)
        assertEquals(ProjectionPhase.CONNECTING, session.state.phase)
        assertFalse(session.inputReady)
        assertFalse(session.touch(listOf(ProjectionContact(0, .5, .5, true))))
        val stopped = session.stop()
        session.activate()
        shadowOf(Looper.getMainLooper()).idle()
        stopped.get(3, TimeUnit.SECONDS)
        assertSame(stopped, session.stop())
        assertEquals(ProjectionPhase.STOPPED, session.state.phase)
        assertFalse(session.media!!.mediaSnapshot().available)
    }
    @Test fun localAndRemoteSessionsKeepTheirMediaSessionPolicy() {
        for (remote in listOf(false, true)) {
            val session = CarLifeProjectionSession(RuntimeEnvironment.getApplication(),
                SourceDescriptor(ProjectionSourceIds.CARLIFE, "CarLife"),
                CarLifeConnection(if (remote) "192.168.1.2" else "127.0.0.1", CarLifeProtocolMode.AUTO, true))
            assertEquals(remote, session.media.publishNativeSession)
            assertTrue(session.media.managesAudioFocus)
            val stopped = session.stop(); shadowOf(Looper.getMainLooper()).idle(); stopped.get(3, TimeUnit.SECONDS)
        }
    }
}
