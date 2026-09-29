package com.shilapi.xcertplay.web

import androidx.annotation.Keep

/** All methods are called on one media worker; native callbacks only enqueue events. */
@Keep
internal class RtcNative(private val event: (String, String) -> Unit) {
    external fun create(profile: String, hevc: Boolean = false): Long
    external fun begin(handle: Long)
    external fun answer(handle: Long, sdp: String)
    external fun send(handle: Long, annexB: ByteArray, parameters: ByteArray?, timestamp90Khz: Long): Boolean
    external fun sendNative(handle: Long, frame: Long, parameters: ByteArray?, timestamp90Khz: Long): Boolean
    external fun srtpStats(handle: Long): LongArray?
    external fun destroy(handle: Long)
    @Keep private fun onEvent(kind: String, value: String) = event(kind, value)
    companion object { init { System.loadLibrary("wheelplay-rtc") } }
}
