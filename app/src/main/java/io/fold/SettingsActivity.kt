package io.fold

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.fold.backend.BackendHolder
import io.fold.github.GitHub
import io.fold.probe.Ui
import io.fold.probe.Ui.addSpaced
import io.fold.probe.Ui.dp
import io.fold.runtime.Runtime
import io.fold.vscode.VsCodeLink
import kotlin.concurrent.thread

/**
 * FoldCode's own settings, opened from the "FoldCode" item in VS Code's status bar:
 * GitHub sign-in, phone files, keyboard, notifications, AI agents in the terminal, VS Code.
 */
class SettingsActivity : Activity() {

    private lateinit var rt: Runtime
    private lateinit var gh: GitHub
    private lateinit var link: VsCodeLink
    private lateinit var githubStatus: TextView
    private lateinit var filesStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var keyboardStatus: TextView
    private val inputPrefs by lazy { getSharedPreferences("input", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = Runtime(this)
        gh = GitHub(rt)
        link = VsCodeLink(rt) { _, _ -> }

        val col = Ui.column(this).apply { setPadding(dp(20), dp(28), dp(20), dp(28)) }
        col.addSpaced(Ui.title(this, "FoldCode"), 18)

        // GitHub
        col.addSpaced(section("GitHub"), 6)
        githubStatus = status()
        col.addSpaced(githubStatus, 8)
        col.addSpaced(buttons(
            "Sign in" to { signIn() },
            "Sign out" to { signOut() },
        ), 18)

        // Files
        col.addSpaced(section("Phone files"), 6)
        filesStatus = status()
        col.addSpaced(filesStatus, 8)
        col.addSpaced(buttons(
            "Allow access" to { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) },
            "Open Download" to { openInVsCode("/sdcard/Download") },
            "Open Documents" to { openInVsCode("/sdcard/Documents") },
        ), 18)

        // Keyboard
        col.addSpaced(section("Keyboard"), 6)
        keyboardStatus = status()
        col.addSpaced(keyboardStatus, 8)
        col.addSpaced(buttons(
            "FoldCode keyboard" to { setKeyboard("CODE") },
            "System keyboard" to { setKeyboard("SYSTEM") },
            "Vibration on/off" to {
                inputPrefs.edit().putBoolean("haptics", !inputPrefs.getBoolean("haptics", true)).apply()
                refresh()
            },
        ), 18)

        // Notifications
        col.addSpaced(section("Running in the background"), 6)
        notifStatus = status()
        col.addSpaced(notifStatus, 8)
        col.addSpaced(buttons(
            "Turn on notifications" to {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            },
        ), 18)

        // AI agents
        col.addSpaced(section("AI agents in the terminal"), 6)
        col.addSpaced(status().apply {
            text = "Installs into FoldCode and starts in a new VS Code terminal. Each agent asks you to sign in to its own service."
        }, 8)
        col.addSpaced(buttons(
            "Gemini CLI" to { runInVsCode("Gemini CLI", "npm install -g @google/gemini-cli && gemini") },
            "Codex" to { runInVsCode("Codex", "pkg install -y codex && codex") },
        ), 6)
        col.addSpaced(status().apply {
            text = "Claude Code: needs a musl runtime package (planned).\nCursor CLI: needs the glibc compatibility layer (planned)."
        }, 18)

        // VS Code
        col.addSpaced(section("VS Code"), 6)
        col.addSpaced(buttons(
            "Restart VS Code" to {
                BackendHolder.restart(this)
                finish()
            },
            "Show log" to { showLog() },
        ), 18)

        col.addSpaced(status().apply {
            text = "FoldCode ${packageManager.getPackageInfo(packageName, 0).versionName} · bootstrap ${BuildConfig.BOOTSTRAP_SHA256.take(12)}"
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })

        // Only FoldCode itself (the non-exported activity, not the public alias) may start sign-in.
        val internal = intent.component?.className == SettingsActivity::class.java.name
        if (internal && savedInstanceState == null && intent.getStringExtra(EXTRA_ACTION) == ACTION_GITHUB) signIn()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        filesStatus.text = if (rt.hasStorageAccess) {
            rt.setupStorageLinks()
            "Allowed. In the terminal: ~/storage/downloads, ~/storage/documents, ~/storage/shared"
        } else {
            "Not allowed yet. FoldCode needs \"All files access\" to open folders like Download in VS Code."
        }
        notifStatus.text = if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            "Notifications are on: the VS Code notification has a Stop button."
        } else {
            "Notifications are off. VS Code keeps running anyway; turn them on to get its Stop button."
        }
        val mode = inputPrefs.getString("keyboard_mode", "CODE")
        keyboardStatus.text = "Default: ${if (mode == "SYSTEM") "system keyboard" else "FoldCode keyboard"} · vibration ${if (inputPrefs.getBoolean("haptics", true)) "on" else "off"}"
        githubStatus.text = "Checking…"
        thread {
            val login = gh.login()
            runOnUiThread {
                githubStatus.text = when {
                    login != null -> "Signed in as $login. git push/pull and VS Code's GitHub account use it."
                    gh.installed -> "Not signed in."
                    else -> "Not signed in. Signing in installs the GitHub CLI (gh) first."
                }
            }
        }
    }

    private fun signIn() {
        githubStatus.text = "Starting sign-in…"
        thread {
            if (!gh.install { msg -> runOnUiThread { githubStatus.text = msg } }) {
                runOnUiThread { githubStatus.text = "Couldn't install the GitHub CLI. Check the internet connection." }
                return@thread
            }
            gh.signIn(
                onCode = { code ->
                    runOnUiThread {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("GitHub code", code))
                        githubStatus.text = "Your code: $code (copied). Paste it on github.com and confirm."
                        Toast.makeText(this, "Code $code copied, opening github.com", Toast.LENGTH_LONG).show()
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHub.DEVICE_URL))) }
                    }
                },
                onDone = { ok, msg ->
                    runOnUiThread {
                        githubStatus.text = msg
                        if (ok) {
                            Toast.makeText(this, "$msg. Restarting VS Code…", Toast.LENGTH_LONG).show()
                            BackendHolder.restart(this)
                        }
                    }
                },
            )
        }
    }

    private fun signOut() {
        thread {
            val ok = gh.installed && gh.signOut()
            runOnUiThread {
                githubStatus.text = if (ok) "Signed out." else "Not signed in."
                if (ok) BackendHolder.restart(this)
            }
        }
    }

    private fun setKeyboard(mode: String) {
        inputPrefs.edit().putString("keyboard_mode", mode).apply()
        refresh()
    }

    private fun openInVsCode(path: String) {
        if (!rt.hasStorageAccess) {
            Toast.makeText(this, "Allow file access first", Toast.LENGTH_SHORT).show()
            return
        }
        link.openFolder(path)
        finish()
    }

    private fun runInVsCode(name: String, command: String) {
        link.runInTerminal(name, command)
        finish()
    }

    private fun showLog() {
        val f = BackendHolder.logFile(this)
        val text = if (f.exists()) f.readLines().takeLast(80).joinToString("\n") else "(no log yet)"
        val view = Ui.mono(this, 10f).apply { this.text = text; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        AlertDialog.Builder(this).setTitle("VS Code backend log").setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Close", null).show()
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.ACCENT)
    }

    private fun status() = TextView(this).apply {
        textSize = 13f
        setTextColor(Ui.TEXT2)
    }

    private fun buttons(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val wrap = Ui.column(this)
        var row = Ui.row(this)
        items.forEachIndexed { i, (label, action) ->
            if (i > 0 && i % 2 == 0) {
                wrap.addSpaced(row, 6)
                row = Ui.row(this)
            }
            row.addSpaced(Ui.button(this, label) { action() }, 8)
        }
        wrap.addSpaced(row, 0)
        return wrap
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_GITHUB = "github"
    }
}
