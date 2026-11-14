package com.shilapi.xcertplay.web

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.TeslaHttpConfig
import com.shilapi.xcertplay.shared.R as UiR

/** Ordinary listeners never request su; privileged ports belong to Tesla's mapping settings. */
internal object WebListenSettings {
    const val DEFAULT_HTTP_PORT = 8080
    const val DEFAULT_HTTPS_PORT = 8443
    const val DEFAULT_MDNS_HOSTNAME = "wheelplay.local"
    private val mdnsPattern = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.local")

    fun validMdnsHostname(value: String) = value.length <= 69 && mdnsPattern.matches(value)

    enum class CertificateType(@param:androidx.annotation.StringRes val labelRes: Int) {
        DEFAULT(UiR.string.ui_default_local_certificate),
        PEM(UiR.string.host_pem),
        PKCS12(UiR.string.host_pkcs_12),
        EMUDHRA_CA(R.string.settings_emudhra_ca),
        BUNDLED_CA(R.string.settings_bundled_ca),
    }

    @Synchronized
    internal fun migrate(context: Context) {
        val prefs = context.getSharedPreferences("web_listen", Context.MODE_PRIVATE)
        if (prefs.getBoolean("migrated", false)) return
        val oldHttp = context.getSharedPreferences("tesla_http", Context.MODE_PRIVATE)
        val oldHttps = context.getSharedPreferences("web_https", Context.MODE_PRIVATE)
        val http = oldHttp.getInt("port", DEFAULT_HTTP_PORT)
        val https = oldHttps.getInt("port", DEFAULT_HTTPS_PORT)
        var httpListener = http.takeIf { it in 1024..65535 } ?: DEFAULT_HTTP_PORT
        var httpsListener = https.takeIf { it in 1024..65535 } ?: DEFAULT_HTTPS_PORT
        if (httpListener == httpsListener) {
            if (https !in 1024..65535)
                httpsListener = if (httpListener == DEFAULT_HTTPS_PORT) 8444 else DEFAULT_HTTPS_PORT
            else httpListener = if (httpsListener == DEFAULT_HTTP_PORT) 8081 else DEFAULT_HTTP_PORT
        }
        val httpMapping = oldHttp.getInt("http_mapping_port", http.takeIf { it in 1..1023 } ?: 80)
        val httpsMapping = https.takeIf { it in 1..1023 } ?: if (httpMapping == 443) 444 else 443
        val hostname =
            oldHttp
                .getString("hostname", "")
                .orEmpty()
                .takeIf(TeslaHttpConfig::isHostname)
                .orEmpty()
        val mappings = oldHttp.edit()
        if (!oldHttp.contains("http_mapping_port"))
            mappings.putInt("http_mapping_port", httpMapping)
        if (!oldHttp.contains("https_mapping_port"))
            mappings.putInt("https_mapping_port", httpsMapping)
        check(mappings.commit()) {
            context.getString(UiR.string.ui_port_forwarding_migration_failed)
        }
        check(
            prefs
                .edit()
                .putInt("http_port", httpListener)
                .putInt("https_port", httpsListener)
                .putString("hostname", hostname)
                .putBoolean("https_enabled", true)
                .putBoolean("migrated", true)
                .commit()
        ) {
            context.getString(UiR.string.ui_web_listener_migration_failed)
        }
    }

    private fun preferences(context: Context): android.content.SharedPreferences {
        migrate(context)
        return context.getSharedPreferences("web_listen", Context.MODE_PRIVATE)
    }

    fun httpPort(context: Context) =
        preferences(context).getInt("http_port", DEFAULT_HTTP_PORT).takeIf { it in 1024..65535 }
            ?: DEFAULT_HTTP_PORT

    fun httpsPort(context: Context) =
        preferences(context).getInt("https_port", DEFAULT_HTTPS_PORT).takeIf { it in 1024..65535 }
            ?: DEFAULT_HTTPS_PORT

    fun httpsEnabled(context: Context) = preferences(context).getBoolean("https_enabled", true)

    // A provisioned build opts in on first use; an explicit choice of the local CA stays local.
    fun bundledCaEnabled(context: Context): Boolean {
        val prefs = preferences(context)
        return prefs.getBoolean("bundled_ca_enabled",
            prefs.getString("certificate_type", null) != "DEFAULT" && LanTls.hasBundledCa(context))
    }

    fun saveBundledCaEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean("bundled_ca_enabled", enabled).commit()) {
            context.getString(R.string.settings_bundled_ca_save_failed)
        }
    }

    fun hostname(context: Context) =
        preferences(context)
            .getString("hostname", "")
            .orEmpty()
            .takeIf(TeslaHttpConfig::isHostname)
            .orEmpty()

    fun mdnsHostname(context: Context) =
        preferences(context)
            .getString("mdns_hostname", DEFAULT_MDNS_HOSTNAME)
            .orEmpty()
            .takeIf(::validMdnsHostname) ?: DEFAULT_MDNS_HOSTNAME

    fun saveMdnsHostname(context: Context, value: String) {
        require(validMdnsHostname(value)) { context.getString(R.string.settings_mdns_invalid) }
        check(preferences(context).edit().putString("mdns_hostname", value).commit()) {
            context.getString(R.string.settings_mdns_save_failed)
        }
    }

    fun certificateType(context: Context) =
        runCatching {
                CertificateType.valueOf(
                    preferences(context)
                        .getString(
                            "certificate_type",
                            if (LanTls.hasCustom(context)) "PEM"
                            else if (LanTls.usesBundledCa(context)) "BUNDLED_CA" else "DEFAULT",
                        )!!
                )
            }
            .getOrDefault(CertificateType.DEFAULT)

    fun saveHttpPort(context: Context, port: Int) = savePort(context, "http_port", port)

    fun saveHttpsPort(context: Context, port: Int) = savePort(context, "https_port", port)

    private fun savePort(context: Context, key: String, port: Int) {
        require(port in 1024..65535) {
            context.getString(UiR.string.ui_web_listener_ports_must_be_between_1024_and_65535)
        }
        check(preferences(context).edit().putInt(key, port).commit()) {
            context.getString(UiR.string.ui_could_not_save_web_port)
        }
    }

    fun saveHttpsEnabled(context: Context, enabled: Boolean) {
        check(preferences(context).edit().putBoolean("https_enabled", enabled).commit()) {
            context.getString(UiR.string.ui_could_not_save_https_setting)
        }
    }

    fun saveHostname(context: Context, hostname: String) {
        require(TeslaHttpConfig.isHostname(hostname)) {
            context.getString(UiR.string.ui_enter_a_hostname_without_a_scheme_port_or_path)
        }
        check(preferences(context).edit().putString("hostname", hostname).commit()) {
            context.getString(UiR.string.ui_could_not_save_web_hostname)
        }
    }

    fun saveCertificateType(context: Context, type: CertificateType) {
        check(preferences(context).edit().putString("certificate_type", type.name).commit()) {
            context.getString(UiR.string.ui_could_not_save_certificate_type)
        }
    }
}
