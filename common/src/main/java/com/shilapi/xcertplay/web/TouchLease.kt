package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AirPlayContact

/** Serializes browser ownership and releases fingers when a client disappears. */
class TouchLease(private val send: (List<AirPlayContact>) -> Boolean) {
    private var owner: Any? = null
    private var lastTouch = 0L
    private var pressed = false

    @Synchronized fun claim(client: Any): Boolean {
        if (owner != null && owner !== client) return false
        owner = client
        return true
    }

    @Synchronized fun touch(client: Any, contacts: List<AirPlayContact>, now: Long): Boolean {
        if (owner !== client || contacts.size > 2 || contacts.any {
            !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 || it.id !in 0..1
        } || contacts.map { it.id }.distinct().size != contacts.size) return false
        // HID slots must not shift when the first of two fingers lifts.
        val slots = (0..1).map { id -> contacts.find { it.id == id } ?: AirPlayContact(id, 0.0, 0.0, false) }
        val accepted = send(slots)
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

    private fun releaseFingers() { send(emptyList()); pressed = false }
}
