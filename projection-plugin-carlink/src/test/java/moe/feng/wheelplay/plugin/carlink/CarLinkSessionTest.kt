package moe.feng.wheelplay.plugin.carlink

import moe.feng.wheelplay.plugin.PluginContact
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class CarLinkSessionTest {
    private class Clock : SessionScheduler {
        data class Job(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val jobs = mutableListOf<Job>()
        override fun execute(action: () -> Unit) = action()
        override fun delay(ms: Long, action: () -> Unit): Cancellation {
            val job = Job(now + ms, action); jobs += job
            return Cancellation { job.cancelled = true }
        }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = jobs.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                jobs.remove(next); now = next.at; next.action()
            }
            now = target
        }
    }
    private class Engine : CarLinkEngine {
        lateinit var listener: CarLinkEngine.Listener
        var advertisements = 0
        var casts = 0
        var acceptCast = true
        var stops = 0
        val stopped = CompletableFuture<Unit>()
        val touches = mutableListOf<TouchEvent>()
        override fun initialize(config: CarLinkConfig, listener: CarLinkEngine.Listener) { this.listener = listener }
        override fun advertise(): Boolean { advertisements++; return true }
        override fun refreshAdvertise(): Boolean { advertisements++; return true }
        override fun startCast(): Boolean { casts++; return acceptCast }
        override fun touch(event: TouchEvent): Boolean { touches += event; return true }
        override fun stop(): CompletableFuture<Unit> { stops++; return stopped }
    }
    private class Fixture(connection: CarLinkConnection = CarLinkConnection.P2P) {
        val clock = Clock()
        val engine = Engine()
        val statuses = mutableListOf<Triple<String, String?, Boolean>>()
        val errors = mutableListOf<String>()
        var hostUiRequests = 0
        val session = CarLinkSession(engine, clock, CarLinkConfig(), { a,b,c -> statuses += Triple(a,b,c) }, { errors += it }, connection, requestHostUi = { hostUiRequests++ })
        init { session.start() }
        fun ready() { engine.listener.initialized(); engine.listener.connection(2, -1); engine.listener.connection(4, -1); engine.listener.surfaceReady() }
    }
    @Test fun goToDesktopOpensHostControlsOnlyDuringReadySessionWithoutStopping() {
        val f = Fixture()
        f.engine.listener.connection(6, -1)
        assertEquals(0, f.hostUiRequests)
        f.ready()
        f.engine.listener.connection(6, -1)
        assertEquals(1, f.hostUiRequests)
        assertEquals(CarLinkPhase.READY, f.session.phase)
        assertEquals(0, f.engine.stops)
        f.engine.listener.connection(7, -1)
        assertEquals(1, f.hostUiRequests)
        f.session.stop()
        f.engine.listener.connection(6, -1)
        assertEquals(1, f.hostUiRequests)
    }

    @Test fun phasesFollowUsbAuthorizationConfirmationAndActualVideo() {
        val f = Fixture(CarLinkConnection.USB)
        assertEquals(CarLinkPhase.INITIALIZING, f.session.phase)
        f.engine.listener.initialized()
        assertEquals(CarLinkPhase.WAITING_DEVICE, f.session.phase)
        f.engine.listener.usbPermissionWaiting(true)
        assertEquals(CarLinkPhase.USB_PERMISSION, f.session.phase)
        f.engine.listener.usbPermissionWaiting(false)
        assertEquals(CarLinkPhase.PHONE_CONFIRMATION, f.session.phase)
        f.engine.listener.connection(2, 0)
        assertEquals(CarLinkPhase.WAITING_VIDEO, f.session.phase)
        f.engine.listener.connection(4, 0)
        assertEquals(CarLinkPhase.WAITING_VIDEO, f.session.phase)
        f.engine.listener.surfaceReady()
        assertEquals(CarLinkPhase.READY, f.session.phase)
        f.engine.listener.usbPermissionWaiting(true)
        assertEquals(CarLinkPhase.READY, f.session.phase)
    }
    @Test fun errorCodeAndFailedPhaseSurviveStopForRecoveryUi() {
        val f = Fixture(CarLinkConnection.USB)
        f.engine.listener.connection(3, 10007)
        assertEquals(CarLinkPhase.FAILED, f.session.phase)
        assertEquals(10007, f.session.errorCode)
        f.session.stop()
        assertEquals(CarLinkPhase.FAILED, f.session.phase)
    }
    @Test fun usbWaitsForCableWithoutWirelessRefreshAndStillTimesOut() {
        val f = Fixture(CarLinkConnection.USB)
        f.engine.listener.initialized()
        assertTrue(f.statuses.last().first.contains("USB"))
        f.clock.advance(30_000)
        assertEquals(1, f.engine.advertisements)
        f.clock.advance(25_000)
        assertTrue(f.errors.single().contains("超时"))
    }
    @Test fun usbDoesNotRetryWirelessErrors() {
        val f = Fixture(CarLinkConnection.USB)
        f.engine.listener.initialized()
        f.engine.listener.connection(3, 10006)
        f.clock.advance(5_000)
        assertEquals(1, f.engine.advertisements)
        assertEquals(1, f.errors.size)
    }
    @Test fun usbReportsSenderConfirmationWithoutClaimingVideoReady() {
        val f = Fixture(CarLinkConnection.USB)
        f.engine.listener.initialized()
        f.engine.listener.progress(20)
        assertTrue(f.statuses.last().first.contains("发送手机"))
        assertFalse(f.statuses.last().third)
        f.engine.listener.connection(3, 10007)
        assertTrue(f.errors.single().contains("发送端响应超时"))
        assertTrue(f.errors.single().contains("10007"))
    }
    @Test fun softApKeepsWirelessPairingRefresh() {
        val f = Fixture(CarLinkConnection.SOFT_AP)
        f.engine.listener.initialized()
        f.clock.advance(30_000)
        assertEquals(2, f.engine.advertisements)
        assertTrue(f.errors.isEmpty())
    }
    @Test fun deviceConnectedAndCastAcceptedAreNotReady() {
        val f = Fixture()
        f.engine.listener.initialized(); f.engine.listener.connection(2, -1); f.engine.listener.connection(4, -1)
        assertFalse(f.statuses.any { it.third })
        f.engine.listener.surfaceReady()
        assertTrue(f.statuses.last().third)
        f.clock.advance(60_000)
        assertTrue(f.errors.isEmpty())
        assertEquals(1, f.engine.advertisements)
    }
    @Test fun stateUsesSecondParameterNotErrorCode() {
        val f = Fixture()
        f.engine.listener.connection(3, 10007)
        assertEquals(listOf("CarLink 连接失败（10007），请检查连接方式和手机端提示后重试"), f.errors)
        assertEquals(0, f.engine.casts)
    }
    @Test fun connectedArrivingAfterFirstFramePreservesReady() {
        val f = Fixture()
        f.engine.listener.initialized()
        f.engine.listener.connection(4, -1)
        f.engine.listener.surfaceReady()
        val readyIndex = f.statuses.lastIndex
        f.engine.listener.connection(2, -1)
        assertTrue(f.statuses.drop(readyIndex).all { it.third })
        assertEquals(1, f.engine.casts)
        f.clock.advance(60_000)
        assertTrue(f.errors.isEmpty())
    }
    @Test fun pairingResetDoesNotExtendOverallHostDeadline() {
        val f = Fixture()
        f.engine.listener.initialized(); f.clock.advance(20_000)
        f.engine.listener.pairing("123456", "Test")
        f.clock.advance(29_999)
        assertEquals(1, f.engine.advertisements)
        f.clock.advance(1)
        assertEquals(2, f.engine.advertisements)
        f.clock.advance(5_000)
        assertTrue(f.errors.single().contains("超时"))
        assertNull(f.statuses.last().second)
    }
    @Test fun wirelessFailuresBackOffAndHaveBudget() {
        val f = Fixture(); f.engine.listener.initialized()
        for (attempt in 1..3) {
            f.engine.listener.connection(3, 10006)
            f.clock.advance(CarLinkSession.retryDelay(attempt) - 1)
            assertEquals(attempt, f.engine.advertisements)
            f.clock.advance(1)
            assertEquals(attempt + 1, f.engine.advertisements)
        }
        f.engine.listener.connection(3, 10006)
        assertEquals(1, f.errors.size)
        assertEquals(8_000, CarLinkSession.retryDelay(8))
    }
    @Test fun surfaceRejectionRetriesOnlyTwentyTimes() {
        val f = Fixture(); f.engine.acceptCast = false
        f.engine.listener.connection(2, -1); f.clock.advance(10_000)
        assertEquals(20, f.engine.casts)
        assertTrue(f.errors.single().contains("Surface"))
    }
    @Test fun stopDiscardsLateCallbacksAndDoesNotInventCleanupSuccess() {
        val f = Fixture(); f.ready()
        f.session.contacts(listOf(PluginContact(9, .5, .5, true)))
        val future = f.session.stop()
        assertFalse(future.isDone)
        assertSame(future, f.session.stop())
        assertEquals(1, f.engine.stops)
        assertEquals(1, f.engine.touches.last().action)
        val statusCount = f.statuses.size
        f.engine.listener.pairing("999999", "Late"); f.engine.listener.connection(4, -1); f.engine.listener.surfaceReady()
        f.clock.advance(90_000)
        assertEquals(statusCount, f.statuses.size)
        f.engine.stopped.completeExceptionally(IllegalStateException("P2P still active"))
        assertTrue(future.isCompletedExceptionally)
    }
    @Test fun backgroundNotificationsPreserveReadyButDisconnectEndsSession() {
        val f = Fixture(); f.ready()
        f.engine.listener.connection(6, -1); f.engine.listener.connection(7, -1)
        assertTrue(f.errors.isEmpty())
        f.engine.listener.connection(1, 11)
        assertEquals(listOf("发送手机已断开，请从 WheelPlay 重新连接"), f.errors)
    }
    @Test fun noInputBeforeRenderedFrame() {
        val f = Fixture(); f.engine.listener.connection(4, -1)
        f.session.contacts(listOf(PluginContact(1, 0.0, 0.0, true)))
        assertTrue(f.engine.touches.isEmpty())
    }
    @Test fun restartRequiresNewSession() {
        val f = Fixture(); f.session.stop()
        assertThrows(IllegalStateException::class.java) { f.session.start() }
    }
}
