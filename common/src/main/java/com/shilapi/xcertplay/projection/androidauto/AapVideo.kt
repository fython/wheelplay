package com.shilapi.xcertplay.projection.androidauto

import com.shilapi.xcertplay.androidauto.AapMediaAssembler
import com.shilapi.xcertplay.media.HeapVideoFrame
import com.shilapi.xcertplay.media.VideoCodec
import com.shilapi.xcertplay.projection.ProjectionVideoOutput
import java.io.ByteArrayOutputStream

/** Keep SPS/PPS out of the transport; convert Annex-B config to the host's avcC port. */
internal class AapVideo(private val output: ProjectionVideoOutput, private val keyframe: () -> Unit) {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var config: ByteArray? = null
    private var revision = 0L
    fun accept(unit: AapMediaAssembler.AccessUnit): Boolean {
        val nals = annexB(unit.data)
        nals.forEach { nal -> when (nal[0].toInt() and 31) { 7 -> sps = nal; 8 -> pps = nal } }
        val s = sps; val p = pps
        if (s != null && p != null) {
            val next = avcC(s, p)
            if (config?.contentEquals(next) != true) { revision = output.configure(VideoCodec.H264, next, keyframe); config = next }
        }
        return revision != 0L && nals.any { it[0].toInt() and 31 in 1..5 } &&
            output.frame(revision, HeapVideoFrame(canonicalAnnexB(nals)), unit.ptsUs)
    }
    companion object {
        private fun canonicalAnnexB(nals: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply {
            nals.forEach { nal ->
                write(byteArrayOf(0, 0, 0, 1))
                write(nal)
            }
        }.toByteArray()

        fun annexB(data: ByteArray): List<ByteArray> {
            val starts = ArrayList<Pair<Int, Int>>()
            var i = 0
            while (i + 3 <= data.size) {
                val size = when {
                    i + 4 <= data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte() -> 4
                    data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte() -> 3
                    else -> 0
                }
                if (size != 0) { starts += i to size; i += size } else i++
            }
            require(starts.isNotEmpty() && starts.first().first == 0) { "Expected Annex-B H.264" }
            return starts.mapIndexedNotNull { index, (start, size) ->
                val end = starts.getOrNull(index + 1)?.first ?: data.size
                if (end > start + size) data.copyOfRange(start + size, end) else null
            }
        }
        fun avcC(sps: ByteArray, pps: ByteArray): ByteArray {
            require(sps.size in 4..65535 && pps.size in 1..65535)
            return ByteArrayOutputStream().apply {
                write(byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte()))
                write(sps.size ushr 8); write(sps.size); write(sps); write(1)
                write(pps.size ushr 8); write(pps.size); write(pps)
            }.toByteArray()
        }
    }
}
