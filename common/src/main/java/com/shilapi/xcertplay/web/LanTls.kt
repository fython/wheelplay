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
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.InputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/** Local CA or imported server identity, stored only in private non-backup storage. */
internal object LanTls {
    data class Endpoint(val sockets: SSLServerSocketFactory, val certificate: ByteArray, val fingerprint: String,
                        val custom: Boolean = false, val subject: String = "")
    class Prepared(val encoded: ByteArray, val endpoint: Endpoint) : AutoCloseable {
        override fun close() { encoded.fill(0) }
    }
    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    private val provider = BouncyCastleProvider()
    fun readImport(input: InputStream): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_IMPORT_BYTES) { "证书文件不能超过 2 MiB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray().also { require(it.isNotEmpty()) { "文件为空" } }
    }
    private fun customFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, "browser-server-custom.p12"))
    fun hasCustom(context: Context) = customFile(context).baseFile.exists()

    @Synchronized fun create(context: Context, addresses: List<String>, hostname: String = ""): Endpoint {
        val file = customFile(context)
        if (file.baseFile.exists()) {
            val store = KeyStore.getInstance("PKCS12")
            file.openRead().use { store.load(it, CharArray(0)) }
            return endpoint(store, "server", true)
        }
        return createDefault(context, addresses, hostname)
    }

    @Synchronized fun createDefault(context: Context, addresses: List<String>, hostname: String = ""): Endpoint {
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
            keys.public, rootKey, 7, false, (addresses + "127.0.0.1" + "::1").distinct(), hostname)
        val leafStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password); setKeyEntry("server", keys.private, password, arrayOf(leaf, root))
        }
        return endpoint(leafStore, "server", false)
    }

    private fun endpoint(store: KeyStore, alias: String, custom: Boolean): Endpoint {
        val chain = store.getCertificateChain(alias).map { it as X509Certificate }
        chain.forEach { it.checkValidity() }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        val certificate = chain.last()
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 255) }
        return Endpoint(tls.serverSocketFactory, certificate.encoded, fingerprint, custom, chain.first().subjectX500Principal.name)
    }

    fun preparePem(certificates: ByteArray, privateKey: ByteArray, password: CharArray = CharArray(0)): Prepared {
        require(certificates.size in 1..MAX_IMPORT_BYTES && privateKey.size in 1..MAX_IMPORT_BYTES) { "证书或私钥文件过大/为空" }
        val chain = CertificateFactory.getInstance("X.509").generateCertificates(certificates.inputStream())
            .map { it as X509Certificate }
        val converter = JcaPEMKeyConverter().setProvider(provider)
        val key = PEMParser(StringReader(privateKey.toString(Charsets.UTF_8))).use { parser ->
            var value = parser.readObject()
            // OpenSSL traditional EC PEM can prefix the key with named or explicit curve parameters.
            if (value is ASN1ObjectIdentifier || value is X9ECParameters) value = parser.readObject()
            val info = when (value) {
                is PEMKeyPair -> value.privateKeyInfo
                is PrivateKeyInfo -> value
                is PEMEncryptedKeyPair -> value.decryptKeyPair(JcePEMDecryptorProviderBuilder().setProvider(provider).build(password)).privateKeyInfo
                is PKCS8EncryptedPrivateKeyInfo -> value.decryptPrivateKeyInfo(JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(provider).build(password))
                else -> error("请选择 PEM 私钥（PKCS#8、RSA 或 EC）")
            }
            require(parser.readObject() == null) { "私钥文件必须只包含一把私钥" }
            converter.getPrivateKey(info)
        }
        return prepare(key, chain)
    }

    private fun loadPkcs12(bytes: ByteArray, password: CharArray): KeyStore {
        require(bytes.size in 1..MAX_IMPORT_BYTES) { "PKCS#12 文件过大/为空" }
        return runCatching { KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), password) } }
            .getOrElse { KeyStore.getInstance("PKCS12", provider).apply { load(bytes.inputStream(), password) } }
    }

    fun pkcs12Aliases(bytes: ByteArray, password: CharArray): List<String> {
        val store = loadPkcs12(bytes, password)
        return store.aliases().toList().filter { store.isKeyEntry(it) }.also { require(it.isNotEmpty()) { "PKCS#12 中没有私钥条目" } }
    }

    fun preparePkcs12(bytes: ByteArray, password: CharArray, alias: String? = null,
                      keyPassword: CharArray = password): Prepared {
        val store = loadPkcs12(bytes, password)
        val aliases = store.aliases().toList().filter { store.isKeyEntry(it) }
        val selected = alias ?: aliases.singleOrNull() ?: error("请选择 PKCS#12 中的私钥条目")
        require(selected in aliases) { "所选条目没有私钥" }
        val key = store.getKey(selected, keyPassword) as? PrivateKey ?: error("条目没有可用私钥，请检查私钥密码")
        val chain = store.getCertificateChain(selected)?.map { it as X509Certificate } ?: error("条目缺少证书链")
        return prepare(key, chain)
    }

    private fun prepare(privateKey: PrivateKey, chain: List<X509Certificate>): Prepared {
        // PEM converters may name an EC key "ECDSA". Conscrypt's PrivateKeyEntry
        // requires the same "EC" algorithm name as the certificate's public key.
        val key = if (privateKey.algorithm.equals("ECDSA", ignoreCase = true))
            KeyFactory.getInstance("EC", provider).generatePrivate(PKCS8EncodedKeySpec(privateKey.encoded))
        else privateKey
        require(chain.isNotEmpty() && chain.first().basicConstraints < 0) { "需要服务器证书，不能使用 CA 证书作为服务器身份" }
        chain.forEach { it.checkValidity() }
        val signatureAlgorithm = when (key.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            "ED25519" -> "Ed25519"
            "ED448" -> "Ed448"
            else -> error("不支持的私钥算法：${key.algorithm}")
        }
        val probe = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature = Signature.getInstance(signatureAlgorithm, provider).apply { initSign(key); update(probe) }.sign()
        val matches = Signature.getInstance(signatureAlgorithm, provider).apply { initVerify(chain.first()); update(probe) }.verify(signature)
        require(matches) { "服务器证书与私钥不匹配" }
        for (i in 0 until chain.lastIndex) {
            require(chain[i].issuerX500Principal == chain[i + 1].subjectX500Principal) { "证书链应按服务器证书、签发者顺序排列" }
            chain[i].verify(chain[i + 1].publicKey, provider)
        }
        chain.first().extendedKeyUsage?.let {
            require("1.3.6.1.5.5.7.3.1" in it || "2.5.29.37.0" in it) { "证书不允许 TLS 服务器用途" }
        }
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, CharArray(0)); setKeyEntry("server", key, CharArray(0), chain.toTypedArray())
        }
        val bytes = ByteArrayOutputStream().use { store.store(it, CharArray(0)); it.toByteArray() }
        return Prepared(bytes, endpoint(store, "server", true))
    }

    @Synchronized fun saveCustom(context: Context, prepared: Prepared) {
        val file = customFile(context)
        val output = file.startWrite()
        try {
            output.write(prepared.encoded)
            file.finishWrite(output)
            file.baseFile.setReadable(false, false); file.baseFile.setReadable(true, true)
            file.baseFile.setWritable(false, false); file.baseFile.setWritable(true, true)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }

    @Synchronized fun clearCustom(context: Context) {
        val file = customFile(context)
        file.delete()
        check(!file.baseFile.exists()) { "自定义证书清除失败" }
    }

    private fun certificate(issuer: X500Name, subject: X500Name, publicKey: java.security.PublicKey,
        signer: PrivateKey, days: Int, ca: Boolean, addresses: List<String>, hostname: String = ""): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(issuer, BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - 300_000), Date(now + days * 86_400_000L), subject, publicKey)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign
            else KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        if (!ca) {
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(
                (addresses.map { GeneralName(GeneralName.iPAddress, it) } + GeneralName(GeneralName.dNSName, "localhost") +
                    listOfNotNull(hostname.takeIf { it.isNotEmpty() }?.let { GeneralName(GeneralName.dNSName, it) })).toTypedArray()))
        }
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(signer)))
    }
}
