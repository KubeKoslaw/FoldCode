package io.fold.probe

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.webkit.WebView
import java.io.File
import java.util.concurrent.TimeUnit

/** One-shot facts about the phone that the rest of the plan depends on. */
object DeviceReport {

    fun build(ctx: Context): String = buildString {
        appendLine("== Device")
        appendLine("model        ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})")
        appendLine("market name  ${prop("ro.product.marketname") ?: prop("ro.config.marketing_name") ?: "?"}")
        appendLine("android      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("build        ${Build.DISPLAY}")
        for (key in listOf("ro.build.version.magic", "msc.config.magic.version", "ro.magic.api.version", "ro.build.version.emui")) {
            prop(key)?.let { appendLine("$key = $it") }
        }
        appendLine("abis         ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("kernel       ${System.getProperty("os.version")}")

        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        appendLine("ram          ${mi.totalMem / (1024 * 1024)} MB total, ${mi.availMem / (1024 * 1024)} MB free, memoryClass ${am.largeMemoryClass} MB")

        val wv = WebView.getCurrentWebViewPackage()
        appendLine("webview      ${wv?.packageName} ${wv?.versionName}")

        appendLine()
        appendLine("== Displays")
        val dm = ctx.getSystemService(DisplayManager::class.java)
        for (d in dm.displays) {
            val mode = d.mode
            appendLine("#${d.displayId} '${d.name}' ${mode.physicalWidth}x${mode.physicalHeight} @${"%.0f".format(mode.refreshRate)}Hz state=${d.state} flags=${d.flags}")
        }

        appendLine()
        appendLine("== Fold hardware")
        val pm = ctx.packageManager
        for (f in listOf("android.hardware.sensor.hinge_angle", "android.hardware.type.automotive", PackageManager.FEATURE_PC)) {
            appendLine("feature $f = ${pm.hasSystemFeature(f)}")
        }
        val sm = ctx.getSystemService(SensorManager::class.java)
        val hinge = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        appendLine("hinge sensor ${hinge?.let { "${it.name} (${it.vendor}), range ${it.maximumRange}" } ?: "none"}")
        val posture = sm.getSensorList(Sensor.TYPE_ALL).filter {
            it.stringType.contains("posture", true) || it.stringType.contains("fold", true) || it.name.contains("fold", true) || it.name.contains("hinge", true)
        }
        posture.forEach { appendLine("sensor       ${it.name} / ${it.stringType} (${it.vendor})") }

        appendLine()
        appendLine("== Exec from app data dir (targetSdk ${ctx.applicationInfo.targetSdkVersion})")
        appendLine(execTest(ctx))
    }

    /**
     * The whole runtime plan depends on this: can we exec() a binary that lives in our own data
     * dir? Copies the system toybox there and runs it, directly and through the system linker.
     */
    fun execTest(ctx: Context): String = buildString {
        val src = File("/system/bin/toybox")
        val dst = File(ctx.filesDir, "probe-bin/toybox")
        try {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            dst.setExecutable(true, false)
            appendLine("direct exec  ${run(listOf(dst.absolutePath, "echo", "ok-direct"))}")
            appendLine("linker64     ${run(listOf("/system/bin/linker64", dst.absolutePath, "echo", "ok-linker"))}")
            appendLine("uname -a     ${run(listOf(dst.absolutePath, "uname", "-a"))}")
        } catch (t: Throwable) {
            appendLine("exec test failed: $t")
        }
    }

    private fun run(cmd: List<String>): String = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
        "exit=${p.exitValue()} '$out'"
    } catch (t: Throwable) {
        "FAILED: ${t.message}"
    }

    /** android.os.SystemProperties is hidden; read it reflectively (read-only props only). */
    fun prop(key: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as String).ifBlank { null }
    } catch (_: Throwable) {
        null
    }
}
