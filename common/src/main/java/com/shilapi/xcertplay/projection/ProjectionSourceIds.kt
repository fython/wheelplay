package com.shilapi.xcertplay.projection

/** Stable IDs shared by source providers, UI selection and saved preferences.
 * Family IDs (Android Auto, VNC, diagnostics and plugins) group concrete sessions.
 * Plugin session IDs remain dynamic: PLUGIN_PREFIX followed by the service component.
 * These values are persisted; renaming a Kotlin symbol must not change its string value.
 */
internal object ProjectionSourceIds {
    const val CARPLAY = "carplay"
    const val ANDROID_AUTO = "android-auto"
    const val ANDROID_AUTO_LOCAL = "$ANDROID_AUTO-local"
    const val ANDROID_AUTO_USB = "$ANDROID_AUTO-usb"
    const val ANDROID_AUTO_TCP = "$ANDROID_AUTO-tcp"
    const val VNC = "vnc"
    const val VNC_TCP = "$VNC-tcp"
    const val ANDROID_DESKTOP = "android-desktop"
    const val ICCOA = "iccoa"
    const val ICCOA_CARLINK = "$ICCOA-carlink"
    const val DEMO = "demo"
    const val DEMO_SURFACE = "$DEMO-surface"
    const val PLUGIN = "plugin"
    const val PLUGIN_PREFIX = "$PLUGIN-"
}
