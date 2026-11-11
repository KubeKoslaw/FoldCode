package io.fold.fold

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * Places FoldCode's parts for the current posture (plan.md §3):
 *  - FOLDED (outer screen): code or preview full screen (switch button), keys at the bottom;
 *  - FLAT (inner screen): code | preview side by side with a draggable divider, keys at the bottom;
 *  - BOOK (half open, vertical hinge): code + keys on the left half, preview on the right, split at the hinge;
 *  - LAPTOP (half open, horizontal hinge): code (| preview) on the top half; keys + trackpad on the bottom half.
 * Children are set once; this view only moves and resizes them.
 */
class FoldLayout(ctx: Context) : ViewGroup(ctx) {

    lateinit var code: View
    lateinit var preview: View
    lateinit var keyboard: View
    lateinit var trackpad: View
    /** Draws the virtual mouse pointer over the code area; never takes touches. */
    lateinit var pointer: View
    /** Small floating button that switches code ↔ preview on the folded screen. */
    lateinit var switchButton: View

    var reading: PostureReading? = null
        set(value) {
            field = value
            requestLayout()
        }

    var previewOpen = false
        set(value) {
            field = value
            requestLayout()
        }

    /** Folded screen only: show the preview instead of the code. */
    var foldedShowsPreview = false
        set(value) {
            field = value
            requestLayout()
        }

    /** Share of the width given to the code when code and preview sit side by side (FLAT). */
    var split = 0.55f
        set(value) {
            field = value.coerceIn(0.25f, 0.8f)
            requestLayout()
        }

    var onSplitChanged: (Float) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()
    private val dividerWidth = dp(10f)
    private val dividerRect = Rect()
    private val dividerPaint = Paint().apply { color = 0xFF2D2F33.toInt() }
    private val gripPaint = Paint().apply { color = 0xFF6B7078.toInt() }
    private val loc = IntArray(2)
    private var dragging = false

    val posture: Posture get() = reading?.posture ?: Posture.FOLDED

    init {
        setWillNotDraw(false)
    }

    /** Hinge rectangle in this view's coordinates, if the posture has one. */
    private fun hingeLocal(): Rect? {
        val h = reading?.hinge ?: return null
        getLocationInWindow(loc)
        return Rect(h).apply { offset(-loc[0], -loc[1]) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        // Keyboard height depends only on its width; measure it at the width it will get.
        keyboard.measure(MeasureSpec.makeMeasureSpec(keyboardWidth(w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        switchButton.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
    }

    private fun keyboardWidth(w: Int): Int {
        val hinge = hingeLocal()
        return if (posture == Posture.BOOK && hinge != null && previewOpen) hinge.left.coerceAtLeast(dp(200f)) else w
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val hinge = hingeLocal()
        val kbShown = keyboard.visibility != GONE
        val kbH = if (kbShown) keyboard.measuredHeight else 0
        dividerRect.setEmpty()
        trackpad.visibility = if (posture == Posture.LAPTOP && hinge != null) VISIBLE else GONE
        switchButton.visibility = if (posture == Posture.FOLDED && previewOpen) VISIBLE else GONE

        when {
            posture == Posture.LAPTOP && hinge != null -> {
                val top = Rect(0, 0, w, hinge.top)
                val bottom = Rect(0, hinge.bottom, w, h)
                placeSideBySide(top, previewOpen)
                val kbHeight = kbH.coerceAtMost(bottom.height() - dp(48f))
                place(keyboard, Rect(0, bottom.top, w, bottom.top + kbHeight), kbShown)
                place(trackpad, Rect(0, bottom.top + kbHeight, w, bottom.bottom), true)
            }
            posture == Posture.BOOK && hinge != null && previewOpen -> {
                place(code, Rect(0, 0, hinge.left, h - kbH), true)
                place(keyboard, Rect(0, h - kbH, hinge.left, h), kbShown)
                place(preview, Rect(hinge.right, 0, w, h), true)
            }
            posture == Posture.FOLDED -> {
                val showPreview = previewOpen && foldedShowsPreview
                val area = Rect(0, 0, w, if (showPreview) h else h - kbH)
                place(code, area, !showPreview)
                place(preview, area, showPreview)
                place(keyboard, Rect(0, h - kbH, w, h), kbShown && !showPreview)
            }
            else -> { // FLAT, or a half-open posture without hinge bounds
                placeSideBySide(Rect(0, 0, w, h - kbH), previewOpen)
                place(keyboard, Rect(0, h - kbH, w, h), kbShown)
            }
        }
        if (posture != Posture.LAPTOP || hinge == null) place(trackpad, Rect(), false)
        // The pointer overlay covers exactly the code area.
        place(pointer, Rect(code.left, code.top, code.right, code.bottom), code.visibility == VISIBLE)
        val sb = switchButton
        sb.layout(w - sb.measuredWidth - dp(12f), h - kbH - sb.measuredHeight - dp(12f), w - dp(12f), h - kbH - dp(12f))
        invalidate()
    }

    /** Code on the left, divider, preview on the right (or code alone). */
    private fun placeSideBySide(area: Rect, withPreview: Boolean) {
        if (!withPreview) {
            place(code, area, true)
            place(preview, Rect(), false)
            return
        }
        val x = area.left + (area.width() * split).toInt()
        place(code, Rect(area.left, area.top, x - dividerWidth / 2, area.bottom), true)
        place(preview, Rect(x + dividerWidth / 2, area.top, area.right, area.bottom), true)
        dividerRect.set(x - dividerWidth / 2, area.top, x + dividerWidth / 2, area.bottom)
    }

    private fun place(v: View, rect: Rect, visible: Boolean) {
        if (!visible || rect.isEmpty) {
            if (v.visibility == VISIBLE && v !== keyboard) v.visibility = INVISIBLE
            v.layout(0, 0, 0, 0)
            return
        }
        if (v.visibility == INVISIBLE) v.visibility = VISIBLE
        v.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
        v.layout(rect.left, rect.top, rect.right, rect.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        if (dividerRect.isEmpty) return
        canvas.drawRect(dividerRect, dividerPaint)
        val cx = dividerRect.exactCenterX()
        val cy = dividerRect.exactCenterY()
        canvas.drawRoundRect(cx - dp(2f), cy - dp(24f), cx + dp(2f), cy + dp(24f), dp(2f).toFloat(), dp(2f).toFloat(), gripPaint)
    }

    // ---- divider drag ------------------------------------------------------------------------

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !dividerRect.isEmpty) {
            val grab = Rect(dividerRect).apply { inset(-dp(12f), 0) }
            if (grab.contains(e.x.toInt(), e.y.toInt())) {
                dragging = true
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> split = e.x / width
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onSplitChanged(split)
            }
        }
        return true
    }
}
