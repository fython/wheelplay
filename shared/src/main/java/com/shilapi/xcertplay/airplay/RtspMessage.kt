package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream

/**
 * RTSP/HTTP-style request framing for the CarPlay control channel.
 *
 * The control connection on the advertised TCP port speaks a text request line plus headers plus an optional
 * body, using the same framing RTSP and HTTP share. Encryption, once pair-verify completes, wraps
 * this framing in a separate layer.
 */
object RtspMessage {
    data class Request(
        val method: String,
        val path: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    data class Response(
        val protocol: String? = null,
        val status: Int? = null,
        val statusText: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    data class Parsed(val messages: List<Request>, val rest: ByteArray)

    private val headerEnd = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    private val statusText = mapOf(
        200 to "OK",
        400 to "Bad Request",
        401 to "Unauthorized",
        404 to "Not Found",
        500 to "Internal Server Error",
    )

    internal const val MAX_HEADER_BYTES = 16 * 1024
    internal const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /** Incremental bounded framing shared by the control and event channels. */
    class Decoder {
        private var header = ByteArrayOutputStream()
        private var body = ByteArrayOutputStream()
        private var terminatorBytes = 0
        private var request: Request? = null
        private var contentLength = 0

        val hasPending: Boolean get() = header.size() != 0

        fun feed(bytes: ByteArray, onMessage: (Request) -> Unit) {
            var offset = 0
            while (offset < bytes.size) {
                if (request == null) {
                    require(header.size() < MAX_HEADER_BYTES) { "RTSP: header limit exceeded" }
                    val byte = bytes[offset++]
                    header.write(byte.toInt())
                    terminatorBytes = when {
                        byte == headerEnd[terminatorBytes] -> terminatorBytes + 1
                        byte == headerEnd[0] -> 1
                        else -> 0
                    }
                    if (terminatorBytes != headerEnd.size) continue
                    request = parseHeader(header.toByteArray())
                    val length = request!!.headers["content-length"]
                    if (length != null) {
                        require(length.isNotEmpty() && length.all { it in '0'..'9' }) {
                            "RTSP: invalid Content-Length"
                        }
                        val parsed = length.toLongOrNull()
                        require(parsed != null && parsed <= MAX_BODY_BYTES) { "RTSP: body limit exceeded" }
                        contentLength = parsed.toInt()
                    }
                }
                val count = minOf(contentLength - body.size(), bytes.size - offset)
                body.write(bytes, offset, count)
                offset += count
                if (body.size() == contentLength) {
                    val complete = request!!.copy(body = body.toByteArray())
                    // Release large buffers after each message, including before callbacks throw.
                    header = ByteArrayOutputStream()
                    body = ByteArrayOutputStream()
                    request = null
                    terminatorBytes = 0
                    contentLength = 0
                    onMessage(complete)
                }
            }
        }

        internal fun remainder(): ByteArray = header.toByteArray() + body.toByteArray()
    }

    private fun parseHeader(bytes: ByteArray): Request {
        val lines = String(bytes, 0, bytes.size - headerEnd.size, Charsets.US_ASCII).split("\r\n")
        val requestLine = lines.first().split(" ", limit = 3)
        require(requestLine.size == 3 && requestLine.all { it.isNotEmpty() }) { "RTSP: invalid start line" }
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            require(separator > 0) { "RTSP: invalid header" }
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            require(name != "content-length" || !headers.containsKey(name)) { "RTSP: duplicate Content-Length" }
            require(name != "transfer-encoding") { "RTSP: unsupported Transfer-Encoding" }
            headers[name] = value
        }
        return Request(requestLine[0], requestLine[1], requestLine[2], headers, ByteArray(0))
    }

    /** Compatibility helper; socket readers keep a Decoder across reads to avoid repeated copies. */
    fun parseMessages(buffer: ByteArray): Parsed {
        val decoder = Decoder()
        val messages = ArrayList<Request>()
        decoder.feed(buffer) { messages.add(it) }
        return Parsed(messages, decoder.remainder())
    }

    /** Builds a response, echoing the request protocol and CSeq. */
    fun buildResponse(request: Request, response: Response): ByteArray {
        val protocol = response.protocol ?: request.protocol
        val status = response.status ?: 200
        val statusLine = response.statusText ?: statusText[status] ?: "OK"
        val body = response.body
        val headers = LinkedHashMap<String, String>(response.headers)
        request.headers["cseq"]?.let { headers["CSeq"] = it }
        headers["Content-Length"] = body.size.toString()

        val head = buildString {
            append(protocol).append(' ').append(status).append(' ').append(statusLine).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + body
    }

}
