package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable

/** Borrowed during callbacks. Async consumers retain once and close on send/drop/shutdown. */
interface CompressedVideoFrame : Closeable {
    val size: Int
    fun retain(): CompressedVideoFrame
    fun isRandomAccess(codec: VideoCodec): Boolean
    fun toByteArray(): ByteArray
}
class HeapVideoFrame(private val bytes: ByteArray) : CompressedVideoFrame {
    override val size get() = bytes.size
    override fun retain() = this
    override fun isRandomAccess(codec: VideoCodec) = MediaCodecSupport.isRandomAccess(bytes, codec)
    override fun toByteArray() = bytes
    override fun close() = Unit
}
interface VideoFrameDecoder : Closeable {
    val name: String
    fun decode(counter: Long, header: ByteArray, body: ByteArray, size: Int): CompressedVideoFrame
}
