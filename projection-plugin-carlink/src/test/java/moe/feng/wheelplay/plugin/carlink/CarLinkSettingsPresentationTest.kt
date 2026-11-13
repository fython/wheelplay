package moe.feng.wheelplay.plugin.carlink

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarLinkSettingsPresentationTest {
    @Test fun diagnosticReportOmitsSecretsAndRawErrors() {
        val state = Bundle().apply {
            putString("phase", "FAILED"); putInt("errorCode", 10007)
            putString("connection", "usb"); putString("videoOutput", "webrtc")
            putString("pin", "secret-pin"); putString("message", "raw-error-secret")
            putInt("width", 1280); putInt("height", 720)
        }
        val config = Bundle().apply {
            putString("connection", "softap"); putString("hotspotSsid", "private-network")
            putString("hotspotPassword", "secret-password"); putString("carId", "device-id")
        }
        val report = carLinkDiagnosticReport("0.1.0", state, config, Bundle())
        listOf("secret-pin", "raw-error-secret", "private-network", "secret-password", "device-id").forEach {
            assertFalse(report.contains(it))
        }
        assertTrue(report.contains("10007")); assertTrue(report.contains("1280 × 720"))
        assertTrue(report.contains("USB")); assertTrue(report.contains("低延迟视频模式"))
    }
    @Test fun hotspotFieldsExposeIndependentValidation() {
        val credentials = CarLinkSoftAp.Credentials("short", "bad")
        assertNotNull(credentials.ssidProblem()); assertNotNull(credentials.passwordProblem())
        val validName = CarLinkSoftAp.Credentials("My hotspot", "bad")
        assertNull(validName.ssidProblem()); assertNotNull(validName.passwordProblem())
    }
}
