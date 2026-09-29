package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/** Optional compressed-video consumer. True means the local decoder may be suspended. */
interface EncodedVideoSink {
    fun createFrameDecoder(key: ByteArray): VideoFrameDecoder? = null
    fun frame(type: Int, frame: CompressedVideoFrame, pts: Long, receivedAtNs: Long, decryptNs: Long): Boolean =
        frame(type, frame.toByteArray(), pts, receivedAtNs, decryptNs)
    fun configure(type: Int, codec: VideoCodec, data: ByteArray)
    fun frame(type: Int, data: ByteArray, presentationTimeUs: Long): Boolean
    fun frame(type: Int, data: ByteArray, presentationTimeUs: Long, receivedAtNs: Long, decryptNs: Long): Boolean =
        frame(type, data, presentationTimeUs)
    fun recovery(type: Int, request: () -> Unit)
    fun stopped(type: Int)
}
