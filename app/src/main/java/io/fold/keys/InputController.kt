package io.fold.keys

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import java.util.EnumMap

/**
 * Decides what sits under VS Code:
 *  - SYSTEM (default): Gboard/Honor keyboard, with FoldCode's two-row key bar above it;
 *  - CODE: FoldCode's own full keyboard. The window then sets FLAG_ALT_FOCUSABLE_IM, so the
 *    system keyboard can't appear (the combination measured best in phase 0).
 * Keys only show while VS Code has a text field focused (reported by HostBridge).
 */
class InputController(
    private val activity: Activity,
    private val web: WebView,
    private val kb: KeyboardView,
) {
    enum class Mode { SYSTEM, CODE }

    private val prefs = activity.getSharedPreferences("input", Context.MODE_PRIVATE)
    // FoldCode's own keyboard by default: it replaces Gboard + the bar, so more code stays visible.
    // (New key name, so the earlier default stored as "mode" doesn't stick around.)
    var mode = runCatching { Mode.valueOf(prefs.getString(PREF_MODE, Mode.CODE.name)!!) }.getOrDefault(Mode.CODE)
        private set
    private var editable = false
    private var collapsed = false
    private var layer = Layouts.LETTERS
    private val latches = EnumMap<Mod, Latch>(Mod::class.java).apply { Mod.entries.forEach { put(it, Latch.OFF) } }
    private val injector = KeyInjector(web)
    private val imm = activity.getSystemService(InputMethodManager::class.java)

    fun attach() {
        kb.onKey = { key, alt -> handle(key, alt) }
        kb.onCursorStep = { step -> injector.key(if (step < 0) LEFT else RIGHT, metaNow()) }
        kb.latchOf = { latches[it] ?: Latch.OFF }
        kb.upperCase = { latches[Mod.SHIFT] != Latch.OFF }
        applyWindowMode()
        refresh()
    }

    /** Tells the page which keyboard is active (see HostBridge: inputmode="none" in CODE mode). */
    fun syncPage() {
        web.evaluateJavascript("window.__foldSetKeyboardMode && window.__foldSetKeyboardMode('${if (mode == Mode.CODE) "code" else "system"}')", null)
    }

    /** Called on every focus of a text field (and when focus leaves them). */
    fun onEditableFocus(focused: Boolean) {
        if (focused) syncPage()
        if (focused && mode == Mode.CODE) collapsed = false
        if (focused == editable && !focused) return
        editable = focused
        refresh()
    }

    /** Back: fold the full keyboard away first. Returns true if it consumed the press. */
    fun handleBack(): Boolean {
        if (mode == Mode.CODE && editable && !collapsed) {
            collapsed = true
            refresh()
            return true
        }
        return false
    }

    fun setNight(night: Boolean) {
        kb.night = night
    }

    /** Laptop mode: keep the keys up even when no text field has focus. */
    var pinned = false
        set(value) {
            field = value
            if (value) collapsed = false
            refresh()
        }

    private fun refresh() {
        kb.rows = when (mode) {
            Mode.SYSTEM -> listOf(
                Layouts.functionRow(withShift = true, last = Layouts.toCode),
                if (latches[Mod.CTRL] != Latch.OFF) Layouts.barCtrl else Layouts.barSymbols,
            )
            Mode.CODE -> if (collapsed) {
                listOf(Layouts.functionRow(withShift = true, last = Layouts.expand))
            } else {
                listOf(Layouts.functionRow(withShift = false, last = Layouts.toSystem)) +
                    if (layer == Layouts.SYMBOLS) Layouts.symbols else Layouts.letters
            }
        }
        kb.visibility = if (editable || pinned) View.VISIBLE else View.GONE
        kb.invalidate()
    }

    private fun setMode(m: Mode) {
        mode = m
        prefs.edit().putString(PREF_MODE, m.name).apply()
        collapsed = false
        layer = Layouts.LETTERS
        applyWindowMode()
        refresh()
    }

    private fun applyWindowMode() {
        syncPage()
        val window = activity.window
        if (mode == Mode.CODE) {
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            imm.hideSoftInputFromWindow(web.windowToken, 0)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        // Android only re-reads FLAG_ALT_FOCUSABLE_IM when the *window* regains focus. Make it
        // unfocusable for one frame, then focusable again, so the new mode takes effect now.
        if (!attached) {
            attached = true
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        web.post {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            web.requestFocus()
            imm.restartInput(web)
            if (mode == Mode.SYSTEM && editable) {
                // Give the window a moment to get focus back before asking for the keyboard.
                web.postDelayed({ imm.showSoftInput(web, 0) }, 150)
            }
        }
    }

    /** False until the first applyWindowMode() (in onCreate, before the window has focus). */
    private var attached = false

    private fun handle(key: Key, alt: String?) {
        when (val a = key.action) {
            is Action.Text -> type(alt ?: a.text)
            is Action.Code -> {
                injector.key(a.keyCode, metaNow())
                releaseOneShots()
            }
            is Action.Chord -> {
                var meta = 0
                if (a.ctrl) meta = meta or KeyInjector.CTRL_META
                if (a.shift) meta = meta or KeyInjector.SHIFT_META
                if (a.alt) meta = meta or KeyInjector.ALT_META
                injector.key(a.keyCode, meta)
                releaseOneShots(includingLocked = true)
            }
            is Action.Modifier -> {
                latches[a.mod] = when (latches[a.mod]) {
                    Latch.OFF -> Latch.ONCE
                    Latch.ONCE -> Latch.LOCK
                    else -> Latch.OFF
                }
                refresh()
            }
            is Action.Layer -> { layer = a.name; refresh() }
            Action.ToCodeKeyboard, Action.CodeKeyboardKeepCtrl -> setMode(Mode.CODE)
            Action.ToSystemKeyboard -> setMode(Mode.SYSTEM)
            Action.Collapse -> { collapsed = true; refresh() }
            Action.Expand -> { collapsed = false; refresh() }
            Action.None -> Unit
        }
    }

    private fun type(text: String) {
        val ctrl = latches[Mod.CTRL] != Latch.OFF
        val alt = latches[Mod.ALT] != Latch.OFF
        val shift = latches[Mod.SHIFT] != Latch.OFF
        when {
            (ctrl || alt) && text.length == 1 -> injector.chord(text[0], metaNow())
            shift -> injector.text(text.uppercase())
            else -> injector.text(text)
        }
        releaseOneShots()
    }

    private fun metaNow(): Int {
        var m = 0
        if (latches[Mod.CTRL] != Latch.OFF) m = m or KeyInjector.CTRL_META
        if (latches[Mod.ALT] != Latch.OFF) m = m or KeyInjector.ALT_META
        if (latches[Mod.SHIFT] != Latch.OFF) m = m or KeyInjector.SHIFT_META
        return m
    }

    private fun releaseOneShots(includingLocked: Boolean = false) {
        var changed = false
        for (m in Mod.entries) {
            val l = latches[m]
            if (l == Latch.ONCE || (includingLocked && l == Latch.LOCK && m == Mod.CTRL)) {
                latches[m] = Latch.OFF
                changed = true
            }
        }
        if (changed) refresh()
    }

    companion object {
        private const val PREF_MODE = "keyboard_mode"
        private const val LEFT = android.view.KeyEvent.KEYCODE_DPAD_LEFT
        private const val RIGHT = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    }
}
