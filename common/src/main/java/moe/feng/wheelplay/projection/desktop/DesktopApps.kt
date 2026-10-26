package moe.feng.wheelplay.projection.desktop

import android.content.ComponentName
import android.content.pm.ActivityInfo

internal val ActivityInfo.isDesktopLaunchable: Boolean
    get() = exported && enabled && applicationInfo.enabled

internal val ActivityInfo.desktopComponent: ComponentName
    get() = ComponentName(packageName, name)
