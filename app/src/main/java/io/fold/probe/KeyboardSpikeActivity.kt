package io.fold.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import io.fold.probe.Ui.addSpaced
import io.fold.probe.Ui.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 0 keyboard spike: how should FoldCode's own on-screen code keyboard deliver keys to
 * VS Code running in a WebView? Tries four delivery methods against Monaco, xterm.js and a plain
 * <input>, under three ways of keeping the system keyboard away:
 *
 *  - KEYEVENT: WebView.dispatchKeyEvent (keys + KeyCharacterMap for text) → trusted DOM events
 *  - IC:       the WebView's own InputConnection (commitText / sendKeyEvent), as an IME would
 *  - BRIDGE:   evaluateJavascript → editor/terminal APIs directly (semantic, no key events)
 *  - SYNTH:    evaluateJavascript → synthetic (untrusted) DOM KeyboardEvents
 *
 * Automated run (results in logcat tag "KbdSpike" and /sdcard/Android/data/io.fold/files/kbd-spike.log):
 *   adb shell am start -n io.fold/.probe.KeyboardSpikeActivity --ez autotest true --es ime system
 * ime = system | blocked | altfocus
 */
class KeyboardSpikeActivity : Activity() {

    enum class Method { KEYEVENT, IC, BRIDGE, SYNTH }
    enum class ImeMode { SYSTEM, BLOCKED, ALTFOCUS }

    /** A key as both Android and the DOM know it. */
    data class K(
        val label: String,
        val name: String,
        val keyCode: Int,
        val domCode: String,
        val domKeyCode: Int,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    )

    private sealed interface Step
    private data class Text(val s: String) : Step
    private data class Key(val k: K) : Step
    private class Case(val id: String, val target: String, val steps: List<Step>, val expect: String, val check: (JSONObject) -> Boolean)

    private lateinit var web: SpikeWebView
    private lateinit var results: TextView
    private val methodButtons = mutableMapOf<Method, Button>()
    private val imeButtons = mutableMapOf<ImeMode, Button>()
    private val handler = Handler(Looper.getMainLooper())
    private var method = Method.KEYEVENT
    private var ime = ImeMode.SYSTEM
    private var running = false
    private var logFile: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logFile = getExternalFilesDir(null)?.let { File(it, "kbd-spike.log") }
        WebView.setWebContentsDebuggingEnabled(true)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = SpikeWebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d("KbdSpikeJS", "${m.messageLevel()} ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/spike/index.html")
        }

        results = Ui.mono(this, 10.5f, Ui.TEXT2).apply { text = "Pick a method, tap into Monaco / terminal / input, press keys below. Or run all tests." }

        val panel = Ui.column(this).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Ui.SURFACE)
            addSpaced(toggleRow(Method.entries, methodButtons) { selectMethod(it) }, 4)
            addSpaced(toggleRow(ImeMode.entries, imeButtons) { applyIme(it) }, 4)
            addSpaced(keyRow(), 4)
            addSpaced(Ui.row(context).apply {
                addSpaced(Ui.button(context, "Run all tests") { runSuite(Method.entries) })
                addSpaced(Ui.button(context, "Run ${'$'}method") { runSuite(listOf(method)) }.also { runCurrent = it })
            }, 4)
            addView(ScrollView(context).apply { addView(results) }, LinearLayout.LayoutParams(-1, dp(130)))
        }

        setContentView(Ui.column(this).apply {
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(panel, LinearLayout.LayoutParams(-1, -2))
        })

        selectMethod(Method.KEYEVENT)
        val imeExtra = intent.getStringExtra("ime")?.uppercase()
        applyIme(ImeMode.entries.firstOrNull { it.name == imeExtra } ?: ImeMode.SYSTEM)
        if (intent.getBooleanExtra("autotest", false)) {
            val only = intent.getStringExtra("methods")?.split(',')?.mapNotNull { n -> Method.entries.firstOrNull { it.name.equals(n.trim(), true) } }
            whenReady { runSuite(only?.ifEmpty { null } ?: Method.entries) }
        }
    }

    private var runCurrent: Button? = null

    // ---- UI ------------------------------------------------------------------------------------

    private fun <E : Enum<E>> toggleRow(values: List<E>, store: MutableMap<E, Button>, onPick: (E) -> Unit): LinearLayout =
        Ui.row(this).apply {
            for (v in values) addSpaced(Ui.button(context, v.name.lowercase()) { onPick(v) }.also { store[v] = it }, 6)
        }

    private fun highlight(buttons: Map<*, Button>, selected: Any) {
        for ((k, b) in buttons) {
            (b.background as GradientDrawable).setColor(if (k == selected) Ui.ACCENT else Ui.SURFACE2)
        }
    }

    private fun selectMethod(m: Method) {
        method = m
        highlight(methodButtons, m)
        runCurrent?.text = "Run ${m.name.lowercase()}"
    }

    private fun applyIme(mode: ImeMode) {
        ime = mode
        highlight(imeButtons, mode)
        web.blockIme = mode == ImeMode.BLOCKED
        if (mode == ImeMode.ALTFOCUS) window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val imm = getSystemService(InputMethodManager::class.java)
        if (mode != ImeMode.SYSTEM) imm.hideSoftInputFromWindow(web.windowToken, 0)
        imm.restartInput(web)
        log("ime mode → $mode")
    }

    private fun keyRow(): HorizontalScrollView {
        val row = Ui.row(this)
        for (k in MANUAL_KEYS) row.addSpaced(Ui.button(this, k.label) { sendKey(k) }, 4)
        row.addSpaced(Ui.button(this, "{") { sendText("{") }, 4)
        row.addSpaced(Ui.button(this, "ą") { sendText("ą") }, 4)
        row.addSpaced(Ui.button(this, "sample") { sendText("if (x) { y = 'zażółć'; }") }, 4)
        return HorizontalScrollView(this).apply { addView(row) }
    }

    // ---- delivery methods -------------------------------------------------------------------------

    private fun sendText(s: String) {
        when (method) {
            Method.KEYEVENT -> {
                val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                for (c in s) {
                    val evs = kcm.getEvents(charArrayOf(c))
                    if (evs != null) evs.forEach { web.dispatchKeyEvent(it) }
                    // Not on the virtual keymap (e.g. Polish letters): try the old ACTION_MULTIPLE form.
                    else @Suppress("DEPRECATION") web.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), c.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
                }
            }
            Method.IC -> withIc { it.commitText(s, 1) }
            Method.BRIDGE -> js("spike.type(${JSONObject.quote(s)})")
            Method.SYNTH -> for (c in s) {
                val code = if (c.isLetter() && c.code < 128) "Key${c.uppercaseChar()}" else if (c.isDigit()) "Digit$c" else ""
                js("spike.synth(${JSONObject.quote(c.toString())}, ${JSONObject.quote(code)}, ${c.uppercaseChar().code}, false, false, false)")
            }
        }
    }

    private fun sendKey(k: K) {
        when (method) {
            Method.KEYEVENT -> keyEvents(k).forEach { web.dispatchKeyEvent(it) }
            Method.IC -> withIc { ic -> keyEvents(k).forEach { ic.sendKeyEvent(it) } }
            Method.BRIDGE -> js("spike.key(${JSONObject.quote(k.name)}, ${k.ctrl}, ${k.alt}, ${k.shift})")
            Method.SYNTH -> js("spike.synth(${JSONObject.quote(k.name)}, ${JSONObject.quote(k.domCode)}, ${k.domKeyCode}, ${k.ctrl}, ${k.alt}, ${k.shift})")
        }
    }

    /** Modifier downs, the key, modifier ups — the same sequence a hardware keyboard produces. */
    private fun keyEvents(k: K): List<KeyEvent> {
        val t = SystemClock.uptimeMillis()
        val mods = buildList {
            if (k.ctrl) add(KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
            if (k.alt) add(KeyEvent.KEYCODE_ALT_LEFT to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
            if (k.shift) add(KeyEvent.KEYCODE_SHIFT_LEFT to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        }
        fun ev(action: Int, code: Int, meta: Int) = KeyEvent(
            t, t, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD,
        )
        val out = mutableListOf<KeyEvent>()
        var meta = 0
        for ((code, m) in mods) { meta = meta or m; out += ev(KeyEvent.ACTION_DOWN, code, meta) }
        out += ev(KeyEvent.ACTION_DOWN, k.keyCode, meta)
        out += ev(KeyEvent.ACTION_UP, k.keyCode, meta)
        for ((code, m) in mods.reversed()) { meta = meta and m.inv(); out += ev(KeyEvent.ACTION_UP, code, meta) }
        return out
    }

    private fun withIc(block: (InputConnection) -> Unit) {
        val ic = web.capturedIc ?: web.onCreateInputConnection(EditorInfo())?.also { web.capturedIc = it }
        if (ic == null) {
            log("IC: WebView gave no InputConnection (requests so far: ${web.icRequests})")
            return
        }
        val h = ic.handler
        if (h != null && h.looper != Looper.myLooper()) h.post { block(ic) } else block(ic)
    }

    private fun js(code: String, cb: ((String) -> Unit)? = null) {
        web.evaluateJavascript(code) { cb?.invoke(it ?: "null") }
    }

    private fun whenReady(tries: Int = 0, action: () -> Unit) {
        js("window.spike && spike.ready()") { r ->
            when {
                r == "true" -> action()
                tries > 100 -> log("page never became ready")
                else -> handler.postDelayed({ whenReady(tries + 1, action) }, 300)
            }
        }
    }

    // ---- test suite -------------------------------------------------------------------------------

    private fun cases(): List<Case> {
        fun v(j: JSONObject) = j.optString("value")
        val ascii = "function f(x) { return x*2; }"
        val polish = "zażółć gęślą jaźń"
        return listOf(
            Case("monaco/ascii", "monaco", listOf(Text(ascii)), ascii) { v(it) == ascii },
            Case("monaco/polish", "monaco", listOf(Text(polish)), polish) { v(it) == polish },
            Case("monaco/backspace", "monaco", listOf(Text("abc"), Key(BKSP)), "ab") { v(it) == "ab" },
            Case("monaco/arrow", "monaco", listOf(Text("ac"), Key(LEFT), Text("b")), "abc") { v(it) == "abc" },
            Case("monaco/home", "monaco", listOf(Text("bc"), Key(HOME), Text("a")), "abc") { v(it) == "abc" },
            Case("monaco/enter", "monaco", listOf(Text("a"), Key(ENTER), Text("b")), "a\\nb") { v(it) == "a\nb" },
            Case("monaco/tab", "monaco", listOf(Key(TAB), Text("x")), "4 spaces + x") { v(it) == "    x" },
            Case("monaco/ctrl+s", "monaco", listOf(Key(CTRL_S)), "save=1, empty") { it.getJSONObject("counters").optInt("save") == 1 && v(it).isEmpty() },
            Case("monaco/ctrl+shift+p", "monaco", listOf(Key(CTRL_SHIFT_P)), "palette=1") { it.getJSONObject("counters").optInt("palette") == 1 && v(it).isEmpty() },
            Case("monaco/ctrl+z", "monaco", listOf(Text("abc"), Key(CTRL_Z)), "undone (≠ abc)") { v(it) != "abc" },
            Case("xterm/ascii", "xterm", listOf(Text("ls -la | grep x")), "ls -la | grep x") { v(it) == "ls -la | grep x" },
            Case("xterm/polish", "xterm", listOf(Text("zażółć")), "zażółć") { v(it) == "zażółć" },
            Case("xterm/keys", "xterm", listOf(Key(LEFT), Key(BKSP), Key(ENTER), Key(TAB), Key(ESC)), "\\e[D \\x7f \\r \\t \\e") { v(it) == "\u001b[D\u007f\r\t\u001b" },
            Case("xterm/ctrl+c", "xterm", listOf(Key(CTRL_C)), "\\x03") { v(it) == "\u0003" },
            Case("input/ascii", "input", listOf(Text("hello {world}")), "hello {world}") { v(it) == "hello {world}" },
            Case("input/polish", "input", listOf(Text("zażółć")), "zażółć") { v(it) == "zażółć" },
            Case("input/edit", "input", listOf(Text("ac"), Key(LEFT), Text("b"), Key(END), Text("x"), Key(BKSP)), "abc") { v(it) == "abc" },
        )
    }

    private fun runSuite(methods: List<Method>) {
        if (running) return
        running = true
        results.text = ""
        val queue = ArrayDeque(methods.flatMap { m -> cases().map { m to it } })
        val passes = mutableMapOf<Method, Int>()
        val totals = mutableMapOf<Method, Int>()
        log("=== suite start: ime=$ime methods=${methods.joinToString()} on ${android.os.Build.MODEL}, WebView ${WebView.getCurrentWebViewPackage()?.versionName}")
        val previous = method

        fun next() {
            val item = queue.removeFirstOrNull()
            if (item == null) {
                val summary = methods.joinToString("  ") { "${it.name}=${passes[it] ?: 0}/${totals[it] ?: 0}" }
                log("=== SUMMARY ime=$ime  $summary")
                selectMethod(previous)
                running = false
                return
            }
            val (m, c) = item
            runOne(m, c) { pass ->
                totals[m] = (totals[m] ?: 0) + 1
                if (pass) passes[m] = (passes[m] ?: 0) + 1
                next()
            }
        }
        next()
    }

    private fun runOne(m: Method, c: Case, done: (Boolean) -> Unit) {
        selectMethod(m)
        web.capturedIc = null
        js("spike.reset('${c.target}')") {
            web.requestFocus()
            handler.postDelayed({
                runSteps(c.steps, 0) {
                    handler.postDelayed({ js("spike.read('${c.target}')") { raw -> done(evaluate(m, c, raw)) } }, 350)
                }
            }, 300)
        }
    }

    private fun runSteps(steps: List<Step>, i: Int, done: () -> Unit) {
        if (i == steps.size) return done()
        when (val s = steps[i]) {
            is Text -> sendText(s.s)
            is Key -> sendKey(s.k)
        }
        handler.postDelayed({ runSteps(steps, i + 1, done) }, 120)
    }

    private fun evaluate(m: Method, c: Case, raw: String): Boolean {
        val json = try {
            JSONObject(JSONArray("[$raw]").getString(0))
        } catch (e: Exception) {
            log("FAIL ${m.name.padEnd(8)} ${c.id}: could not read page state ($raw)")
            return false
        }
        val pass = try { c.check(json) } catch (_: Exception) { false }
        val imeVisible = web.rootWindowInsets?.isVisible(WindowInsets.Type.ime())
        val evs = json.optJSONArray("events") ?: JSONArray()
        var trusted = 0
        for (i in 0 until evs.length()) if (evs.getJSONObject(i).optBoolean("trusted")) trusted++
        val got = JSONObject.quote(json.optString("value"))
        log("${if (pass) "PASS" else "FAIL"} ${m.name.padEnd(8)} ${c.id.padEnd(20)} got=$got expect=${c.expect} " +
            "focus=${json.optString("focus")} ime=${if (imeVisible == true) "shown" else "hidden"} events=${evs.length()} trusted=$trusted")
        if (!pass) {
            val brief = (0 until evs.length()).joinToString(" ") { i ->
                val e = evs.getJSONObject(i)
                "${e.optString("type")}:${e.optString("key")}${if (e.optBoolean("trusted")) "" else "!"}"
            }
            log("     events: ${brief.take(600)}")
        }
        return pass
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        results.append(msg + "\n")
        try {
            logFile?.appendText(msg + "\n")
        } catch (_: Exception) {
        }
    }

    /** WebView that can refuse the system keyboard while keeping the connection for itself. */
    class SpikeWebView(ctx: Context) : WebView(ctx) {
        var blockIme = false
        var capturedIc: InputConnection? = null
        var icRequests = 0

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            icRequests++
            val ic = super.onCreateInputConnection(outAttrs)
            if (ic != null) capturedIc = ic
            Log.d(TAG, "onCreateInputConnection #$icRequests → ${ic?.javaClass?.simpleName} (block=$blockIme)")
            return if (blockIme) null else ic
        }
    }

    companion object {
        const val TAG = "KbdSpike"

        val LEFT = K("←", "ArrowLeft", KeyEvent.KEYCODE_DPAD_LEFT, "ArrowLeft", 37)
        val RIGHT = K("→", "ArrowRight", KeyEvent.KEYCODE_DPAD_RIGHT, "ArrowRight", 39)
        val UP = K("↑", "ArrowUp", KeyEvent.KEYCODE_DPAD_UP, "ArrowUp", 38)
        val DOWN = K("↓", "ArrowDown", KeyEvent.KEYCODE_DPAD_DOWN, "ArrowDown", 40)
        val HOME = K("Home", "Home", KeyEvent.KEYCODE_MOVE_HOME, "Home", 36)
        val END = K("End", "End", KeyEvent.KEYCODE_MOVE_END, "End", 35)
        val BKSP = K("⌫", "Backspace", KeyEvent.KEYCODE_DEL, "Backspace", 8)
        val ENTER = K("⏎", "Enter", KeyEvent.KEYCODE_ENTER, "Enter", 13)
        val TAB = K("Tab", "Tab", KeyEvent.KEYCODE_TAB, "Tab", 9)
        val ESC = K("Esc", "Escape", KeyEvent.KEYCODE_ESCAPE, "Escape", 27)
        val CTRL_S = K("Ctrl+S", "s", KeyEvent.KEYCODE_S, "KeyS", 83, ctrl = true)
        val CTRL_SHIFT_P = K("Ctrl+⇧+P", "P", KeyEvent.KEYCODE_P, "KeyP", 80, ctrl = true, shift = true)
        val CTRL_Z = K("Ctrl+Z", "z", KeyEvent.KEYCODE_Z, "KeyZ", 90, ctrl = true)
        val CTRL_C = K("Ctrl+C", "c", KeyEvent.KEYCODE_C, "KeyC", 67, ctrl = true)

        val MANUAL_KEYS = listOf(ESC, TAB, LEFT, RIGHT, UP, DOWN, HOME, END, BKSP, ENTER, CTRL_S, CTRL_SHIFT_P, CTRL_Z, CTRL_C)
    }
}
