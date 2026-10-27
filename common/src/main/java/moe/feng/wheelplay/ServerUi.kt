package moe.feng.wheelplay


import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.textview.MaterialTextView

/** View helpers for the legacy CarPlay connection host; the main console uses Compose. */
internal class ServerUi(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val compactWidth get() = context.resources.configuration.screenWidthDp < 360
    private val pageGutter get() = maxOf(if (compactWidth) 16 else 24,
        (context.resources.configuration.screenWidthDp - 760) / 2)
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

    fun gap(height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
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
