package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.i18n.UiText
import com.shilapi.xcertplay.i18n.requireUi
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Supplied signing material only; no vendor keys, trust-store edits, or network interception. */
internal object SigningCa {
    private val provider = BouncyCastleProvider()

    private fun algorithm(key: PrivateKey) = when (key.algorithm.uppercase()) {
        "RSA" -> "SHA256withRSA"
        "EC", "ECDSA" -> "SHA384withECDSA"
        else -> throw IllegalArgumentException("Unsupported CA key algorithm: ${key.algorithm}")
    }

    fun validate(key: PrivateKey, chain: List<X509Certificate>) {
        requireUi(chain.isNotEmpty()) { UiText(R.string.settings_ca_chain_required) }
        chain.forEachIndexed { index, certificate ->
            certificate.checkValidity()
            requireUi(certificate.basicConstraints >= index) {
                UiText(R.string.settings_ca_constraints_invalid)
            }
            requireUi(certificate.keyUsage?.let { it.getOrNull(5) == true } != false) {
                UiText(R.string.settings_ca_constraints_invalid)
            }
            requireUi(certificate.extendedKeyUsage?.let {
                "1.3.6.1.5.5.7.3.1" in it || "2.5.29.37.0" in it
            } != false) { UiText(R.string.settings_ca_server_use_required) }
            if (index < chain.lastIndex) {
                val issuer = chain[index + 1]
                requireUi(certificate.issuerX500Principal == issuer.subjectX500Principal) {
                    UiText(R.string.settings_ca_chain_order)
                }
                certificate.verify(issuer.publicKey, provider)
            }
        }
        val probe = ByteArray(32).also(SecureRandom()::nextBytes)
        val signature = Signature.getInstance(algorithm(key), provider).run {
            initSign(key)
            update(probe)
            sign()
        }
        val matches = Signature.getInstance(algorithm(key), provider).run {
            // Key matching is independent of digitalSignature usage: CA keys can be
            // restricted to keyCertSign, which was checked above.
            initVerify(chain.first().publicKey)
            update(probe)
            verify(signature)
        }
        requireUi(matches) { UiText(R.string.settings_ca_key_mismatch) }
    }

    fun issue(
        key: PrivateKey,
        chain: List<X509Certificate>,
        addresses: List<String>,
        hostname: String,
    ): KeyStore {
        validate(key, chain)
        val now = System.currentTimeMillis()
        val notBefore = maxOf(now - 300_000, chain.maxOf { it.notBefore.time })
        val notAfter = minOf(now + 7 * 86_400_000L, chain.minOf { it.notAfter.time })
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val issuer = chain.first()
        val builder = JcaX509v3CertificateBuilder(
            X500Name.getInstance(issuer.subjectX500Principal.encoded),
            BigInteger(159, SecureRandom()).add(BigInteger.ONE),
            Date(notBefore),
            Date(notAfter),
            X500Name("CN=WheelPlay LAN"),
            keys.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        val names = (addresses + "127.0.0.1" + "::1").distinct().map {
            GeneralName(GeneralName.iPAddress, it)
        } + GeneralName(GeneralName.dNSName, "localhost") + listOfNotNull(
            hostname.takeIf { it.isNotEmpty() }?.let { GeneralName(GeneralName.dNSName, it) }
        )
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        val extensions = JcaX509ExtensionUtils()
        // This overload copies the issuer's actual SKI, including non-SHA1 identifiers.
        builder.addExtension(Extension.authorityKeyIdentifier, false,
            extensions.createAuthorityKeyIdentifier(issuer))
        builder.addExtension(Extension.subjectKeyIdentifier, false,
            extensions.createSubjectKeyIdentifier(keys.public))
        val leaf = JcaX509CertificateConverter().setProvider(provider).getCertificate(
            builder.build(JcaContentSignerBuilder(algorithm(key)).setProvider(provider).build(key))
        )
        leaf.verify(issuer.publicKey, provider)
        return KeyStore.getInstance("PKCS12").apply {
            load(null, CharArray(0))
            setKeyEntry("server", keys.private, CharArray(0), (listOf(leaf) + chain).toTypedArray())
        }
    }
}
