package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaArtworkDecoderTest {
    @Test fun jpegSamplingRoundsUpAndNeverExceeds512Pixels() {
        val input = Bitmap.createBitmap(2049, 513, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        assertTrue(input.compress(Bitmap.CompressFormat.JPEG, 90, out)); input.recycle()
        val result = MediaArtworkDecoder.decode(out.toByteArray())!!.bytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(result, 0, result.size, bounds)
        assertTrue(bounds.outWidth in 1..512); assertTrue(bounds.outHeight in 1..512)
    }

    @Test fun malformedJpegReturnsNoArtwork() {
        assertNull(MediaArtworkDecoder.decode(byteArrayOf(-1,-40,-1,-39)))
        assertNull(MediaArtworkDecoder.decode(byteArrayOf()))
    }
}
