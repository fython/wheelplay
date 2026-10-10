package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.network.TeslaHttpConfig

/** Builds the same-device browser entry without exposing a LAN address. */
internal object BrowserExperienceLink {
    private val pairingCode = Regex("[0-9]{6}")

    fun local(code: String, port: Int = TeslaHttpConfig.DEFAULT_PORT): String? =
        if (pairingCode.matches(code) && port in 1..65535) "http://127.0.0.1:$port/?code=$code" else null
}
