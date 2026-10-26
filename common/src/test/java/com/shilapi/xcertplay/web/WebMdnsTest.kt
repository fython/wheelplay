package com.shilapi.xcertplay.web

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.web.LanAddresses.Entry
import com.shilapi.xcertplay.web.LanAddresses.Kind
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager.ShadowMulticastLock
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [WebMdnsTest.TrackingMulticastLock::class])
class WebMdnsTest {
    @Implements(WifiManager.MulticastLock::class)
    class TrackingMulticastLock : ShadowMulticastLock() {
        @Implementation public override fun acquire() {
            acquisitions++
            super.acquire()
            if (failAcquire) throw SecurityException("acquire failed after taking lock")
        }

        @Implementation public override fun release() {
            assertTrue("Never release an unheld lock", super.isHeld())
            releases++
            super.release()
        }

        companion object {
            var acquisitions = 0
            var releases = 0
            var failAcquire = false
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val wifi get() = context.getSystemService(WifiManager::class.java)
    private val fixtures = mutableListOf<Fixture>()
    private val wlan = Entry("wlan0", "192.168.1.2", Kind.WIFI)
    private val ethernet = Entry("eth0", "192.168.2.2", Kind.ETHERNET)

    @Before fun setUp() {
        TrackingMulticastLock.acquisitions = 0
        TrackingMulticastLock.releases = 0
        TrackingMulticastLock.failAcquire = false
        context.getSharedPreferences("web_listen", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        fixtures.forEach { it.close() }
        assertEquals(TrackingMulticastLock.acquisitions, TrackingMulticastLock.releases)
    }

    private inner class Fixture(var entries: List<Entry> = listOf(wlan)) {
        val responders = mutableListOf<FakeResponder>()
        var failCreate = false
        var failRegister = false
        var failClose = false
        var wifiAvailable = true
        var wifiRequests = 0
        var beforeCreate: () -> Unit = {}
        var beforeRegister: () -> Unit = {}
        val mdns = WebMdns(
            context,
            wifiManager = { wifiRequests++; if (wifiAvailable) wifi else null },
            discover = { entries },
            createResponder = { address, hostname ->
                beforeCreate()
                if (failCreate) throw IOException("create failed")
                FakeResponder(address.hostAddress!!, hostname, failRegister, failClose)
                    .also { it.onRegister = { beforeRegister() }; responders.add(it) }
            },
            autoRefresh = false,
        )

        init { fixtures.add(this) }

        fun refresh() { mdns.refresh().get(5, TimeUnit.SECONDS) }
        fun close() {
            mdns.close()
            assertTrue("mDNS worker must finish cleanup", mdns.awaitClosed(5, TimeUnit.SECONDS))
        }
    }

    private class FakeResponder(
        val address: String,
        val hostname: String,
        val failRegister: Boolean,
        val failClose: Boolean,
    ) : WebMdns.Responder {
        var port = 0
        var registerCount = 0
        var closeCount = 0
        var registerThread: Thread? = null
        var closeThread: Thread? = null
        var onRegister: () -> Unit = {}
        var onClose: () -> Unit = {}
        override fun register(port: Int) {
            registerThread = Thread.currentThread()
            registerCount++
            onRegister()
            this.port = port
            if (failRegister) throw IOException("register failed")
        }
        override fun close() {
            closeThread = Thread.currentThread()
            closeCount++
            onClose()
            if (failClose) throw IOException("close failed")
        }
    }

    @Test fun webOnlyAcquiresBeforeCreatingDnsAndReleasesAfterClosingIt() {
        val f = Fixture()
        f.beforeCreate = {
            assertEquals(1, TrackingMulticastLock.acquisitions)
            assertEquals(0, TrackingMulticastLock.releases)
        }
        f.refresh()
        val responder = f.responders.single()
        assertEquals("wheelplay", responder.hostname)
        assertEquals(WebSession.httpPort, responder.port)
        responder.onClose = { assertEquals(0, TrackingMulticastLock.releases) }
        f.close()
        f.close()
        f.refresh()
        assertEquals(1, responder.closeCount)
        assertEquals(1, TrackingMulticastLock.releases)
        assertEquals(1, f.wifiRequests)
    }

    @Test fun repeatedRefreshAndMultipleWifiAddressesShareOneLock() {
        val f = Fixture(listOf(wlan, wlan.copy(address = "192.168.1.3"),
            Entry("ap0", "192.168.43.1", Kind.HOTSPOT)))
        repeat(3) { f.refresh() }
        assertEquals(3, f.responders.size)
        assertEquals(1, TrackingMulticastLock.acquisitions)
        f.entries = f.entries.drop(1)
        f.refresh()
        assertEquals(1, f.responders.first().closeCount)
        assertEquals(0, TrackingMulticastLock.releases)
        f.close()
        assertTrue(f.responders.all { it.closeCount == 1 })
        assertEquals(1, TrackingMulticastLock.releases)
    }

    @Test fun ethernetDoesNotNeedWifiManagerAndExcludedNetworksAreNotPublished() {
        val f = Fixture(listOf(ethernet) + listOf(Kind.VPN, Kind.P2P, Kind.CELLULAR, Kind.TESLA_HTTP)
            .map { Entry(it.name, "10.0.0.1", it) })
        f.wifiAvailable = false
        f.refresh()
        assertEquals(ethernet.address, f.responders.single().address)
        assertEquals(0, f.wifiRequests)
        assertEquals(0, TrackingMulticastLock.acquisitions)
    }

    @Test fun wifiLossReleasesLockWhileEthernetContinuesAndWifiCanReturn() {
        val f = Fixture(listOf(wlan, ethernet))
        f.refresh()
        val wired = f.responders.last()
        f.entries = listOf(ethernet)
        f.refresh()
        assertEquals(1, TrackingMulticastLock.releases)
        assertEquals(0, wired.closeCount)
        f.entries = listOf(wlan, ethernet)
        f.refresh()
        assertEquals(2, TrackingMulticastLock.acquisitions)
        assertEquals(3, f.responders.size)
        f.entries = emptyList()
        f.refresh()
        assertEquals(2, TrackingMulticastLock.releases)
    }

    @Test fun addressHostnameAndPortChangesReplaceRespondersWithoutLeakingLocks() {
        val f = Fixture()
        f.refresh()
        f.entries = listOf(wlan.copy(address = "192.168.3.2"))
        f.refresh()
        WebListenSettings.saveMdnsHostname(context, "new-name.local")
        f.refresh()
        val oldPort = WebSession.httpPort
        try {
            ReflectionHelpers.setStaticField(WebSession::class.java, "httpPort", oldPort + 1)
            f.refresh()
            assertEquals(oldPort + 1, f.responders.last().port)
        } finally { ReflectionHelpers.setStaticField(WebSession::class.java, "httpPort", oldPort) }
        assertEquals("new-name", f.responders.last().hostname)
        assertEquals(4, f.responders.size)
        assertTrue(f.responders.dropLast(1).all { it.closeCount == 1 })
        assertEquals(4, TrackingMulticastLock.acquisitions)
        assertEquals(3, TrackingMulticastLock.releases)
    }

    @Test fun creationFailureReleasesLockAndNextRefreshRetries() {
        val f = Fixture()
        f.failCreate = true
        f.refresh()
        assertEquals(1, TrackingMulticastLock.releases)
        assertTrue(f.responders.isEmpty())
        f.failCreate = false
        f.refresh()
        assertEquals(2, TrackingMulticastLock.acquisitions)
        assertEquals(1, f.responders.size)
    }

    @Test fun changedTransportOnSameAddressReevaluatesLockOwnership() {
        val f = Fixture()
        f.refresh()
        f.entries = listOf(wlan.copy(kind = Kind.ETHERNET))
        f.refresh()
        assertEquals(1, TrackingMulticastLock.releases)
        f.entries = listOf(wlan)
        f.refresh()
        assertEquals(2, TrackingMulticastLock.acquisitions)
        assertEquals(3, f.responders.size)
    }

    @Test fun registrationAndCleanupFailuresStillReleaseLock() {
        val f = Fixture()
        f.failRegister = true
        f.failClose = true
        f.refresh()
        assertEquals(1, f.responders.single().closeCount)
        assertEquals(1, TrackingMulticastLock.releases)
        f.close()
        assertEquals(1, f.responders.single().closeCount)
    }

    @Test fun responderCloseFailureDoesNotPreventOtherCleanup() {
        val f = Fixture(listOf(wlan, ethernet))
        f.failClose = true
        f.refresh()
        f.close()
        assertTrue(f.responders.all { it.closeCount == 1 })
        assertEquals(1, TrackingMulticastLock.releases)
    }

    @Test fun failedAdditionalWifiPublicationDoesNotReleaseExistingLock() {
        val f = Fixture()
        f.refresh()
        f.entries = listOf(wlan, wlan.copy(address = "192.168.1.3"))
        f.failRegister = true
        f.refresh()
        assertEquals(1, f.responders.last().closeCount)
        assertEquals(0, f.responders.first().closeCount)
        assertEquals(1, TrackingMulticastLock.acquisitions)
        assertEquals(0, TrackingMulticastLock.releases)
    }

    @Test fun unavailableWifiManagerSkipsWifiButStillPublishesEthernetAndRetries() {
        val f = Fixture(listOf(wlan, ethernet))
        f.wifiAvailable = false
        f.refresh()
        assertEquals(ethernet.address, f.responders.single().address)
        f.wifiAvailable = true
        f.refresh()
        assertEquals(wlan.address, f.responders.last().address)
        assertEquals(1, TrackingMulticastLock.acquisitions)
    }

    @Test fun acquisitionFailureCleansUpPartiallyAcquiredLockAndRetries() {
        val f = Fixture()
        TrackingMulticastLock.failAcquire = true
        f.refresh()
        assertTrue(f.responders.isEmpty())
        assertEquals(1, TrackingMulticastLock.releases)
        TrackingMulticastLock.failAcquire = false
        f.refresh()
        assertEquals(1, f.responders.size)
        assertEquals(2, TrackingMulticastLock.acquisitions)
    }

    @Test fun rapidRestartOwnsSeparateLocksAndCannotReviveClosedInstance() {
        val first = Fixture()
        first.refresh()
        val second = Fixture()
        second.refresh()
        first.close()
        repeat(3) { first.refresh(); second.refresh() }
        assertEquals(2, TrackingMulticastLock.acquisitions)
        assertEquals(1, TrackingMulticastLock.releases)
        assertEquals(0, second.responders.single().closeCount)
        second.close()
        assertEquals(2, TrackingMulticastLock.releases)
    }

    @Test fun closingWebMdnsLeavesAnIndependentCarPlayLockHeld() {
        val carPlay = wifi.createMulticastLock("carplay-bonjour")
        carPlay.acquire()
        try {
            val f = Fixture()
            f.refresh()
            f.close()
            assertTrue(carPlay.isHeld)
            assertEquals(2, TrackingMulticastLock.acquisitions)
            assertEquals(1, TrackingMulticastLock.releases)
        } finally { carPlay.release() }
    }

    private fun assertReturnsPromptly(action: () -> Unit) {
        val start = System.nanoTime()
        action()
        assertTrue("Caller must not wait for network work",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
    }

    @Test fun closeReturnsDuringCreationAndDoesNotRegisterTheLateResponder() {
        val f = Fixture()
        val creating = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.beforeCreate = {
            creating.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        val refresh = f.mdns.refresh()
        try {
            assertTrue(creating.await(5, TimeUnit.SECONDS))
            assertReturnsPromptly { f.mdns.close() }
            assertFalse(f.mdns.awaitClosed(10, TimeUnit.MILLISECONDS))
        } finally {
            proceed.countDown()
            refresh.get(5, TimeUnit.SECONDS)
            f.close()
        }
        f.refresh()
        assertEquals(0, f.responders.single().registerCount)
        assertEquals(1, f.responders.single().closeCount)
        assertEquals(1, TrackingMulticastLock.acquisitions)
        assertEquals(1, TrackingMulticastLock.releases)
    }

    @Test fun closeDuringRegistrationDrainsOnlyItsOwnResponderAndSkipsQueuedRefreshes() {
        val f = Fixture()
        val registering = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.beforeRegister = {
            registering.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        val refresh = f.mdns.refresh()
        var queued: java.util.concurrent.Future<*>? = null
        try {
            assertTrue(registering.await(5, TimeUnit.SECONDS))
            queued = f.mdns.refresh()
            assertReturnsPromptly { f.mdns.close() }
        } finally {
            proceed.countDown()
            refresh.get(5, TimeUnit.SECONDS)
            queued?.get(5, TimeUnit.SECONDS)
            f.close()
        }
        assertEquals(1, f.responders.size)
        assertEquals(1, f.responders.single().closeCount)
        assertNotEquals(Thread.currentThread(), f.responders.single().registerThread)
        assertSame(f.responders.single().registerThread, f.responders.single().closeThread)
        assertEquals(1, TrackingMulticastLock.releases)
    }

    @Test fun webSessionStopOnMainThreadAndMediaQueriesDoNotWaitForSlowUnregistration() {
        val f = Fixture()
        f.refresh()
        val closing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.responders.single().onClose = {
            closing.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        ReflectionHelpers.setStaticField(WebSession::class.java, "mdns", f.mdns)
        val queries = Executors.newSingleThreadExecutor()
        try {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            assertReturnsPromptly { WebSession.stop() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertEquals("stopped", queries.submit<WebSession.MediaSnapshot> {
                WebSession.mediaSnapshot()
            }.get(1, TimeUnit.SECONDS).sourceState)
            assertEquals(0, TrackingMulticastLock.releases)
        } finally {
            proceed.countDown()
            queries.shutdownNow()
            f.close()
            WebSession.stop()
        }
        assertNotEquals(Thread.currentThread(), f.responders.single().closeThread)
        assertEquals(1, TrackingMulticastLock.releases)
    }

    @Test fun hostnameChangeDoesNotWaitForRetiringResponder() {
        val f = Fixture()
        f.refresh()
        val closing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.responders.single().onClose = {
            closing.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        ReflectionHelpers.setStaticField(WebSession::class.java, "mdns", f.mdns)
        try {
            assertReturnsPromptly {
                assertNull(WebSession.setMdnsHostname(context, "renamed.local"))
            }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertEquals("renamed.local", WebListenSettings.mdnsHostname(context))
        } finally {
            proceed.countDown()
            f.close()
            WebSession.stop()
        }
    }

    @Test fun newLifecycleCanPublishWhilePreviousCleanupIsBlocked() {
        val first = Fixture()
        first.refresh()
        val closing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        first.responders.single().onClose = {
            closing.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        val second = Fixture()
        try {
            assertReturnsPromptly { first.mdns.close() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            WebListenSettings.saveMdnsHostname(context, "successor.local")
            second.refresh()
            assertEquals("successor", second.responders.single().hostname)
            assertEquals(2, TrackingMulticastLock.acquisitions)
            assertEquals(0, TrackingMulticastLock.releases)
        } finally {
            proceed.countDown()
            first.close()
        }
        first.refresh()
        assertEquals(0, second.responders.single().closeCount)
        assertEquals(1, TrackingMulticastLock.releases)
        second.close()
        assertEquals(2, TrackingMulticastLock.releases)
    }

    @Test fun stopDuringNetworkReplacementSkipsNewAddressesAndClosesEveryOldResponder() {
        val f = Fixture(listOf(wlan, ethernet))
        f.refresh()
        val closing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.responders.first().onClose = {
            closing.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        f.entries = listOf(wlan.copy(address = "192.168.3.2"))
        val refresh = f.mdns.refresh()
        try {
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertReturnsPromptly { f.mdns.close() }
        } finally {
            proceed.countDown()
            refresh.get(5, TimeUnit.SECONDS)
            f.close()
        }
        assertEquals(2, f.responders.size)
        assertTrue(f.responders.all { it.closeCount == 1 })
        assertEquals(1, TrackingMulticastLock.releases)
    }

    @Test fun automaticRegistrationAndCleanupRunOnTheSameBackgroundWorker() {
        val registered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var registerThread: Thread? = null
        var closeThread: Thread? = null
        val mdns = WebMdns(context, discover = { listOf(ethernet) },
            createResponder = { _, _ ->
                object : WebMdns.Responder {
                    override fun register(port: Int) {
                        registerThread = Thread.currentThread()
                        registered.countDown()
                    }
                    override fun close() {
                        closeThread = Thread.currentThread()
                        closed.countDown()
                    }
                }
            })
        try {
            assertTrue(registered.await(5, TimeUnit.SECONDS))
            assertNotEquals(Thread.currentThread(), registerThread)
        } finally {
            assertReturnsPromptly { mdns.close() }
            assertTrue(mdns.awaitClosed(5, TimeUnit.SECONDS))
        }
        assertEquals(0L, closed.count)
        assertSame(registerThread, closeThread)
    }
}
