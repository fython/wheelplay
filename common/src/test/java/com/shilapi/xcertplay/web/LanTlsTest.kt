package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.MfiAssetStore
import com.shilapi.xcertplay.SyntheticMfiIdentity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.math.BigInteger
import java.net.ServerSocket
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LanTlsTest {
    @Before fun startWithoutRootAuthorization() {
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(RuntimeEnvironment.getApplication().noBackupFilesDir)
            .installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
        val original = RootAccess.authorize
        try {
            RootAccess.authorize = { throw java.io.IOException("Permission denied") }
            RootAccess.request()
        } finally { RootAccess.authorize = original }
    }

    private val provider = BouncyCastleProvider()
    private data class Identity(val keys: KeyPair, val chain: List<X509Certificate>)
    private fun identity(): Identity {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rootKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun certificate(name: String, signer: KeyPair, public: KeyPair, ca: Boolean): X509Certificate {
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(X500Name("CN=Test Root"), BigInteger.valueOf(if (ca) 1 else 2),
                Date(now - 60000), Date(now + 86400000), X500Name("CN=$name"), public.public)
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            if (!ca) builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(arrayOf(
                GeneralName(GeneralName.iPAddress, "127.0.0.1"), GeneralName(GeneralName.dNSName, "car.example.com"))))
            return JcaX509CertificateConverter().setProvider(provider).getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(signer.private)))
        }
        return Identity(keys, listOf(certificate("Test Server", rootKeys, keys, false), certificate("Test Root", rootKeys, rootKeys, true)))
    }
    private fun pem(vararg objects: Any): ByteArray = StringWriter().also { output ->
        JcaPEMWriter(output).use { writer -> objects.forEach(writer::writeObject) }
    }.toString().toByteArray()
    private fun pkcs12(identity: Identity, password: CharArray, extra: Boolean = false, keyPassword: CharArray = password): ByteArray {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, password); setKeyEntry("car", identity.keys.private, keyPassword, identity.chain.toTypedArray())
            if (extra) setKeyEntry("other", identity.keys.private, keyPassword, identity.chain.toTypedArray())
        }
        return ByteArrayOutputStream().use { store.store(it, password); it.toByteArray() }
    }
    private fun waitForHttps() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (WebSession.tls == null && WebSession.tlsError == null && System.nanoTime() < deadline) Thread.sleep(20)
        assertNotNull("HTTPS failed: ${WebSession.tlsError}", WebSession.tls)
    }
    private fun httpsGet(port: Int): String {
        val root = CertificateFactory.getInstance("X.509").generateCertificate(WebSession.tls!!.certificate.inputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("root", root) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val connection = URL("https://127.0.0.1:$port/").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = tls.socketFactory
        connection.connectTimeout = 3000; connection.readTimeout = 3000
        return try { connection.inputStream.use { it.reader().readText() } } finally { connection.disconnect() }
    }

    @Test fun importedPemServesATrustedHandshakeAndSurvivesRestart() {
        val context = RuntimeEnvironment.getApplication()
        val identity = identity()
        WebSession.start(context)
        try {
            waitForHttps()
            val code = WebSession.code
            LanTls.preparePem(pem(*identity.chain.toTypedArray()), pem(identity.keys.private)).use { prepared ->
                assertNull(WebSession.installCertificate(context, prepared))
                assertTrue(WebSession.tls!!.custom)
                assertEquals(code, WebSession.code)
                assertTrue(httpsGet(WebSession.httpsPort).contains("设备上的六位配对码"))
                assertTrue(LanTls.hasCustom(context))
                val fingerprint = WebSession.tls!!.fingerprint
                WebSession.stop(); WebSession.start(context); waitForHttps()
                assertEquals(fingerprint, WebSession.tls!!.fingerprint)
                assertTrue(httpsGet(WebSession.httpsPort).contains("WheelPlay"))
            }
            assertNull(WebSession.restoreDefaultCertificate(context))
            assertFalse(LanTls.hasCustom(context))
            assertFalse(WebSession.tls!!.custom)
            assertTrue(httpsGet(WebSession.httpsPort).contains("WheelPlay"))
        } finally { WebSession.stop() }
    }

    @Test fun pkcs12SupportsPasswordAliasSelectionAndDistinctKeyPassword() {
        val identity = identity()
        val password = "store-password".toCharArray()
        val keyPassword = "key-password".toCharArray()
        val bytes = pkcs12(identity, password, extra = true, keyPassword = keyPassword)
        assertEquals(setOf("car", "other"), LanTls.pkcs12Aliases(bytes, password).toSet())
        assertTrue(runCatching { LanTls.preparePkcs12(bytes, password) }.isFailure)
        assertTrue(runCatching { LanTls.preparePkcs12(bytes, "incorrect".toCharArray(), "car") }.isFailure)
        assertTrue(runCatching { LanTls.preparePkcs12(bytes, password, "car", "incorrect".toCharArray()) }.isFailure)
        LanTls.preparePkcs12(bytes, password, "car", keyPassword).use {
            assertTrue(it.endpoint.custom)
            LanTls.saveCustom(RuntimeEnvironment.getApplication(), it)
        }
        assertTrue(LanTls.create(RuntimeEnvironment.getApplication(), listOf("127.0.0.1")).custom)
    }

    @Test fun invalidKeyAndFilesLeaveTheExistingCertificateUntouched() {
        val context = RuntimeEnvironment.getApplication()
        val valid = identity()
        LanTls.preparePem(pem(*valid.chain.toTypedArray()), pem(valid.keys.private)).use { LanTls.saveCustom(context, it) }
        val fingerprint = LanTls.create(context, emptyList()).fingerprint
        val other = identity()
        assertTrue(runCatching { LanTls.preparePem(pem(*valid.chain.toTypedArray()), pem(other.keys.private)) }.isFailure)
        assertTrue(runCatching { LanTls.preparePem("invalid".toByteArray(), pem(valid.keys.private)) }.isFailure)
        assertTrue(runCatching { LanTls.readImport(ByteArray(LanTls.MAX_IMPORT_BYTES + 1).inputStream()) }.isFailure)
        assertEquals(fingerprint, LanTls.create(context, emptyList()).fingerprint)
    }

    @Test fun encryptedPemAndPkcs12BothServeTheSelectedServerIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val identity = identity()
        val password = "fixture-password".toCharArray()
        val encryptor = JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
            .setProvider(provider).setPassword(password).build()
        val encrypted = pem(JcaPKCS8Generator(identity.keys.private, encryptor))
        assertTrue(runCatching { LanTls.preparePem(pem(*identity.chain.toTypedArray()), encrypted, "wrong".toCharArray()) }.isFailure)
        LanTls.preparePem(pem(*identity.chain.toTypedArray()), encrypted, password).use {
            assertTrue(it.endpoint.custom)
        }
        WebSession.start(context)
        try {
            waitForHttps()
            LanTls.preparePkcs12(pkcs12(identity, password), password).use {
                assertNull(WebSession.installCertificate(context, it))
                assertTrue(httpsGet(WebSession.httpsPort).contains("WheelPlay"))
                assertEquals("CN=Test Server", WebSession.tls!!.subject)
            }
        } finally { WebSession.stop() }
    }

    @Test fun ordinaryListenersRejectPrivilegedPortsAndKeepBothExistingEndpoints() {
        val context = RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            waitForHttps()
            val code = WebSession.code
            val http = WebSession.httpPort
            val https = WebSession.httpsPort
            assertNotNull(WebSession.setHttpPort(context, 80))
            assertNotNull(WebSession.setHttpsPort(context, 443))
            assertEquals(http, WebSession.httpPort)
            assertEquals(https, WebSession.httpsPort)
            assertFalse(RootAccess.granted)
            assertEquals(code, WebSession.code)
            assertTrue(URL("http://127.0.0.1:$http/").readText().contains("WheelPlay"))
            assertTrue(httpsGet(https).contains("WheelPlay"))
        } finally { WebSession.stop() }
    }

    @Test fun disablingHttpsStopsItsListenerAndMappingWhileKeepingHttpPairingAndCertificate() {
        val context = RuntimeEnvironment.getApplication()
        val identity = identity()
        WebSession.start(context)
        try {
            waitForHttps()
            LanTls.preparePem(pem(*identity.chain.toTypedArray()), pem(identity.keys.private)).use {
                assertNull(WebSession.installCertificate(context, it))
            }
            val fingerprint = WebSession.tls!!.fingerprint
            val code = WebSession.code
            val https = WebSession.httpsPort
            val config = com.shilapi.xcertplay.network.TeslaHttpConfig(enabled = true)
            val interfaces = listOf(HttpDownstream("ap0", 12, "192.168.43.1/24"))
            val before = TeslaHttpCompatibility.plan(config, interfaces, WebSession.httpListenerPort, WebSession.httpsListenerPort, true)!!
            assertEquals(443, before.httpsMappingPort)
            assertNull(WebSession.setHttpsEnabled(context, false))
            assertFalse(WebSession.httpsReady)
            assertNull(WebSession.httpsListenerPort)
            assertTrue(LanTls.hasCustom(context))
            assertTrue(WebSession.running)
            assertEquals(code, WebSession.code)
            ServerSocket(https).use { assertEquals(https, it.localPort) }
            val after = TeslaHttpCompatibility.plan(config, interfaces, WebSession.httpListenerPort, WebSession.httpsListenerPort, true)!!
            assertEquals(80, after.httpMappingPort)
            assertNull(after.httpsMappingPort)
            val info = org.json.JSONObject(URL("http://127.0.0.1:${WebSession.httpPort}/tls.json").readText())
            assertFalse(info.getBoolean("available")); assertFalse(info.getBoolean("enabled"))
            WebSession.stop(); WebSession.start(context)
            assertFalse(WebSession.httpsReady)
            assertFalse(WebListenSettings.httpsEnabled(context))
            assertNull(WebSession.setHttpsEnabled(context, true))
            assertTrue(WebSession.httpsReady)
            assertEquals(fingerprint, WebSession.tls!!.fingerprint)
            assertEquals(https, WebSession.httpsPort)
            assertTrue(httpsGet(https).contains("WheelPlay"))
        } finally { WebSession.stop() }
    }

    @Test fun enablingHttpsOnAnOccupiedPortKeepsItDisabledAndHttpUsable() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(WebSession.setHttpsEnabled(context, false))
        WebSession.start(context)
        try {
            val code = WebSession.code
            ServerSocket(0).use { occupied ->
                assertNull(WebSession.setHttpsPort(context, occupied.localPort))
                assertNotNull(WebSession.setHttpsEnabled(context, true))
                assertFalse(WebListenSettings.httpsEnabled(context))
                assertFalse(WebSession.httpsReady)
                assertEquals(code, WebSession.code)
                assertTrue(URL("http://127.0.0.1:${WebSession.httpPort}/").readText().contains("WheelPlay"))
            }
            assertNull(WebSession.setHttpsEnabled(context, true))
            assertTrue(WebSession.httpsReady)
            assertTrue(httpsGet(WebSession.httpsPort).contains("WheelPlay"))
        } finally { WebSession.stop() }
    }

    @Test fun httpsPortBindingFailureKeepsListenerAndNewPortPersists() {
        val context = RuntimeEnvironment.getApplication()
        WebSession.start(context)
        try {
            waitForHttps()
            val oldPort = WebSession.httpsPort
            val code = WebSession.code
            val fingerprint = WebSession.tls!!.fingerprint
            ServerSocket(0).use { occupied ->
                assertNotNull(WebSession.setHttpsPort(context, occupied.localPort))
            }
            assertEquals(oldPort, WebSession.httpsPort)
            assertEquals(oldPort, WebListenSettings.httpsPort(context))
            assertTrue(httpsGet(oldPort).contains("WheelPlay"))
            assertNotNull(WebSession.setHttpsPort(context, WebSession.httpPort))
            val port = ServerSocket(0).use { it.localPort }
            assertNull(WebSession.setHttpsPort(context, port))
            assertEquals(port, WebListenSettings.httpsPort(context))
            assertEquals(code, WebSession.code)
            assertEquals(fingerprint, WebSession.tls!!.fingerprint)
            assertTrue(httpsGet(port).contains("WheelPlay"))
            val info = org.json.JSONObject(URL("http://127.0.0.1:${WebSession.httpPort}/tls.json").readText())
            assertEquals(port, info.getInt("port"))
            WebSession.stop(); WebSession.start(context); waitForHttps()
            assertEquals(port, WebSession.httpsPort)
            assertTrue(httpsGet(port).contains("WheelPlay"))
        } finally { WebSession.stop() }
    }
}
