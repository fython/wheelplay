package com.shilapi.xcertplay.web

/** Receiver counters complement local UDP acceptance; they never imply delivery of a sent frame. */
internal class RtcReceiverHealth(private val recoveryGraceMs: Long = RtcRecoveryPolicy.BALANCED.graceMs) {
    data class Report(val packets: Long, val lost: Long, val decoded: Long, val nack: Long, val rttMs: Double?)
    enum class Action { NONE, KEYFRAME, FALLBACK }
    private var last: Report? = null
    private var stalledSince: Long? = null
    private var sentAtLastProgress = 0L
    private var lastSent = 0L
    private var lastSentAt = 0L
    var congested = false; private set
    fun update(report: Report, sent: Long, nowMs: Long): Action {
        if (sent != lastSent) { lastSent = sent; lastSentAt = nowMs }
        val previous = last
        last = report
        if (previous == null || report.packets < previous.packets || report.decoded < previous.decoded) {
            stalledSince = null; sentAtLastProgress = sent; congested = false; return Action.NONE
        }
        val received = report.packets - previous.packets
        val lost = (report.lost - previous.lost).coerceAtLeast(0)
        congested = (received + lost >= 20 && lost.toDouble() / (received + lost) > .1) || (report.rttMs ?: 0.0) > 250
        if (report.decoded > previous.decoded || sent <= sentAtLastProgress || nowMs - lastSentAt > 1500) {
            stalledSince = null; sentAtLastProgress = sent; return Action.NONE
        }
        val since = stalledSince ?: nowMs.also { stalledSince = it }
        return when {
            nowMs - since >= recoveryGraceMs -> Action.FALLBACK
            nowMs - since >= 2000 && received > 0 -> Action.KEYFRAME
            else -> Action.NONE
        }
    }
}
