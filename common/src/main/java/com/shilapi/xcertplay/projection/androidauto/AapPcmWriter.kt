package com.shilapi.xcertplay.projection.androidauto

import java.io.IOException

/** Preserve unwritten PCM, but allow stop/focus loss to interrupt a full audio buffer. */
internal object AapPcmWriter {
    fun write(size: Int, active: () -> Boolean, write: (Int, Int) -> Int,
        pause: () -> Unit = { Thread.sleep(5) }) {
        var offset = 0
        var stalled = 0
        while (offset < size && active()) {
            val count = write(offset, size - offset)
            if (count < 0 || count > size - offset) throw IOException("AA PCM write failed: $count")
            if (count == 0) {
                if (++stalled >= 400) throw IOException("AA audio output made no progress")
                pause()
            } else {
                offset += count
                stalled = 0
            }
        }
    }
}
