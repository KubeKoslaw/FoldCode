package io.fold.probe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import io.fold.probe.Ui.addSpaced
import io.fold.probe.Ui.dp
import io.fold.runtime.Runtime
import java.io.File
import kotlin.concurrent.thread

/**
 * Phase 1 probe: installs a bootstrap zip pushed to the app's external files dir and checks that
 * the toolchain inside it works (bash, apt, git, ssh, rg, node, shebangs through termux-exec).
 *
 *   adb push out/bootstrap/bootstrap-aarch64.zip /sdcard/Android/data/io.fold/files/
 *   adb shell am start -n io.fold/.probe.BootstrapProbeActivity --ez install true --ez checks true
 *
 * Log: /sdcard/Android/data/io.fold/files/bootstrap-probe.log
 */
class BootstrapProbeActivity : Activity() {

    private lateinit var out: android.widget.TextView
    private lateinit var rt: Runtime
    private var logFile: File? = null
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rt = Runtime(this)
        logFile = getExternalFilesDir(null)?.let { File(it, "bootstrap-probe.log") }
        out = Ui.mono(this, 11f)

        val buttons = Ui.row(this)
        buttons.addSpaced(Ui.button(this, "Install bootstrap") { work { install() } })
        buttons.addSpaced(Ui.button(this, "Run checks") { work { checks() } })

        val col = Ui.column(this).apply {
            setPadding(dp(16), dp(28), dp(16), dp(16))
            addSpaced(Ui.title(this@BootstrapProbeActivity, "Bootstrap probe"))
            addSpaced(buttons, 12)
            addSpaced(out)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        log("prefix ${rt.prefix} installed=${rt.isInstalled}")
        val doInstall = intent.getBooleanExtra("install", false)
        val doChecks = intent.getBooleanExtra("checks", false)
        // Debug probe only: package names are restricted so the exported activity can't run arbitrary commands.
        val aptPkgs = intent.getStringExtra("apt")?.split(',', ' ')?.map { it.trim() }?.filter { it.matches(Regex("[a-z0-9][a-z0-9.+-]*")) }.orEmpty()
        if (doInstall || doChecks || aptPkgs.isNotEmpty()) work {
            if (doInstall) install()
            if (aptPkgs.isNotEmpty()) aptInstall(aptPkgs)
            if (doChecks) checks()
            log("=== DONE")
        }
    }

    private fun work(block: () -> Unit) {
        if (busy) return
        busy = true
        thread(name = "bootstrap-probe") {
            try {
                block()
            } catch (t: Throwable) {
                log("ERROR: $t")
            } finally {
                busy = false
            }
        }
    }

    private fun install() {
        val zip = getExternalFilesDir(null)?.let { File(it, "bootstrap-aarch64.zip") }
        if (zip == null || !zip.exists()) {
            log("no bootstrap at $zip (adb push it there first)")
            return
        }
        log("installing ${zip.name} (${zip.length() / 1024 / 1024} MB)…")
        val t0 = System.currentTimeMillis()
        rt.installBootstrap(zip.inputStream()) { log(it) }
        log("installed in ${(System.currentTimeMillis() - t0) / 1000.0}s")
    }

    private fun checks() {
        if (!rt.isInstalled) {
            log("bootstrap not installed")
            return
        }
        log("env LD_PRELOAD=${rt.env()["LD_PRELOAD"]}")
        val r = rt.bash(CHECKS, timeoutSec = 180)
        log("checks exit=${r.exit}\n${r.output.trimEnd()}")
    }

    private fun aptInstall(pkgs: List<String>) {
        log("apt install ${pkgs.joinToString(" ")}…")
        val r = rt.bash("apt-get update -q 2>&1 | tail -n 3 && apt-get install -y -q ${pkgs.joinToString(" ")} 2>&1 | tail -n 15", timeoutSec = 900)
        log("apt exit=${r.exit}\n${r.output.trimEnd()}")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            logFile?.appendText(msg + "\n")
        } catch (_: Exception) {
        }
        runOnUiThread { out.append(msg + "\n") }
    }

    companion object {
        const val TAG = "BootstrapProbe"

        private val CHECKS = """
            echo "uname    ${'$'}(uname -srm)"
            echo "bash     ${'$'}BASH_VERSION"
            echo "prefix   ${'$'}PREFIX   home ${'$'}HOME"
            for c in apt dpkg git ssh rg node python3 php clang; do
                if command -v "${'$'}c" >/dev/null 2>&1; then
                    printf '%-8s %s\n' "${'$'}c" "${'$'}(${'$'}c --version 2>&1 | head -n1)"
                else
                    printf '%-8s %s\n' "${'$'}c" "(not installed)"
                fi
            done
            echo "arch     ${'$'}(dpkg --print-architecture)"
            echo "pkgs     ${'$'}(dpkg -l | grep -c '^ii') installed"
            echo "sources: ${'$'}(grep -v '^#' ${'$'}PREFIX/etc/apt/sources.list | tr '\n' ' ')"
            printf '#!/usr/bin/env bash\necho shebang-ok\n' > "${'$'}TMPDIR/t.sh" && chmod +x "${'$'}TMPDIR/t.sh" && echo "shebang  ${'$'}("${'$'}TMPDIR/t.sh" 2>&1)"
            rm -rf "${'$'}TMPDIR/g" && git init -q "${'$'}TMPDIR/g" && cd "${'$'}TMPDIR/g" && echo hi > a && git add a \
                && git -c user.name=t -c user.email=t@t commit -qm init && echo "git      commit ok (${'$'}(git log --oneline | wc -l) commit)"
            echo "rg       ${'$'}(printf 'foo\nbar\n' | rg -c bar 2>&1) match"
            echo "ssh-keygen ${'$'}(ssh-keygen -t ed25519 -N '' -q -f "${'$'}TMPDIR/k" <<<y >/dev/null 2>&1 && echo ok || echo FAIL)"
            echo "second-stage lock: ${'$'}(ls -la ${'$'}PREFIX/etc/termux/termux-bootstrap/second-stage/ 2>/dev/null | grep -c lock)"
            echo "apt update:"; apt-get update -q 2>&1 | tail -n 4 | sed 's/^/  /'
            echo "apt policy: ${'$'}(apt-cache policy 2>/dev/null | grep -c 127.0.0.1) FoldCode source(s)"
        """.trimIndent()
    }
}
