package io.fold.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * FoldCode's on-screen code keyboard (and the key bar above the system keyboard). Draws its rows
 * itself, never takes focus (the WebView keeps it), and supports two thumbs at once,
 * long-press alternatives, key repeat and cursor movement by swiping on the space bar.
 */
class KeyboardView(ctx: Context) : View(ctx) {

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            requestLayout()
            layoutKeys()
            invalidate()
        }

    /** A key was tapped; [alt] is the chosen long-press alternative, if any. */
    var onKey: (key: Key, alt: String?) -> Unit = { _, _ -> }
    /** Swipe on the space bar: negative = left, positive = right, one call per step. */
    var onCursorStep: (step: Int) -> Unit = {}
    var latchOf: (Mod) -> Latch = { Latch.OFF }
    var upperCase: () -> Boolean = { false }
    var haptics = true

    var night = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    /** Lower keys on short screens (the folded phone's outer display) so more code stays visible. */
    private val compact get() = resources.configuration.screenHeightDp < 800
    private val functionRowHeight get() = dp(if (compact) 36f else 40f)
    private val rowHeight get() = dp(if (compact) 40f else 46f)
    private val gap = dp(4f)

    private val keyRects = mutableListOf<Pair<Key, RectF>>()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val handler = Handler(Looper.getMainLooper())

    private class Press(val key: Key, val rect: RectF, val downX: Float) {
        var popup: List<String>? = null
        var popupIndex = -1
        var popupRect = RectF()
        var dragSteps = 0
        var repeated = false
        var longPress: Runnable? = null
        var repeat: Runnable? = null
    }

    private val presses = HashMap<Int, Press>()

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = true
    }

    // ---- layout ----------------------------------------------------------------------------

    private fun heightFor(rowIndex: Int) = if (rowIndex == 0) functionRowHeight else rowHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = rows.indices.sumOf { heightFor(it).toDouble() }.toFloat() + gap * (rows.size + 1)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        keyRects.clear()
        if (width == 0) return
        var y = gap
        rows.forEachIndexed { i, row ->
            val h = heightFor(i)
            val total = row.sumOf { it.width.toDouble() }.toFloat()
            val unit = (width - gap * (row.size + 1)) / total
            var x = gap
            for (k in row) {
                val w = unit * k.width
                keyRects += k to RectF(x, y, x + w, y + h)
                x += w + gap
            }
            y += h + gap
        }
    }

    // ---- drawing -----------------------------------------------------------------------------

    private val bg get() = if (night) 0xFF1E1E1E.toInt() else 0xFFE4E6EA.toInt()
    private val keyColor get() = if (night) 0xFF3A3D41.toInt() else 0xFFFFFFFF.toInt()
    private val specialColor get() = if (night) 0xFF2D2F33.toInt() else 0xFFCDD1D8.toInt()
    private val accentColor = 0xFF0E639C.toInt()
    private val pressedColor = 0xFF094771.toInt()
    private val textColor get() = if (night) 0xFFE8E8E8.toInt() else 0xFF1F1F1F.toInt()
    private val hintColor get() = if (night) 0xFF8A8F98.toInt() else 0xFF6B7078.toInt()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        val radius = dp(6f)
        val pressed = presses.values.map { it.key }.toSet()
        for ((k, r) in keyRects) {
            if (k.style == Style.SPACER) continue
            val latch = (k.action as? Action.Modifier)?.let { latchOf(it.mod) } ?: Latch.OFF
            keyPaint.color = when {
                k in pressed -> pressedColor
                latch != Latch.OFF || k.style == Style.ACCENT -> accentColor
                k.style == Style.SPECIAL -> specialColor
                else -> keyColor
            }
            canvas.drawRoundRect(r, radius, radius, keyPaint)
            if (latch == Latch.LOCK) {
                keyPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawRect(r.left + r.width() * 0.3f, r.bottom - dp(4f), r.right - r.width() * 0.3f, r.bottom - dp(2f), keyPaint)
            }
            val label = labelOf(k)
            textPaint.color = if (latch != Latch.OFF || k.style == Style.ACCENT) 0xFFFFFFFF.toInt() else textColor
            textPaint.textSize = dp(if (k.style == Style.CHAR && label.length == 1) 19f else 14f)
            canvas.drawText(label, r.centerX(), r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
            k.alts.firstOrNull()?.let { hint ->
                hintPaint.color = hintColor
                hintPaint.textSize = dp(10f)
                canvas.drawText(hint, r.right - dp(4f), r.top + dp(12f), hintPaint)
            }
        }
        for (p in presses.values) p.popup?.let { drawPopup(canvas, p, it) }
    }

    private fun labelOf(k: Key): String {
        if (k.style != Style.CHAR || k.label.length != 1 || !k.label[0].isLetter()) return k.label
        return if (upperCase()) k.label.uppercase() else k.label
    }

    private fun drawPopup(canvas: Canvas, p: Press, alts: List<String>) {
        val cell = maxOf(p.rect.width(), dp(40f))
        val w = cell * alts.size
        val left = (p.rect.centerX() - w / 2).coerceIn(gap, width - gap - w)
        val top = (p.rect.top - rowHeight - gap).coerceAtLeast(0f)
        p.popupRect.set(left, top, left + w, top + rowHeight)
        keyPaint.color = if (night) 0xFF4A4E54.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(p.popupRect, dp(8f), dp(8f), keyPaint)
        alts.forEachIndexed { i, s ->
            val cellRect = RectF(left + i * cell, top, left + (i + 1) * cell, top + rowHeight)
            if (i == p.popupIndex) {
                keyPaint.color = accentColor
                canvas.drawRoundRect(cellRect, dp(8f), dp(8f), keyPaint)
            }
            textPaint.color = if (i == p.popupIndex) 0xFFFFFFFF.toInt() else textColor
            textPaint.textSize = dp(19f)
            val text = if (upperCase()) s.uppercase() else s
            canvas.drawText(text, cellRect.centerX(), cellRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
    }

    // ---- touch -------------------------------------------------------------------------------

    private fun keyAt(x: Float, y: Float): Pair<Key, RectF>? {
        keyRects.firstOrNull { (k, r) -> k.style != Style.SPACER && r.contains(x, y) }?.let { return it }
        // Between keys: take the nearest one in the row, so gaps never swallow a tap.
        return keyRects.filter { (k, r) -> k.style != Style.SPACER && y >= r.top - gap && y <= r.bottom + gap }
            .minByOrNull { (_, r) -> abs(r.centerX() - x) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val hit = keyAt(e.getX(i), e.getY(i)) ?: return true
                down(e.getPointerId(i), hit.first, hit.second, e.getX(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                presses[e.getPointerId(i)]?.let { move(it, e.getX(i)) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                presses.remove(id)?.let { up(it) }
            }
            MotionEvent.ACTION_CANCEL -> {
                presses.values.forEach { cancelTimers(it) }
                presses.clear()
            }
        }
        invalidate()
        return true
    }

    private fun down(id: Int, key: Key, rect: RectF, x: Float) {
        val p = Press(key, rect, x)
        presses[id] = p
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val action = key.action
        if (action is Action.Code && action.repeat) {
            onKey(key, null)
            p.repeated = true
            p.repeat = object : Runnable {
                override fun run() {
                    onKey(key, null)
                    handler.postDelayed(this, 50)
                }
            }.also { handler.postDelayed(it, 400) }
        } else if (key.alts.isNotEmpty()) {
            p.longPress = Runnable {
                p.popup = key.alts
                p.popupIndex = 0
                if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }.also { handler.postDelayed(it, 350) }
        }
    }

    private fun move(p: Press, x: Float) {
        p.popup?.let { alts ->
            val cell = p.popupRect.width() / alts.size
            p.popupIndex = ((x - p.popupRect.left) / cell).toInt().coerceIn(0, alts.size - 1)
            return
        }
        if (p.key.label == " ") {
            val steps = ((x - p.downX) / dp(14f)).toInt()
            while (p.dragSteps < steps) { p.dragSteps++; onCursorStep(+1) }
            while (p.dragSteps > steps) { p.dragSteps--; onCursorStep(-1) }
            if (p.dragSteps != 0) p.longPress?.let { handler.removeCallbacks(it) }
        }
    }

    private fun up(p: Press) {
        cancelTimers(p)
        when {
            p.popup != null -> p.popup!!.getOrNull(p.popupIndex)?.let { onKey(p.key, it) }
            p.repeated -> Unit
            p.key.label == " " && p.dragSteps != 0 -> Unit
            else -> onKey(p.key, null)
        }
    }

    private fun cancelTimers(p: Press) {
        p.longPress?.let { handler.removeCallbacks(it) }
        p.repeat?.let { handler.removeCallbacks(it) }
    }
}
