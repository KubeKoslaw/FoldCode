package io.fold.fold

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import io.fold.keys.InputController
import io.fold.keys.KeyboardView
import io.fold.preview.DevServerWatcher
import io.fold.preview.PreviewPane

/**
 * Everything around the code view that depends on the fold: the posture-driven layout, the
 * trackpad + pointer for laptop mode, and the page preview that opens when a dev server starts.
 * Used by MainActivity (VS Code) and KeyboardDemoActivity (emulator tests).
 */
class FoldScreen(
    private val activity: Activity,
    val code: WebView,
    keyboard: KeyboardView,
    private val input: InputController,
    ignorePorts: () -> Set<Int>,
) {
    val layout = FoldLayout(activity)
    val preview = PreviewPane(activity) { closePreview(userClosed = true) }
    private val overlay = PointerOverlay(activity)
    private val trackpad = TrackpadView(activity)
    private val switch = TextView(activity)
    private val prefs = activity.getSharedPreferences("fold", Context.MODE_PRIVATE)
    private val dismissed = mutableSetOf<Int>()
    private var previewPort = -1
    private val tracker = PostureTracker(activity) { onPosture(it) }
    private val watcher = DevServerWatcher(ignorePorts, ::onServerStarted, ::onServerStopped)

    /** Called with every posture change (for logs and tests). */
    var onPostureChanged: (PostureReading) -> Unit = {}

    init {
        val d = activity.resources.displayMetrics.density
        switch.apply {
            text = "⇄"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 22 * d; setColor(0xE00E639C.toInt()); setStroke((2 * d).toInt(), 0xCCFFFFFF.toInt()) }
            elevation = 6 * d
            minWidth = (44 * d).toInt()
            minHeight = (44 * d).toInt()
            setOnClickListener { this@FoldScreen.layout.foldedShowsPreview = !this@FoldScreen.layout.foldedShowsPreview }
        }
        // The code view resizes on every fold/rotation; never let Chromium zoom the page out to
        // fit a momentarily too-wide layout (it then stays zoomed out).
        code.settings.setSupportZoom(false)
        code.settings.builtInZoomControls = false
        code.settings.loadWithOverviewMode = false
        code.settings.useWideViewPort = false
        trackpad.mouse = VirtualMouse(code, overlay)
        preview.visibility = View.INVISIBLE
        layout.code = code
        layout.preview = preview
        layout.keyboard = keyboard
        layout.trackpad = trackpad
        layout.pointer = overlay
        layout.switchButton = switch
        listOf(code, preview, keyboard, trackpad, overlay, switch).forEach { layout.addView(it) }
        layout.split = prefs.getFloat("split", 0.55f)
        layout.onSplitChanged = { prefs.edit().putFloat("split", it).apply() }
    }

    fun start() {
        tracker.start()
        watcher.start()
    }

    fun stop() {
        tracker.stop()
        watcher.stop()
    }

    fun destroy() {
        watcher.quit()
        preview.web.destroy()
    }

    fun onConfigurationChanged() {
        tracker.recheck()
        layout.requestLayout()
    }

    fun setNight(night: Boolean) {
        trackpad.night = night
    }

    fun openPreview(url: String) {
        preview.open(url)
        layout.previewOpen = true
    }

    /** Tests only: pin a posture regardless of the sensors (the emulator can't reach FOLDED). */
    var lockedPosture: PostureReading? = null
        set(value) {
            field = value
            value?.let { onPosture(it) }
        }

    private fun onPosture(reading: PostureReading) {
        val r = lockedPosture ?: reading
        layout.reading = r
        // Laptop mode: the keys stay on the bottom half, like a laptop keyboard.
        input.pinned = r.posture == Posture.LAPTOP && r.hinge != null
        onPostureChanged(r)
    }

    private fun onServerStarted(port: Int) {
        if (port in dismissed) return
        if (layout.previewOpen && previewPort > 0 && previewPort != port) return // keep the one already shown
        previewPort = port
        openPreview("http://127.0.0.1:$port/")
    }

    private fun onServerStopped(port: Int) {
        dismissed.remove(port) // a restarted server may open the preview again
    }

    private fun closePreview(userClosed: Boolean) {
        if (userClosed && previewPort > 0) dismissed.add(previewPort)
        previewPort = -1
        layout.previewOpen = false
        layout.foldedShowsPreview = false
        preview.web.loadUrl("about:blank")
    }
}
