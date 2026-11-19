package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IapPackageBoundsTest {
    private val key = ByteArray(32) { 7 }
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun frame(counter: Long, plain: ByteArray): ByteArray {
        val aad = byteArrayOf(plain.size.toByte(), (plain.size shr 8).toByte())
        return aad + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, aad)
    }

    private fun header(size: Int, type: String): ByteArray {
        val h = ByteArray(32)
        h[0] = (size ushr 24).toByte(); h[1] = (size ushr 16).toByte()
        h[2] = (size ushr 8).toByte(); h[3] = size.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(h, 16)
        return h
    }

    @Test fun iapTunnelClosesOnInvalidPackageLengthsWithoutWaitingForTheBody() {
        for (size in listOf(0, 31, -1, Int.MAX_VALUE)) {
            val closed = CountDownLatch(1)
            val tunnel = IapTunnel(key, expectedPeer = loopback, bindAddress = loopback)
            var failure: Throwable? = null
            val port = tunnel.listen(object : IapTunnel.Listener {
                override fun onClosed(cause: Throwable?) { failure = cause; closed.countDown() }
            })
            try {
                Socket(loopback, port).use { client ->
                    client.getOutputStream().apply { write(frame(0, header(size, "comm"))); flush() }
                    assertTrue("tunnel still open after invalid package size $size", closed.await(3, TimeUnit.SECONDS))
                    assertTrue("wrong terminal failure: $failure", failure is IllegalArgumentException)
                }
            } finally { tunnel.close() }
        }
    }

    @Test fun iapTunnelDeliversRealPackage() {
        val got = CountDownLatch(1)
        var body = ByteArray(0)
        val tunnel = IapTunnel(key, expectedPeer = loopback, bindAddress = loopback)
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onIap(bytes: ByteArray) { body = bytes; got.countDown() }
        })
        try {
            Socket(loopback, port).use { client ->
                val payload = byteArrayOf(1, 2, 3)
                client.getOutputStream().apply { write(frame(0, header(32 + payload.size, "comm") + payload)); flush() }
                assertTrue(got.await(3, TimeUnit.SECONDS))
                assertArrayEquals(payload, body)
            }
        } finally {
            tunnel.close()
        }
    }

    @Test fun aPartialPackageCanFinishInTheNextEncryptedFrame() {
        val got = CountDownLatch(1)
        var body = ByteArray(0)
        val tunnel = IapTunnel(key, expectedPeer = loopback, bindAddress = loopback)
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onIap(bytes: ByteArray) { body = bytes; got.countDown() }
        })
        try {
            Socket(loopback, port).use { client ->
                val payload = byteArrayOf(1, 2, 3)
                val pack = header(32 + payload.size, "comm") + payload
                client.getOutputStream().apply { write(frame(0, pack.copyOf(33))); flush() }
                org.junit.Assert.assertFalse("partial body was delivered", got.await(200, TimeUnit.MILLISECONDS))
                client.getOutputStream().apply { write(frame(1, pack.copyOfRange(33, pack.size))); flush() }
                assertTrue(got.await(3, TimeUnit.SECONDS))
                assertArrayEquals(payload, body)
            }
        } finally { tunnel.close() }
    }

}
