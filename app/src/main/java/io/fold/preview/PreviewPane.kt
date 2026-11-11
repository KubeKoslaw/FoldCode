package io.fold.preview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The page preview next to the code: a WebView with a small toolbar (address, reload,
 * mobile/desktop width, open in the browser, close). Only local dev servers are loaded here;
 * links to other sites open in the browser.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class PreviewPane(ctx: Context, private val onClose: () -> Unit) : LinearLayout(ctx) {

    val web = WebView(ctx)
    private val address = TextView(ctx)
    private val modeButton: TextView
    private var desktop = false
    private val mobileUserAgent: String
    var url: String? = null
        private set

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF1E1E1E.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        mobileUserAgent = web.settings.userAgentString
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "127.0.0.1" || u.host == "localhost" || u.host == "::1" || u.host == "[::1]") return false
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }

            override fun onPageFinished(view: WebView, u: String) {
                address.text = u.removePrefix("http://")
            }
        }

        address.apply {
            setTextColor(0xFFB8BCC4.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(6f), 0)
        }
        modeButton = toolButton("📱") { toggleDesktop() }
        val bar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            setBackgroundColor(0xFF252526.toInt())
            addView(address, LayoutParams(0, dp(36f), 1f))
            addView(toolButton("⟳") { web.reload() })
            addView(modeButton)
            addView(toolButton("↗") { url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } })
            addView(toolButton("✕") { onClose() })
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(36f)))
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun toolButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(0xFFE0E0E0.toInt())
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(dp(40f), dp(36f))
        setOnClickListener { onClick() }
    }

    fun open(newUrl: String) {
        url = newUrl
        address.text = newUrl.removePrefix("http://")
        web.loadUrl(newUrl)
    }

    /** Mobile width (the phone's own) or a desktop-like page (wide viewport, desktop user agent). */
    private fun toggleDesktop() {
        desktop = !desktop
        web.settings.userAgentString = if (desktop) DESKTOP_UA else mobileUserAgent
        web.settings.useWideViewPort = desktop
        web.settings.loadWithOverviewMode = desktop
        modeButton.text = if (desktop) "🖥" else "📱"
        web.reload()
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"
    }
}
