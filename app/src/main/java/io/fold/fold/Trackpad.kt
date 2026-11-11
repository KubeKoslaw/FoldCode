package io.fold.fold

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A mouse for the code area, driven by the trackpad strip in laptop mode. Sends real mouse
 * events (SOURCE_MOUSE) to the WebView, so VS Code gets hover, precise clicks and right-click
 * context menus, which are hard to hit with a finger.
 */
class VirtualMouse(private val target: WebView, private val overlay: PointerOverlay) {
    var x = 0f
        private set
    var y = 0f
        private set
    private var placed = false

    fun move(dx: Float, dy: Float) {
        if (!placed) {
            x = target.width / 2f
            y = target.height / 2f
            placed = true
        }
        x = (x + dx).coerceIn(0f, (target.width - 1).toFloat())
        y = (y + dy).coerceIn(0f, (target.height - 1).toFloat())
        target.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 0))
        overlay.show(x, y)
    }

    fun click(secondary: Boolean = false) {
        if (!placed) move(0f, 0f)
        val button = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        val down = SystemClock.uptimeMillis()
        target.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, button, down))
        target.dispatchGenericMotionEvent(event(MotionEvent.ACTION_BUTTON_PRESS, button, down, actionButton = button))
        target.dispatchGenericMotionEvent(event(MotionEvent.ACTION_BUTTON_RELEASE, 0, down, actionButton = button))
        target.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 0, down))
        overlay.show(x, y)
    }

    fun scroll(dx: Float, dy: Float) {
        if (!placed) move(0f, 0f)
        val e = event(MotionEvent.ACTION_SCROLL, 0, axes = mapOf(MotionEvent.AXIS_VSCROLL to dy, MotionEvent.AXIS_HSCROLL to dx))
        target.dispatchGenericMotionEvent(e)
        overlay.show(x, y)
    }

    private fun event(
        action: Int,
        buttons: Int,
        downTime: Long = SystemClock.uptimeMillis(),
        actionButton: Int = 0,
        axes: Map<Int, Float> = emptyMap(),
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = this@VirtualMouse.x
            this.y = this@VirtualMouse.y
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
            for ((axis, v) in axes) setAxisValue(axis, v)
        }
        val e = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        if (actionButton != 0) setActionButton(e, actionButton)
        return e
    }

    /** MotionEvent.setActionButton is hidden; ACTION_BUTTON_PRESS/RELEASE need it to name the button. */
    private fun setActionButton(e: MotionEvent, button: Int) {
        runCatching { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).invoke(e, button) }
    }
}

/** Draws the mouse pointer over the code; fades out after a few seconds without use. Never takes touches. */
class PointerOverlay(ctx: Context) : View(ctx) {
    private var px = -1f
    private var py = -1f
    private var lastUse = 0L
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 17f * d); lineTo(4.5f * d, 13f * d); lineTo(7.5f * d, 19.5f * d)
        lineTo(10f * d, 18.5f * d); lineTo(7f * d, 12f * d); lineTo(12.5f * d, 12f * d); close()
    }
    private val hide = Runnable { invalidate() }

    init {
        isClickable = false
        isFocusable = false
    }

    fun show(x: Float, y: Float) {
        px = x
        py = y
        lastUse = SystemClock.uptimeMillis()
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, HIDE_AFTER_MS + 50)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        if (px < 0 || SystemClock.uptimeMillis() - lastUse > HIDE_AFTER_MS) return
        canvas.save()
        canvas.translate(px, py)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }

    companion object {
        private const val HIDE_AFTER_MS = 5000L
    }
}

/**
 * The trackpad strip under the keyboard in laptop mode: one finger moves the pointer (with
 * acceleration), a tap clicks, a two-finger tap right-clicks, two fingers dragging scroll.
 */
class TrackpadView(ctx: Context) : View(ctx) {
    var mouse: VirtualMouse? = null
    var night = true
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val bg = Paint()
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 12f * d }
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = 0f
    private var maxPointers = 0
    private var scrollAccX = 0f
    private var scrollAccY = 0f

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onDraw(canvas: Canvas) {
        bg.color = if (night) 0xFF252528.toInt() else 0xFFD9DCE1.toInt()
        canvas.drawRoundRect(4 * d, 4 * d, width - 4 * d, height - 4 * d, 10 * d, 10 * d, bg)
        hint.color = if (night) 0xFF6B7078.toInt() else 0xFF8A8F98.toInt()
        canvas.drawText("trackpad · tap = click · 2 fingers: tap = right click, drag = scroll", width / 2f, height / 2f + 4 * d, hint)
    }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        for (i in 0 until e.pointerCount) { sx += e.getX(i); sy += e.getY(i) }
        return sx / e.pointerCount to sy / e.pointerCount
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = mouse ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = 0f; maxPointers = 1
                scrollAccX = 0f; scrollAccY = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                val (cx, cy) = centroid(e)
                lastX = cx; lastY = cy
            }
            MotionEvent.ACTION_MOVE -> {
                val (cx, cy) = centroid(e)
                val dx = cx - lastX
                val dy = cy - lastY
                lastX = cx; lastY = cy
                moved += hypot(dx, dy)
                if (e.pointerCount >= 2) {
                    scrollAccX += dx; scrollAccY += dy
                    val step = 24 * d
                    if (abs(scrollAccY) >= step || abs(scrollAccX) >= step) {
                        m.scroll(-scrollAccX / step, scrollAccY / step)
                        scrollAccX = 0f; scrollAccY = 0f
                    }
                } else {
                    // Acceleration: slow moves are precise, fast moves cover the screen.
                    val speed = hypot(dx, dy) / d
                    val gain = 1.2f + (speed / 6f).coerceAtMost(2.5f)
                    m.move(dx * gain, dy * gain)
                }
            }
            MotionEvent.ACTION_UP -> {
                val tap = e.eventTime - downTime < 250 && moved < 10 * d
                if (tap) m.click(secondary = maxPointers >= 2)
            }
        }
        return true
    }
}
