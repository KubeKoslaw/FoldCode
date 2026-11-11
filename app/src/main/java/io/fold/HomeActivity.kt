package io.fold

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import io.fold.probe.DeviceReport
import io.fold.probe.FoldProbeActivity
import io.fold.probe.KeyboardSpikeActivity
import io.fold.probe.Ui
import io.fold.probe.Ui.addSpaced
import io.fold.probe.Ui.dp
import kotlin.concurrent.thread

/** Phase 0 launcher: entry points to the fold probe, the keyboard spike and the device report. */
class HomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this).apply { setPadding(dp(20), dp(32), dp(20), dp(20)) }
        col.addSpaced(Ui.title(this, "FoldCode · phase 0"), 16)

        val buttons = Ui.row(this)
        buttons.addSpaced(Ui.button(this, "Fold probe") { startActivity(Intent(this, FoldProbeActivity::class.java)) })
        buttons.addSpaced(Ui.button(this, "Keyboard spike") { startActivity(Intent(this, KeyboardSpikeActivity::class.java)) })
        buttons.addSpaced(Ui.button(this, "Bootstrap") { startActivity(Intent(this, io.fold.probe.BootstrapProbeActivity::class.java)) })
        buttons.addSpaced(Ui.button(this, "VS Code") { startActivity(Intent(this, io.fold.probe.VsCodeProbeActivity::class.java)) })
        col.addSpaced(buttons, 16)

        val report = Ui.mono(this, 11f).apply { text = "Collecting device report…" }
        col.addSpaced(Ui.button(this, "Share report") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report.text), "Device report"))
        }, 12)
        col.addSpaced(report)

        setContentView(ScrollView(this).apply { addView(col) })

        thread(name = "device-report") {
            val text = DeviceReport.build(this)
            text.lines().forEach { Log.i(TAG, it) }
            // For scripts/phone-phase0.sh (adb pull), since logcat isn't reliable for this.
            try {
                getExternalFilesDir(null)?.let { java.io.File(it, "device-report.txt").writeText(text) }
            } catch (_: Exception) {
            }
            runOnUiThread { report.text = text }
        }
    }

    companion object {
        const val TAG = "FoldReport"
    }
}
