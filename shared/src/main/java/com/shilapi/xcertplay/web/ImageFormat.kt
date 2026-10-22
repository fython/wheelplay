package com.shilapi.xcertplay.web

import android.graphics.Bitmap
import android.os.Build

/** Still-image formats negotiated independently of the video transport. */
enum class ImageFormat(val wireId: Int, val mime: String) {
    JPEG(1, "image/jpeg"), WEBP(2, "image/webp");

    @Suppress("DEPRECATION")
    val compression: Bitmap.CompressFormat
        get() = when (this) {
            JPEG -> Bitmap.CompressFormat.JPEG
            WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        }
}
