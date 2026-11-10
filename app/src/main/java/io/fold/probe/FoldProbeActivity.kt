package io.fold.probe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import io.fold.probe.Ui.addSpaced
import io.fold.probe.Ui.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 fold probe. Shows and logs everything the fold-aware layout will depend on:
 * Jetpack WindowManager fold features, the hinge angle sensor, window metrics and configuration.
 * Fold the phone through every posture (closed, flat, laptop, book) and share the log.
 *
 * Also logged to logcat with tag "FoldProbe" and to
 * /sdcard/Android/data/io.fold/files/fold-probe.log for `adb pull`.
 */
class FoldProbeActivity : Activity(), SensorEventListener {

    private lateinit var tracker: WindowInfoTrackerCallbackAdapter
    private lateinit var hingeOverlay: HingeOverlay
    private lateinit var status: android.widget.TextView
    private lateinit var logView: android.widget.TextView
    private val logLines = ArrayDeque<String>()
    private var logFile: File? = null

    private var layoutInfo: WindowLayoutInfo? = null
    private var hingeAngle: Float? = null
    private var lastLoggedAngle = Float.NaN
    private var lastAngleLogAt = 0L
    private var lastPosture: PostureReading? = null

    private val layoutListener = Consumer<WindowLayoutInfo> { info ->
        layoutInfo = info
        log("layout: " + describeFeatures(info))
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this))
        logFile = getExternalFilesDir(null)?.let { File(it, "fold-probe.log") }

        hingeOverlay = HingeOverlay(this)
        status = Ui.mono(this, 12f)
        logView = Ui.mono(this, 11f, Ui.TEXT2)

        val buttons = Ui.row(this)
        buttons.addSpaced(Ui.button(this, "Share log") { shareLog() })
        buttons.addSpaced(Ui.button(this, "Clear") { logLines.clear(); logView.text = "" })

        val panel = Ui.column(this).apply {
            setPadding(dp(16), dp(28), dp(16), dp(16))
            addSpaced(Ui.title(this@FoldProbeActivity, "Fold probe"))
            addSpaced(buttons)
            addSpaced(status, 12)
            addSpaced(logView)
        }
        val scroll = ScrollView(this).apply { addView(panel) }

        setContentView(FrameLayout(this).apply {
            addView(hingeOverlay, FrameLayout.LayoutParams(-1, -1))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        })
        log("probe started on ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
    }

    override fun onStart() {
        super.onStart()
        tracker.addWindowLayoutInfoListener(this, mainExecutor, layoutListener)
        val sm = getSystemService(SensorManager::class.java)
        val hinge = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (hinge == null) log("no TYPE_HINGE_ANGLE sensor")
        else sm.registerListener(this, hinge, SensorManager.SENSOR_DELAY_UI)
        refresh()
    }

    override fun onStop() {
        tracker.removeWindowLayoutInfoListener(layoutListener)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        log("config: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp sw${newConfig.smallestScreenWidthDp} " +
            "orient=${if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"} dpi=${newConfig.densityDpi}")
        refresh()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val angle = event.values[0]
        hingeAngle = angle
        val now = SystemClock.uptimeMillis()
        if (lastLoggedAngle.isNaN() || (kotlin.math.abs(angle - lastLoggedAngle) >= 5f && now - lastAngleLogAt > 250)) {
            lastLoggedAngle = angle
            lastAngleLogAt = now
            log("hinge ${"%.0f".format(angle)}°")
        }
        refresh()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun refresh() {
        val cfg = resources.configuration
        @Suppress("DEPRECATION")
        val rotation = display?.rotation ?: windowManager.defaultDisplay.rotation
        val reading = PostureClassifier.classify(layoutInfo, hingeAngle, cfg.smallestScreenWidthDp, rotation)
        if (reading.posture != lastPosture?.posture || reading.source != lastPosture?.source) {
            log("posture → ${reading.posture} (${reading.source})")
            lastPosture = reading
        }

        val calc = WindowMetricsCalculator.getOrCreate()
        val cur = calc.computeCurrentWindowMetrics(this).bounds
        val max = calc.computeMaximumWindowMetrics(this).bounds
        status.text = buildString {
            appendLine("posture   ${reading.posture}   via ${reading.source}")
            appendLine("hinge     ${hingeAngle?.let { "%.1f°".format(it) } ?: "n/a"}")
            appendLine("features  ${describeFeatures(layoutInfo)}")
            appendLine("window    ${cur.width()}x${cur.height()} at ${cur.left},${cur.top}   max ${max.width()}x${max.height()}")
            appendLine("config    ${cfg.screenWidthDp}x${cfg.screenHeightDp}dp  sw${cfg.smallestScreenWidthDp}dp  dpi ${cfg.densityDpi}")
            append("display   #${display?.displayId} '${display?.name}' rotation ${rotation * 90}°")
        }
        hingeOverlay.update(reading)
    }

    private fun describeFeatures(info: WindowLayoutInfo?): String {
        if (info == null) return "(no WindowLayoutInfo yet)"
        if (info.displayFeatures.isEmpty()) return "none"
        return info.displayFeatures.joinToString("; ") { f ->
            if (f is FoldingFeature) {
                "Fold state=${f.state} orient=${f.orientation} occl=${f.occlusionType} separating=${f.isSeparating} bounds=${f.bounds.toShortString()}"
            } else {
                "${f.javaClass.simpleName} bounds=${f.bounds.toShortString()}"
            }
        }
    }

    private fun log(msg: String) {
        val line = "${TIME.format(Date())} $msg"
        Log.i(TAG, msg)
        logLines.addFirst(line)
        while (logLines.size > 200) logLines.removeLast()
        if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    private fun shareLog() {
        val text = status.text.toString() + "\n\n" + logLines.reversed().joinToString("\n")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Fold probe log"))
    }

    /** Paints the hinge area and labels each half with the posture, to check the bounds visually. */
    private class HingeOverlay(ctx: Context) : View(ctx) {
        private var reading: PostureReading? = null
        private val hingePaint = Paint().apply { color = 0x80F14C4C.toInt() }
        private val halfA = Paint().apply { color = 0x203794FF }
        private val halfB = Paint().apply { color = 0x204EC9B0 }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            textSize = 64f * ctx.resources.displayMetrics.density / 2f
            textAlign = Paint.Align.CENTER
        }
        private val loc = IntArray(2)

        fun update(r: PostureReading) {
            reading = r
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = reading ?: return
            val hingeWin = r.hinge ?: run {
                canvas.drawText(r.posture.name, width / 2f, height / 2f, label)
                return
            }
            getLocationInWindow(loc)
            val hinge = Rect(hingeWin).apply { offset(-loc[0], -loc[1]) }
            val horizontal = hinge.width() > hinge.height() || (hinge.height() == 0 && hinge.width() > 0)
            if (horizontal) {
                canvas.drawRect(0f, 0f, width.toFloat(), hinge.top.toFloat(), halfA)
                canvas.drawRect(0f, hinge.bottom.toFloat(), width.toFloat(), height.toFloat(), halfB)
                canvas.drawRect(hinge.left.toFloat(), hinge.top - 2f, hinge.right.toFloat(), hinge.bottom + 2f, hingePaint)
                canvas.drawText("${r.posture} · top", width / 2f, hinge.top / 2f, label)
                canvas.drawText("bottom", width / 2f, (hinge.bottom + height) / 2f, label)
            } else {
                canvas.drawRect(0f, 0f, hinge.left.toFloat(), height.toFloat(), halfA)
                canvas.drawRect(hinge.right.toFloat(), 0f, width.toFloat(), height.toFloat(), halfB)
                canvas.drawRect(hinge.left - 2f, hinge.top.toFloat(), hinge.right + 2f, hinge.bottom.toFloat(), hingePaint)
                canvas.drawText("${r.posture} · left", hinge.left / 2f, height / 2f, label)
                canvas.drawText("right", (hinge.right + width) / 2f, height / 2f, label)
            }
        }
    }

    companion object {
        const val TAG = "FoldProbe"
        private val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
