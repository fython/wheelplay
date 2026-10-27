package moe.feng.wheelplay

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.google.android.material.bottomnavigation.BottomNavigationView
import moe.feng.wheelplay.settings.SettingsCategory

internal object SettingsNavigation {
    fun views(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()

    fun open(activity: WheelPlayActivity, category: String, compose: ComposeTestRule) {
        views(activity.window.decorView)
            .filterIsInstance<BottomNavigationView>()
            .single()
            .selectedItemId = 3
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("settings:back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("settings:back").performClick()
        }
        val selected = SettingsCategory.entries.single { activity.getString(it.title) == category }
        compose.onNodeWithTag("category:${selected.name}").performScrollTo().performClick()
    }
}
