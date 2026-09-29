package com.shilapi.xcertplay.web

/** Handler-thread confined trailing-edge throttle; the owner keeps the latest texture. */
internal class FramePublishScheduler(
    private val now: () -> Long,
    private val wanted: () -> Boolean,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val publish: () -> Unit,
    private val intervalMillis: Long = 66,
) {
    private var lastPublished: Long? = null
    private var dirty = false
    private var scheduled = false
    private var closed = false
    private val task = Runnable {
        scheduled = false
        if (!closed && dirty && wanted()) {
            dirty = false
            lastPublished = now()
            publish()
        }
    }

    fun request() {
        if (closed) return
        dirty = true
        resume()
    }

    fun resume() {
        if (closed || !dirty || scheduled || !wanted()) return
        scheduled = true
        val delay = lastPublished?.let { (it + intervalMillis - now()).coerceAtLeast(0) } ?: 0
        post(task, delay)
    }

    fun close() {
        closed = true
        dirty = false
        scheduled = false
        remove(task)
    }
}
