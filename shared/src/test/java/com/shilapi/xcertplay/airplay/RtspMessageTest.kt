package com.shilapi.xcertplay.airplay

import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class RtspMessageTest {
    @Test fun parsesFragmentedAndCoalescedRequestsAndEventResponses() {
        val wire = ("POST /command RTSP/1.0\r\nCSeq: 7\r\nContent-Length: 5\r\n\r\nhello" +
            "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n" + "RECORD / RTSP/1.0\r\n\r\n").toByteArray()
        for (chunk in listOf(1, 2, 3, 17, wire.size)) {
            val decoder = RtspMessage.Decoder()
            val messages = mutableListOf<RtspMessage.Request>()
            wire.asList().chunked(chunk).forEach { decoder.feed(it.toByteArray(), messages::add) }
            assertEquals(3, messages.size)
            assertEquals("hello", messages[0].body.toString(Charsets.US_ASCII))
            assertEquals("7", messages[0].headers["cseq"])
            assertEquals("RTSP/1.0", messages[1].method)
            assertEquals("RECORD", messages[2].method)
            assertFalse(decoder.hasPending)
        }
    }

    @Test fun helperPreservesIncompleteRemainderExactly() {
        val complete = "OPTIONS * RTSP/1.0\r\n\r\n".toByteArray()
        for (partial in listOf("POST /", "POST /info RTSP/1.0\r\nContent-Length: 5\r\n\r\nhe")) {
            val parsed = RtspMessage.parseMessages(complete + partial.toByteArray())
            assertEquals(1, parsed.messages.size)
            assertArrayEquals(partial.toByteArray(), parsed.rest)
        }
    }

    @Test fun rejectsOversizedAndAmbiguousLengthsAsSoonAsHeadersArrive() {
        for (length in listOf("1073741824", "2147483647", "4294967296", "999999999999999999999", "-1", "+1", "1x", "")) {
            reject("SETUP / RTSP/1.0\r\nContent-Length: $length\r\n\r\n")
        }
        reject("POST / RTSP/1.0\r\nContent-Length: 0\r\ncontent-length: 1\r\n\r\n")
        reject("POST / RTSP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n")
    }

    @Test fun acceptsExactHeaderLimitAndRejectsUnterminatedOrOversizedHeaders() {
        val prefix = "OPTIONS * RTSP/1.0\r\nX: "
        val atLimit = prefix + "a".repeat(RtspMessage.MAX_HEADER_BYTES - prefix.length - 4) + "\r\n\r\n"
        assertEquals(1, RtspMessage.parseMessages(atLimit.toByteArray()).messages.size)
        reject(atLimit.replace("X: ", "X: aa"))
        val decoder = RtspMessage.Decoder()
        decoder.feed(ByteArray(RtspMessage.MAX_HEADER_BYTES) { 65 }) { fail() }
        assertThrows(IllegalArgumentException::class.java) { decoder.feed(byteArrayOf(65)) { fail() } }
    }

    @Test fun acceptsMaximumBodyInSmallChunksAndReleasesItBeforeNextRequest() {
        val decoder = RtspMessage.Decoder()
        decoder.feed("POST /info RTSP/1.0\r\nContent-Length: ${RtspMessage.MAX_BODY_BYTES}\r\n\r\n".toByteArray()) { fail() }
        var received = 0
        repeat(RtspMessage.MAX_BODY_BYTES / 1024) {
            decoder.feed(ByteArray(1024) { 7 }) { request ->
                received++
                assertEquals(RtspMessage.MAX_BODY_BYTES, request.body.size)
                assertTrue(request.body.all { it == 7.toByte() })
            }
        }
        decoder.feed("OPTIONS * RTSP/1.0\r\n\r\n".toByteArray()) { received++; assertEquals(0, it.body.size) }
        assertEquals(2, received)
        assertFalse(decoder.hasPending)
    }

    @Test fun encryptedFragmentationUsesTheSameBoundedDecoder() {
        val key = ByteArray(32) { it.toByte() }
        val sender = ControlCipher(key, key)
        val receiver = ControlCipher(key, key)
        val decoder = RtspMessage.Decoder()
        var encrypted = ByteArray(0)
        val wire = sender.encrypt("POST /command RTSP/1.0\r\nContent-Length: 3\r\n\r\nabc".toByteArray())
        var received = 0
        for (byte in wire) {
            encrypted += byte
            val decrypted = receiver.decrypt(encrypted)
            encrypted = decrypted.rest
            decoder.feed(decrypted.data) { received++; assertArrayEquals("abc".toByteArray(), it.body) }
        }
        assertEquals(1, received)
        val tooLarge = sender.encrypt("SETUP / RTSP/1.0\r\nContent-Length: 1073741824\r\n\r\n".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { decoder.feed(receiver.decrypt(tooLarge).data) { fail() } }
    }

    @Test fun slowDripCannotExtendDeadlineAndIdleSessionsAreNotTimedOut() {
        var now = 0L
        val deadline = RtspReadDeadline({ now }, 100)
        now = 1_000_000_000L
        assertEquals(0, deadline.socketTimeoutMillis())
        deadline.receivedBytes()
        now += 60_000_000L
        deadline.receivedBytes()
        deadline.retainPartial(true)
        assertEquals(40, deadline.socketTimeoutMillis())
        now += 40_000_000L
        assertThrows(SocketTimeoutException::class.java) { deadline.receivedBytes() }
        deadline.messageCompleted()
        now += 1_000_000_000L
        assertEquals(0, deadline.socketTimeoutMillis())
        deadline.retainPartial(true) // Next partial message in the same read gets its own deadline.
        assertEquals(100, deadline.socketTimeoutMillis())
        deadline.retainPartial(false)
        assertEquals(0, deadline.socketTimeoutMillis())
    }

    private fun reject(wire: String) {
        assertThrows(IllegalArgumentException::class.java) { RtspMessage.parseMessages(wire.toByteArray()) }
    }
}
