package com.shilapi.xcertplay

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AuthenticationImportIntentsTest {
    @Test fun acceptsLocalSharedAndOpenedDocuments() {
        val uri = Uri.parse("content://documents/downloads/DiPlay.apk")
        assertEquals(uri, AuthenticationImportIntents.apk(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        assertEquals(uri, AuthenticationImportIntents.apk(Intent(Intent.ACTION_SEND).apply {
            clipData = ClipData.newRawUri("APK", uri)
        }))
        assertEquals(uri, AuthenticationImportIntents.apk(Intent(Intent.ACTION_VIEW, uri)))
    }

    @Test fun rejectsNetworkUrisPrivatePathsAndUnrelatedActions() {
        for (value in listOf("https://example.com/DiPlay.apk", "file:///data/user/0/other/private.apk")) {
            val uri = Uri.parse(value)
            assertNull(AuthenticationImportIntents.apk(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
            assertNull(AuthenticationImportIntents.apk(Intent(Intent.ACTION_VIEW, uri)))
        }
        assertNull(AuthenticationImportIntents.apk(Intent(Intent.ACTION_MAIN, Uri.parse("content://documents/apk"))))
        assertNull(AuthenticationImportIntents.apk(Intent(Intent.ACTION_SEND)))
        assertNull(AuthenticationImportIntents.apk(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, android.os.Bundle())))
    }
}
