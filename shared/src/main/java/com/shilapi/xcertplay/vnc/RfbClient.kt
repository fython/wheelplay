package com.shilapi.xcertplay.vnc

import com.shilapi.xcertplay.projection.ProjectionFramebuffer
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** RFB 3.x client. Only advertised encodings are accepted; all deltas precede snapshot coalescing. */
class RfbClient(private val input: DataInputStream, private val send: (ByteArray) -> Unit) {
    lateinit var framebuffer: ProjectionFramebuffer
        private set
    data class Update(val snapshot: ProjectionFramebuffer.Snapshot?, val resized: Boolean)

    fun initialize(password: CharArray): String {
        try {
            val banner = ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII)
            require(Regex("RFB 003\\.[0-9]{3}\n").matches(banner)) { "Unsupported VNC protocol version" }
            val minor = banner.substring(8, 11).toInt()
            val version = when { minor >= 8 -> 8; minor >= 7 -> 7; minor >= 3 -> 3; else -> error("Unsupported VNC version") }
            send("RFB 003.00$version\n".toByteArray(Charsets.US_ASCII))
            val security = if (version == 3) input.readInt().also {
                if (it == 0) throw IOException("VNC server rejected connection: ${text(4096)}")
            } else {
                val count = input.readUnsignedByte()
                if (count == 0) throw IOException("VNC server rejected connection: ${text(4096)}")
                val types = ByteArray(count).also(input::readFully).map { it.toInt() and 255 }
                val selected = if (password.isNotEmpty()) 2 else if (1 in types) 1 else 2
                require(selected in types) { "Server requires unsupported VNC security (TLS/VeNCrypt is not supported)" }
                send(byteArrayOf(selected.toByte())); selected
            }
            require(security == 1 || security == 2) { "Unsupported VNC security type: $security" }
            require(security != 1 || password.isEmpty()) { "Server does not offer VNC password authentication" }
            if (security == 2) {
                require(password.isNotEmpty()) { "VNC password required" }
                send(challengeResponse(password, ByteArray(16).also(input::readFully)))
            }
            if (security == 2 || version == 8) {
                if (input.readInt() != 0) {
                    val reason = if (version == 8) text(4096) else "password rejected"
                    throw IOException("VNC authentication failed: $reason")
                }
            }
            send(byteArrayOf(1)) // Shared desktop: do not evict other VNC viewers.
            val width = input.readUnsignedShort(); val height = input.readUnsignedShort()
            checkSize(width, height)
            input.readFully(ByteArray(16)) // Replaced by our explicit pixel format before requesting pixels.
            val name = text(4096)
            framebuffer = ProjectionFramebuffer(width, height)
            send(packet { writeByte(0); write(ByteArray(3)); write(PIXEL_FORMAT) })
            send(packet { writeByte(2); writeByte(0); writeShort(4); listOf(1, 5, 0, -223).forEach(::writeInt) })
            return name
        } finally { password.fill('\u0000') }
    }

    fun request(incremental: Boolean) = send(packet {
        writeByte(3); writeBoolean(incremental); writeShort(0); writeShort(0)
        writeShort(framebuffer.width); writeShort(framebuffer.height)
    })

    /** Caller permits indefinite idle before the message byte, then applies a payload deadline. */
    fun message(type: Int): Update? = when (type) {
        0 -> update()
        1 -> { // Unused colour map: requested format is true-colour.
            input.readUnsignedByte(); input.readUnsignedShort()
            discard(input.readUnsignedShort() * 6); null
        }
        2 -> null // Bell: no audio capability advertised.
        3 -> { input.readFully(ByteArray(3)); discard(input.readInt().also { require(it in 0..1_048_576) }); null }
        else -> throw IOException("Unsupported VNC server message: $type")
    }

    private fun update(): Update {
        input.readUnsignedByte()
        val count = input.readUnsignedShort().also { require(it <= 4096) }
        var changed = false
        var resized = false
        var pixels = 0L
        repeat(count) { index ->
            val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
            val w = input.readUnsignedShort(); val h = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                -223 -> {
                    require(index == count - 1 && x == 0 && y == 0) { "Invalid VNC desktop resize" }
                    checkSize(w, h)
                    framebuffer = ProjectionFramebuffer(w, h); resized = true; changed = false
                }
                0, 1, 5 -> {
                    require(w > 0 && h > 0 && x + w <= framebuffer.width && y + h <= framebuffer.height) { "VNC rectangle exceeds desktop" }
                    pixels += w.toLong() * h
                    require(pixels <= MAX_PIXELS * 8L) { "VNC update exceeds processing budget" }
                    when (encoding) {
                        0 -> framebuffer.update(x, y, w, h, raw(w, h))
                        1 -> framebuffer.copy(input.readUnsignedShort(), input.readUnsignedShort(), x, y, w, h)
                        5 -> hextile(x, y, w, h)
                    }
                    changed = true
                }
                else -> throw IOException("Unrequested VNC encoding: $encoding")
            }
        }
        return Update(if (changed) framebuffer.snapshot() else null, resized)
    }

    private fun pixel(): Int {
        val b = input.readUnsignedByte(); val g = input.readUnsignedByte(); val r = input.readUnsignedByte()
        input.readUnsignedByte()
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
    private fun raw(w: Int, h: Int) = IntArray(w * h) { pixel() }
    private fun hextile(x: Int, y: Int, w: Int, h: Int) {
        var background: Int? = null
        var foreground: Int? = null
        for (ty in 0 until h step 16) for (tx in 0 until w step 16) {
            val tw = minOf(16, w - tx); val th = minOf(16, h - ty)
            val flags = input.readUnsignedByte().also { require(it and 31 == it) }
            if (flags and 1 != 0) framebuffer.update(x + tx, y + ty, tw, th, raw(tw, th))
            else {
                if (flags and 2 != 0) background = pixel()
                if (flags and 4 != 0) foreground = pixel()
                val tile = IntArray(tw * th) { checkNotNull(background) { "Missing Hextile background" } }
                if (flags and 8 != 0) repeat(input.readUnsignedByte()) {
                    val color = if (flags and 16 != 0) pixel() else checkNotNull(foreground) { "Missing Hextile foreground" }
                    val xy = input.readUnsignedByte(); val wh = input.readUnsignedByte()
                    val sx = xy ushr 4; val sy = xy and 15; val sw = (wh ushr 4) + 1; val sh = (wh and 15) + 1
                    require(sx + sw <= tw && sy + sh <= th) { "Invalid Hextile subrectangle" }
                    repeat(sh) { row -> java.util.Arrays.fill(tile, (sy + row) * tw + sx, (sy + row) * tw + sx + sw, color) }
                }
                framebuffer.update(x + tx, y + ty, tw, th, tile)
            }
        }
    }
    private fun text(limit: Int): String {
        val size = input.readInt().also { require(it in 0..limit) { "VNC text exceeds limit" } }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
    private fun discard(size: Int) {
        val buffer = ByteArray(minOf(size, 4096))
        var left = size
        while (left > 0) { val n = minOf(left, buffer.size); input.readFully(buffer, 0, n); left -= n }
    }

    companion object {
        const val MAX_PIXELS = 4_194_304
        val PIXEL_FORMAT: ByteArray get() = byteArrayOf(32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0)
        fun checkSize(w: Int, h: Int) { require(w in 1..8192 && h in 1..8192 && w.toLong() * h <= MAX_PIXELS) { "VNC desktop is too large (maximum 4 megapixels)" } }
        fun packet(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).apply(block).flush() }.toByteArray()
        fun challengeResponse(password: CharArray, challenge: ByteArray): ByteArray {
            require(password.size in 1..8 && password.all { it.code in 1..255 } && challenge.size == 16) { "VNC passwords use at most 8 Latin-1 characters" }
            val key = ByteArray(8) { i -> if (i < password.size) (Integer.reverse(password[i].code) ushr 24).toByte() else 0 }
            return try {
                Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")) }.doFinal(challenge)
            } finally { key.fill(0) }
        }
    }
}
