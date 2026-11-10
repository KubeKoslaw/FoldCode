package io.fold.probe

import android.graphics.Rect
import android.view.Surface
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo

/**
 * The layouts FoldCode switches between (plan.md §3).
 *
 * [source] says how we got there: the Jetpack WindowManager fold feature (preferred), the hinge
 * angle sensor (fallback when MagicOS doesn't report a fold feature), or window size alone.
 */
enum class Posture { FOLDED, FLAT, LAPTOP, BOOK }

data class PostureReading(
    val posture: Posture,
    val source: String,
    /** Hinge area in window coordinates, if known. */
    val hinge: Rect?,
)

object PostureClassifier {
    /** Below this the window is treated as the outer (folded) screen. */
    private const val UNFOLDED_MIN_SMALLEST_DP = 600

    fun classify(
        info: WindowLayoutInfo?,
        hingeAngle: Float?,
        smallestWidthDp: Int,
        rotation: Int,
    ): PostureReading {
        val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
        if (fold != null) {
            val p = when {
                fold.state == FoldingFeature.State.HALF_OPENED &&
                    fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> Posture.LAPTOP
                fold.state == FoldingFeature.State.HALF_OPENED -> Posture.BOOK
                else -> Posture.FLAT
            }
            return PostureReading(p, "WindowManager", fold.bounds)
        }

        // Closed hinge wins even before the window has moved to the outer screen.
        if (hingeAngle != null && hingeAngle < 30f) {
            return PostureReading(Posture.FOLDED, "hinge sensor", null)
        }
        if (smallestWidthDp < UNFOLDED_MIN_SMALLEST_DP) {
            return PostureReading(Posture.FOLDED, "window size", null)
        }

        // Unfolded but no fold feature reported: fall back to the hinge sensor.
        // Assumption to verify in phase 0: in the natural orientation (rotation 0) the hinge is
        // vertical (book), rotated 90/270 it is horizontal (laptop).
        if (hingeAngle != null && hingeAngle in 30f..150f) {
            val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            return PostureReading(if (sideways) Posture.LAPTOP else Posture.BOOK, "hinge sensor", null)
        }
        return PostureReading(Posture.FLAT, if (hingeAngle != null) "hinge sensor" else "window size", null)
    }
}
