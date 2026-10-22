package com.shilapi.xcertplay.web

import android.os.SystemClock

/** Per-client, per-video-configuration retry gate. Called under the client's RTC lock. */
internal class RtcRetryPolicy(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val delays = longArrayOf(5_000, 10_000, 20_000, 30_000)
    private var failures = 0
    private var inFlight = false
    private var retryAt = 0L
    private var readyAt: Long? = null
    var attempted = false
        private set
    var blocked = false
        private set

    val retryAfterMs get() = if (blocked || inFlight) 0L else (retryAt - now()).coerceAtLeast(0)
    val available get() = !blocked && !inFlight && now() >= retryAt

    fun start(): Boolean {
        if (!available) return false
        attempted = true; inFlight = true; readyAt = null
        return true
    }

    fun ready() { if (inFlight && readyAt == null) readyAt = now() }

    fun finish(retryable: Boolean = true) {
        // A permanent browser rejection can arrive just after a native failure closed this peer.
        if (!retryable && attempted) blocked = true
        if (!inFlight) return // A stale/repeated stop cannot advance the backoff.
        val at = now()
        // A brief successful first frame must not reset a flapping connection's backoff.
        if (readyAt?.let { at - it >= 60_000 } == true) failures = 0
        val delay = delays[failures.coerceAtMost(delays.lastIndex)]
        failures = (failures + 1).coerceAtMost(delays.size)
        inFlight = false; readyAt = null; blocked = !retryable; retryAt = at + delay
    }

    fun reset() {
        failures = 0; inFlight = false; retryAt = 0; readyAt = null
        attempted = false; blocked = false
    }
}
