package com.shilapi.xcertplay.web

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.TeslaHttpConfig
import com.shilapi.xcertplay.network.TeslaHttpStatus

/** A web-service-owned binding, independent of whether an iPhone session exists. Main thread only. */
internal object TeslaHttpCompatibility {
    private val main = Handler(Looper.getMainLooper())
    private var owner: Context? = null
    private var bound = false
    @Volatile private var service: CarPlayVpnService? = null
    @Volatile private var bindingStatus = TeslaHttpStatus()
    val status: TeslaHttpStatus get() = service?.browserStatus?.let {
        it.copy(error = it.error ?: bindingStatus.error)
    } ?: bindingStatus

    private fun preferences(context: Context) = context.getSharedPreferences("tesla_http", Context.MODE_PRIVATE)
    fun config(context: Context): TeslaHttpConfig {
        val prefs = preferences(context)
        return runCatching {
            TeslaHttpConfig(prefs.getBoolean("enabled", false),
                prefs.getString("address", TeslaHttpConfig.DEFAULT_ADDRESS)!!,
                prefs.getString("hostname", "")!!)
        }.getOrDefault(TeslaHttpConfig())
    }

    fun save(context: Context, config: TeslaHttpConfig) {
        preferences(context).edit().putBoolean("enabled", config.enabled)
            .putString("address", config.address).putString("hostname", config.hostname).apply()
        main.post { owner?.let(::applyConfig) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!bound || owner == null) return
            service = (binder as CarPlayVpnService.LocalBinder).service
            bindingStatus = TeslaHttpStatus()
            owner?.let(::applyConfig)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bindingStatus = TeslaHttpStatus(error = "本地 VPN 已断开，请重新开启 Tesla HTTP 模式")
            unbind()
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    fun start(context: Context) {
        main.post {
            owner = context.applicationContext
            applyConfig(owner!!)
        }
    }

    fun stop() {
        main.post {
            // Clear the alias even when the NCM transport is still tearing down.
            // WebSession.stop is always followed by closing the CarPlay session/service binding.
            service?.clearBrowserAddress()
            unbind()
            owner = null
            bindingStatus = TeslaHttpStatus()
        }
    }

    private fun applyConfig(context: Context) {
        val config = config(context)
        if (!config.enabled) {
            val result = service?.setBrowserAddress(null)
            if (result is CarPlayVpnService.AttachResult.Failed) {
                bindingStatus = TeslaHttpStatus(error = result.message)
                return
            }
            unbind()
            bindingStatus = TeslaHttpStatus()
            return
        }
        if (CarPlayVpnService.prepare(context) != null) {
            bindingStatus = TeslaHttpStatus(error = "请在设置中重新开启 Tesla HTTP 模式并授权本地 VPN")
            return
        }
        val current = service
        if (current != null) {
            val result = current.setBrowserAddress(config.address)
            bindingStatus = if (result is CarPlayVpnService.AttachResult.Failed) TeslaHttpStatus(error = result.message)
                else TeslaHttpStatus()
            return
        }
        if (bound) return
        bindingStatus = TeslaHttpStatus(error = "正在准备 HTTP 虚拟地址…")
        try {
            bound = context.bindService(Intent(context, CarPlayVpnService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) bindingStatus = TeslaHttpStatus(error = "无法启动本地 VPN 服务")
        } catch (error: Exception) {
            bound = false
            bindingStatus = TeslaHttpStatus(error = "本地 VPN 启动失败：${error.javaClass.simpleName}")
        }
    }

    private fun unbind() {
        if (bound) owner?.let { runCatching { it.unbindService(connection) } }
        bound = false
        service = null
    }
}
