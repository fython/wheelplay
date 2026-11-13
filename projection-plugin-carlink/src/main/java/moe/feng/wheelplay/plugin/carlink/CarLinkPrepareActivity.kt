package moe.feng.wheelplay.plugin.carlink

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.feng.wheelplay.plugin.ProjectionPlugin
import moe.feng.wheelplay.ui.WheelPlaySettingsTheme

/** Owns plugin permissions; never starts the projection SDK or grants host permissions. */
class CarLinkPrepareActivity : ComponentActivity() {
    private var problems by mutableStateOf(emptyList<String>())
    private var attempted by mutableStateOf(false)
    private var requesting by mutableStateOf(false)
    private var resumed = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requesting = false
        if (resumed) checkPreparation()
    }
    private fun connection() = CarLinkConnection.fromKey(settingsCall("config").getString("connection"))
    private fun missing() = CarLinkEnvironment.permissions(connection()).filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attempted = savedInstanceState?.getBoolean("attempted") ?: false
        requesting = savedInstanceState?.getBoolean("requesting") ?: false
        setContent {
            WheelPlaySettingsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("准备连接 CarLink", style = MaterialTheme.typography.headlineSmall)
                        Text("请为 CarLink 插件完成以下准备。完成后将返回 WheelPlay 并继续本次连接。")
                        problems.forEach { Text(it) }
                        if (missing().isNotEmpty()) {
                            Button(onClick = {
                                if (attempted && missing().all { !shouldShowRequestPermissionRationale(it) }) open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                                else requestMissing()
                            }, enabled = !requesting) { Text("授予所需权限") }
                            TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("打开插件应用权限设置") }
                        }
                        if (connection().wireless) {
                            TextButton(onClick = { open(Settings.ACTION_WIRELESS_SETTINGS) }) { Text("打开无线设置") }
                            TextButton(onClick = { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("打开定位设置") }
                        }
                        TextButton(onClick = { startActivity(Intent(this@CarLinkPrepareActivity, CarLinkSettingsActivity::class.java)) }) { Text("修改插件连接方式") }
                        Button(onClick = { checkPreparation() }, enabled = !requesting) { Text("检查并继续") }
                        TextButton(onClick = { setResult(RESULT_CANCELED); finish() }) { Text("取消连接") }
                    }
                }
            }
        }
    }
    private fun open(action: String, app: Boolean = false) {
        runCatching { startActivity(Intent(action).apply { if (app) data = Uri.parse("package:$packageName") }) }
            .onFailure { problems = problems + "无法打开系统设置，请从系统设置中手动处理后返回。" }
    }
    private fun requestMissing() {
        val needed = missing()
        if (needed.isEmpty()) { checkPreparation(); return }
        attempted = true; requesting = true
        permissions.launch(needed)
    }
    private fun checkPreparation() {
        if (isFinishing || requesting) return
        problems = CarLinkEnvironment.problems(this, connection())
        if (problems.isEmpty()) {
            setResult(RESULT_OK, Intent().putExtra(ProjectionPlugin.EXTRA_PREPARE_REQUEST,
                intent.getStringExtra(ProjectionPlugin.EXTRA_PREPARE_REQUEST)))
            finish()
        } else if (!attempted && missing().isNotEmpty()) requestMissing()
    }
    override fun onResume() { super.onResume(); resumed = true; checkPreparation() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("attempted", attempted); outState.putBoolean("requesting", requesting)
        super.onSaveInstanceState(outState)
    }
}
