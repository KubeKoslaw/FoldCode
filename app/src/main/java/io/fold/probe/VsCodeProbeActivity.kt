package io.fold.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import io.fold.runtime.Backend
import io.fold.runtime.Runtime
import java.io.File
import kotlin.concurrent.thread

/**
 * Phase 2 smoke test: starts the VS Code backend inside the app and shows the workbench in a
 * WebView. Logs to /sdcard/Android/data/io.fold/files/vscode-probe.log (backend output:
 * code-server.log). With --ez autotest true it records whether the workbench rendered.
 */
class VsCodeProbeActivity : Activity() {

    private lateinit var rt: Runtime
    private lateinit var backend: Backend
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var logFile: File? = null
    private val t0 = SystemClock.elapsedRealtime()
    private var checked = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = Runtime(this)
        backend = Backend(rt)
        logFile = getExternalFilesDir(null)?.let { File(it, "vscode-probe.log") }
        WebView.setWebContentsDebuggingEnabled(true)

        status = Ui.mono(this, 12f, Ui.TEXT2).apply { text = "Starting VS Code backend…"; setPadding(32, 120, 32, 32) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) log("console error: ${m.message().take(300)}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    log("page finished ${url.substringBefore('?')} at ${secs()}s")
                    if (intent.getBooleanExtra("autotest", false) && !checked && !url.contains("/login")) {
                        checked = true
                        postDelayed({ checkWorkbench() }, 15_000)
                    }
                }
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2))
        })

        thread(name = "backend") { boot() }
    }

    private fun boot() {
        try {
            val folder = File(rt.home, "projects/hello").apply { mkdirs() }
            File(folder, "hello.js").takeIf { !it.exists() }?.writeText("// FoldCode\nconsole.log('hello from the fold')\n")
            val backendLog = getExternalFilesDir(null)?.let { File(it, "code-server.log") } ?: File(rt.home, "code-server.log")
            backend.start(folder, backendLog)
            log("backend started on port ${backend.port}")
            if (!backend.waitUntilHealthy()) {
                log("ERROR backend not healthy (alive=${backend.isRunning}); see code-server.log")
                ui("Backend failed to start, see code-server.log")
                return
            }
            log("backend healthy at ${secs()}s")
            val cookie = backend.login()
            log(if (cookie != null) "login ok" else "ERROR login failed")
            runOnUiThread {
                cookie?.let {
                    CookieManager.getInstance().setCookie(backend.baseUrl, it)
                    CookieManager.getInstance().flush()
                }
                status.visibility = android.view.View.GONE
                web.loadUrl("${backend.baseUrl}/?folder=${folder.path}")
            }
        } catch (t: Throwable) {
            log("ERROR $t")
            ui("Error: $t")
        }
    }

    private fun checkWorkbench() {
        val js = """(function(){
            const q=s=>document.querySelector(s);
            return JSON.stringify({
              workbench: !!q('.monaco-workbench'),
              editor: !!q('.monaco-editor'),
              explorerFiles: Array.from(document.querySelectorAll('.explorer-item .label-name')).map(e=>e.textContent).slice(0,8),
              statusItems: document.querySelectorAll('.statusbar-item').length,
              notifications: Array.from(document.querySelectorAll('.notification-list-item-message')).map(e=>e.textContent).slice(0,5),
              title: document.title
            });
        })()"""
        web.evaluateJavascript(js) { r ->
            log("workbench check at ${secs()}s: $r")
            log("=== DONE")
        }
    }

    private fun secs() = "%.1f".format((SystemClock.elapsedRealtime() - t0) / 1000.0)

    private fun ui(msg: String) = runOnUiThread { status.text = msg; status.visibility = android.view.View.VISIBLE }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            logFile?.appendText(msg + "\n")
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        backend.stop()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val TAG = "VsCodeProbe"
    }
}
