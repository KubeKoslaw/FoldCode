package io.fold.fold

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/**
 * Follows the phone's posture (folded / flat / laptop / book) from Jetpack WindowManager, with the
 * hinge angle sensor as fallback, and reports changes after a short debounce: MagicOS flips
 * BOOK↔FLAT several times within a second while folding (plan.md §8).
 */
class PostureTracker(
    private val activity: Activity,
    private val onChange: (PostureReading) -> Unit,
) : SensorEventListener {
    private val adapter = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity))
    private val sensors = activity.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var info: WindowLayoutInfo? = null
    private var hingeAngle: Float? = null
    private var current: PostureReading? = null

    val posture: PostureReading? get() = current

    private val consumer = Consumer<WindowLayoutInfo> {
        info = it
        schedule()
    }

    private val evaluate = Runnable {
        @Suppress("DEPRECATION")
        val rotation = activity.display?.rotation ?: activity.windowManager.defaultDisplay.rotation
        val cfg = activity.resources.configuration
        val reading = PostureClassifier.classify(info, hingeAngle, cfg.smallestScreenWidthDp, rotation)
        if (reading != current) {
            current = reading
            onChange(reading)
        }
    }

    fun start() {
        adapter.addWindowLayoutInfoListener(activity, activity.mainExecutor, consumer)
        sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        schedule(0)
    }

    fun stop() {
        adapter.removeWindowLayoutInfoListener(consumer)
        sensors.unregisterListener(this)
        handler.removeCallbacks(evaluate)
    }

    /** Call from onConfigurationChanged (screen switch, rotation). */
    fun recheck() = schedule()

    private fun schedule(delayMs: Long = DEBOUNCE_MS) {
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, delayMs)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val a = event.values[0]
        val previous = hingeAngle
        hingeAngle = a
        if (previous == null || kotlin.math.abs(previous - a) >= 5f) schedule()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val DEBOUNCE_MS = 400L
    }
}
