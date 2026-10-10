package com.shilapi.xcertplay.web

import android.content.Context
import com.shilapi.xcertplay.network.TeslaHttpConfig

/** Ordinary listeners never request su; privileged ports belong to Tesla's mapping settings. */
internal object WebListenSettings {
    const val DEFAULT_HTTP_PORT = 8080
    const val DEFAULT_HTTPS_PORT = 8443
    enum class CertificateType(val label: String) { DEFAULT("默认本地证书"), PEM("PEM"), PKCS12("PKCS#12") }

    @Synchronized internal fun migrate(context: Context) {
        val prefs = context.getSharedPreferences("web_listen", Context.MODE_PRIVATE)
        if (prefs.getBoolean("migrated", false)) return
        val oldHttp = context.getSharedPreferences("tesla_http", Context.MODE_PRIVATE)
        val oldHttps = context.getSharedPreferences("web_https", Context.MODE_PRIVATE)
        val http = oldHttp.getInt("port", DEFAULT_HTTP_PORT)
        val https = oldHttps.getInt("port", DEFAULT_HTTPS_PORT)
        var httpListener = http.takeIf { it in 1024..65535 } ?: DEFAULT_HTTP_PORT
        var httpsListener = https.takeIf { it in 1024..65535 } ?: DEFAULT_HTTPS_PORT
        if (httpListener == httpsListener) {
            if (https !in 1024..65535) httpsListener = if (httpListener == DEFAULT_HTTPS_PORT) 8444 else DEFAULT_HTTPS_PORT
            else httpListener = if (httpsListener == DEFAULT_HTTP_PORT) 8081 else DEFAULT_HTTP_PORT
        }
        val httpMapping = oldHttp.getInt("http_mapping_port", http.takeIf { it in 1..1023 } ?: 80)
        val httpsMapping = https.takeIf { it in 1..1023 } ?: if (httpMapping == 443) 444 else 443
        val hostname = oldHttp.getString("hostname", "").orEmpty().takeIf(TeslaHttpConfig::isHostname).orEmpty()
        val mappings = oldHttp.edit()
        if (!oldHttp.contains("http_mapping_port")) mappings.putInt("http_mapping_port", httpMapping)
        if (!oldHttp.contains("https_mapping_port")) mappings.putInt("https_mapping_port", httpsMapping)
        check(mappings.commit()) { "端口映射迁移失败" }
        check(prefs.edit().putInt("http_port", httpListener)
            .putInt("https_port", httpsListener)
            .putString("hostname", hostname).putBoolean("https_enabled", true)
            .putBoolean("migrated", true).commit()) { "Web 监听配置迁移失败" }
    }

    private fun preferences(context: Context): android.content.SharedPreferences {
        migrate(context)
        return context.getSharedPreferences("web_listen", Context.MODE_PRIVATE)
    }
    fun httpPort(context: Context) = preferences(context).getInt("http_port", DEFAULT_HTTP_PORT)
        .takeIf { it in 1024..65535 } ?: DEFAULT_HTTP_PORT
    fun httpsPort(context: Context) = preferences(context).getInt("https_port", DEFAULT_HTTPS_PORT)
        .takeIf { it in 1024..65535 } ?: DEFAULT_HTTPS_PORT
    fun httpsEnabled(context: Context) = preferences(context).getBoolean("https_enabled", true)
    fun hostname(context: Context) = preferences(context).getString("hostname", "").orEmpty()
        .takeIf(TeslaHttpConfig::isHostname).orEmpty()
    fun certificateType(context: Context) = runCatching {
        CertificateType.valueOf(preferences(context).getString("certificate_type",
            if (LanTls.hasCustom(context)) "PEM" else "DEFAULT")!!)
    }.getOrDefault(CertificateType.DEFAULT)
    fun saveHttpPort(context: Context, port: Int) = savePort(context, "http_port", port)
    fun saveHttpsPort(context: Context, port: Int) = savePort(context, "https_port", port)
    private fun savePort(context: Context, key: String, port: Int) {
        require(port in 1024..65535) { "Web 监听端口必须为 1024–65535；低位端口请在 Tesla 兼容中映射" }
        check(preferences(context).edit().putInt(key, port).commit()) { "Web 端口保存失败" }
    }
    fun saveHttpsEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean("https_enabled", enabled).commit()) { "HTTPS 开关保存失败" }
    }
    fun saveHostname(context: Context, hostname: String) {
        require(TeslaHttpConfig.isHostname(hostname)) { "请输入域名，不要包含协议、端口或路径" }
        check(preferences(context).edit().putString("hostname", hostname).commit()) { "Web 域名保存失败" }
    }
    fun saveCertificateType(context: Context, type: CertificateType) {
        check(preferences(context).edit().putString("certificate_type", type.name).commit()) { "证书类型保存失败" }
    }
}
