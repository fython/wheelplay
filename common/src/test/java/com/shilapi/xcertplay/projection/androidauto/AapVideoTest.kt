package com.shilapi.xcertplay.projection.androidauto

import android.view.Surface
import com.shilapi.xcertplay.androidauto.AapMediaAssembler
import com.shilapi.xcertplay.media.*
import com.shilapi.xcertplay.projection.*
import org.junit.Assert.*
import org.junit.Test

class AapVideoTest {
    private class Output : ProjectionVideoOutput {
        override val surface: Surface get() = error("encoded")
        val configs = mutableListOf<ByteArray>()
        val frameData = mutableListOf<ByteArray>()
        var frames = 0
        override fun configure(codec: VideoCodec, codecData: ByteArray, requestKeyframe: () -> Unit): Long {
            configs += codecData; return configs.size.toLong()
        }
        override fun frame(formatRevision: Long, frame: CompressedVideoFrame, ptsUs: Long): Boolean {
            assertEquals(configs.size.toLong(), formatRevision)
            frameData += frame.toByteArray()
            frames++
            return true
        }
        override fun framebuffer(snapshot: ProjectionFramebuffer.Snapshot) = false
        override fun close() = Unit
    }
    @Test fun annexBConfigBecomesAvcCBeforeFramesAndReconfiguresOnChange() {
        val out = Output(); val video = AapVideo(out) {}
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1e, 1)
        val pps = byteArrayOf(0x68, 2)
        fun nal(bytes: ByteArray) = byteArrayOf(0, 0, 0, 1) + bytes
        video.accept(AapMediaAssembler.AccessUnit(nal(byteArrayOf(0x65, 4)), 42))
        assertEquals(0, out.frames)
        video.accept(AapMediaAssembler.AccessUnit(nal(sps) + nal(pps), 0))
        assertArrayEquals(byteArrayOf(1, 0x42, 0, 0x1e, -1, -31, 0, 5) + sps + byteArrayOf(1, 0, 2) + pps, out.configs.single())
        video.accept(AapMediaAssembler.AccessUnit(nal(byteArrayOf(0x65, 4)), 43))
        assertEquals(1, out.frames)
        video.accept(AapMediaAssembler.AccessUnit(nal(sps) + nal(pps), 0))
        assertEquals(1, out.configs.size)
        video.accept(AapMediaAssembler.AccessUnit(nal(sps.copyOf().also { it[4] = 3 }) + nal(pps), 0))
        assertEquals(2, out.configs.size)
    }

    @Test fun threeByteFourByteAndMixedStartCodesReachThePublicImageDecoderAsCanonicalAnnexB() {
        val out = Output(); val video = AapVideo(out) {}
        val sps = byteArrayOf(0x67, 0x42, 0, 0x1e, 1)
        val pps = byteArrayOf(0x68, 2)
        val start3 = byteArrayOf(0, 0, 1)
        val start4 = byteArrayOf(0, 0, 0, 1)
        video.accept(AapMediaAssembler.AccessUnit(start3 + sps + start4 + pps, 0))
        assertEquals(1, out.configs.size)

        val idr = byteArrayOf(0x65, 4)
        val slice = byteArrayOf(0x41, 5)
        val inputs = listOf(start3 + idr, start4 + idr, start3 + idr + start4 + slice)
        val expected = listOf(start4 + idr, start4 + idr, start4 + idr + start4 + slice)
        inputs.forEachIndexed { index, input ->
            video.accept(AapMediaAssembler.AccessUnit(input, 42L + index))
            val encodedFrame = out.frameData[index]
            assertArrayEquals(expected[index], encodedFrame)
            assertArrayEquals(encodedFrame, com.shilapi.xcertplay.media.MediaCodecSupport.toAnnexB(encodedFrame))
        }
    }
}
