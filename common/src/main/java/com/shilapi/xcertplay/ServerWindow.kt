package com.shilapi.xcertplay

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The Android app is a server console, so system navigation always remains available. */
internal object ServerWindow {
    @Suppress("DEPRECATION")
    fun showSystemBars(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun fitContent(view: View) = fitInsets(view, null)

    fun fitBottomNavigation(view: View, navigation: View, onTopInset: (Int) -> Unit) =
        fitInsets(view, navigation, onTopInset)

    private fun fitInsets(view: View, navigation: View?, onTopInset: ((Int) -> Unit)? = null) {
        val navigationPadding = navigation?.paddingBottom ?: 0
        // The outer navigation surface owns the system inset; its child keeps its full content height.
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            target.setPadding(bars.left, if (onTopInset == null) bars.top else 0, bars.right,
                if (navigation == null) bars.bottom else 0)
            onTopInset?.invoke(bars.top)
            navigation?.setPadding(navigation.paddingLeft, navigation.paddingTop,
                navigation.paddingRight, navigationPadding + bars.bottom)
            insets
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { ViewCompat.requestApplyInsets(v) }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}
