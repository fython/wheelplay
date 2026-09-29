package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.media.CompressedVideoFrame
import com.shilapi.xcertplay.media.HeapVideoFrame
import com.shilapi.xcertplay.media.VideoFrameDecoder
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(private val key: ByteArray, private val onDiagnostic: (String) -> Unit = {}, private val decoderFactory: ((ByteArray) -> VideoFrameDecoder?)? = null) : Closeable {
    interface Listener {
        fun onFrame(frame: CompressedVideoFrame, pts: Long, receivedAtNs: Long, decryptNs: Long) = onFrame(frame.toByteArray(), pts, receivedAtNs, decryptNs)
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        fun onFrame(naluBytes: ByteArray, presentationTimeUs: Long) = onFrame(naluBytes)
        fun onFrame(naluBytes: ByteArray, presentationTimeUs: Long, receivedAtNs: Long, decryptNs: Long) = onFrame(naluBytes, presentationTimeUs)
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private var frameDecoder: VideoFrameDecoder? = null
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        try {
            frameDecoder = decoderFactory?.invoke(key)
            onDiagnostic("Video processing: ${frameDecoder?.name ?: "JVM"}")
            val input = sock.getInputStream()
            val header = ByteArray(HEADER_LEN)
            var reusableBody = ByteArray(0)
            while (!closed.get()) {
                stats.reading()
                if (!readInto(input, header, HEADER_LEN)) break
                val bodySize = readU32Le(header, 0)
                if (bodySize < 0 || bodySize > MAX_BODY) break
                val nativeFrame = frameDecoder != null && (header[OPCODE_OFFSET].toInt() and 255) == OP_VIDEO_FRAME
                val body = if (nativeFrame) {
                    if (reusableBody.size < bodySize) reusableBody = ByteArray(minOf(MAX_BODY, maxOf(bodySize, reusableBody.size * 2)))
                    if (!readInto(input, reusableBody, bodySize)) break
                    reusableBody
                } else readFully(input, bodySize) ?: break
                stats.received(HEADER_LEN + bodySize)
                onMessage(header, body, bodySize)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            frameDecoder?.close(); frameDecoder = null
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, bodySize: Int) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                val receivedAtNs = System.nanoTime()
                val decoder = frameDecoder
                if (decoder != null && bodySize >= ScreenCodec.TAG_SIZE) {
                    val frame = decoder.decode(frameCounter.get(), header, body, bodySize)
                    frameCounter.incrementAndGet()
                    val decryptNs = System.nanoTime() - receivedAtNs
                    frame.use { listener.onFrame(it, ScreenCodec.presentationTimeUs(header), receivedAtNs, decryptNs) }
                } else {
                    val exact = if (body.size == bodySize) body else body.copyOf(bodySize)
                    val payload = if (exact.size >= ScreenCodec.TAG_SIZE) {
                        ScreenCodec.decryptFrame(key, frameCounter.get(), header, exact).also { frameCounter.incrementAndGet() }
                    } else exact
                    val decryptNs = System.nanoTime() - receivedAtNs
                    HeapVideoFrame(ScreenCodec.lengthPrefixedToAnnexB(payload)).use {
                        listener.onFrame(it, ScreenCodec.presentationTimeUs(header), receivedAtNs, decryptNs)
                    }
                }
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = ScreenCodec.detectConfig(body)
                Log.i(TAG, "video codec config codec=$codec body=${body.size} data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readInto(input: InputStream, output: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(output, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return output
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /** AirPlayScreenHeader stores a little-endian unsigned NTP 32.32 PTS at byte 8. */
    fun presentationTimeUs(header: ByteArray): Long {
        require(header.size >= 16)
        val fraction = readU32Le(header, 8).toLong() and 0xffffffffL
        val seconds = readU32Le(header, 12).toLong() and 0xffffffffL
        return seconds * 1_000_000L + ((fraction * 1_000_000L) ushr 32)
    }

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
