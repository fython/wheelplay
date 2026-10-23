package com.shilapi.xcertplay.projection.androidauto

import com.shilapi.xcertplay.androidauto.AapFraming
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** AAP is TLS carried in channel-zero envelopes, not TLS over the underlying socket directly. */
internal class AapTls(clientMode: Boolean = true) {
    internal val engine: SSLEngine
    init {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=WheelPlay Android Auto")
        val certificate = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(
            name, BigInteger(128, SecureRandom()), Date(System.currentTimeMillis() - 86400000),
            Date(System.currentTimeMillis() + 365L * 86400000), name, pair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
        val password = CharArray(0)
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("headunit", pair.private, password, arrayOf(certificate))
        }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        // AAP peers use protocol-specific/self-signed certificates; this is not HTTPS trust.
        // Access is limited to an explicitly selected USB device or phone endpoint.
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        engine = SSLContext.getInstance("TLSv1.2").apply { init(km.keyManagers, arrayOf(trust), SecureRandom()) }
            .createSSLEngine().apply { useClientMode = clientMode; enabledProtocols = arrayOf("TLSv1.2") }
    }
    private fun tasks() { while (true) (engine.delegatedTask ?: break).run() }
    fun handshake(input: DataInputStream, output: OutputStream) {
        AapFraming.write(output, 0, 3, AapFraming.message(1, byteArrayOf(0, 1, 0, 2)))
        val version = AapFraming.read(input)
        require(version.channel == 0 && version.flags == 3 && AapFraming.type(version.payload) == 2)
        require(version.payload.size >= 8 && version.payload[6] == 0.toByte() && version.payload[7] == 0.toByte()) { "Unsupported AAP version" }
        engine.beginHandshake()
        var pending = ByteArray(0)
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> tasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val buffer = ByteBuffer.allocate(65535)
                    val result = engine.wrap(ByteBuffer.allocate(0), buffer)
                    check(result.status == SSLEngineResult.Status.OK)
                    if (buffer.position() > 0) AapFraming.write(output, 0, 3, AapFraming.message(3, buffer.array().copyOf(buffer.position())))
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (pending.isEmpty()) pending = handshakePacket(input)
                    val source = ByteBuffer.wrap(pending)
                    val result = engine.unwrap(source, ByteBuffer.allocate(65535))
                    pending = pending.copyOfRange(source.position(), pending.size)
                    when (result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> { pending += handshakePacket(input); require(pending.size <= 262144) }
                        SSLEngineResult.Status.OK -> Unit
                        else -> error("AAP TLS handshake: ${result.status}")
                    }
                }
                SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> break
                else -> error("Unexpected TLS handshake state")
            }
        }
        require(pending.isEmpty())
        AapFraming.write(output, 0, 3, AapFraming.message(4, byteArrayOf(8, 0)))
    }
    private fun handshakePacket(input: DataInputStream): ByteArray {
        val packet = AapFraming.read(input)
        require(packet.channel == 0 && packet.flags == 3 && AapFraming.type(packet.payload) == 3)
        return packet.payload.copyOfRange(2, packet.payload.size)
    }
    @Synchronized fun encrypt(bytes: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(bytes); val dst = ByteBuffer.allocate(65535)
        val result = engine.wrap(src, dst)
        check(result.status == SSLEngineResult.Status.OK && !src.hasRemaining())
        return dst.array().copyOf(dst.position())
    }
    @Synchronized fun decrypt(bytes: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(bytes); val dst = ByteBuffer.allocate(65535)
        while (src.hasRemaining()) {
            val result = engine.unwrap(src, dst)
            check(result.status == SSLEngineResult.Status.OK && result.bytesConsumed() > 0) { "Invalid AAP TLS record" }
            tasks()
        }
        return dst.array().copyOf(dst.position())
    }
}
