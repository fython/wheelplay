package com.shilapi.xcertplay.projection.androidauto

import com.shilapi.xcertplay.projection.ProjectionSourceIds
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Local head-unit server setup. Does not depend on private wireless-start broadcasts. */
internal object LocalAndroidAuto {
    const val PACKAGE = "com.google.android.projection.gearhead"
    const val SOURCE_ID = ProjectionSourceIds.ANDROID_AUTO_LOCAL
    const val SETUP_SEEN = "aa_local_setup_seen"

    fun isInstalledAndEnabled(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) { false }

    // The AA settings activity is version-dependent. Callers try each intent and handle failure.
    fun settingsIntents(): List<Intent> = listOf(
        Intent().setClassName(PACKAGE, "$PACKAGE.companion.settings.DefaultSettingsActivity"),
        Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(PACKAGE),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PACKAGE"))
    )
}
