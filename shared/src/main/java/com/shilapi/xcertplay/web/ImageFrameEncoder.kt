package com.shilapi.xcertplay.web

import java.io.ByteArrayOutputStream

/** Reuse staging storage, but publish immutable bytes. A failed WebP encoder stays on JPEG. */
internal class ImageFrameEncoder {
    private val output = ByteArrayOutputStream()
    private var webpFailed = false

    fun encode(preferred: ImageFormat, compress: (ImageFormat, ByteArrayOutputStream) -> Boolean): Pair<ByteArray, ImageFormat> {
        var format = if (webpFailed) ImageFormat.JPEG else preferred
        output.reset()
        val encoded = try { compress(format, output) } catch (error: Exception) {
            if (format == ImageFormat.JPEG) throw error
            false
        }
        if (!encoded && format == ImageFormat.WEBP) {
            webpFailed = true; format = ImageFormat.JPEG; output.reset()
            check(compress(format, output)) { "JPEG encoding failed" }
        } else check(encoded) { "JPEG encoding failed" }
        return output.toByteArray() to format
    }
}
