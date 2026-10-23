package com.shilapi.xcertplay.web

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shilapi.xcertplay.shared.R as UiR
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on Android: JVM regex accepts syntax that Android's ICU engine rejects. */
@RunWith(AndroidJUnit4::class)
class BrowserPageRuntimeTest {
    @Test
    fun localizedEntryPagesAndScriptsAreAccessibleOverHttp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (tag in listOf("en-US", "zh-CN", "zh-TW")) {
            val owner =
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        setLocales(LocaleList(Locale.forLanguageTag(tag)))
                    }
                )
            // Bind an OS-assigned port without changing the user's listener preferences.
            val server = LanWebServer(owner, "123456", port = 0)
            try {
                server.start(5000, true)
                fun get(path: String, mimeType: String): String {
                    val connection =
                        URL("http://127.0.0.1:${server.listeningPort}$path").openConnection()
                            as HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    return try {
                        assertEquals("$tag $path", 200, connection.responseCode)
                        assertTrue(connection.contentType.startsWith(mimeType))
                        assertEquals("no-store", connection.getHeaderField("Cache-Control"))
                        assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"))
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }
                }
                for (path in listOf("/", "/index.html", "/?code=123456")) {
                    val html = get(path, "text/html")
                    assertTrue(html.contains("<html lang=\"$tag\">"))
                    assertTrue(
                        html.contains(owner.getString(UiR.string.web_connect_your_carplay_display))
                    )
                    assertFalse(html.contains("{{"))
                    assertTrue(html.contains("pattern=\"[0-9]{6}\""))
                    assertTrue(html.contains("<script src=\"/i18n.js\"></script>"))
                    assertTrue(html.contains("<script type=\"module\" src=\"/app.js\"></script>"))
                }
                val script = get("/i18n.js", "text/javascript")
                val prefix = "globalThis.wheelplayMessages = "
                assertTrue(script.startsWith(prefix) && script.endsWith(";"))
                val messages = JSONObject(script.removePrefix(prefix).removeSuffix(";"))
                assertEquals(
                    owner.getString(UiR.string.web_start_display),
                    messages.getString("web_start_display"),
                )
                for (asset in
                    listOf(
                        "app.js",
                        "text.js",
                        "browser-audio.js",
                        "browser-media-session.js",
                        "browser-location.js",
                        "audio-worklet.js",
                        "style.css",
                    )) {
                    val mimeType = if (asset.endsWith(".js")) "text/javascript" else "text/css"
                    val expected =
                        owner.assets.open("web/$asset").bufferedReader().use { it.readText() }
                    assertEquals("$tag /$asset", expected, get("/$asset", mimeType))
                }
            } finally {
                server.stop()
            }
        }
    }
}
