package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.projection.ProjectionContact

/** Serializes browser ownership and releases fingers when a client disappears. */
class TouchLease(private val maxContacts: () -> Int = { 2 }, private val send: (List<ProjectionContact>) -> Boolean) {
    private var owner: Any? = null
    private var lastTouch = 0L
    private var pressed = false

    @Synchronized fun claim(client: Any): Boolean {
        if (owner != null && owner !== client) return false
        owner = client
        return true
    }

    @Synchronized fun touch(client: Any, contacts: List<ProjectionContact>, now: Long): Boolean {
        if (owner !== client || contacts.size > maxContacts() || contacts.any {
            !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 || it.id !in 0 until maxContacts()
        } || contacts.map { it.id }.distinct().size != contacts.size) return false
        val accepted = send(contacts.sortedBy { it.id })
        pressed = accepted && contacts.any { it.down }
        lastTouch = now
        return accepted
    }

    @Synchronized fun expire(now: Long) {
        if (pressed && now - lastTouch > 1500) releaseFingers()
    }

    @Synchronized fun drop(client: Any) {
        if (owner !== client) return
        releaseFingers(); owner = null
    }

    @Synchronized fun reset() { releaseFingers(); owner = null }

    @Synchronized fun cancel() { releaseFingers() }

    private fun releaseFingers() { send(emptyList()); pressed = false }
}
