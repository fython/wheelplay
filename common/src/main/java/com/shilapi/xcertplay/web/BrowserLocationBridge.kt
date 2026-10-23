package com.shilapi.xcertplay.web

import android.os.SystemClock
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import org.json.JSONObject

/** Only the paired HTTPS viewer can opt in; fixes never outlive its connection. */
internal class BrowserLocationBridge(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var owner: Any? = null
    private var secure = false
    private var enabled = false
    private var fix: CarPlayLocationFix? = null
    private var expiresAt = 0L

    @Synchronized fun attach(client: Any, secure: Boolean) {
        check(owner == null)
        owner = client; this.secure = secure; enabled = false; fix = null
    }

    @Synchronized fun detach(client: Any) {
        if (owner !== client) return
        owner = null; secure = false; enabled = false; fix = null
    }

    @Synchronized fun control(client: Any, data: JSONObject) {
        if (owner !== client || !secure) return
        when (data.getString("type")) {
            "location-start" -> { enabled = true; fix = null }
            "location-stop" -> { enabled = false; fix = null }
            "location-clear" -> fix = null
            "location" -> {
                if (!enabled) return
                fun number(name: String): Double = (data.get(name) as Number).toDouble().also {
                    require(it.isFinite())
                }
                fun optional(name: String): Double? = if (data.isNull(name)) null else number(name)
                val age = number("ageMillis").also { require(it in 0.0..30_000.0) }.toLong()
                val timestamp = number("timestamp").also { require(it in 1.0..253402300799999.0) }.toLong()
                val next = CarPlayLocationFix(
                    latitudeDegrees = number("latitude"), longitudeDegrees = number("longitude"),
                    accuracyMeters = number("accuracy").also { require(it >= 0) },
                    altitudeMeters = optional("altitude"),
                    bearingDegrees = optional("heading")?.also { require(it >= 0 && it < 360) },
                    speedMetersPerSecond = optional("speed")?.also { require(it >= 0) },
                    timestampMillis = timestamp,
                )
                if (timestamp <= (fix?.timestampMillis ?: 0L)) return
                fix = next
                // Use reception's monotonic clock; the browser and host wall clocks may differ.
                expiresAt = now() + 30_000L - age
            }
        }
    }

    @Synchronized private fun latestNmea(): String? =
        fix?.takeIf { enabled && owner != null && now() < expiresAt }?.let(NmeaLocationEncoder::encode)

    /** Negotiation is session-scoped; browser consent and fixes remain connection-scoped. */
    fun createProvider(android: Iap2LocationProvider? = null): Iap2LocationProvider = object : Iap2LocationProvider {
        @Volatile private var started = false
        override fun start(): Boolean {
            android?.start()
            started = true
            return true // The browser may opt in after iAP2 subscribes.
        }
        override fun stop() { started = false; android?.stop() }
        override fun latestNmea(): String? = if (started) this@BrowserLocationBridge.latestNmea() ?: android?.latestNmea() else null
        override fun close() { started = false; android?.close() }
    }
}
