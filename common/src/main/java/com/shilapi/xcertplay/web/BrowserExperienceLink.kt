package com.shilapi.xcertplay.web


/** Builds the same-device browser entry without exposing a LAN address. */
internal object BrowserExperienceLink {
    private val pairingCode = Regex("[0-9]{6}")

    fun local(code: String, port: Int = WebListenSettings.DEFAULT_HTTP_PORT): String? =
        if (pairingCode.matches(code) && port in 1..65535) "http://127.0.0.1:$port/?code=$code" else null
}
