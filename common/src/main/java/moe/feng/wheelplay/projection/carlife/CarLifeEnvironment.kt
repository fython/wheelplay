package moe.feng.wheelplay.projection.carlife

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import moe.feng.wheelplay.carlife.CarLifeBrands

/** Package visibility and entry resolution only; does not connect sockets or start system services. */
internal object CarLifeEnvironment {
    fun detect(context: Context): CarLifeBrands.Profile {
        val visible = CarLifeBrands.PROFILES.flatMap { listOf(it.hostPackage, it.enginePackage) }.filter {
            runCatching { context.packageManager.getApplicationInfo(it, 0).enabled }.getOrDefault(false)
        }.toSet()
        return CarLifeBrands.select(visible, Build.MANUFACTURER)
    }
    /** Called only from the visible preparation page; never silently launch from a service. */
    fun publicConnectionEntry(context: Context, profile: CarLifeBrands.Profile): Intent? =
        if (profile.id == "xiaomi") callableActivity(context, ComponentName(
            "com.miui.carlink", "com.miui.carlink.castfwk.usb.CarlifeAdbActivity")) else null

    private fun callableActivity(context: Context, component: ComponentName): Intent? {
        val info = runCatching { context.packageManager.getActivityInfo(component, 0) }.getOrNull()
            ?: return null
        return if (info.enabled && info.applicationInfo.enabled && info.exported &&
            (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED))
            Intent().setComponent(component) else null
    }
    fun entry(context: Context, profile: CarLifeBrands.Profile): Intent? {
        val pm = context.packageManager
        if (profile.entryClass.isNotEmpty()) {
            val component = ComponentName(profile.entryPackage, profile.entryClass)
            callableActivity(context, component)?.let { return it }
        }
        // A launcher is only a place for manual setup, never proof that the source started.
        return profile.hostPackage.takeIf { it.isNotEmpty() }?.let(pm::getLaunchIntentForPackage)
            ?: Intent(Settings.ACTION_SETTINGS).takeIf { it.resolveActivity(pm) != null }
    }
}
