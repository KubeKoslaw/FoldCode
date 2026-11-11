package io.fold.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import io.fold.HostBridge
import io.fold.keys.InputController
import io.fold.keys.KeyboardView
import org.json.JSONArray
import java.io.File

/**
 * Phase 4 probe: the real FoldCode keyboard (KeyboardView + InputController + HostBridge focus
 * tracking) over the phase 0 Monaco/xterm.js page. Needs no backend, so it also runs on the
 * x86_64 emulator. After every key it logs the editor/terminal/input contents to
 * /sdcard/Android/data/io.fold/files/kbd-demo.log.
 */
class KeyboardDemoActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var input: InputController
    private lateinit var fold: io.fold.fold.FoldScreen
    private val handler = Handler(Looper.getMainLooper())
    private var logFile: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logFile = getExternalFilesDir(null)?.let { File(it, "kbd-demo.log") }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("JSON.stringify({host: typeof window.foldHost, shim: !!window.__foldClipboard})") {
                        log("page finished: $it")
                    }
                    var n = 0
                    val probe = object : Runnable {
                        override fun run() {
                            view.evaluateJavascript("(()=>{const a=document.activeElement;return a.tagName+'.'+(a.className||'')+' hasFocus='+document.hasFocus()})()") { log("active: $it") }
                            if (++n < 8) handler.postDelayed(this, 1500)
                        }
                    }
                    handler.postDelayed(probe, 1500)
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    log("console ${m.messageLevel()}: ${m.message().take(200)}")
                    return true
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        val kb = KeyboardView(this)
        input = InputController(this, web, kb)
        val bridge = HostBridge(this, web) { focused ->
            input.onEditableFocus(focused)
            log("focus editable=$focused")
        }
        log("bridge supported=${bridge.supported}")
        bridge.install(ORIGIN)
        fold = io.fold.fold.FoldScreen(this, web, kb, input) { emptySet() }
        fold.onPostureChanged = { r ->
            log("posture ${r.posture} via ${r.source} hinge=${r.hinge?.toShortString()}")
            handler.postDelayed(viewport, 2500)
        }
        setContentView(fold.layout)
        intent.getStringExtra("posture")?.let { name ->
            io.fold.fold.Posture.entries.firstOrNull { it.name == name }?.let {
                fold.lockedPosture = io.fold.fold.PostureReading(it, "forced", null)
                log("posture forced to $it")
            }
        }
        input.attach()
        web.isFocusableInTouchMode = true
        web.requestFocus()
        // Wrap the controller's key handler to log the result of each key.
        val handlerAfterAttach = kb.onKey
        kb.onKey = { key, alt ->
            handlerAfterAttach(key, alt)
            log("key '${alt ?: key.label}' mode=${input.mode}")
            handler.removeCallbacks(dump)
            handler.postDelayed(dump, 400)
        }
        input.setNight((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        web.loadUrl("$ORIGIN/assets/spike/index.html")
    }

    override fun onStart() {
        super.onStart()
        fold.start()
    }

    override fun onStop() {
        fold.stop()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fold.onConfigurationChanged()
    }

    override fun onDestroy() {
        fold.destroy()
        super.onDestroy()
    }

    private val viewport = Runnable {
        web.evaluateJavascript("JSON.stringify({iw: innerWidth, ih: innerHeight, cw: document.documentElement.clientWidth, dpr: devicePixelRatio, vv: visualViewport && visualViewport.scale})") {
            log("viewport view=${web.width}x${web.height} page=$it")
        }
    }

    private val dump = Runnable {
        web.evaluateJavascript("JSON.stringify(['monaco','xterm','input'].map(t => JSON.parse(spike.read(t)).value))") { raw ->
            val values = try {
                JSONArray(JSONArray("[$raw]").getString(0))
            } catch (_: Exception) {
                null
            }
            log("state monaco=${values?.optString(0)?.let { JSONArray().put(it).toString() }} " +
                "xterm=${values?.optString(1)?.let { JSONArray().put(it).toString() }} " +
                "input=${values?.optString(2)?.let { JSONArray().put(it).toString() }}")
        }
    }

    private fun log(msg: String) {
        android.util.Log.i("KbdDemo", msg)
        try {
            logFile?.appendText(msg + "\n")
        } catch (_: Exception) {
        }
    }

    @Deprecated("Back folds the keyboard first")
    override fun onBackPressed() {
        if (!input.handleBack()) finish()
    }

    companion object {
        private const val ORIGIN = "https://appassets.androidplatform.net"
    }
}
