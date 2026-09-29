package com.shilapi.xcertplay.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserExperienceLinkTest {
    @Test fun localLinkCarriesAValidSixDigitCode() {
        assertEquals("http://127.0.0.1:8080/?code=123456", BrowserExperienceLink.local("123456"))
    }

    @Test fun invalidOrMissingCodesNeverProduceALink() {
        assertNull(BrowserExperienceLink.local(""))
        assertNull(BrowserExperienceLink.local("12345"))
        assertNull(BrowserExperienceLink.local("12345x"))
    }
}
