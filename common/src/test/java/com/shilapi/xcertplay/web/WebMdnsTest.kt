package com.shilapi.xcertplay.web

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.web.LanAddresses.Entry
import com.shilapi.xcertplay.web.LanAddresses.Kind
import java.io.IOException
import java.util.concurrent.CountDownLatch
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
        fixtures.forEach { it.mdns.close() }
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
        val mdns = WebMdns(
            context,
            wifiManager = { wifiRequests++; if (wifiAvailable) wifi else null },
            discover = { entries },
            createResponder = { address, hostname ->
                beforeCreate()
                if (failCreate) throw IOException("create failed")
                FakeResponder(address.hostAddress!!, hostname, failRegister, failClose)
                    .also { responders.add(it) }
            },
            startWorker = false,
        )

        init { fixtures.add(this) }
    }

    private class FakeResponder(
        val address: String,
        val hostname: String,
        val failRegister: Boolean,
        val failClose: Boolean,
    ) : WebMdns.Responder {
        var port = 0
        var closeCount = 0
        var onClose: () -> Unit = {}
        override fun register(port: Int) {
            this.port = port
            if (failRegister) throw IOException("register failed")
        }
        override fun close() {
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
        f.mdns.refresh()
        val responder = f.responders.single()
        assertEquals("wheelplay", responder.hostname)
        assertEquals(WebSession.httpPort, responder.port)
        responder.onClose = { assertEquals(0, TrackingMulticastLock.releases) }
        f.mdns.close()
        f.mdns.close()
        f.mdns.refresh()
        assertEquals(1, responder.closeCount)
        assertEquals(1, TrackingMulticastLock.releases)
        assertEquals(1, f.wifiRequests)
    }

    @Test fun repeatedRefreshAndMultipleWifiAddressesShareOneLock() {
        val f = Fixture(listOf(wlan, wlan.copy(address = "192.168.1.3"),
            Entry("ap0", "192.168.43.1", Kind.HOTSPOT)))
        repeat(3) { f.mdns.refresh() }
        assertEquals(3, f.responders.size)
        assertEquals(1, TrackingMulticastLock.acquisitions)
        f.entries = f.entries.drop(1)
        f.mdns.refresh()
        assertEquals(1, f.responders.first().closeCount)
        assertEquals(0, TrackingMulticastLock.releases)
        f.mdns.close()
        assertTrue(f.responders.all { it.closeCount == 1 })
        assertEquals(1, TrackingMulticastLock.releases)
    }

    @Test fun ethernetDoesNotNeedWifiManagerAndExcludedNetworksAreNotPublished() {
        val f = Fixture(listOf(ethernet) + listOf(Kind.VPN, Kind.P2P, Kind.CELLULAR, Kind.TESLA_HTTP)
            .map { Entry(it.name, "10.0.0.1", it) })
        f.wifiAvailable = false
        f.mdns.refresh()
        assertEquals(ethernet.address, f.responders.single().address)
        assertEquals(0, f.wifiRequests)
        assertEquals(0, TrackingMulticastLock.acquisitions)
    }

    @Test fun wifiLossReleasesLockWhileEthernetContinuesAndWifiCanReturn() {
        val f = Fixture(listOf(wlan, ethernet))
        f.mdns.refresh()
        val wired = f.responders.last()
        f.entries = listOf(ethernet)
        f.mdns.refresh()
        assertEquals(1, TrackingMulticastLock.releases)
        assertEquals(0, wired.closeCount)
        f.entries = listOf(wlan, ethernet)
        f.mdns.refresh()
        assertEquals(2, TrackingMulticastLock.acquisitions)
        assertEquals(3, f.responders.size)
        f.entries = emptyList()
        f.mdns.refresh()
        assertEquals(2, TrackingMulticastLock.releases)
    }

    @Test fun addressHostnameAndPortChangesReplaceRespondersWithoutLeakingLocks() {
        val f = Fixture()
        f.mdns.refresh()
        f.entries = listOf(wlan.copy(address = "192.168.3.2"))
        f.mdns.refresh()
        WebListenSettings.saveMdnsHostname(context, "new-name.local")
        f.mdns.refresh()
        val oldPort = WebSession.httpPort
        try {
            ReflectionHelpers.setStaticField(WebSession::class.java, "httpPort", oldPort + 1)
            f.mdns.refresh()
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
        f.mdns.refresh()
        assertEquals(1, TrackingMulticastLock.releases)
        assertTrue(f.responders.isEmpty())
        f.failCreate = false
        f.mdns.refresh()
        assertEquals(2, TrackingMulticastLock.acquisitions)
        assertEquals(1, f.responders.size)
    }

    @Test fun changedTransportOnSameAddressReevaluatesLockOwnership() {
        val f = Fixture()
        f.mdns.refresh()
        f.entries = listOf(wlan.copy(kind = Kind.ETHERNET))
        f.mdns.refresh()
        assertEquals(1, TrackingMulticastLock.releases)
        f.entries = listOf(wlan)
        f.mdns.refresh()
        assertEquals(2, TrackingMulticastLock.acquisitions)
        assertEquals(3, f.responders.size)
    }

    @Test fun registrationAndCleanupFailuresStillReleaseLock() {
        val f = Fixture()
        f.failRegister = true
        f.failClose = true
        f.mdns.refresh()
        assertEquals(1, f.responders.single().closeCount)
        assertEquals(1, TrackingMulticastLock.releases)
        f.mdns.close()
        assertEquals(1, f.responders.single().closeCount)
    }

    @Test fun responderCloseFailureDoesNotPreventOtherCleanup() {
        val f = Fixture(listOf(wlan, ethernet))
        f.failClose = true
        f.mdns.refresh()
        f.mdns.close()
        assertTrue(f.responders.all { it.closeCount == 1 })
        assertEquals(1, TrackingMulticastLock.releases)
    }

    @Test fun failedAdditionalWifiPublicationDoesNotReleaseExistingLock() {
        val f = Fixture()
        f.mdns.refresh()
        f.entries = listOf(wlan, wlan.copy(address = "192.168.1.3"))
        f.failRegister = true
        f.mdns.refresh()
        assertEquals(1, f.responders.last().closeCount)
        assertEquals(0, f.responders.first().closeCount)
        assertEquals(1, TrackingMulticastLock.acquisitions)
        assertEquals(0, TrackingMulticastLock.releases)
    }

    @Test fun unavailableWifiManagerSkipsWifiButStillPublishesEthernetAndRetries() {
        val f = Fixture(listOf(wlan, ethernet))
        f.wifiAvailable = false
        f.mdns.refresh()
        assertEquals(ethernet.address, f.responders.single().address)
        f.wifiAvailable = true
        f.mdns.refresh()
        assertEquals(wlan.address, f.responders.last().address)
        assertEquals(1, TrackingMulticastLock.acquisitions)
    }

    @Test fun acquisitionFailureCleansUpPartiallyAcquiredLockAndRetries() {
        val f = Fixture()
        TrackingMulticastLock.failAcquire = true
        f.mdns.refresh()
        assertTrue(f.responders.isEmpty())
        assertEquals(1, TrackingMulticastLock.releases)
        TrackingMulticastLock.failAcquire = false
        f.mdns.refresh()
        assertEquals(1, f.responders.size)
        assertEquals(2, TrackingMulticastLock.acquisitions)
    }

    @Test fun rapidRestartOwnsSeparateLocksAndCannotReviveClosedInstance() {
        val first = Fixture()
        first.mdns.refresh()
        val second = Fixture()
        second.mdns.refresh()
        first.mdns.close()
        repeat(3) { first.mdns.refresh(); second.mdns.refresh() }
        assertEquals(2, TrackingMulticastLock.acquisitions)
        assertEquals(1, TrackingMulticastLock.releases)
        assertEquals(0, second.responders.single().closeCount)
        second.mdns.close()
        assertEquals(2, TrackingMulticastLock.releases)
    }

    @Test fun closingWebMdnsLeavesAnIndependentCarPlayLockHeld() {
        val carPlay = wifi.createMulticastLock("carplay-bonjour")
        carPlay.acquire()
        try {
            val f = Fixture()
            f.mdns.refresh()
            f.mdns.close()
            assertTrue(carPlay.isHeld)
            assertEquals(2, TrackingMulticastLock.acquisitions)
            assertEquals(1, TrackingMulticastLock.releases)
        } finally { carPlay.release() }
    }

    @Test fun closeSerializesWithInFlightRefreshAndPreventsLatePublication() {
        val f = Fixture()
        val creating = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        f.beforeCreate = {
            creating.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }
        val refresh = Thread { f.mdns.refresh() }
        val closing = Thread { f.mdns.close() }
        try {
            refresh.start()
            assertTrue(creating.await(5, TimeUnit.SECONDS))
            closing.start()
        } finally {
            proceed.countDown()
            refresh.join(5_000)
            if (closing.state != Thread.State.NEW) closing.join(5_000)
        }
        assertFalse(refresh.isAlive)
        assertFalse(closing.isAlive)
        f.mdns.refresh()
        assertEquals(1, f.responders.single().closeCount)
        assertEquals(1, TrackingMulticastLock.acquisitions)
        assertEquals(1, TrackingMulticastLock.releases)
    }
}
