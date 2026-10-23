package com.shilapi.xcertplay.androidauto

import java.io.ByteArrayOutputStream

/** Small bounded proto2 wire codec. Field numbers follow AAP control/media/input/sensor schemas. */
object AapProto {
    class Builder {
        private val out = ByteArrayOutputStream()
        private fun varint(value: Long) { var v = value; while (v and -128L != 0L) { out.write((v.toInt() and 127) or 128); v = v ushr 7 }; out.write(v.toInt()) }
        fun number(field: Int, value: Long): Builder { require(field > 0); varint((field.toLong() shl 3)); varint(value); return this }
        fun bytes(field: Int, value: ByteArray): Builder { require(field > 0); varint((field.toLong() shl 3) or 2); varint(value.size.toLong()); out.write(value); return this }
        fun text(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
        fun build(): ByteArray = out.toByteArray()
    }
    fun build(block: Builder.() -> Unit): ByteArray = Builder().apply(block).build()
    data class Field(val number: Int, val value: Long = 0, val bytes: ByteArray? = null)
    fun parse(data: ByteArray): List<Field> {
        require(data.size <= 4 * 1024 * 1024)
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                require(offset < data.size) { "Truncated protobuf" }
                val b = data[offset++].toInt() and 255
                require(i != 9 || b <= 1) { "Varint overflow" }
                value = value or ((b and 127).toLong() shl (i * 7))
                if (b and 128 == 0) return value
            }
            error("Malformed varint")
        }
        val result = ArrayList<Field>()
        while (offset < data.size) {
            require(result.size < 4096)
            val tag = varint(); require(tag ushr 3 in 1L..536870911L)
            val number = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> result.add(Field(number, varint()))
                2 -> { val count = varint(); require(count in 0..(data.size - offset).toLong()); result.add(Field(number, bytes = data.copyOfRange(offset, offset + count.toInt()))); offset += count.toInt() }
                1, 5 -> { val count = if (tag and 7 == 1L) 8 else 4; require(count <= data.size - offset); offset += count }
                else -> error("Unsupported wire type")
            }
        }
        return result
    }
    fun number(data: ByteArray, field: Int, default: Long = 0): Long = parse(data).firstOrNull { it.number == field && it.bytes == null }?.value ?: default
}
