package moe.feng.wheelplay.plugin

/** Event-driven snapshots: no audio payloads or session tokens. */
object PluginAudioDiagnostics {
    @Volatile private var file: java.io.File? = null
    fun initialize(context: android.content.Context) {
        file = java.io.File(context.filesDir, "audio-diagnostic-${android.os.Process.myPid()}.log")
        record("DIAGNOSTICS_READY pid=${android.os.Process.myPid()}")
    }
    @Synchronized fun record(message: String) {
        android.util.Log.i("WheelPlayAudioDiag", message)
        runCatching {
            file?.let {
                if (it.length() > 512 * 1024) {
                    it.copyTo(java.io.File(it.path + ".previous"), overwrite = true)
                    it.writeText("")
                }
                it.appendText("${System.currentTimeMillis()} $message\n")
            }
        }
    }
    fun dump(reason: String) {
        Thread.getAllStackTraces().forEach { (thread, stack) ->
            if (thread.name.startsWith("plugin-audio") || thread.name == "main" || thread.name.startsWith("carlife-")) {
                record("$reason thread=${thread.name} state=${thread.state}\n" + stack.take(24).joinToString("\n"))
            }
        }
    }
}
