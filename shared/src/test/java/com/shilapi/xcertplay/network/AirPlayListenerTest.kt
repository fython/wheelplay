package com.shilapi.xcertplay.network

import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AirPlayListenerTest {
    private val address = InetAddress.getByName("127.0.0.1")

    @Test fun aSuccessfulPreferredBindReturnsTheSameOpenSocket() {
        Sockets(null).use { sockets ->
            val listener = bindAirPlayListener(address, 7000, sockets::create)

            assertSame(sockets[0], listener)
            assertEquals(listOf(InetSocketAddress(address, 7000)), sockets[0].binds)
            assertEquals(0, sockets[0].closeCalls)
            assertEquals(1, sockets.created)
        }
    }

    @Test fun aCollisionClosesTheFirstSocketBeforeBindingDynamicallyOnTheSameAddress() {
        Sockets(BindException("Address already in use"), null).use { sockets ->
            val listener = bindAirPlayListener(address, 7000, sockets::create)

            assertSame(sockets[1], listener)
            assertEquals(listOf(InetSocketAddress(address, 7000)), sockets[0].binds)
            assertEquals(listOf(InetSocketAddress(address, 0)), sockets[1].binds)
            assertEquals(1, sockets[0].closeCalls)
            assertEquals(0, sockets[1].closeCalls)
            assertEquals(2, sockets.created)
            assertEquals(
                listOf("create:0", "bind:0:7000", "close:0", "create:1", "bind:1:0"),
                sockets.events,
            )
        }
    }

    @Test fun explicitlyRequestingAnEphemeralPortOnlyBindsOnce() {
        Sockets(null).use { sockets ->
            val listener = bindAirPlayListener(address, 0, sockets::create)

            assertSame(sockets[0], listener)
            assertEquals(listOf(InetSocketAddress(address, 0)), sockets[0].binds)
            assertEquals(0, sockets[0].closeCalls)
            assertEquals(1, sockets.created)
        }
    }

    @Test fun anEphemeralBindFailureIsClosedWithoutAnotherAttempt() {
        val failure = BindException("No dynamic port available")
        Sockets(failure).use { sockets ->
            val thrown = assertThrows(BindException::class.java) {
                bindAirPlayListener(address, 0, sockets::create)
            }

            assertSame(failure, thrown)
            assertEquals(listOf(InetSocketAddress(address, 0)), sockets[0].binds)
            assertEquals(1, sockets[0].closeCalls)
            assertEquals(1, sockets.created)
        }
    }

    @Test fun anUnrelatedIoFailureIsClosedAndPropagatedWithoutFallback() {
        assertNoFallback(IOException("Socket operation failed"))
    }

    @Test fun aSecurityFailureIsClosedAndPropagatedWithoutFallback() {
        assertNoFallback(SecurityException("Listen denied"))
    }

    @Test fun aFailedFallbackClosesBothSocketsAndRetainsTheOriginalBindFailure() {
        for (fallbackFailure in listOf(
            BindException("No dynamic port available"),
            IOException("Socket operation failed"),
            SecurityException("Listen denied"),
        )) {
            val collision = BindException("Address already in use")
            Sockets(collision, fallbackFailure).use { sockets ->
                val thrown = assertThrows(Exception::class.java) {
                    bindAirPlayListener(address, 7000, sockets::create)
                }

                assertSame(fallbackFailure, thrown)
                assertSame(collision, thrown.suppressed.single())
                assertEquals(1, sockets[0].closeCalls)
                assertEquals(1, sockets[1].closeCalls)
                assertEquals(2, sockets.created)
                assertEquals(listOf(InetSocketAddress(address, 0)), sockets[1].binds)
            }
        }
    }

    @Test fun invalidPortsAreRejectedBeforeCreatingASocket() {
        for (port in listOf(-1, 65_536)) {
            var created = 0
            assertThrows(IllegalArgumentException::class.java) {
                bindAirPlayListener(address, port) {
                    created++
                    throw AssertionError("Invalid port must not allocate a socket")
                }
            }
            assertEquals(0, created)
        }
    }

    private fun assertNoFallback(failure: Exception) {
        Sockets(failure).use { sockets ->
            val thrown = assertThrows(Exception::class.java) {
                bindAirPlayListener(address, 7000, sockets::create)
            }

            assertSame(failure, thrown)
            assertEquals(listOf(InetSocketAddress(address, 7000)), sockets[0].binds)
            assertEquals(1, sockets[0].closeCalls)
            assertEquals(1, sockets.created)
        }
    }

    private class Sockets(vararg failures: Exception?) : Closeable {
        val events = mutableListOf<String>()
        private val sockets = failures.mapIndexed { index, failure ->
            FakeSocket(index, failure, events)
        }
        var created = 0
            private set

        operator fun get(index: Int): FakeSocket = sockets[index]

        fun create(): ServerSocket {
            val index = created++
            events += "create:$index"
            return sockets.getOrElse(index) { throw AssertionError("Unexpected socket retry") }
        }

        override fun close() {
            sockets.filter { it.closeCalls == 0 }.forEach { it.close() }
        }
    }

    private class FakeSocket(
        private val id: Int,
        private val failure: Exception?,
        private val events: MutableList<String>,
    ) : ServerSocket() {
        val binds = mutableListOf<InetSocketAddress>()
        var closeCalls = 0
            private set

        override fun bind(endpoint: SocketAddress?) {
            val target = endpoint as InetSocketAddress
            binds += target
            events += "bind:$id:${target.port}"
            failure?.let { throw it }
        }

        override fun close() {
            closeCalls++
            events += "close:$id"
            super.close()
        }
    }
}
