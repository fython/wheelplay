package moe.feng.wheelplay.plugin.carlink

import android.os.Bundle

internal enum class CarLinkPhase(val label: String) {
    IDLE("尚未连接"), INITIALIZING("正在准备连接"), WAITING_DEVICE("等待发送手机"),
    USB_PERMISSION("等待 USB 授权"), PHONE_CONFIRMATION("等待手机确认"),
    WAITING_VIDEO("等待画面"), READY("手机投影已就绪"), FAILED("连接遇到问题"), STOPPED("投影已停止");
    companion object {
        fun from(state: Bundle) = entries.firstOrNull { it.name == state.getString("phase") }
            ?: if (state.getBoolean("ready")) READY else if (state.getBoolean("active")) WAITING_DEVICE else IDLE
    }
}

internal fun videoOutputLabel(value: String?) = if (value == "webrtc") "低延迟视频模式" else "兼容模式"

/** Never copy raw status messages, PINs, credentials, addresses or device identifiers. */
internal fun carLinkDiagnosticReport(version: String, state: Bundle, config: Bundle, host: Bundle): String = buildString {
    appendLine("WheelPlay CarLink：$version")
    appendLine("WheelPlay：${host.getString("hostVersion") ?: "请从 WheelPlay 打开插件设置以获取"}")
    appendLine("SDK：1.5.3 / MiAuto 0.1.3")
    appendLine("状态：${CarLinkPhase.from(state).label}")
    appendLine("已保存连接：${CarLinkConnection.fromKey(config.getString("connection")).label}")
    state.getString("connection")?.let { appendLine("${if (state.getBoolean("active")) "当前" else "上次"}连接：${CarLinkConnection.fromKey(it).label}") }
    appendLine("已保存输出：${videoOutputLabel(config.getString("videoOutput"))}")
    state.getString("videoOutput")?.let { appendLine("会话输出：${videoOutputLabel(it)}") }
    appendLine("输出画布：${state.getInt("width")} × ${state.getInt("height")}")
    appendLine("源视频：${state.getInt("encodedWidth")} × ${state.getInt("encodedHeight")}")
    appendLine("会话 DPI：${state.getInt("activeDpi")}")
    append("错误码：${if (state.containsKey("errorCode")) state.getInt("errorCode").toString() else "无"}")
}
