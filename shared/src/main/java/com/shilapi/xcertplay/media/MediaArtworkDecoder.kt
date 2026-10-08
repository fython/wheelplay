package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** Called only by the artwork worker, before publishing immutable scaled JPEG bytes. */
object MediaArtworkDecoder {
    fun decode(bytes: ByteArray): MediaArtwork? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while ((maxOf(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > 512) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
            if (bitmap == null) null else {
                val out = ByteArrayOutputStream()
                val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                bitmap.recycle()
                if (encoded) MediaArtwork(out.toByteArray()) else null
            }
        }
    } catch (_: Exception) { null }
}
