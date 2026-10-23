package com.shilapi.xcertplay.vnc

import com.shilapi.xcertplay.projection.ProjectionInput

/** Maps semantic desktop input to RFB; caller serializes admission and cancellation. */
class RfbInput(private val width: Int, private val height: Int, private val send: (ByteArray) -> Boolean) {
    private var x = 0
    private var y = 0
    private var buttons = 0
    private val keys = linkedSetOf<Int>()
    fun accept(event: ProjectionInput): Boolean = when (event) {
        is ProjectionInput.Pointer -> {
            x = (event.x * (width - 1)).toInt(); y = (event.y * (height - 1)).toInt()
            buttons = (event.buttons and 1) or ((event.buttons and 2) shl 1) or ((event.buttons and 4) ushr 1)
            pointer(buttons)
        }
        is ProjectionInput.Scroll -> {
            x = (event.x * (width - 1)).toInt(); y = (event.y * (height - 1)).toInt()
            var accepted = true
            for ((delta, negative, positive) in listOf(Triple(event.dy, 8, 16), Triple(event.dx, 32, 64))) {
                repeat(kotlin.math.abs(delta)) {
                    if (accepted) accepted = pointer(buttons or if (delta < 0) negative else positive) && pointer(buttons)
                }
            }
            accepted
        }
        is ProjectionInput.Key -> {
            val symbol = keysym(event.key)
            if (symbol == null) false
            else if (event.down && symbol !in keys && keys.size >= 64) false
            else { if (event.down) keys.add(symbol) else keys.remove(symbol); key(symbol, event.down) }
        }
        ProjectionInput.Release -> {
            var accepted = true
            if (buttons != 0) { buttons = 0; accepted = pointer(0) }
            for (symbol in keys) if (!key(symbol, false)) accepted = false
            keys.clear(); accepted
        }
        ProjectionInput.KeepAlive -> true
    }
    private fun pointer(mask: Int) = send(RfbClient.packet { writeByte(5); writeByte(mask); writeShort(x); writeShort(y) })
    private fun key(symbol: Int, down: Boolean) = send(RfbClient.packet { writeByte(4); writeBoolean(down); writeShort(0); writeInt(symbol) })
    companion object {
        private val named = mapOf("Backspace" to 0xff08, "Tab" to 0xff09, "Enter" to 0xff0d, "Escape" to 0xff1b,
            "Insert" to 0xff63, "Delete" to 0xffff, "Home" to 0xff50, "End" to 0xff57,
            "PageUp" to 0xff55, "PageDown" to 0xff56, "ArrowLeft" to 0xff51, "ArrowUp" to 0xff52,
            "ArrowRight" to 0xff53, "ArrowDown" to 0xff54, "Shift" to 0xffe1, "Control" to 0xffe3,
            "Alt" to 0xffe9, "Meta" to 0xffeb, "CapsLock" to 0xffe5, "NumLock" to 0xff7f,
            "ScrollLock" to 0xff14, "Pause" to 0xff13, "PrintScreen" to 0xff61)
        fun keysym(key: String): Int? {
            named[key]?.let { return it }
            if (key.startsWith("F")) key.drop(1).toIntOrNull()?.takeIf { it in 1..12 }?.let { return 0xffbd + it }
            if (key.codePointCount(0, key.length) != 1) return null
            val cp = key.codePointAt(0)
            if (cp < 32 || cp in 0xd800..0xdfff || cp > 0x10ffff) return null
            return if (cp <= 255) cp else 0x01000000 or cp
        }
    }
}
