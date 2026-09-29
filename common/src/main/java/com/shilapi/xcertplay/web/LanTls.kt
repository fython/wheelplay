package com.shilapi.xcertplay.web

import android.content.Context
import android.util.AtomicFile
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/** Per-installation local CA, stored only in private non-backup storage. No private-key export. */
internal object LanTls {
    data class Endpoint(val sockets: SSLServerSocketFactory, val certificate: ByteArray, val fingerprint: String)
    private val provider = BouncyCastleProvider()
    @Synchronized fun create(context: Context, addresses: List<String>): Endpoint {
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12")
        val file = AtomicFile(File(context.noBackupFilesDir, "browser-audio-ca.p12"))
        if (file.baseFile.exists()) file.openRead().use { store.load(it, password) }
        else {
            store.load(null, password)
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=WheelPlay Local CA ${BigInteger(32, SecureRandom()).toString(16)}")
            val root = certificate(name, name, keys.public, keys.private, 3650, true, emptyList())
            store.setKeyEntry("ca", keys.private, password, arrayOf(root))
            val output = file.startWrite()
            try { store.store(output, password); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
        val root = store.getCertificate("ca") as X509Certificate
        val rootKey = store.getKey("ca", password) as PrivateKey
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val leaf = certificate(X500Name(root.subjectX500Principal.name), X500Name("CN=WheelPlay LAN"),
            keys.public, rootKey, 7, false, (addresses + "127.0.0.1" + "::1").distinct())
        val leafStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password); setKeyEntry("server", keys.private, password, arrayOf(leaf, root))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(leafStore, password) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(root.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 255) }
        return Endpoint(tls.serverSocketFactory, root.encoded, fingerprint)
    }

    private fun certificate(issuer: X500Name, subject: X500Name, publicKey: java.security.PublicKey,
        signer: PrivateKey, days: Int, ca: Boolean, addresses: List<String>): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(issuer, BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - 300_000), Date(now + days * 86_400_000L), subject, publicKey)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign
            else KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        if (!ca) {
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(
                (addresses.map { GeneralName(GeneralName.iPAddress, it) } + GeneralName(GeneralName.dNSName, "localhost")).toTypedArray()))
        }
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(signer)))
    }
}
