package com.shilapi.xcertplay.web

import moe.feng.wheelplay.MfiAssetStore
import moe.feng.wheelplay.SyntheticMfiIdentity
import java.io.StringWriter
import java.io.File
import java.math.BigInteger
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
class SigningCaTest {
    private val bc = BouncyCastleProvider()
    private val context get() = RuntimeEnvironment.getApplication()
    private data class Authority(val key: KeyPair, val chain: List<X509Certificate>)

    private fun keys(algorithm: String) = KeyPairGenerator.getInstance(algorithm, bc).apply {
        if (algorithm == "EC") initialize(ECGenParameterSpec("secp384r1")) else initialize(2048)
    }.generateKeyPair()

    private fun certificate(
        name: String,
        issuer: String,
        subjectKey: KeyPair,
        signer: KeyPair,
        ca: Boolean = true,
        pathLength: Int = 2,
        keyUsage: Int = KeyUsage.keyCertSign or KeyUsage.cRLSign,
        eku: KeyPurposeId = KeyPurposeId.id_kp_serverAuth,
        expires: Long = System.currentTimeMillis() + 3 * 86_400_000L,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"), BigInteger(128, java.security.SecureRandom()),
            Date(System.currentTimeMillis() - 60_000), Date(expires),
            X500Name("CN=$name"), subjectKey.public,
        )
        builder.addExtension(Extension.basicConstraints, true,
            if (ca) BasicConstraints(pathLength) else BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(keyUsage))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(eku))
        // A deliberately nonstandard SKI catches accidental SHA1 recomputation in AKI.
        builder.addExtension(Extension.subjectKeyIdentifier, false,
            SubjectKeyIdentifier(ByteArray(16) { (it + name.length).toByte() }))
        return JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(
            JcaContentSignerBuilder(if (signer.private.algorithm == "RSA") "SHA256withRSA" else "SHA384withECDSA")
                .setProvider(bc).build(signer.private)
        ))
    }

    private fun authority(algorithm: String = "EC"): Authority {
        val root = keys(algorithm)
        val signer = keys(algorithm)
        return Authority(signer, listOf(
            certificate("Signer", "Root", signer, root, pathLength = 0),
            certificate("Root", "Root", root, root),
        ))
    }

    private fun pem(vararg values: Any) = StringWriter().also { output ->
        JcaPEMWriter(output).use { writer -> values.forEach(writer::writeObject) }
    }.toString().toByteArray()

    private fun prepare(authority: Authority, hostname: String = "car.example.com") =
        LanTls.prepareSigningCaPem(pem(*authority.chain.toTypedArray()), pem(authority.key.private),
            addresses = listOf("127.0.0.1", "6.6.6.6"), hostname = hostname)

    private fun bundle(authority: Authority) = pem(authority.key.private, *authority.chain.toTypedArray())

    private fun addBundledAsset(bytes: ByteArray) {
        val apk = File(context.cacheDir, "test-https-ca.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/${LanTls.BUNDLED_CA_ASSET}"))
            zip.write(bytes)
            zip.closeEntry()
        }
        val cookie = ReflectionHelpers.callInstanceMethod<Int>(context.assets, "addAssetPath",
            ReflectionHelpers.ClassParameter.from(String::class.java, apk.absolutePath))
        assertTrue(cookie > 0)
    }

    private fun client(root: X509Certificate): SSLContext {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("root", root)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
    }

    private fun httpsGet(root: X509Certificate): String {
        val connection = URL("https://127.0.0.1:${WebSession.httpsPort}/").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = client(root).socketFactory
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try { connection.inputStream.use { it.reader().readText() } }
        finally { connection.disconnect() }
    }

    /** Validates the peer with a root-only trust store and HTTPS hostname checks enabled. */
    private fun handshake(endpoint: LanTls.Endpoint, root: X509Certificate): List<X509Certificate> {
        return (endpoint.sockets.createServerSocket(0) as SSLServerSocket).use { listener ->
            listener.soTimeout = 5000
            val server = CompletableFuture.runAsync {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5000
                    socket.startHandshake()
                    socket.outputStream.write(42)
                }
            }
            val chain = (client(root).socketFactory.createSocket("127.0.0.1", listener.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 5000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                assertEquals(42, socket.inputStream.read())
                socket.session.peerCertificates.map { it as X509Certificate }
            }
            server.get(6, TimeUnit.SECONDS)
            chain
        }
    }

    @Test fun rsaAndEcSignersServeTrustedChainsWithIndependentLeafKeysAndMatchingAki() {
        for (algorithm in listOf("RSA", "EC")) {
            val authority = authority(algorithm)
            prepare(authority).use { prepared ->
                val endpoint = prepared.endpoint
                assertTrue(endpoint.custom)
                assertTrue(endpoint.signingCa)
                val chain = handshake(endpoint, authority.chain.last())
                val leaf = chain.first()
                leaf.verify(authority.key.public, bc)
                assertEquals(-1, leaf.basicConstraints)
                assertFalse(leaf.publicKey.encoded.contentEquals(authority.key.public.encoded))
                assertEquals(3, chain.size)
                assertTrue(leaf.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
                val sans = leaf.subjectAlternativeNames.map { it[1].toString() }
                assertTrue(sans.containsAll(listOf("127.0.0.1", "6.6.6.6", "car.example.com", "localhost")))
                assertTrue(authority.chain.all { leaf.notAfter <= it.notAfter && leaf.notBefore >= it.notBefore })
                val ski = SubjectKeyIdentifier.fromExtensions(JcaX509CertificateHolder(authority.chain.first()).extensions)
                val aki = AuthorityKeyIdentifier.fromExtensions(JcaX509CertificateHolder(leaf).extensions)
                assertArrayEquals(ski.keyIdentifier, aki.keyIdentifier)
            }
        }
    }

    @Test fun encryptedEcSignerPersistsAndNewAddressesAreSignedAfterReload() {
        val authority = authority()
        val password = "fixture-only".toCharArray()
        val encrypted = pem(JcaPKCS8Generator(authority.key.private,
            JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                .setProvider(bc).setPassword(password).build()))
        LanTls.prepareSigningCaPem(pem(*authority.chain.toTypedArray()), encrypted, password,
            listOf("127.0.0.1"), "old.example.com").use { prepared ->
            LanTls.saveCustom(context, prepared)
        }
        assertTrue(LanTls.hasSigningCa(context))
        assertTrue(LanTls.generatesCertificates(context))
        val restored = LanTls.create(context, listOf("192.168.43.1"), "new.example.com")
        val leaf = handshake(restored, authority.chain.last()).first()
        val sans = leaf.subjectAlternativeNames.map { it[1].toString() }
        assertTrue(sans.containsAll(listOf("192.168.43.1", "new.example.com")))
        assertFalse("old.example.com" in sans)
        assertTrue(restored.signingCa)
        LanTls.clearCustom(context)
        assertFalse(LanTls.hasSigningCa(context))
        assertFalse(LanTls.create(context, emptyList()).signingCa)
    }

    @Test fun wrongKeyWrongOrderAndBadSignaturesLeaveInstalledSignerUntouched() {
        val authority = authority()
        prepare(authority).use { LanTls.saveCustom(context, it) }
        val fingerprint = LanTls.create(context, emptyList()).fingerprint
        val other = keys("EC")
        for (invalid in listOf(
            Authority(other, authority.chain),
            Authority(authority.key, authority.chain.reversed()),
            Authority(authority.key, listOf(authority.chain.first(),
                certificate("Root", "Root", other, other))),
        )) {
            assertTrue(runCatching { prepare(invalid).use {} }.isFailure)
        }
        assertEquals(fingerprint, LanTls.create(context, emptyList()).fingerprint)
        handshake(LanTls.create(context, emptyList()), authority.chain.last())
    }

    @Test fun rejectsLeafCertificatesMissingSigningPermissionRestrictedEkuAndExpiredCa() {
        val key = keys("EC")
        val invalid = listOf(
            certificate("CA", "CA", key, key, ca = false),
            certificate("CA", "CA", key, key, keyUsage = KeyUsage.digitalSignature),
            certificate("CA", "CA", key, key, eku = KeyPurposeId.id_kp_clientAuth),
            certificate("CA", "CA", key, key, expires = System.currentTimeMillis() - 30_000),
        )
        invalid.forEach { cert ->
            assertTrue(runCatching { prepare(Authority(key, listOf(cert))).use {} }.isFailure)
        }
        assertFalse(LanTls.hasCustom(context))
    }

    @Test fun rejectsIssuerWithInsufficientPathLength() {
        val root = keys("EC")
        val signer = keys("EC")
        val chain = listOf(
            certificate("Signer", "Root", signer, root, pathLength = 0),
            certificate("Root", "Root", root, root, pathLength = 0),
        )
        assertTrue(runCatching { prepare(Authority(signer, chain)).use {} }.isFailure)
    }

    @Test fun signingCaIsSeparateFromExistingServerPemImport() {
        val authority = authority()
        assertTrue(runCatching {
            LanTls.preparePem(pem(*authority.chain.toTypedArray()), pem(authority.key.private)).use {}
        }.isFailure)
    }

    @Test fun bundledCaAutomaticallySignsWithoutImportAndExplicitLocalChoiceSurvivesRestart() {
        val authority = authority()
        addBundledAsset(bundle(authority))
        assertTrue(LanTls.hasBundledCa(context))
        assertFalse(LanTls.hasCustom(context))
        assertEquals(WebListenSettings.CertificateType.BUNDLED_CA, WebListenSettings.certificateType(context))
        val first = LanTls.create(context, listOf("127.0.0.1"))
        assertTrue(first.bundledCa)
        assertTrue(first.signingCa)
        handshake(first, authority.chain.last())
        // Selecting a manual-import form without importing must not change the active source.
        WebListenSettings.saveCertificateType(context, WebListenSettings.CertificateType.EMUDHRA_CA)
        assertTrue(LanTls.create(context, emptyList()).bundledCa)
        assertNull(WebSession.restoreDefaultCertificate(context))
        assertFalse(LanTls.create(context, emptyList()).bundledCa)
        assertFalse(WebListenSettings.bundledCaEnabled(context))
        assertNull(WebSession.useBundledCertificate(context))
        assertTrue(LanTls.create(context, emptyList()).bundledCa)
        assertFalse(LanTls.hasCustom(context))
    }

    @Test fun manualIdentityOverridesBundledCaUntilTheBuiltInSourceIsSelectedAgain() {
        val bundled = authority()
        val manual = authority()
        addBundledAsset(bundle(bundled))
        prepare(manual).use { LanTls.saveCustom(context, it) }
        val overridden = LanTls.create(context, emptyList())
        assertFalse(overridden.bundledCa)
        handshake(overridden, manual.chain.last())
        assertNull(WebSession.useBundledCertificate(context))
        assertFalse(LanTls.hasCustom(context))
        handshake(LanTls.create(context, emptyList()), bundled.chain.last())
    }

    @Test fun invalidBundledMaterialFailsWithoutReplacingAnExplicitLocalIdentity() {
        addBundledAsset("invalid PEM".toByteArray())
        assertTrue(runCatching { LanTls.create(context, emptyList()) }.isFailure)
        assertNull(WebSession.restoreDefaultCertificate(context))
        assertFalse(LanTls.create(context, emptyList()).bundledCa)
        assertNotNull(WebSession.useBundledCertificate(context))
        assertFalse(LanTls.usesBundledCa(context))
        assertFalse(LanTls.create(context, emptyList()).bundledCa)
    }

    @Test fun combinedPemRejectsMissingCertificatesMissingOrMultipleKeysAndBadKeyPairs() {
        val authority = authority()
        val other = authority()
        for (bytes in listOf(
            pem(*authority.chain.toTypedArray()),
            pem(authority.key.private),
            bundle(authority) + pem(authority.key.private),
            pem(other.key.private, *authority.chain.toTypedArray()),
        )) {
            assertTrue(runCatching { LanTls.prepareSigningCaBundle(bytes, emptyList()).use {} }.isFailure)
        }
    }

    @Test fun explicitlySelectedMissingBundleDoesNotSilentlyGenerateALocalCa() {
        assertFalse(LanTls.hasBundledCa(context))
        WebListenSettings.saveBundledCaEnabled(context, true)
        assertTrue(runCatching { LanTls.create(context, emptyList()) }.isFailure)
        assertNull(WebSession.restoreDefaultCertificate(context))
        assertFalse(LanTls.create(context, emptyList()).bundledCa)
    }

    @Test fun bundledHttpsSupportsRequestsRefreshAndSwitchingSourcesWithoutImport() {
        val authority = authority()
        addBundledAsset(bundle(authority))
        val mfi = SyntheticMfiIdentity.create()
        MfiAssetStore(context.noBackupFilesDir).installFiles(
            { mfi.key.inputStream() }, { mfi.certificate.inputStream() })
        WebSession.start(context)
        try {
            waitFor { WebSession.httpsReady }
            val code = WebSession.code
            assertTrue(WebSession.tls!!.bundledCa)
            assertTrue(httpsGet(authority.chain.last()).contains("WheelPlay"))
            val previous = WebSession.tls
            assertNull(WebSession.setHostname(context, "bundled.example.com"))
            waitFor { WebSession.tls !== previous }
            assertTrue(WebSession.tls!!.bundledCa)
            val sans = handshake(WebSession.tls!!, authority.chain.last()).first().subjectAlternativeNames
            assertTrue(sans.any { it[1] == "bundled.example.com" })
            val info = JSONObject(URL("http://127.0.0.1:${WebSession.httpPort}/tls.json").readText())
            assertTrue(info.getBoolean("bundledCa"))
            assertNull(WebSession.restoreDefaultCertificate(context))
            assertFalse(WebSession.tls!!.bundledCa)
            assertNull(WebSession.useBundledCertificate(context))
            assertTrue(WebSession.tls!!.bundledCa)
            assertEquals(code, WebSession.code)
            assertFalse(LanTls.hasCustom(context))
            assertTrue(httpsGet(authority.chain.last()).contains("WheelPlay"))
        } finally { WebSession.stop() }
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("HTTPS update timed out: ${WebSession.tlsError}", predicate())
    }

    @Test fun webServiceRefreshRestartDisableAndRestoreKeepPairingAndHttpAvailable() {
        val mfi = SyntheticMfiIdentity.create()
        MfiAssetStore(context.noBackupFilesDir).installFiles(
            { mfi.key.inputStream() }, { mfi.certificate.inputStream() })
        val authority = authority()
        WebSession.start(context)
        try {
            waitFor { WebSession.httpsReady }
            val code = WebSession.code
            assertNull(WebSession.installSigningCa(context,
                pem(*authority.chain.toTypedArray()), pem(authority.key.private)))
            assertTrue(WebSession.tls!!.signingCa)
            assertTrue(httpsGet(authority.chain.last()).contains("WheelPlay"))
            val fingerprint = WebSession.tls!!.fingerprint
            assertEquals(code, WebSession.code)
            assertNull(WebSession.setHostname(context, "changed.example.com"))
            waitFor {
                WebSession.tls?.let { endpoint ->
                    handshake(endpoint, authority.chain.last()).first().subjectAlternativeNames
                        .any { it[1] == "changed.example.com" }
                } == true
            }
            assertEquals(fingerprint, WebSession.tls!!.fingerprint)
            assertTrue(httpsGet(authority.chain.last()).contains("WheelPlay"))
            val info = JSONObject(URL("http://127.0.0.1:${WebSession.httpPort}/tls.json").readText())
            assertTrue(info.getBoolean("signingCa"))
            assertNull(WebSession.setHttpsEnabled(context, false))
            assertTrue(LanTls.hasSigningCa(context))
            assertTrue(URL("http://127.0.0.1:${WebSession.httpPort}/").readText().contains("WheelPlay"))
            WebSession.stop()
            WebSession.start(context)
            assertFalse(WebSession.httpsReady)
            assertNull(WebSession.setHttpsEnabled(context, true))
            assertTrue(WebSession.tls!!.signingCa)
            assertEquals(fingerprint, WebSession.tls!!.fingerprint)
            handshake(WebSession.tls!!, authority.chain.last())
            assertNull(WebSession.restoreDefaultCertificate(context))
            assertFalse(LanTls.hasCustom(context))
            assertFalse(WebSession.tls!!.signingCa)
        } finally { WebSession.stop() }
    }
}
