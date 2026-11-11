package io.fold

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.fold.backend.BackendHolder
import io.fold.backend.BackendService
import io.fold.backend.BackendState
import io.fold.probe.Ui
import io.fold.probe.Ui.addSpaced
import io.fold.probe.Ui.dp
import io.fold.runtime.BootstrapDownloader
import io.fold.runtime.Runtime
import io.fold.runtime.VsCodeDefaults
import java.io.File
import kotlin.concurrent.thread

/**
 * FoldCode's main screen: VS Code full screen. On first start it downloads, verifies and
 * installs the environment; then it starts the backend service and shows the workbench.
 * Folding/unfolding/rotating never recreates it (configChanges in the manifest).
 */
class MainActivity : Activity() {

    private lateinit var rt: Runtime
    private lateinit var web: WebView
    private lateinit var bridge: HostBridge
    private lateinit var keyboard: io.fold.keys.KeyboardView
    private lateinit var input: io.fold.keys.InputController
    private lateinit var statusPanel: LinearLayout
    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actions: LinearLayout
    private var loadedPort = -1
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val listener: (BackendState) -> Unit = { onBackend(it) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = Runtime(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            // Without view focus Chromium never fires focus events in the page, so the keys wouldn't show.
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.host == "127.0.0.1" && u.port == loadedPort) return false
                    // Links to the outside world open in the browser, never inside the editor.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    return runCatching { startActivityForResult(pick, REQ_FILES); true }.getOrElse {
                        fileCallback = null
                        false
                    }
                }
            }
            visibility = View.INVISIBLE
        }
        keyboard = io.fold.keys.KeyboardView(this).apply { visibility = View.GONE }
        input = io.fold.keys.InputController(this, web, keyboard)
        bridge = HostBridge(this, web) { focused -> input.onEditableFocus(focused) }

        title = Ui.title(this, "FoldCode")
        message = Ui.mono(this, 12f, Ui.TEXT2)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        actions = Ui.row(this)
        statusPanel = Ui.column(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Ui.BG)
            addSpaced(title, 12)
            addSpaced(message, 16)
            addView(progress, LinearLayout.LayoutParams(-1, -2))
            addSpaced(actions, 0)
        }
        val content = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(ScrollView(context).apply { isFillViewport = true; addView(statusPanel) }, FrameLayout.LayoutParams(-1, -1))
        }
        // VS Code above, FoldCode's keys below (and above the system keyboard, thanks to adjustResize).
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(keyboard, LinearLayout.LayoutParams(-1, -2))
        })
        input.attach()
        applyTheme()

        if (needsSetup()) runSetup() else startBackend()
    }

    // ---- first start ------------------------------------------------------------------------

    private fun needsSetup() = !rt.isInstalled || !File(rt.prefix, "bin/code-server").exists()

    private fun runSetup() {
        showStatus("Setting up FoldCode", "Downloading the environment (bash, Node.js, VS Code)…", progressValue = 0)
        thread(name = "setup") {
            try {
                check(BuildConfig.BOOTSTRAP_URL.isNotEmpty()) { "This build has no bootstrap URL (app/bootstrap.properties)." }
                val zip = File(cacheDir, "bootstrap-aarch64.zip")
                BootstrapDownloader(BuildConfig.BOOTSTRAP_URL, BuildConfig.BOOTSTRAP_SHA256, BuildConfig.BOOTSTRAP_SIZE)
                    .download(zip) { done, total ->
                        runOnUiThread {
                            progress.progress = (done * 1000 / total.coerceAtLeast(1)).toInt()
                            message.text = "Downloading the environment… ${done / 1_048_576} / ${total / 1_048_576} MB"
                        }
                    }
                runOnUiThread { showStatus("Setting up FoldCode", "Installing…", progressValue = null) }
                rt.installBootstrap(zip, Runtime.releaseAptSource(BuildConfig.REPO_URL)) { Log.i(TAG, it) }
                zip.delete()
                VsCodeDefaults.ensure(rt)
                runOnUiThread { startBackend() }
            } catch (t: Throwable) {
                Log.e(TAG, "setup failed", t)
                runOnUiThread { showError("Setup failed", t.message ?: t.toString()) { runSetup() } }
            }
        }
    }

    // ---- backend ----------------------------------------------------------------------------

    private fun startBackend() {
        VsCodeDefaults.ensure(rt)
        BackendService.start(this)
        BackendHolder.removeListener(listener)
        BackendHolder.addListener(listener)
    }

    private fun onBackend(s: BackendState) {
        when (s) {
            BackendState.Stopped -> showStatus("VS Code is stopped", "", progressValue = null, retry = true)
            BackendState.Starting -> if (loadedPort < 0) showStatus("Starting VS Code…", "", progressValue = null)
            is BackendState.Failed -> showError("VS Code couldn't start", s.message) { BackendService.start(this) }
            is BackendState.Ready -> if (s.port != loadedPort) {
                loadedPort = s.port
                bridge.install(s.url)
                s.cookie?.let {
                    CookieManager.getInstance().setCookie(s.url, it)
                    CookieManager.getInstance().flush()
                }
                web.loadUrl("${s.url}/?folder=${File(rt.home, "projects").path}")
                web.visibility = View.VISIBLE
                web.requestFocus()
                (statusPanel.parent as View).visibility = View.GONE
            }
        }
    }

    // ---- UI helpers ---------------------------------------------------------------------------

    private fun showStatus(head: String, text: String, progressValue: Int?, retry: Boolean = false) {
        (statusPanel.parent as View).visibility = View.VISIBLE
        title.text = head
        message.text = text
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = progressValue == null
        progressValue?.let { progress.progress = it }
        actions.removeAllViews()
        if (retry) actions.addSpaced(Ui.button(this, "Start") { BackendService.start(this) })
    }

    private fun showError(head: String, text: String, retry: () -> Unit) {
        (statusPanel.parent as View).visibility = View.VISIBLE
        title.text = head
        message.text = text
        progress.visibility = View.GONE
        actions.removeAllViews()
        actions.addSpaced(Ui.button(this, "Retry") { retry() })
        actions.addSpaced(Ui.button(this, "Show log") {
            val log = BackendHolder.logFile(this)
            message.text = text + "\n\n" + (if (log.exists()) log.readLines().takeLast(40).joinToString("\n") else "(no log yet)")
        })
    }

    /** VS Code follows `prefers-color-scheme`; tell the WebView whether Android is in dark mode. */
    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(web.settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(web.settings, if (night) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }
        input.setNight(night)
    }

    override fun onResume() {
        super.onResume()
        if (web.visibility == View.VISIBLE) web.requestFocus()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    @Deprecated("Activity result API is not used to keep dependencies minimal")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILES) {
            val uris = when {
                resultCode != RESULT_OK || data == null -> null
                data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Back keeps VS Code running and just leaves the app")
    override fun onBackPressed() {
        if (input.handleBack()) return
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        BackendHolder.removeListener(listener)
        bridge.uninstall()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FoldCode"
        private const val REQ_FILES = 41
    }
}
