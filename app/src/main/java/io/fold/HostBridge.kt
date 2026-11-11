package io.fold

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Bridge between the VS Code page and Android, only for the backend's own origin.
 * Today: clipboard. In a WebView `navigator.clipboard.readText()` is refused (no permission UI),
 * which breaks VS Code's Paste; the shim routes it through Android's ClipboardManager.
 */
class HostBridge(
    private val ctx: Context,
    private val web: WebView,
    /** Called when the page focuses or leaves a text field (editor, terminal, input box). */
    private val onEditableFocus: (Boolean) -> Unit = {},
) {
    private var script: ScriptHandler? = null
    private var installedOrigin: String? = null

    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @SuppressLint("RequiresFeature")
    fun install(origin: String) {
        if (!supported || origin == installedOrigin) return
        uninstall()
        WebViewCompat.addWebMessageListener(web, NAME, setOf(origin)) { _, message, _, isMainFrame, reply ->
            if (!isMainFrame) return@addWebMessageListener
            val req = try {
                JSONObject(message.data ?: return@addWebMessageListener)
            } catch (_: Exception) {
                return@addWebMessageListener
            }
            if (req.optString("op") == "focus") {
                onEditableFocus(req.optBoolean("editable"))
                return@addWebMessageListener
            }
            val res = JSONObject().put("id", req.optInt("id"))
            try {
                when (req.optString("op")) {
                    "clipboard.readText" -> res.put("ok", true).put("value", clipboardText())
                    "clipboard.writeText" -> {
                        clipboard().setPrimaryClip(ClipData.newPlainText("FoldCode", req.optString("arg")))
                        res.put("ok", true)
                    }
                    else -> res.put("ok", false).put("error", "unknown op")
                }
            } catch (t: Throwable) {
                res.put("ok", false).put("error", t.message ?: "failed")
            }
            reply.postMessage(res.toString())
        }
        script = WebViewCompat.addDocumentStartJavaScript(web, SHIM, setOf(origin))
        installedOrigin = origin
    }

    @SuppressLint("RequiresFeature")
    fun uninstall() {
        if (installedOrigin == null) return
        WebViewCompat.removeWebMessageListener(web, NAME)
        script?.remove()
        script = null
        installedOrigin = null
    }

    private fun clipboard() = ctx.getSystemService(ClipboardManager::class.java)

    private fun clipboardText(): String =
        clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""

    companion object {
        private const val NAME = "foldHost"

        private val SHIM = """
            (() => {
              const host = window.$NAME;
              if (!host || window.__foldClipboard) return;
              window.__foldClipboard = true;
              let seq = 0;
              const pending = new Map();
              host.onmessage = (e) => {
                let m; try { m = JSON.parse(e.data); } catch { return; }
                const p = pending.get(m.id); if (!p) return;
                pending.delete(m.id);
                m.ok ? p.resolve(m.value) : p.reject(new DOMException(m.error || 'failed', 'NotAllowedError'));
              };
              const call = (op, arg) => new Promise((resolve, reject) => {
                const id = ++seq; pending.set(id, { resolve, reject });
                host.postMessage(JSON.stringify({ id, op, arg }));
              });
              const clip = {
                readText: () => call('clipboard.readText'),
                writeText: (t) => call('clipboard.writeText', String(t)),
                read: async () => {
                  const text = await call('clipboard.readText');
                  return [new ClipboardItem({ 'text/plain': new Blob([text], { type: 'text/plain' }) })];
                },
                write: async (items) => {
                  for (const it of items) {
                    if (it.types.includes('text/plain')) {
                      return call('clipboard.writeText', await (await it.getType('text/plain')).text());
                    }
                  }
                },
                addEventListener() {}, removeEventListener() {}, dispatchEvent() { return true; },
              };
              Object.defineProperty(Navigator.prototype, 'clipboard', { get: () => clip, configurable: true });

              // Tell the app when a text field has focus, so it can show its keys.
              const editable = (el) => !!el && (el.tagName === 'TEXTAREA' || el.isContentEditable ||
                (el.tagName === 'INPUT' && !['button', 'checkbox', 'radio', 'submit', 'range', 'color', 'file', 'reset'].includes(el.type)) ||
                (el.classList && el.classList.contains('native-edit-context')));
              // FoldCode keyboard mode: with 'code', focused text fields get inputmode="none", so
              // Chromium never asks for the system keyboard (they still accept text and keys).
              let kbMode = 'system';
              const marked = new Set();
              const applyMode = (el) => {
                if (kbMode !== 'code' || !editable(el) || el.getAttribute('inputmode') === 'none') return;
                el.dataset.foldInputmode = el.getAttribute('inputmode') ?? '';
                el.setAttribute('inputmode', 'none');
                marked.add(el);
              };
              window.__foldSetKeyboardMode = (m) => {
                kbMode = m;
                if (m === 'code') { applyMode(document.activeElement); return; }
                for (const el of marked) {
                  const prev = el.dataset.foldInputmode;
                  if (prev) el.setAttribute('inputmode', prev); else el.removeAttribute('inputmode');
                  delete el.dataset.foldInputmode;
                }
                marked.clear();
              };
              document.addEventListener('focusin', (e) => applyMode(e.target), true);

              let last = null;
              const report = (always) => {
                const v = editable(document.activeElement);
                if (v !== last || (always && v)) { last = v; host.postMessage(JSON.stringify({ op: 'focus', editable: v })); }
              };
              // Every focus of a text field is reported (it re-opens a folded keyboard); leaving only on change.
              document.addEventListener('focusin', () => report(true), true);
              // A tap into the already focused editor (no new focusin) also brings the keys back.
              // Detected from pointer events (Monaco swallows click); drags and scrolls don't count.
              let down = null;
              document.addEventListener('pointerdown', (e) => { down = { x: e.clientX, y: e.clientY, t: e.timeStamp }; }, true);
              document.addEventListener('pointerup', (e) => {
                if (down && Math.abs(e.clientX - down.x) < 10 && Math.abs(e.clientY - down.y) < 10 && e.timeStamp - down.t < 500) {
                  setTimeout(() => report(true), 0);
                }
                down = null;
              }, true);
              document.addEventListener('focusout', () => setTimeout(() => report(false), 0), true);
              window.addEventListener('blur', () => setTimeout(() => report(false), 0));
            })();
        """.trimIndent()
    }
}
