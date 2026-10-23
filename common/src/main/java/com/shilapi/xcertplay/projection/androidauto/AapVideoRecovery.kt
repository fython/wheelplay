package com.shilapi.xcertplay.projection.androidauto

import java.io.IOException

/** AAP has no IDR request. Escalate an unanswered gain notification to a bounded focus cycle. */
internal class AapVideoRecovery(private val now: () -> Long, private val focus: (Int) -> Unit) {
    private var projected = true
    private var closed = false
    private var waitingSince: Long? = null
    private var regainAt: Long? = null
    private var lastNudge: Long? = null
    private var lastCycle: Long? = null
    private var cycles = 0

    @Synchronized fun request() {
        if (closed || !projected) return
        val time = now()
        if (waitingSince == null) waitingSince = time
        if (regainAt != null || lastNudge?.let { time - it < 500 } == true) return
        lastNudge = time
        focus(1)
    }

    /** Called by the send worker even when no packets arrive from the phone. */
    @Synchronized fun tick() {
        if (closed || !projected) return
        val time = now()
        regainAt?.let {
            if (time >= it) { regainAt = null; waitingSince = time; focus(1) }
            return
        }
        val since = waitingSince ?: return
        if (cycles >= 3) {
            if (time - since >= 5000) throw IOException("Android Auto video did not recover; reconnect the input source")
            return
        }
        if (time - since < 2000 || lastCycle?.let { time - it < 10000 } == true) return
        cycles++
        lastCycle = time
        regainAt = time + 250
        focus(2)
    }

    @Synchronized fun recovered() {
        // An in-flight IDR from before the release must not cancel the required regain.
        if (regainAt == null) waitingSince = null
    }

    @Synchronized fun setProjected(value: Boolean) {
        projected = value
        if (!value) { waitingSince = null; regainAt = null }
    }

    @Synchronized fun close() { closed = true; waitingSince = null; regainAt = null }
}
