package com.shilapi.xcertplay.projection.plugin

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import moe.feng.wheelplay.AirPlayPersistence
import com.shilapi.xcertplay.projection.ImageOutputDiagnostics
import com.shilapi.xcertplay.web.ImageEncodingOptions
import com.shilapi.xcertplay.web.ImageFormat
import com.shilapi.xcertplay.web.WebSession

/** An explicit, narrowly scoped capability; no exported preferences provider. */
internal class PluginSettingsBridge(context: Context) : ResultReceiver(Handler(Looper.getMainLooper())) {
    private val app = context.applicationContext
    private val hostVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("未知")
    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        val data = resultData ?: return
        val reply = data.getParcelable<ResultReceiver>("reply") ?: return
        val error = runCatching {
            if (resultCode == 1) {
                // Validate the complete update before writing any preference.
                val quality = data.getInt("quality")
                val fps = data.getInt("fps")
                val format = ImageFormat.valueOf(data.getString("format") ?: "")
                require(quality in ImageEncodingOptions.QUALITY_RANGE && fps in listOf(10, 15, 20, 30))
                AirPlayPersistence.saveImageQuality(app, quality)
                AirPlayPersistence.saveImageFormat(app, format)
                AirPlayPersistence.saveBrowserImageFps(app, fps)
            }
        }.exceptionOrNull()
        reply.send(if (error == null) 0 else 1, Bundle().apply {
            putString("hostVersion", hostVersion)
            putInt("quality", AirPlayPersistence.loadImageQuality(app))
            putString("format", AirPlayPersistence.loadImageFormat(app).name)
            putInt("fps", AirPlayPersistence.loadBrowserImageFps(app))
            val viewport = WebSession.currentBrowserViewport
            putString("viewport", viewport?.let { "${it.first} × ${it.second} px" } ?: "尚未获取")
            putString("output", ImageOutputDiagnostics.describe())
            if (error != null) putString("error", "设置未保存，请重试")
        })
    }
}
