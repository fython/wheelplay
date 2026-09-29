package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.shilapi.xcertplay.host.R
import com.google.android.material.textview.MaterialTextView

/** Material 3 components. The shell and bottom navigation stay mounted across page changes. */
internal class ServerUi(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val compactWidth get() = context.resources.configuration.screenWidthDp < 360
    private val pageGutter get() = maxOf(if (compactWidth) 16 else 24,
        (context.resources.configuration.screenWidthDp - 760) / 2)
    private val cardPadding get() = if (compactWidth) 16 else 20
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun text(value: String, size: Int = 16, color: Int = MUTED, bold: Boolean = false) = MaterialTextView(context).apply {
        val appearance = when {
            size >= 28 -> com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall
            size >= 21 -> com.google.android.material.R.style.TextAppearance_Material3_TitleLarge
            bold -> com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
            size <= 14 -> com.google.android.material.R.style.TextAppearance_Material3_BodySmall
            else -> com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
        }
        setTextAppearance(appearance); text = value; setTextColor(color)
    }
    fun button(title: String, primary: Boolean = false, @androidx.annotation.DrawableRes iconRes: Int = 0, action: () -> Unit): MaterialButton {
        val layout = if (primary) com.shilapi.xcertplay.host.R.layout.server_button_filled
            else com.shilapi.xcertplay.host.R.layout.server_button_outlined
        return (android.view.LayoutInflater.from(context).inflate(layout, null) as MaterialButton).apply {
            text = title
            if (iconRes != 0) {
                setIconResource(iconRes)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconSize = dp(24)
                iconPadding = dp(8)
            }
            setOnClickListener { action() }
        }
    }
    fun secondaryButtonLayout(top: Int = 0) = LinearLayout.LayoutParams(-2, -2).apply {
        gravity = android.view.Gravity.END
        topMargin = dp(top)
    }

    fun preference(title: String, value: String, action: () -> Unit) = PreferenceRow(title, value, action)

    /** A whole-row preference target; its value is separate from its stable title. */
    inner class PreferenceRow(private val title: String, value: String, action: () -> Unit) : LinearLayout(context) {
        private val valueView = text(value, 16, MUTED).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(0, dp(16), 0, dp(16))
            val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = attributes.getDrawable(0)
            attributes.recycle()
            addView(text(title, 16, TEXT).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
            addView(valueView, LayoutParams(0, -2, 1f))
            addView(android.widget.ImageView(context).apply {
                setImageResource(com.shilapi.xcertplay.host.R.drawable.ic_settings_chevron)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { action() }
            setValue(value)
        }
        fun setValue(value: String) {
            valueView.text = value
            contentDescription = "$title，$value"
        }
    }
    /** Compact trailing button; its state layer is clipped to a pill-shaped target. */
    inner class DisclosureRow(private val title: String, action: () -> Unit) : LinearLayout(context) {
        private var expanded = false
        private val arrow = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_chevron)
            rotation = 90f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            orientation = HORIZONTAL
            isBaselineAligned = false
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val stateColor = com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface)
            fun pill(alpha: Int) = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(stateColor, alpha))
            }
            val states = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pill(31))
                addState(intArrayOf(android.R.attr.state_focused), pill(31))
                addState(intArrayOf(android.R.attr.state_hovered), pill(20))
                addState(intArrayOf(), pill(0))
            }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(stateColor, 31)),
                states, pill(255))
            addView(text(title, 14, MUTED).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(-2, -2))
            addView(arrow, LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) })
            contentDescription = title
            isFocusable = true
            setOnClickListener { action() }
            setExpanded(false)
        }
        override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
        fun setExpanded(value: Boolean, animate: Boolean = false) {
            val changed = expanded != value
            expanded = value
            androidx.core.view.ViewCompat.setStateDescription(this, if (value) "已展开" else "已折叠")
            if (!changed && animate) return
            arrow.animate().cancel()
            val angle = if (value) 270f else 90f
            if (animate && changed && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                arrow.animate().rotation(angle).setDuration(200)
                    .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator()).start()
            } else arrow.rotation = angle
        }
    }
    fun gap(height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    fun section(parent: LinearLayout, title: String, description: String? = null, content: (LinearLayout) -> Unit) {
        parent.addView(text(title, 21, TEXT, true))
        if (description != null) parent.addView(text(description).apply { setPadding(0, dp(8), 0, 0) })
        val body = column().apply { setPadding(0, dp(16), 0, dp(24)) }
        content(body)
        // Rows already include touch-target padding; keep the visual group gaps consistent.
        val firstInset = body.getChildAt(0)?.paddingTop ?: 0
        val lastInset = body.getChildAt(body.childCount - 1)?.paddingBottom ?: 0
        body.setPadding(0, (dp(16) - firstInset).coerceAtLeast(0), 0,
            (dp(24) - lastInset).coerceAtLeast(0))
        parent.addView(body)
        parent.addView(MaterialDivider(context), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
    }
    fun card(parent: LinearLayout, title: String, icon: Int, content: (LinearLayout) -> Unit) {
        val body = column().apply { setPadding(dp(cardPadding), dp(20), dp(cardPadding), dp(20)) }
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        heading.addView(android.widget.ImageView(context).apply {
            setImageResource(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        heading.addView(text(title, 21, TEXT, true).apply {
            includeFontPadding = false
            setFallbackLineSpacing(false)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(heading); body.addView(gap(16)); content(body)
        parent.addView(MaterialCardView(context).apply {
            radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(SURFACE)
            addView(body, android.view.ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    fun content(build: (LinearLayout) -> Unit): ScrollView {
        val gutter = pageGutter
        val body = column().apply { setPadding(dp(gutter), dp(24), dp(gutter), dp(32)) }
        build(body)
        // A trailing section divider and its group gap would duplicate the page bottom space.
        if (body.getChildAt(body.childCount - 1) is MaterialDivider) {
            body.removeViewAt(body.childCount - 1)
            body.getChildAt(body.childCount - 1)?.let { last ->
                last.setPadding(last.paddingLeft, last.paddingTop, last.paddingRight, 0)
            }
        }
        return ScrollView(context).apply {
            isFillViewport = true; clipToPadding = false
            addView(body, android.view.ViewGroup.LayoutParams(-1, -2))
        }
    }
    inner class Shell(initialPage: String, private val onPageChanged: (String) -> Unit) {
        val root = FrameLayout(context).apply { setBackgroundColor(BG) }
        private val layout = column()
        private var topInset = 0
        private val statusScrim = View(context).apply {
            setBackgroundColor(Color.argb(224, 17, 22, 26))
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
        }
        val tabs = BottomNavigationView(context).apply {
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            minimumHeight = dp(80)
            itemIconSize = dp(24)
            setItemHorizontalTranslationEnabled(false)
            setItemTextAppearanceActive(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setItemTextAppearanceInactive(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setItemTextAppearanceActiveBoldEnabled(false)
            // Insets belong to the surrounding surface, never to the icon / label layout.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> insets }
        }
        private val navigationSurface = FrameLayout(context).apply {
            val surface = com.google.android.material.color.MaterialColors.getColor(
                tabs, com.google.android.material.R.attr.colorSurfaceContainer)
            setBackgroundColor(surface)
            tabs.setBackgroundColor(surface)
            tabs.elevation = 0f
            addView(tabs, FrameLayout.LayoutParams(-1, -2))
        }
        private val container = FrameLayout(context)
        private val pages = linkedMapOf<String, View>()
        private val ids = listOf("service", "phone", "settings")
        var selected = initialPage
            private set
        init {
            root.addView(layout, FrameLayout.LayoutParams(-1, -1))
            root.addView(statusScrim, FrameLayout.LayoutParams(-1, 0, android.view.Gravity.TOP))
            ServerWindow.fitBottomNavigation(root, navigationSurface) { inset ->
                topInset = inset
                statusScrim.layoutParams = (statusScrim.layoutParams as FrameLayout.LayoutParams).apply { height = inset }
                pages.values.forEach(::applyTopInset)
                updateStatusScrim()
            }
            val icons = listOf(R.drawable.ic_server_launch, R.drawable.ic_server_phone, R.drawable.ic_server_settings)
            listOf("启动", "iPhone", "设置").forEachIndexed { index, title ->
                tabs.menu.add(0, index + 1, index, title).setIcon(icons[index])
            }
            // CJK fallback metrics otherwise enlarge only Chinese labels, shifting their baseline.
            for (id in 1..3) {
                val item = tabs.findViewById<View>(id)
                listOf(com.google.android.material.R.id.navigation_bar_item_small_label_view,
                    com.google.android.material.R.id.navigation_bar_item_large_label_view).forEach { labelId ->
                    item.findViewById<TextView>(labelId)?.apply {
                        includeFontPadding = true
                        setFallbackLineSpacing(false)
                        minHeight = dp(20)
                        gravity = android.view.Gravity.CENTER
                    }
                }
            }
            tabs.selectedItemId = ids.indexOf(initialPage).coerceAtLeast(0) + 1
            layout.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
            layout.addView(navigationSurface, LinearLayout.LayoutParams(-1, -2))
            tabs.setOnItemSelectedListener { item -> select(ids[item.itemId - 1]); true }

        }
        fun put(id: String, view: View) {
            val old = pages.put(id, view)
            val scrollY = old?.scrollY ?: 0
            if (old != null) container.removeView(old)
            container.addView(view, FrameLayout.LayoutParams(-1, -1))
            view.visibility = if (id == selected) View.VISIBLE else View.GONE
            applyTopInset(view)
            view.setOnScrollChangeListener { _, _, _, _, _ ->
                if (selected == id) updateStatusScrim()
            }
            updateStatusScrim()
            view.post { view.scrollTo(0, scrollY); updateStatusScrim() }
        }
        private fun applyTopInset(view: View) {
            // ScrollView draws through this padding, so content can pass behind the status bar.
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, view.paddingBottom)
        }
        private fun updateStatusScrim() {
            statusScrim.alpha = ((pages[selected]?.scrollY ?: 0).toFloat() / dp(24)).coerceIn(0f, 1f)
        }
        fun select(id: String) {
            if (id !in ids || selected == id) return
            selected = id
            pages.forEach { (page, view) -> view.visibility = if (page == id) View.VISIBLE else View.GONE }
            // Only the stock Material indicator animates; no Activity or whole-window transition.
            if (tabs.selectedItemId != ids.indexOf(id) + 1) tabs.selectedItemId = ids.indexOf(id) + 1
            updateStatusScrim()
            onPageChanged(id)
        }
    }
    companion object {
        val BG = Color.rgb(17, 22, 26)
        val SURFACE = Color.rgb(28, 36, 40)
        val BORDER = Color.rgb(65, 73, 65)
        val ACCENT = Color.rgb(180, 217, 177)
        val TEXT = Color.rgb(226, 232, 227)
        val MUTED = Color.rgb(193, 201, 190)
        val WARNING = Color.rgb(239, 190, 144)
    }
}
