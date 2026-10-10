package com.shilapi.xcertplay.web

import android.content.Context
import com.shilapi.xcertplay.network.TeslaHttpConfig
import com.shilapi.xcertplay.network.TeslaHttpStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WebListenSettingsTest {
    @Before fun startWithoutRootAuthorization() {
        val original = RootAccess.authorize
        try {
            RootAccess.authorize = { throw java.io.IOException("Permission denied") }
            RootAccess.request()
        } finally { RootAccess.authorize = original }
    }

    @Test fun oldListenerDomainAndCertificateRemainAvailableWithoutRoot() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tesla_http", Context.MODE_PRIVATE).edit().putBoolean("enabled", true)
            .putInt("port", 9090).putString("hostname", "car.example.com").putString("address", "3.3.3.3").commit()
        context.getSharedPreferences("web_https", Context.MODE_PRIVATE).edit().putInt("port", 9443).commit()
        assertEquals(9090, WebListenSettings.httpPort(context))
        assertEquals(9443, WebListenSettings.httpsPort(context))
        assertEquals("car.example.com", WebListenSettings.hostname(context))
        assertTrue(WebListenSettings.httpsEnabled(context))
        assertEquals(TeslaHttpConfig(true, "3.3.3.3"), TeslaHttpCompatibility.config(context))
        assertFalse(RootAccess.granted)
        WebListenSettings.saveHttpPort(context, 9080)
        assertEquals(80, TeslaHttpCompatibility.config(context).httpMappingPort)
        assertEquals(9080, WebListenSettings.httpPort(context))
        assertEquals("car.example.com", WebListenSettings.hostname(context))
    }

    @Test fun oldPrivilegedPortsBecomeMappingsAndListenersUseUnprivilegedDefaults() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tesla_http", Context.MODE_PRIVATE).edit().putInt("port", 81).commit()
        context.getSharedPreferences("web_https", Context.MODE_PRIVATE).edit().putInt("port", 444).commit()
        assertEquals(8080, WebListenSettings.httpPort(context))
        assertEquals(8443, WebListenSettings.httpsPort(context))
        assertEquals(81, TeslaHttpCompatibility.config(context).httpMappingPort)
        assertEquals(444, TeslaHttpCompatibility.config(context).httpsMappingPort)
        assertFalse(RootAccess.granted)
    }

    @Test fun listenersRejectLowPortsAndDomainCannotInjectAnOrigin() {
        val context = RuntimeEnvironment.getApplication()
        for (port in listOf(0, 80, 443, 65536)) {
            assertTrue(runCatching { WebListenSettings.saveHttpPort(context, port) }.isFailure)
            assertTrue(runCatching { WebListenSettings.saveHttpsPort(context, port) }.isFailure)
        }
        assertTrue(runCatching { WebListenSettings.saveHostname(context, "https://car.example.com/") }.isFailure)
        assertEquals("", WebListenSettings.hostname(context))
        for (port in listOf(1024, 65535)) {
            WebListenSettings.saveHttpPort(context, port)
            assertEquals(port, WebListenSettings.httpPort(context))
        }
    }

    @Test fun migrationKeepsListenerPortsDistinctWhenALowPortUsesTheOtherDefault() {
        val context = RuntimeEnvironment.getApplication()
        for ((http, https, expected) in listOf(
            Triple(8443, 443, 8443 to 8444),
            Triple(80, 8080, 8081 to 8080),
            Triple(9443, 9443, 8080 to 9443)
        )) {
            context.getSharedPreferences("web_listen", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("tesla_http", Context.MODE_PRIVATE).edit().clear().putInt("port", http).commit()
            context.getSharedPreferences("web_https", Context.MODE_PRIVATE).edit().clear().putInt("port", https).commit()
            assertEquals(expected.first, WebListenSettings.httpPort(context))
            assertEquals(expected.second, WebListenSettings.httpsPort(context))
        }
    }

    @Test fun rootPlanRequiresAuthorizationAndEnabledHttpsForItsHttpsMapping() {
        val interfaces = listOf(HttpDownstream("ap0", 7, "192.168.43.1/24"))
        val config = TeslaHttpConfig(enabled = true)
        assertNull(TeslaHttpCompatibility.plan(config, interfaces, 8080, 8443, false))
        assertNull(TeslaHttpCompatibility.plan(config.copy(enabled = false), interfaces, 8080, 8443, true))
        val full = TeslaHttpCompatibility.plan(config, interfaces, 8080, 8443, true)!!
        val plain = TeslaHttpCompatibility.plan(config, interfaces, 8080, null, true)!!
        assertEquals(443, full.httpsMappingPort)
        assertEquals(80, plain.httpMappingPort)
        assertNull(plain.httpsMappingPort)
        assertFalse(plain.commands().any { it.apply.contains("--dport 443") })
    }

    @Test fun httpsLinksUseMappedPortOnlyForTheMappedHttpOrigin() {
        val context = RuntimeEnvironment.getApplication()
        WebListenSettings.saveHostname(context, "car.example.com")
        val status = TeslaHttpStatus("3.3.3.3", httpMappingPort = 80, httpsMappingPort = 443)
        assertEquals(443, WebSession.httpsPortFor(context, "3.3.3.3", status))
        assertEquals(443, WebSession.httpsPortFor(context, "car.example.com:80", status))
        assertEquals(WebSession.httpsPort, WebSession.httpsPortFor(context, "192.168.1.1:8080", status))
        assertEquals(WebSession.httpsPort, WebSession.httpsPortFor(context, "car.example.com:8080", status))
        assertEquals(WebSession.httpsPort, WebSession.httpsPortFor(context, "example.invalid:80", status))
        assertEquals(WebSession.httpsPort, WebSession.httpsPortFor(context, "3.3.3.3:80", status.copy(address = null)))
    }
}
