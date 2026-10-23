package com.shilapi.xcertplay.androidauto

import java.io.DataInputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream

/** AAP header is outside TLS. First fragmented record includes a four-byte big-endian total. */
object AapFraming {
    data class Packet(val channel: Int, val flags: Int, val payload: ByteArray, val total: Int? = null)
    fun read(input: DataInputStream): Packet {
        val channel = input.readUnsignedByte(); val flags = input.readUnsignedByte(); val length = input.readUnsignedShort()
        require(flags and 0xf0 == 0 && length > 0)
        val total = if (flags and 3 == 1) input.readInt().also { require(it in 1..4 * 1024 * 1024) } else null
        return Packet(channel, flags, ByteArray(length).also(input::readFully), total)
    }
    fun write(output: OutputStream, channel: Int, flags: Int, payload: ByteArray) {
        require(channel in 0..255 && payload.size in 1..65535 && flags and 3 == 3)
        output.write(byteArrayOf(channel.toByte(), flags.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()))
        output.write(payload); output.flush()
    }
    fun message(type: Int, body: ByteArray) = byteArrayOf((type ushr 8).toByte(), type.toByte()) + body
    fun type(data: ByteArray): Int { require(data.size >= 2); return ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255) }
}

/** Continuations carry raw media bytes, not a repeated message type or timestamp. */
class AapMediaAssembler {
    data class AccessUnit(val data: ByteArray, val ptsUs: Long)
    private var pending: ByteArrayOutputStream? = null
    private var ptsUs = 0L
    fun accept(flags: Int, payload: ByteArray): AccessUnit? {
        val boundary = flags and 3
        if (boundary == 1 || boundary == 3) {
            check(pending == null) { "Truncated video access unit" }
            val type = AapFraming.type(payload)
            require(type == 0 || type == 1)
            val offset = if (type == 0) 10 else 2
            require(payload.size > offset)
            ptsUs = if (type == 0) java.nio.ByteBuffer.wrap(payload, 2, 8).long else 0L
            if (boundary == 3) return AccessUnit(payload.copyOfRange(offset, payload.size), ptsUs)
            pending = ByteArrayOutputStream().apply { write(payload, offset, payload.size - offset) }
        } else {
            val buffer = checkNotNull(pending) { "Orphan video fragment" }
            require(buffer.size() + payload.size <= 4 * 1024 * 1024)
            buffer.write(payload)
            if (boundary == 2) { pending = null; return AccessUnit(buffer.toByteArray(), ptsUs) }
        }
        return null
    }
}
