package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Button
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w360dp-h800dp")
class ServerNavigationTest {
    @org.junit.Before fun provisionAuthentication() {
        val identity = SyntheticMfiIdentity.create()
        MfiAssetStore(org.robolectric.RuntimeEnvironment.getApplication().noBackupFilesDir)
            .installFiles({ identity.key.inputStream() }, { identity.certificate.inputStream() })
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun tabs(activity: DiPlayActivity) = views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single()

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun primaryButtonIconsFollowTextStartInBothLayoutDirections() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        // The mobile manifest enables RTL; library-only tests need the same application flag.
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        val output = java.io.File("build/reports/material-ui/buttons").apply { mkdirs() }
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val button = ServerUi(activity).button("扫描二维码配对", true,
                com.shilapi.xcertplay.host.R.drawable.ic_server_scan) {}
            val container = android.widget.FrameLayout(activity).apply {
                layoutDirection = direction
                addView(button, android.widget.FrameLayout.LayoutParams(-1, -2))
            }
            activity.setContentView(container)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            container.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
            container.layout(0, 0, 320, 80)
            assertEquals(direction, button.layoutDirection)
            val start = if (direction == View.LAYOUT_DIRECTION_RTL) 2 else 0
            assertSame(button.icon, button.compoundDrawables[start])
            assertNull(button.compoundDrawables[2 - start])
            assertEquals(24, button.icon.bounds.width())
            assertEquals(button.currentTextColor, button.iconTint!!.getColorForState(button.drawableState, 0))
            val bitmap = android.graphics.Bitmap.createBitmap(320, 80, android.graphics.Bitmap.Config.ARGB_8888)
            container.draw(android.graphics.Canvas(bitmap))
            java.io.File(output, "direction-$direction.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        controller.pause().stop().destroy()
    }

    @Test fun repeatedTabChangesKeepTheSameShellAndNeverStartAnActivity() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val tabStrip = tabs(activity)
        val scrolls = views(activity.window.decorView).filterIsInstance<ScrollView>()
        assertEquals(1, tabStrip.selectedItemId)
        repeat(4) {
            for (index in listOf(1, 2, 0)) {
                tabStrip.selectedItemId = index + 1
                assertSame(tabStrip, tabs(activity))
                assertEquals(index + 1, tabStrip.selectedItemId)
                assertEquals(scrolls, views(activity.window.decorView).filterIsInstance<ScrollView>())
                assertEquals(1, scrolls.count { it.visibility == View.VISIBLE })
                assertNull(shadowOf(activity).nextStartedActivity)
            }
        }
        controller.pause().stop().destroy()
    }

    @Test fun materialSwitchSavesSettingAndTabChangesPreserveScrollAndControl() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val tabs = tabs(activity)
        tabs.selectedItemId = 3
        val control = views(activity.window.decorView).filterIsInstance<MaterialSwitch>()
            .single { it.contentDescription == "打开应用时自动连接" }
        val enabled = !control.isChecked
        control.isChecked = enabled
        assertEquals(enabled, DiPlayPreferences.autoConnect(activity))
        val scroll = views(activity.window.decorView).filterIsInstance<ScrollView>().single { it.visibility == View.VISIBLE }
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 360, 800)
        scroll.scrollTo(0, 160)
        val offset = scroll.scrollY
        assertEquals(160, offset)
        tabs.selectedItemId = 2
        tabs.selectedItemId = 3
        assertEquals(offset, scroll.scrollY)
        assertSame(control, views(activity.window.decorView).filterIsInstance<MaterialSwitch>()
            .single { it.contentDescription == "打开应用时自动连接" })
        assertTrue(views(activity.window.decorView).filterIsInstance<Button>().filterNot { it is android.widget.CompoundButton }.all { it is MaterialButton })
        controller.pause().stop().destroy()
    }

    @Test fun materialTabsAndContentFitNarrowAndLandscapeWindows() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        for ((width, height) in listOf(320 to 640, 800 to 400)) {
            val ui = ServerUi(activity)
            val shell = ui.Shell("service", {})
            shell.put("service", ui.content { body ->
                repeat(20) { body.addView(ui.text("长内容可以滚动", 24)) }
            })
            val root = shell.root
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertEquals(3, shell.tabs.menu.size())
            assertTrue(shell.tabs.height >= 48)
            val scroll = views(root).filterIsInstance<ScrollView>().single()
            assertTrue(scroll.height > 0)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
        }
        controller.pause().stop().destroy()
    }
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun renderMaterialPagesForVisualReview() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val output = java.io.File("build/reports/material-ui").apply { mkdirs() }
        for (index in 0..2) {
            tabs(activity).selectedItemId = index + 1
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
            val bitmap = android.graphics.Bitmap.createBitmap(360, 800, android.graphics.Bitmap.Config.ARGB_8888)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,
                androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 24, 0, 0))
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 24))
                    .build())
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 800)
            assertTrue("Rendered navigation should retain its minimum height: ${tabs(activity).height}/${tabs(activity).minimumHeight}",
                tabs(activity).height >= tabs(activity).minimumHeight)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(output, "page-$index.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val scroll = views(root).filterIsInstance<ScrollView>().single { it.visibility == View.VISIBLE }
            scroll.scrollTo(0, 160)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(output, "page-$index-scrolled.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            scroll.scrollTo(0, 0)
            bitmap.recycle()
        }
        controller.pause().stop().destroy()
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun spacingSurvivesNarrowLargeTextAndLandscape() {
        val output = java.io.File("build/reports/material-ui/spacing").apply { mkdirs() }
        for ((width, height, fontScale) in listOf(Triple(320, 640, 1f), Triple(360, 800, 1.3f), Triple(800, 400, 1f))) {
            org.robolectric.RuntimeEnvironment.setQualifiers("zh-rCN-w${width}dp-h${height}dp-mdpi")
            org.robolectric.RuntimeEnvironment.setFontScale(fontScale)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val nav = tabs(activity)
            for (page in 0..2) {
                nav.selectedItemId = page + 1
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,
                    androidx.core.view.WindowInsetsCompat.Builder()
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 24, 0, 0))
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 24)).build())
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val scroll = views(root).filterIsInstance<ScrollView>().single { it.visibility == View.VISIBLE }
                assertTrue("Content must remain scrollable above navigation", scroll.height > 100)
                views(scroll).filterIsInstance<com.google.android.material.card.MaterialCardView>().forEach { card ->
                    val body = card.getChildAt(0) as android.widget.LinearLayout
                    val heading = body.getChildAt(0) as android.widget.LinearLayout
                    val icon = heading.getChildAt(0)
                    val title = heading.getChildAt(1) as android.widget.TextView
                    val offset = kotlin.math.abs((icon.top * 2 + icon.height) - (title.top * 2 + title.height))
                    assertTrue("Card icon and title must share a vertical center: ${title.text}, offset=$offset", offset <= 2)
                }
                val contentBounds = android.graphics.Rect()
                scroll.getDrawingRect(contentBounds)
                (root as ViewGroup).offsetDescendantRectToMyCoords(scroll, contentBounds)
                val navBounds = android.graphics.Rect(0, 0, nav.width, nav.height)
                root.offsetDescendantRectToMyCoords(nav, navBounds)
                assertTrue("Navigation must not cover the scroll viewport", contentBounds.bottom <= navBounds.top)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(output, "$width-$height-$fontScale-page-$page.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                for ((position, offset) in listOf("middle" to (scroll.getChildAt(0).height / 2), "bottom" to (scroll.getChildAt(0).height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0))) {
                    scroll.scrollTo(0, offset)
                    root.draw(android.graphics.Canvas(bitmap))
                    java.io.File(output, "$width-$height-$fontScale-page-$page-$position.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                scroll.scrollTo(0, 0)
                bitmap.recycle()
            }
            controller.pause().stop().destroy()
        }
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun bottomNavigationKeepsIconsVisibleAndLabelsAlignedWithSystemInsets() {
        for (font in listOf(1f, 1.3f)) {
            org.robolectric.RuntimeEnvironment.setFontScale(font)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            val nav = tabs(activity)
            for (bottom in listOf(24, 48)) for (selected in 1..3) {
                nav.selectedItemId = selected
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350))
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root,
                    androidx.core.view.WindowInsetsCompat.Builder()
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 24, 0, 0))
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, bottom)).build())
                root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 360, 800)
                assertTrue("Navigation items need 80 dp excluding system inset: height=${nav.height}, padding=${nav.paddingBottom}",
                    nav.height - nav.paddingTop - nav.paddingBottom >= 80)
                val icons = views(nav).filter { it.id == com.google.android.material.R.id.navigation_bar_item_icon_view }
                assertEquals(3, icons.size)
                icons.forEach { icon ->
                    val visible = android.graphics.Rect()
                    assertTrue(icon.getLocalVisibleRect(visible))
                    assertEquals("Icon must not be clipped", android.graphics.Rect(0, 0, icon.width, icon.height), visible)
                }
                val labels = views(nav).filterIsInstance<android.widget.TextView>().filter {
                    it.visibility == View.VISIBLE && it.text.toString() in listOf("启动", "iPhone", "设置")
                }
                assertEquals(3, labels.size)
                val baselines = labels.map { label ->
                    val rect = android.graphics.Rect(0, 0, label.width, label.height)
                    root.offsetDescendantRectToMyCoords(label, rect)
                    rect.top + label.baseline
                }
                assertTrue("Mixed-language labels must share a baseline: $baselines", baselines.max() - baselines.min() <= 1)
            }
            controller.pause().stop().destroy()
        }
        org.robolectric.RuntimeEnvironment.setFontScale(1f)
    }

}
