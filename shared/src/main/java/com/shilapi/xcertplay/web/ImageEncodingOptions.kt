package com.shilapi.xcertplay.web

/** One immutable snapshot per encoded image; quality is an encoder parameter, not a percentage. */
data class ImageEncodingOptions(val format: ImageFormat = ImageFormat.JPEG, val quality: Int = DEFAULT_QUALITY) {
    init { require(quality in QUALITY_RANGE) }

    companion object {
        const val DEFAULT_QUALITY = 75
        val QUALITY_RANGE = 1..100
        fun sanitizeQuality(value: Int) = value.takeIf { it in QUALITY_RANGE } ?: DEFAULT_QUALITY
    }
}
