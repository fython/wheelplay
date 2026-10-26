package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.projection.ProjectionContact
import com.shilapi.xcertplay.projection.ProjectionInput
import com.shilapi.xcertplay.projection.ProjectionCancelReason

/** Serializes browser ownership and releases fingers when a client disappears. */
class TouchLease(private val maxContacts: () -> Int = { 2 },
    private val sendInput: (ProjectionInput) -> Boolean = { false },
    private val cancelInput: ((ProjectionCancelReason) -> Boolean)? = null, private val send: (List<ProjectionContact>) -> Boolean) {
    private var owner: Any? = null
    private var lastTouch = 0L
    private var pressed = false
    private var desktopActive = false

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
        if ((pressed || desktopActive) && now - lastTouch > 1500) releaseFingers(ProjectionCancelReason.LEASE_EXPIRED)
    }

    @Synchronized fun input(client: Any, event: ProjectionInput, now: Long): Boolean {
        if (owner !== client) return false
        if (event == ProjectionInput.Release) {
            lastTouch = now
            return releaseFingers(ProjectionCancelReason.FOCUS_LOST)
        }
        if (!sendInput(event)) return false
        desktopActive = event != ProjectionInput.Release
        lastTouch = now
        return true
    }

    @Synchronized fun drop(client: Any) {
        if (owner !== client) return
        releaseFingers(ProjectionCancelReason.DISCONNECTED); owner = null
    }

    @Synchronized fun reset() { releaseFingers(ProjectionCancelReason.SOURCE_SWITCH); owner = null }

    @Synchronized fun cancel() { releaseFingers(ProjectionCancelReason.LAYOUT_CHANGED) }

    private fun releaseFingers(reason: ProjectionCancelReason): Boolean {
        val accepted = cancelInput?.invoke(reason) ?: run {
            val touchReleased = send(emptyList())
            val keysReleased = sendInput(ProjectionInput.Release)
            touchReleased || keysReleased
        }
        pressed = false; desktopActive = false
        return accepted
    }
}
