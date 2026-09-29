package com.shilapi.xcertplay.web

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.ScreenCodec
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class NativeVideoDecoderTest {
    private val key = ByteArray(32) { it.toByte() }
    private val header = ByteArray(128) { (it * 3).toByte() }
    private fun plaintext(size: Int) = ByteArray(size) { (it % 251 + 1).toByte() }.apply {
        val length = size - 4
        this[0] = (length ushr 24).toByte(); this[1] = (length ushr 16).toByte()
        this[2] = (length ushr 8).toByte(); this[3] = length.toByte(); this[4] = 0x65
    }
    @Test fun authenticatedFramesMatchJvmAndRejectTamperedHeaderCiphertextAndCounter() {
        for (platform in listOf(false, true)) NativeVideoDecoder(key, platform).use { native ->
            for (counter in listOf(0L, 1L, 0x123456789abcdefL)) {
                val plain = plaintext(32768)
                val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, header)
                val frame = native.open(counter, header, sealed)
                try {
                    assertArrayEquals(ScreenCodec.lengthPrefixedToAnnexB(plain), native.copy(frame))
                    assertTrue(native.keyframe(frame, false))
                } finally { native.release(frame) }
                val corrupt = sealed.copyOf().apply { this[100] = (this[100].toInt() xor 1).toByte() }
                for ((nonce, aad, bytes) in listOf(Triple(counter, header, corrupt),
                    Triple(counter + 1, header, sealed), Triple(counter, header.copyOf().apply { this[0] = 7 }, sealed))) {
                    try { val unexpected = native.open(nonce, aad, bytes); native.release(unexpected); fail("Authentication must fail") }
                    catch (_: javax.crypto.AEADBadTagException) { }
                }
                val again = native.open(counter, header, sealed)
                native.release(again) // A failed authentication cannot poison the reusable context.
            }
        }
    }
    @Test fun retainedFramesSurviveDecoderCloseAndReusedInputBuffers() {
        val decoder = NativeVideoDecoder(key, platform = true)
        val plain = plaintext(1024)
        val encrypted = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), plain, header)
        val first = decoder.decode(0, header, encrypted.copyOf(2048), encrypted.size)
        val retained = first.retain(); first.close()
        val other = decoder.decode(0, header, encrypted, encrypted.size); other.close()
        decoder.close()
        assertArrayEquals(ScreenCodec.lengthPrefixedToAnnexB(plain), retained.toByteArray())
        retained.close()
        try { retained.toByteArray(); fail("Released memory must not be accessed") } catch (_: IllegalStateException) { }
    }

    @Test fun nativeNalConversionMatchesJvmForBothCodecsAndMalformedLengths() {
        val cases = listOf(byteArrayOf(0,0,0,2,0x26,1), byteArrayOf(0,0,0,2,0x2a,1),
            byteArrayOf(0,0,1,0x65,7), byteArrayOf(0,0,0,1,0x41,7),
            byteArrayOf(0,0,0,9,0x65,1), byteArrayOf(0,0,0,0), byteArrayOf(-1,-1,-1,-1))
        for (platform in listOf(false, true)) NativeVideoDecoder(key, platform).use { decoder ->
            cases.forEach { plain ->
                val encrypted = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), plain, header)
                decoder.decode(0, header, encrypted, encrypted.size).use { frame ->
                    assertArrayEquals(ScreenCodec.lengthPrefixedToAnnexB(plain.copyOf()), frame.toByteArray())
                }
            }
            for (codec in com.shilapi.xcertplay.airplay.VideoCodec.entries) {
                val plain = if (codec == com.shilapi.xcertplay.airplay.VideoCodec.H264) byteArrayOf(0,0,0,2,0x65,1) else byteArrayOf(0,0,0,2,0x26,1)
                val encrypted = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), plain, header)
                decoder.decode(0, header, encrypted, encrypted.size).use { assertTrue(it.isRandomAccess(codec)) }
            }
        }
    }

    @Test fun reusableScreenInputPreservesFrameSizesAuthenticationAndCounter() {
        val latch = java.util.concurrent.CountDownLatch(3)
        val frames = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val errors = java.util.concurrent.LinkedBlockingQueue<Throwable>()
        val screen = com.shilapi.xcertplay.airplay.ScreenStream(key, {}, { NativeVideoDecoder(it, platform = true) })
        val port = screen.listen(object : com.shilapi.xcertplay.airplay.ScreenStream.Listener {
            override fun onFrame(frame: com.shilapi.xcertplay.media.CompressedVideoFrame, pts: Long, receivedAtNs: Long, decryptNs: Long) {
                frames.offer(frame.toByteArray()); latch.countDown()
            }
            override fun onClosed(cause: Throwable?) { if (cause != null) errors.offer(cause) }
        })
        try {
            java.net.Socket("127.0.0.1", port).use { socket ->
                val expected = listOf(plaintext(8192), plaintext(128), plaintext(16384))
                expected.forEachIndexed { index, plain ->
                    val length = plain.size + 16
                    val wireHeader = ByteArray(128).apply {
                        this[0] = length.toByte(); this[1] = (length ushr 8).toByte()
                    }
                    val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(index.toLong()), plain, wireHeader)
                    socket.getOutputStream().apply { write(wireHeader,0,13); write(wireHeader,13,115); write(sealed); flush() }
                }
                assertTrue("reader failure: ${errors.peek()}", latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                expected.forEach { assertArrayEquals(ScreenCodec.lengthPrefixedToAnnexB(it), frames.poll()) }
            }
        } finally { screen.close() }
    }

    @Test fun compareVideoDecryptionImplementations() {
        val nonce = AirPlayCrypto.nonce64(0)
        for (size in listOf(32 * 1024, 128 * 1024, 512 * 1024)) {
            val plain = plaintext(size)
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, header)
            NativeVideoDecoder(key).use { native ->
                val platformBuffer = NativeVideoDecoder(key, platform = true)
                val reused = ChaCha20Poly1305()
                val output = ByteArray(size)
                val variants = linkedMapOf<String, () -> Unit>(
                    "jvm-current" to { ScreenCodec.lengthPrefixedToAnnexB(AirPlayCrypto.chachaOpen(key, nonce, sealed, header)); Unit },
                    "jvm-reuse" to {
                        reused.init(false, AEADParameters(KeyParameter(key), 128, nonce, header))
                        val n = reused.processBytes(sealed, 0, sealed.size, output, 0); reused.doFinal(output, n)
                        ScreenCodec.lengthPrefixedToAnnexB(output); Unit
                    },
                    "native-copy" to { val frame = native.open(0, header, sealed); try { native.copy(frame) } finally { native.release(frame) }; Unit },
                    "platform-native-buffer" to { val frame = platformBuffer.open(0, header, sealed); try { platformBuffer.keyframe(frame, false) } finally { platformBuffer.release(frame) }; Unit },
                    "native-buffer" to { val frame = native.open(0, header, sealed); try { native.keyframe(frame, false) } finally { native.release(frame) }; Unit },
                )
                try {
                    val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
                    val secret = SecretKeySpec(key, "ChaCha20")
                    val action = {
                        cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(nonce)); cipher.updateAAD(header)
                        cipher.doFinal(sealed, 0, sealed.size, output, 0)
                        ScreenCodec.lengthPrefixedToAnnexB(output); Unit
                    }
                    action(); variants["jce-${cipher.provider.name}"] = action
                } catch (e: Exception) { Log.i("NativeVideoBench", "JCE unavailable: ${e.javaClass.simpleName}") }
                variants.values.forEach { action -> repeat(20) { action() } }
                // Rotate order between rounds to reduce warmup and thermal ordering bias.
                repeat(3) { round ->
                    val entries = variants.entries.toList()
                    for (offset in entries.indices) {
                        val (name, action) = entries[(offset + round) % entries.size]
                        val allocationBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0
                        val cpuBefore = Debug.threadCpuTimeNanos()
                        val samples = LongArray(30) { val start = System.nanoTime(); action(); System.nanoTime() - start }.sorted()
                        val cpu = Debug.threadCpuTimeNanos() - cpuBefore
                        val allocated = (Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0) - allocationBefore
                        Log.i("NativeVideoBench", "size=$size round=$round impl=$name meanUs=${samples.average()/1000} p95Us=${samples[28]/1000} cpuUs=${cpu/30000} javaBytesPerFrame=${allocated/30}")
                    }
                }
                platformBuffer.close()
            }
        }
    }
}
