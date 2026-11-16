package com.shilapi.xcertplay.projection.plugin

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Extension v1 accepts complete, non-reordered Annex-B AVC pictures. No next-PTS buffering. */
internal class PluginAvcStream {
    data class Unit(val bytes: ByteArray, val ptsUs: Long, val keyframe: Boolean)
    var configuration: ByteArray? = null
        private set
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var previousPts = -1L

    fun accept(bytes: ByteArray, ptsUs: Long): Unit? {
        val nals = split(bytes)
        var firstSlices = 0
        var hasPicture = false
        var keyframe = false
        nals.forEach { nal ->
            val type = nal[0].toInt() and 31
            require(nal[0].toInt() and 128 == 0) { "Invalid AVC NAL header" }
            when (type) {
                7 -> { check(sps == null || sps!!.contentEquals(nal)) { "AVC format changed; reconnect required" }; sps = nal }
                8 -> { check(pps == null || pps!!.contentEquals(nal)) { "AVC format changed; reconnect required" }; pps = nal }
                1, 5 -> {
                    val bits = SliceBits(nal)
                    if (bits.ue() == 0) firstSlices++
                    val sliceType = bits.ue()
                    require(sliceType in 0..9 && sliceType % 5 != 1) { "Reordered AVC pictures are unsupported" }
                    hasPicture = true; keyframe = keyframe || type == 5
                }
                2, 3, 4 -> error("AVC data partitions are unsupported")
            }
        }
        if (configuration == null && sps != null && pps != null) {
            val a = sps!!; val b = pps!!
            require(a.size in 4..65535 && b.size in 1..65535)
            configuration = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).apply {
                writeByte(1); write(a, 1, 3); writeByte(255); writeByte(225)
                writeShort(a.size); write(a); writeByte(1); writeShort(b.size); write(b)
            } }.toByteArray()
        }
        if (!hasPicture) return null
        require(configuration != null && firstSlices == 1) { "Expected one complete AVC picture with SPS/PPS" }
        require(ptsUs > previousPts) { "Non-monotonic AVC timestamp; reconnect required" }
        previousPts = ptsUs
        return Unit(bytes, ptsUs, keyframe)
    }

    private class SliceBits(nal: ByteArray) {
        private val prefix = ArrayList<Int>(16)
        private var bit = 0
        init {
            var zeros = 0
            for (i in 1 until nal.size) {
                val value = nal[i].toInt() and 255
                if (zeros >= 2 && value == 3) { zeros = 0; continue }
                prefix += value
                zeros = if (value == 0) zeros + 1 else 0
                if (prefix.size == 16) break
            }
        }
        private fun read(): Int {
            require(bit < prefix.size * 8) { "Truncated AVC slice" }
            return (prefix[bit / 8] shr (7 - bit++ % 8)) and 1
        }
        fun ue(): Int {
            var zeros = 0
            while (read() == 0) { require(++zeros <= 24) { "Invalid AVC slice value" } }
            var value = 1
            repeat(zeros) { value = value * 2 + read() }
            return value - 1
        }
    }

    companion object {
        fun split(bytes: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Pair<Int, Int>>()
            var i = 0
            while (i + 3 <= bytes.size) {
                val length = when {
                    bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 1.toByte() -> 3
                    i+4 <= bytes.size && bytes[i] == 0.toByte() && bytes[i+1] == 0.toByte() && bytes[i+2] == 0.toByte() && bytes[i+3] == 1.toByte() -> 4
                    else -> 0
                }
                if (length == 0) i++ else { starts += i to length; i += length }
                require(starts.size <= 4096) { "Too many AVC NAL units" }
            }
            require(starts.firstOrNull()?.first == 0) { "Expected AVC Annex B" }
            return starts.mapIndexed { index, (offset, prefix) ->
                val end = starts.getOrNull(index + 1)?.first ?: bytes.size
                require(end > offset + prefix) { "Empty AVC NAL unit" }
                bytes.copyOfRange(offset + prefix, end)
            }
        }
    }
}

/** Separate media reading from paced bootstrap delivery. All limits include live backlog. */
internal class PluginAvcQueue(
    private val maxFrames: Int = 600,
    private val maxBytes: Int = 16 * 1024 * 1024,
) {
    private val gop = ArrayDeque<PluginAvcStream.Unit>()
    private val pending = ArrayDeque<PluginAvcStream.Unit>()
    private var gopBytes = 0
    private var pendingBytes = 0
    private var validGop = false
    private var consumer: Any? = null
    private var bootstrappedConsumer: Any? = null

    @Synchronized fun offer(frame: PluginAvcStream.Unit) {
        if (frame.keyframe) { gop.clear(); gopBytes = 0; validGop = true }
        if (validGop) {
            if (gop.size >= maxFrames || frame.bytes.size > maxBytes - gopBytes) {
                gop.clear(); gopBytes = 0; validGop = false
            } else { gop.addLast(frame); gopBytes += frame.bytes.size }
        }
        if (consumer != null) {
            check(pending.size < maxFrames && frame.bytes.size <= maxBytes - pendingBytes) {
                "WebRTC 视频积压，请重新连接或选择 Surface。"
            }
            pending.addLast(frame); pendingBytes += frame.bytes.size
        }
    }

    @Synchronized fun poll(nextConsumer: Any?): PluginAvcStream.Unit? {
        if (consumer !== nextConsumer) {
            consumer = nextConsumer
            pending.clear(); pendingBytes = 0
            // A new decoder may replay one complete GOP. Never rewind an existing decoder.
            if (consumer != null && consumer !== bootstrappedConsumer) {
                if (validGop) { pending.addAll(gop); pendingBytes = gopBytes }
                bootstrappedConsumer = consumer
            }
        }
        if (consumer == null || pending.isEmpty()) return null
        return pending.removeFirst().also { pendingBytes -= it.bytes.size }
    }
    @Synchronized fun clear() { gop.clear(); pending.clear(); gopBytes = 0; pendingBytes = 0; validGop = false; consumer = null; bootstrappedConsumer = null }
}
