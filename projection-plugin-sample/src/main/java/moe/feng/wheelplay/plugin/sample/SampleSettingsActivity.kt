package moe.feng.wheelplay.plugin.sample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class SampleSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            textSize = 20f
            setPadding(32, 64, 32, 32)
            text = "WheelPlay plugin sample\n\nOpen WheelPlay → Sources → Plugin sources → Settings. The host now embeds a standard settings form and a Jetpack Remote Compose page. Both edit this plugin's own settings; reconnect projection to apply changes.\n\nEnable Surface plugin demo, then connect. This sample draws in a separate APK and receives up to ten browser touches. It needs no network, microphone or USB permissions.\n\nA real CarLink plugin can keep its device permission and SDK setup flow in this separate Activity."
        })
    }
}
