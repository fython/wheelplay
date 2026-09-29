package com.shilapi.xcertplay.web

/** Builds the same-device browser entry without exposing a LAN address. */
internal object BrowserExperienceLink {
    private const val LOCAL_URL = "http://127.0.0.1:8080/"
    private val pairingCode = Regex("[0-9]{6}")

    fun local(code: String): String? =
        if (pairingCode.matches(code)) "$LOCAL_URL?code=$code" else null
}
