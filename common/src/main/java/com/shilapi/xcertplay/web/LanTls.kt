package com.shilapi.xcertplay.web

import android.content.Context
import android.util.AtomicFile
import com.shilapi.xcertplay.i18n.*
import com.shilapi.xcertplay.shared.R as UiR
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.io.StringWriter
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
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter

/** Local CA, imported server identity, or imported signer in private non-backup storage. */
internal object LanTls {
    data class Endpoint(
        val sockets: SSLServerSocketFactory,
        val certificate: ByteArray,
        val fingerprint: String,
        val custom: Boolean = false,
        val subject: String = "",
        val signingCa: Boolean = false,
        val bundledCa: Boolean = false,
    )

    class Prepared(val encoded: ByteArray, val endpoint: Endpoint) : AutoCloseable {
        override fun close() {
            encoded.fill(0)
        }
    }

    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    const val BUNDLED_CA_ASSET = "web-tls/emudhra-ca.pem"
    private val provider = BouncyCastleProvider()

    fun readImport(input: InputStream): ByteArray =
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                requireUi(output.size() + count <= MAX_IMPORT_BYTES) {
                    UiText(UiR.string.ui_certificate_file_must_not_exceed_2_mib)
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also {
                requireUi(it.isNotEmpty()) { UiText(UiR.string.ui_file_is_empty) }
            }
        }

    private fun customFile(context: Context) =
        AtomicFile(File(context.noBackupFilesDir, "browser-server-custom.p12"))

    fun hasCustom(context: Context) = customFile(context).baseFile.exists()

    fun hasBundledCa(context: Context) =
        try { context.assets.open(BUNDLED_CA_ASSET).use { true } }
        catch (_: java.io.FileNotFoundException) { false }

    fun usesBundledCa(context: Context) = !hasCustom(context) && WebListenSettings.bundledCaEnabled(context)

    private fun loadCustom(context: Context): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            customFile(context).openRead().use { load(it, CharArray(0)) }
        }

    @Synchronized
    fun hasSigningCa(context: Context) =
        hasCustom(context) && loadCustom(context).isKeyEntry("signer")

    fun generatesCertificates(context: Context) = !hasCustom(context) || hasSigningCa(context)

    @Synchronized
    fun create(context: Context, addresses: List<String>, hostname: String = ""): Endpoint {
        val file = customFile(context)
        if (file.baseFile.exists()) {
            val store = loadCustom(context)
            if (store.isKeyEntry("signer")) return signingEndpoint(store, addresses, hostname)
            return endpoint(store, "server", true)
        }
        if (usesBundledCa(context)) return createBundled(context, addresses, hostname)
        return createDefault(context, addresses, hostname)
    }

    fun createBundled(context: Context, addresses: List<String>, hostname: String = ""): Endpoint {
        val pem = context.assets.open(BUNDLED_CA_ASSET).use(::readImport)
        return try {
            prepareSigningCaBundle(pem, addresses, hostname).use {
                it.endpoint.copy(bundledCa = true)
            }
        } finally { pem.fill(0) }
    }

    /** Combined key + certificate PEM, like mitmproxy's embedded CA bundle. */
    fun prepareSigningCaBundle(pem: ByteArray, addresses: List<String>, hostname: String = ""): Prepared {
        require(pem.size in 1..MAX_IMPORT_BYTES) { "Invalid HTTPS CA bundle size" }
        val certificates = StringWriter()
        val privateKey = StringWriter()
        PemWriter(certificates).use { certWriter ->
            PemWriter(privateKey).use { keyWriter ->
                PemReader(StringReader(pem.toString(Charsets.UTF_8))).use { reader ->
                    while (true) {
                        val value = reader.readPemObject() ?: break
                        if (value.type == "CERTIFICATE") certWriter.writeObject(value)
                        else keyWriter.writeObject(value)
                    }
                }
            }
        }
        val chain = certificates.toString().toByteArray(Charsets.UTF_8)
        val key = privateKey.toString().toByteArray(Charsets.UTF_8)
        return try { prepareSigningCaPem(chain, key, addresses = addresses, hostname = hostname) }
        finally { chain.fill(0); key.fill(0) }
    }

    @Synchronized
    fun createDefault(context: Context, addresses: List<String>, hostname: String = ""): Endpoint {
        val password = CharArray(0)
        val store = KeyStore.getInstance("PKCS12")
        val file = AtomicFile(File(context.noBackupFilesDir, "browser-audio-ca.p12"))
        if (file.baseFile.exists()) file.openRead().use { store.load(it, password) }
        else {
            store.load(null, password)
            val keys =
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name =
                X500Name("CN=WheelPlay Local CA ${BigInteger(32, SecureRandom()).toString(16)}")
            val root = certificate(name, name, keys.public, keys.private, 3650, true, emptyList())
            store.setKeyEntry("ca", keys.private, password, arrayOf(root))
            val output = file.startWrite()
            try {
                store.store(output, password)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
        val root = store.getCertificate("ca") as X509Certificate
        val rootKey = store.getKey("ca", password) as PrivateKey
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val leaf =
            certificate(
                X500Name(root.subjectX500Principal.name),
                X500Name("CN=WheelPlay LAN"),
                keys.public,
                rootKey,
                7,
                false,
                (addresses + "127.0.0.1" + "::1").distinct(),
                hostname,
            )
        val leafStore =
            KeyStore.getInstance("PKCS12").apply {
                load(null, password)
                setKeyEntry("server", keys.private, password, arrayOf(leaf, root))
            }
        return endpoint(leafStore, "server", false)
    }

    private fun endpoint(
        store: KeyStore,
        alias: String,
        custom: Boolean,
        signingCa: Boolean = false,
    ): Endpoint {
        val chain = store.getCertificateChain(alias).map { it as X509Certificate }
        chain.forEach { it.checkValidity() }
        val managers =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, CharArray(0))
            }
        val tls =
            SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        val certificate = chain.last()
        val fingerprint =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it.toInt() and 255) }
        return Endpoint(
            tls.serverSocketFactory,
            certificate.encoded,
            fingerprint,
            custom,
            chain.first().subjectX500Principal.name,
            signingCa,
        )
    }

    fun preparePem(
        certificates: ByteArray,
        privateKey: ByteArray,
        password: CharArray = CharArray(0),
    ): Prepared {
        val (key, chain) = readPemIdentity(certificates, privateKey, password)
        return prepare(key, chain)
    }

    /** The first certificate is the signing CA, followed by its ordered issuer chain. */
    fun prepareSigningCaPem(
        certificates: ByteArray,
        privateKey: ByteArray,
        password: CharArray = CharArray(0),
        addresses: List<String>,
        hostname: String = "",
    ): Prepared {
        val (parsedKey, chain) = readPemIdentity(certificates, privateKey, password)
        val key = normalizeKey(parsedKey)
        SigningCa.validate(key, chain)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, CharArray(0))
            setKeyEntry("signer", key, CharArray(0), chain.toTypedArray())
        }
        val endpoint = signingEndpoint(store, addresses, hostname)
        return Prepared(encode(store), endpoint)
    }

    private fun signingEndpoint(store: KeyStore, addresses: List<String>, hostname: String): Endpoint {
        val key = store.getKey("signer", CharArray(0)) as PrivateKey
        val chain = store.getCertificateChain("signer").map { it as X509Certificate }
        val identity = SigningCa.issue(key, chain, addresses, hostname)
        return endpoint(identity, "server", true, signingCa = true)
    }

    private fun readPemIdentity(
        certificates: ByteArray,
        privateKey: ByteArray,
        password: CharArray,
    ): Pair<PrivateKey, List<X509Certificate>> {
        requireUi(
            certificates.size in 1..MAX_IMPORT_BYTES && privateKey.size in 1..MAX_IMPORT_BYTES
        ) {
            UiText(UiR.string.ui_certificate_or_private_key_file_is_empty_or_too)
        }
        val chain =
            CertificateFactory.getInstance("X.509")
                .generateCertificates(certificates.inputStream())
                .map { it as X509Certificate }
        val converter = JcaPEMKeyConverter().setProvider(provider)
        val key =
            PEMParser(StringReader(privateKey.toString(Charsets.UTF_8))).use { parser ->
                var value = parser.readObject()
                // OpenSSL traditional EC PEM can prefix the key with named or explicit curve
                // parameters.
                if (value is ASN1ObjectIdentifier || value is X9ECParameters)
                    value = parser.readObject()
                val info =
                    when (value) {
                        is PEMKeyPair -> value.privateKeyInfo
                        is PrivateKeyInfo -> value
                        is PEMEncryptedKeyPair ->
                            value
                                .decryptKeyPair(
                                    JcePEMDecryptorProviderBuilder()
                                        .setProvider(provider)
                                        .build(password)
                                )
                                .privateKeyInfo
                        is PKCS8EncryptedPrivateKeyInfo ->
                            value.decryptPrivateKeyInfo(
                                JceOpenSSLPKCS8DecryptorProviderBuilder()
                                    .setProvider(provider)
                                    .build(password)
                            )
                        else ->
                            errorUi(UiText(UiR.string.ui_choose_a_pem_private_key_pkcs_8_rsa_or))
                    }
                requireUi(parser.readObject() == null) {
                    UiText(UiR.string.ui_the_private_key_file_must_contain_exactly_one_key)
                }
                converter.getPrivateKey(info)
            }
        return key to chain
    }

    private fun loadPkcs12(bytes: ByteArray, password: CharArray): KeyStore {
        requireUi(bytes.size in 1..MAX_IMPORT_BYTES) {
            UiText(UiR.string.ui_pkcs_12_file_is_empty_or_too_large)
        }
        return runCatching {
                KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), password) }
            }
            .getOrElse {
                KeyStore.getInstance("PKCS12", provider).apply {
                    load(bytes.inputStream(), password)
                }
            }
    }

    fun pkcs12Aliases(bytes: ByteArray, password: CharArray): List<String> {
        val store = loadPkcs12(bytes, password)
        return store
            .aliases()
            .toList()
            .filter { store.isKeyEntry(it) }
            .also {
                requireUi(it.isNotEmpty()) {
                    UiText(UiR.string.ui_no_private_key_entries_in_pkcs_12)
                }
            }
    }

    fun preparePkcs12(
        bytes: ByteArray,
        password: CharArray,
        alias: String? = null,
        keyPassword: CharArray = password,
    ): Prepared {
        val store = loadPkcs12(bytes, password)
        val aliases = store.aliases().toList().filter { store.isKeyEntry(it) }
        val selected =
            alias
                ?: aliases.singleOrNull()
                ?: errorUi(UiText(UiR.string.ui_choose_a_private_key_entry_from_pkcs_12))
        requireUi(selected in aliases) {
            UiText(UiR.string.ui_the_selected_entry_has_no_private_key)
        }
        val key =
            store.getKey(selected, keyPassword) as? PrivateKey
                ?: errorUi(UiText(UiR.string.ui_no_usable_private_key_in_this_entry_check_the))
        val chain =
            store.getCertificateChain(selected)?.map { it as X509Certificate }
                ?: errorUi(UiText(UiR.string.ui_the_entry_has_no_certificate_chain))
        return prepare(key, chain)
    }

    private fun normalizeKey(privateKey: PrivateKey): PrivateKey =
        // PEM converters may name an EC key "ECDSA". Conscrypt's PrivateKeyEntry
        // requires the same "EC" algorithm name as the certificate's public key.
        if (privateKey.algorithm.equals("ECDSA", ignoreCase = true))
            KeyFactory.getInstance("EC", provider)
                .generatePrivate(PKCS8EncodedKeySpec(privateKey.encoded))
        else privateKey

    private fun prepare(privateKey: PrivateKey, chain: List<X509Certificate>): Prepared {
        val key = normalizeKey(privateKey)
        requireUi(chain.isNotEmpty() && chain.first().basicConstraints < 0) {
            UiText(UiR.string.ui_a_server_certificate_is_required_a_ca_certificate_cannot)
        }
        chain.forEach { it.checkValidity() }
        val signatureAlgorithm =
            when (key.algorithm.uppercase()) {
                "RSA" -> "SHA256withRSA"
                "EC",
                "ECDSA" -> "SHA256withECDSA"
                "ED25519" -> "Ed25519"
                "ED448" -> "Ed448"
                else ->
                    errorUi(UiText(UiR.string.error_private_key_algorithm, listOf(key.algorithm)))
            }
        val probe = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature =
            Signature.getInstance(signatureAlgorithm, provider)
                .apply {
                    initSign(key)
                    update(probe)
                }
                .sign()
        val matches =
            Signature.getInstance(signatureAlgorithm, provider)
                .apply {
                    initVerify(chain.first())
                    update(probe)
                }
                .verify(signature)
        requireUi(matches) { UiText(UiR.string.ui_server_certificate_and_private_key_do_not_match) }
        for (i in 0 until chain.lastIndex) {
            requireUi(chain[i].issuerX500Principal == chain[i + 1].subjectX500Principal) {
                UiText(UiR.string.ui_order_the_certificate_chain_from_server_certificate_to_issuer)
            }
            chain[i].verify(chain[i + 1].publicKey, provider)
        }
        chain.first().extendedKeyUsage?.let {
            requireUi("1.3.6.1.5.5.7.3.1" in it || "2.5.29.37.0" in it) {
                UiText(UiR.string.ui_the_certificate_does_not_allow_tls_server_use)
            }
        }
        val store =
            KeyStore.getInstance("PKCS12").apply {
                load(null, CharArray(0))
                setKeyEntry("server", key, CharArray(0), chain.toTypedArray())
            }
        return Prepared(encode(store), endpoint(store, "server", true))
    }

    private fun encode(store: KeyStore) = ByteArrayOutputStream().use {
        store.store(it, CharArray(0))
        it.toByteArray()
    }

    @Synchronized
    fun saveCustom(context: Context, prepared: Prepared) {
        val file = customFile(context)
        val output = file.startWrite()
        try {
            output.write(prepared.encoded)
            file.finishWrite(output)
            file.baseFile.setReadable(false, false)
            file.baseFile.setReadable(true, true)
            file.baseFile.setWritable(false, false)
            file.baseFile.setWritable(true, true)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun clearCustom(context: Context) {
        val file = customFile(context)
        file.delete()
        checkUi(!file.baseFile.exists()) {
            UiText(UiR.string.ui_could_not_remove_custom_certificate)
        }
    }

    private fun certificate(
        issuer: X500Name,
        subject: X500Name,
        publicKey: java.security.PublicKey,
        signer: PrivateKey,
        days: Int,
        ca: Boolean,
        addresses: List<String>,
        hostname: String = "",
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val builder =
            JcaX509v3CertificateBuilder(
                issuer,
                BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE),
                Date(now - 300_000),
                Date(now + days * 86_400_000L),
                subject,
                publicKey,
            )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(
                if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign
                else KeyUsage.digitalSignature or KeyUsage.keyEncipherment
            ),
        )
        if (!ca) {
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
            builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(
                    (addresses.map { GeneralName(GeneralName.iPAddress, it) } +
                            GeneralName(GeneralName.dNSName, "localhost") +
                            listOfNotNull(
                                hostname
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { GeneralName(GeneralName.dNSName, it) }
                            ))
                        .toTypedArray()
                ),
            )
        }
        return JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(
                builder.build(
                    JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(signer)
                )
            )
    }
}
