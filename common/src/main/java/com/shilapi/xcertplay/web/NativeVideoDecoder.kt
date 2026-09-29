package com.shilapi.xcertplay.web

import androidx.annotation.Keep
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.CompressedVideoFrame
import com.shilapi.xcertplay.media.VideoFrameDecoder
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Session-scoped cipher; decrypt/destroy are confined to the screen reader thread. */
@Keep
internal class NativeVideoDecoder(key: ByteArray, platform: Boolean = false) : VideoFrameDecoder {
    private val secret = SecretKeySpec(key, "ChaCha20")
    private val cipher = if (platform) try {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(ByteArray(12)))
        }
    } catch (_: java.security.GeneralSecurityException) { null } else null
    private var output = ByteArray(0)
    override val name get() = cipher?.provider?.name ?: "MbedTLS"
    override fun decode(counter: Long, header: ByteArray, body: ByteArray, size: Int): CompressedVideoFrame =
        NativeVideoFrame(this, open(counter, header, body, size), size - 16)
    private var handle = create(key)
    fun open(counter: Long, header: ByteArray, body: ByteArray, size: Int = body.size): Long {
        check(handle != 0L)
        require(header.size == 128 && size in 16..minOf(body.size, 8 * 1024 * 1024))
        val platformCipher = cipher ?: return decrypt(handle, counter, header, body, size)
        if (output.size < size - 16) output = ByteArray(size - 16)
        platformCipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(AirPlayCrypto.nonce64(counter)))
        platformCipher.updateAAD(header)
        // Authentication errors terminate the stream; never retry unauthenticated bytes via fallback.
        val length = platformCipher.doFinal(body, 0, size, output, 0)
        return importFrame(handle, output, length)
    }
    override fun close() { if (handle != 0L) { destroy(handle); handle = 0 } }
    private external fun create(key: ByteArray): Long
    private external fun importFrame(handle: Long, bytes: ByteArray, size: Int): Long
    private external fun decrypt(handle: Long, counter: Long, header: ByteArray, body: ByteArray, size: Int): Long
    external fun copy(frame: Long): ByteArray
    external fun keyframe(frame: Long, hevc: Boolean): Boolean
    external fun release(frame: Long)
    private external fun destroy(handle: Long)
    companion object { init { System.loadLibrary("wheelplay-rtc") } }
}

/** The native pool outlives its decoder until every retained frame is returned. */
internal class NativeVideoFrame(private val owner: NativeVideoDecoder, private val pointer: Long, override val size: Int) : CompressedVideoFrame {
    private val references = AtomicInteger(1)
    val handle get(): Long { check(references.get() > 0); return pointer }
    override fun retain(): NativeVideoFrame {
        while (true) {
            val count = references.get(); check(count > 0)
            if (references.compareAndSet(count, count + 1)) return this
        }
    }
    override fun isRandomAccess(codec: VideoCodec) = owner.keyframe(handle, codec == VideoCodec.H265)
    override fun toByteArray() = owner.copy(handle)
    override fun close() {
        val count = references.decrementAndGet(); check(count >= 0)
        if (count == 0) owner.release(pointer)
    }
}
