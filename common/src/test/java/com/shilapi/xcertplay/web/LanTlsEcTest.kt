package com.shilapi.xcertplay.web

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.sec.SECObjectIdentifiers
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Provider
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LanTlsEcTest {
    private val bc = BouncyCastleProvider()
    private data class Identity(val keys: KeyPair, val chain: List<X509Certificate>)
    private fun identity(): Identity {
        fun keys(curve: String) = KeyPairGenerator.getInstance("EC", bc).apply {
            initialize(ECGenParameterSpec(curve))
        }.generateKeyPair()
        val root = keys("secp384r1")
        val issuer = keys("secp384r1")
        val server = keys("secp256r1")
        fun certificate(subject: String, issuerName: String, public: KeyPair, signer: KeyPair, ca: Boolean): X509Certificate {
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(X500Name("CN=$issuerName"), BigInteger(128, java.security.SecureRandom()),
                Date(now - 60000), Date(now + 86400000), X500Name("CN=$subject"), public.public)
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            if (!ca) builder.addExtension(Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.iPAddress, "127.0.0.1")))
            return JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(
                JcaContentSignerBuilder(if (ca) "SHA384withECDSA" else "SHA256withECDSA").setProvider(bc).build(signer.private)))
        }
        return Identity(server, listOf(
            certificate("EC Server", "EC Issuer", server, issuer, false),
            certificate("EC Issuer", "EC Root", issuer, root, true),
            certificate("EC Root", "EC Root", root, root, true)))
    }

    private fun pem(vararg objects: Any) = StringWriter().also { output ->
        JcaPEMWriter(output).use { writer -> objects.forEach(writer::writeObject) }
    }.toString().toByteArray()

    private fun ecParameters(encoded: ByteArray) = StringWriter().also { output ->
        PemWriter(output).use { it.writeObject(PemObject("EC PARAMETERS", encoded)) }
    }.toString().toByteArray()

    private fun withAndroidKeyManager(action: () -> Unit) {
        // Use Android's real Conscrypt key manager and a BC PKCS#12 store. SunPKCS12
        // rewrites the private key as EC and hides Conscrypt's strict name check.
        val factory = Class.forName("com.android.org.conscrypt.KeyManagerFactoryImpl")
        val pkcs12 = bc.getService("KeyStore", "PKCS12")
        val provider = object : Provider("WheelPlayAndroidTlsTest", 1.0, "Android key manager and BC PKCS12") {
            init {
                putService(object : Service(this, "KeyManagerFactory", "WheelPlayAndroidX509", factory.name, emptyList(), emptyMap()) {
                    override fun newInstance(parameter: Any?) = factory.getConstructor().newInstance()
                })
                putService(object : Service(this, "KeyStore", "PKCS12", pkcs12.className, emptyList(), emptyMap()) {
                    override fun newInstance(parameter: Any?) = pkcs12.newInstance(parameter)
                })
            }
        }
        val original = Security.getProperty("ssl.KeyManagerFactory.algorithm")
        Security.insertProviderAt(provider, 1)
        Security.setProperty("ssl.KeyManagerFactory.algorithm", "WheelPlayAndroidX509")
        try { action() } finally {
            Security.setProperty("ssl.KeyManagerFactory.algorithm", original)
            Security.removeProvider(provider.name)
        }
    }

    private fun assertTrustedHandshake(endpoint: LanTls.Endpoint) {
        val root = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(endpoint.certificate.inputStream())
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("root", root) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val server = endpoint.sockets.createServerSocket(0)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val handshake = executor.submit {
            (server.accept() as SSLSocket).use {
                it.soTimeout = 3000
                it.startHandshake()
                assertEquals(42, it.inputStream.read())
                it.outputStream.write(43)
            }
        }
        try {
            (tls.socketFactory.createSocket("127.0.0.1", server.localPort) as SSLSocket).use { client ->
                client.soTimeout = 3000
                client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                client.startHandshake()
                assertEquals(3, client.session.peerCertificates.size)
                client.outputStream.write(42)
                assertEquals(43, client.inputStream.read())
            }
            handshake.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (error: Exception) {
            try { handshake.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            catch (serverError: Exception) { error.addSuppressed(serverError) }
            throw error
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun ecPemFormatsWorkWithAndroidKeyManagerAndSurviveReload() = withAndroidKeyManager {
        val context = RuntimeEnvironment.getApplication()
        val identity = identity()
        val chain = pem(*identity.chain.toTypedArray())
        val password = "test-password".toCharArray()
        val encryptor = JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC).setProvider(bc).setPassword(password).build()
        val formats = listOf(
            pem(JcaPKCS8Generator(identity.keys.private, null)) to CharArray(0),
            pem(identity.keys.private) to CharArray(0),
            pem(JcaPKCS8Generator(identity.keys.private, encryptor)) to password)
        for ((key, secret) in formats) {
            LanTls.preparePem(chain, key, secret).use { prepared ->
                assertEquals("CN=EC Server", prepared.endpoint.subject)
                assertTrustedHandshake(prepared.endpoint)
                LanTls.saveCustom(context, prepared)
                val restored = LanTls.create(context, emptyList())
                assertEquals(prepared.endpoint.fingerprint, restored.fingerprint)
                assertTrustedHandshake(restored)
            }
        }
    }

    @Test fun ecPkcs12WorksWithAndroidKeyManagerAndSurvivesReload() = withAndroidKeyManager {
        val identity = identity()
        val password = "test-password".toCharArray()
        val key = KeyFactory.getInstance("ECDSA", bc).generatePrivate(PKCS8EncodedKeySpec(identity.keys.private.encoded))
        val store = KeyStore.getInstance("PKCS12", bc).apply {
            load(null, password); setKeyEntry("server", key, password, identity.chain.toTypedArray())
        }
        val encoded = ByteArrayOutputStream().use { store.store(it, password); it.toByteArray() }
        LanTls.preparePkcs12(encoded, password).use { prepared ->
            assertTrustedHandshake(prepared.endpoint)
            val context = RuntimeEnvironment.getApplication()
            LanTls.saveCustom(context, prepared)
            assertTrustedHandshake(LanTls.create(context, emptyList()))
        }
    }

    @Test fun ecParametersBeforeTraditionalPrivateKeyWorkWithAndroidKeyManagerAndSurviveReload() = withAndroidKeyManager {
        val context = RuntimeEnvironment.getApplication()
        val identity = identity()
        val chain = pem(*identity.chain.toTypedArray())
        val key = pem(identity.keys.private)
        assertTrue(key.toString(Charsets.UTF_8).startsWith("-----BEGIN EC PRIVATE KEY-----"))
        for (parameters in listOf(SECObjectIdentifiers.secp256r1.encoded, SECNamedCurves.getByName("secp256r1").encoded)) {
            LanTls.preparePem(chain, ecParameters(parameters) + key).use { prepared ->
                assertTrustedHandshake(prepared.endpoint)
                LanTls.saveCustom(context, prepared)
                val restored = LanTls.create(context, emptyList())
                assertEquals(prepared.endpoint.fingerprint, restored.fingerprint)
                assertTrustedHandshake(restored)
            }
        }
    }

    @Test fun ecParametersDoNotAllowMissingOrMultiplePrivateKeysOrExtraObjects() = withAndroidKeyManager {
        val context = RuntimeEnvironment.getApplication()
        val identity = identity()
        val chain = pem(*identity.chain.toTypedArray())
        val key = pem(identity.keys.private)
        val parameters = ecParameters(SECObjectIdentifiers.secp256r1.encoded)
        LanTls.preparePem(chain, key).use { LanTls.saveCustom(context, it) }
        val old = LanTls.create(context, emptyList())
        for (invalid in listOf(parameters, parameters + key + key, parameters + key + parameters, parameters + parameters + key)) {
            val error = runCatching { LanTls.preparePem(chain, invalid).use { } }.exceptionOrNull()
            assertNotNull(error)
        }
        val current = LanTls.create(context, emptyList())
        assertEquals(old.fingerprint, current.fingerprint)
        assertTrustedHandshake(current)
    }

    @Test fun aDifferentEcPrivateKeyIsRejectedAndKeepsTheExistingIdentity() = withAndroidKeyManager {
        val context = RuntimeEnvironment.getApplication()
        val identity = identity()
        val chain = pem(*identity.chain.toTypedArray())
        LanTls.preparePem(chain, pem(identity.keys.private)).use { LanTls.saveCustom(context, it) }
        val old = LanTls.create(context, emptyList())
        val failure = runCatching { LanTls.preparePem(chain, pem(identity().keys.private)) }.exceptionOrNull()
        assertEquals("服务器证书与私钥不匹配", failure?.message)
        val current = LanTls.create(context, emptyList())
        assertEquals(old.fingerprint, current.fingerprint)
        assertTrustedHandshake(current)
    }
}
