package moe.feng.wheelplay.projection.carlife

import android.content.Context
import android.os.Bundle

/** Settings and receiver share the application process. */
@Synchronized internal fun Context.status(message: String? = null): Bundle {
    val prefs = getSharedPreferences("carlife_status", Context.MODE_PRIVATE)
    if (message != null) {
        val text = message.take(512)
        val history = (prefs.getString("history", "").orEmpty() + "\n${System.currentTimeMillis()} $text").takeLast(6000)
        prefs.edit().putString("message", text).putString("history", history).apply()
    }
    return Bundle().apply {
        putString("message", prefs.getString("message", "尚未连接"))
        putString("history", prefs.getString("history", ""))
    }
}
