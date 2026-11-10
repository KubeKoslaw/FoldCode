package io.fold.probe

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny helpers for building the probe screens in code (no XML layouts, no AppCompat). */
object Ui {
    const val BG = 0xFF1E1E1E.toInt()
    const val SURFACE = 0xFF252526.toInt()
    const val SURFACE2 = 0xFF333337.toInt()
    const val TEXT = 0xFFD4D4D4.toInt()
    const val TEXT2 = 0xFF9DA0A6.toInt()
    const val ACCENT = 0xFF3794FF.toInt()
    const val GOOD = 0xFF4EC9B0.toInt()
    const val BAD = 0xFFF14C4C.toInt()

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    fun mono(ctx: Context, size: Float = 12f, color: Int = TEXT): TextView = TextView(ctx).apply {
        typeface = Typeface.MONOSPACE
        textSize = size
        setTextColor(color)
        setTextIsSelectable(true)
    }

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 20f
        setTextColor(TEXT)
        typeface = Typeface.DEFAULT_BOLD
    }

    fun button(ctx: Context, text: String, onClick: (View) -> Unit): Button = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(TEXT)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(8).toFloat()
            setColor(SURFACE2)
        }
        minHeight = ctx.dp(44)
        minimumHeight = ctx.dp(44)
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        gravity = Gravity.CENTER
        setOnClickListener(onClick)
    }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun LinearLayout.addSpaced(v: View, marginDp: Int = 8): LinearLayout {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        if (orientation == LinearLayout.HORIZONTAL) lp.marginEnd = context.dp(marginDp) else lp.bottomMargin = context.dp(marginDp)
        addView(v, lp)
        return this
    }
}
