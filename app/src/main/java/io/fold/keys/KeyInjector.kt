package io.fold.keys

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebView

/**
 * Sends keys to VS Code the way phase 0 measured works best: `dispatchKeyEvent` for keys,
 * shortcuts and ASCII text (Chromium turns them into trusted DOM events, so VS Code keybindings
 * fire), and the WebView's own InputConnection for characters a keyboard map can't produce
 * (Polish letters, …).
 */
class KeyInjector(private val web: WebView) {
    private val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    /** Types [text] as if from a keyboard. */
    fun text(text: String) {
        for (c in text) {
            val evs = kcm.getEvents(charArrayOf(c))
            if (evs != null) evs.forEach { web.dispatchKeyEvent(it) } else commit(c.toString())
        }
    }

    /** A key with modifiers held, e.g. arrows with Shift, or Ctrl+S. */
    fun key(keyCode: Int, meta: Int) {
        keyEvents(keyCode, meta).forEach { web.dispatchKeyEvent(it) }
    }

    /** A character as a shortcut: Ctrl/Alt + [c], keeping the Shift the character itself needs. */
    fun chord(c: Char, meta: Int) {
        val base = kcm.getEvents(charArrayOf(c.lowercaseChar()))
            ?.firstOrNull { it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode) } ?: return
        key(base.keyCode, meta or (base.metaState and SHIFT_META))
    }

    private fun commit(s: String) {
        val ic = web.onCreateInputConnection(EditorInfo()) ?: return
        val h = ic.handler
        if (h != null && h.looper != Looper.myLooper()) h.post { ic.commitText(s, 1) } else ic.commitText(s, 1)
    }

    /** Modifier downs, the key, modifier ups: the same sequence a hardware keyboard produces. */
    private fun keyEvents(keyCode: Int, meta: Int): List<KeyEvent> {
        val t = SystemClock.uptimeMillis()
        val mods = buildList {
            if (meta and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT to CTRL_META)
            if (meta and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT to ALT_META)
            if (meta and KeyEvent.META_SHIFT_ON != 0) add(KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT_META)
        }
        fun ev(action: Int, code: Int, m: Int) = KeyEvent(
            t, t, action, code, 0, m, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD,
        )
        val out = mutableListOf<KeyEvent>()
        var m = 0
        for ((code, bits) in mods) { m = m or bits; out += ev(KeyEvent.ACTION_DOWN, code, m) }
        out += ev(KeyEvent.ACTION_DOWN, keyCode, m)
        out += ev(KeyEvent.ACTION_UP, keyCode, m)
        for ((code, bits) in mods.reversed()) { m = m and bits.inv(); out += ev(KeyEvent.ACTION_UP, code, m) }
        return out
    }

    companion object {
        const val CTRL_META = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val ALT_META = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        const val SHIFT_META = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    }
}
