package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    // Control plists are small metadata documents, not media payloads. Bound both the wire
    // representation and its expansion: a tiny DAG can otherwise duplicate huge subtrees.
    internal const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    internal const val MAX_OBJECTS = 65_536
    internal const val MAX_DEPTH = 64
    internal const val MAX_EXPANDED_OBJECTS = 100_000
    private const val MAX_DECODED_BYTES = 16 * 1024 * 1024

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size in 40..MAX_INPUT_BYTES) { "bplist: invalid size" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }
        return Decoder(bytes).decode()
    }

    private class Decoder(private val bytes: ByteArray) {
        private val trailer = bytes.size - 32
        private val offsetSize = bytes[trailer + 6].toInt() and 0xff
        private val refSize = bytes[trailer + 7].toInt() and 0xff
        private val offsets: IntArray
        private val visiting: BooleanArray
        private val topObject: Int
        private val objectEnd: Int
        private var remainingObjects = MAX_EXPANDED_OBJECTS
        private var remainingBytes = MAX_DECODED_BYTES

        init {
            require(offsetSize in 1..8 && refSize in 1..8) { "bplist: invalid integer width" }
            val count = unsigned(trailer + 8, 8, bytes.size)
            val top = unsigned(trailer + 16, 8, bytes.size)
            val table = unsigned(trailer + 24, 8, bytes.size)
            require(count in 1..MAX_OBJECTS.toLong()) { "bplist: invalid object count" }
            require(top in 0 until count) { "bplist: invalid root reference" }
            require(table in 8..trailer.toLong() && count * offsetSize <= trailer - table) {
                "bplist: invalid offset table"
            }
            topObject = top.toInt()
            objectEnd = table.toInt()
            offsets = IntArray(count.toInt()) { index ->
                val offset = unsigned(objectEnd + index * offsetSize, offsetSize, trailer)
                require(offset in 8 until table) { "bplist: invalid object offset" }
                offset.toInt()
            }
            visiting = BooleanArray(offsets.size)
        }

        fun decode(): Any? = readObject(topObject, 0)

        private fun span(position: Int, length: Long, end: Int = objectEnd) {
            require(position >= 0 && length >= 0 && position.toLong() + length <= end) {
                "bplist: truncated object"
            }
        }

        private fun unsigned(position: Int, size: Int, end: Int = objectEnd): Long {
            span(position, size.toLong(), end)
            val value = readBigEndianLong(bytes, position.toLong(), size)
            require(value >= 0) { "bplist: integer exceeds supported range" }
            return value
        }

        private fun chargeBytes(count: Int) {
            require(count <= remainingBytes) { "bplist: decoded data limit exceeded" }
            remainingBytes -= count
        }

        private fun readObject(index: Int, depth: Int): Any? {
            require(index in offsets.indices) { "bplist: invalid object reference" }
            require(depth <= MAX_DEPTH) { "bplist: nesting limit exceeded" }
            require(!visiting[index]) { "bplist: cyclic reference" }
            require(remainingObjects-- > 0) { "bplist: expansion limit exceeded" }
            visiting[index] = true
            try {
                var position = offsets[index]
                val marker = bytes[position++].toInt() and 0xff
                val type = marker ushr 4
                val nibble = marker and 0x0f

                fun count(): Int {
                    if (nibble != 0x0f) return nibble
                    span(position, 1)
                    val marker = bytes[position++].toInt() and 0xff
                    require(marker in 0x10..0x13) { "bplist: invalid count marker" }
                    val width = 1 shl (marker and 0x0f)
                    val value = unsigned(position, width)
                    position += width
                    require(value <= MAX_INPUT_BYTES) { "bplist: count limit exceeded" }
                    return value.toInt()
                }

                fun reference(position: Int): Int {
                    val ref = unsigned(position, refSize)
                    require(ref < offsets.size) { "bplist: invalid object reference" }
                    return ref.toInt()
                }

                return when (type) {
                    0x0 -> when (nibble) {
                        8 -> false
                        9 -> true
                        else -> throw IllegalArgumentException("bplist: unsupported primitive")
                    }
                    0x1 -> {
                        require(nibble <= 3) { "bplist: unsupported integer width" }
                        val width = 1 shl nibble
                        span(position, width.toLong())
                        // Keep the existing unsigned-64 bit-pattern representation in Long.
                        readBigEndianLong(bytes, position.toLong(), width)
                    }
                    0x2 -> {
                        require(nibble == 2 || nibble == 3) { "bplist: unsupported real width" }
                        val width = 1 shl nibble
                        span(position, width.toLong())
                        val bits = readBigEndianLong(bytes, position.toLong(), width)
                        if (width == 4) Float.fromBits(bits.toInt()) else Double.fromBits(bits)
                    }
                    0x4, 0x5, 0x6 -> {
                        val length = count() * if (type == 0x6) 2 else 1
                        span(position, length.toLong())
                        chargeBytes(if (type == 0x5) length * 2 else length)
                        when (type) {
                            0x4 -> bytes.copyOfRange(position, position + length)
                            0x5 -> String(bytes, position, length, Charsets.US_ASCII)
                            else -> String(bytes, position, length, Charsets.UTF_16BE)
                        }
                    }
                    0xa, 0xd -> {
                        val count = count()
                        val references = count.toLong() * if (type == 0xd) 2 else 1
                        span(position, references * refSize)
                        require(references <= remainingObjects) { "bplist: expansion limit exceeded" }
                        if (type == 0xa) {
                            List(count) { readObject(reference(position + it * refSize), depth + 1) }
                        } else {
                            val dict = LinkedHashMap<String, Any?>(count)
                            for (i in 0 until count) {
                                val key = readObject(reference(position + i * refSize), depth + 1)
                                require(key is String) { "bplist: dictionary key is not a string" }
                                dict[key] = readObject(reference(position + (count + i) * refSize), depth + 1)
                            }
                            dict
                        }
                    }
                    else -> throw IllegalArgumentException("bplist: unsupported object type $type")
                }
            } finally {
                visiting[index] = false
            }
        }
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        var value = 0L
        var base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()
}
