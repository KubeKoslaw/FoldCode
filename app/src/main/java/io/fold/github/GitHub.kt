package io.fold.github

import io.fold.runtime.Runtime
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * GitHub sign-in through the official GitHub CLI (`gh`) in FoldCode's environment, using
 * GitHub's device flow: gh prints a one-time code, the user confirms it on
 * github.com/login/device, gh stores the token in ~/.config/gh. Then `gh auth setup-git` lets
 * git push/pull use it, and the backend hands it to VS Code (GitHub accounts, extensions).
 */
class GitHub(private val rt: Runtime) {
    private val gh get() = File(rt.prefix, "bin/gh")

    val installed: Boolean get() = gh.exists()

    /** The signed-in login, or null. */
    fun login(): String? {
        if (!installed) return null
        val r = rt.run(listOf(gh.path, "api", "user", "--jq", ".login"), timeoutSec = 20)
        return r.output.trim().takeIf { r.exit == 0 && it.isNotEmpty() && !it.contains(' ') }
    }

    /** The stored token (for VS Code), or null when signed out. */
    fun token(): String? {
        if (!installed) return null
        val r = rt.run(listOf(gh.path, "auth", "token", "--hostname", "github.com"), timeoutSec = 15)
        return r.output.trim().takeIf { r.exit == 0 && it.startsWith("gh") }
    }

    fun install(log: (String) -> Unit): Boolean {
        if (installed) return true
        log("Installing GitHub CLI (gh)…")
        val r = rt.bash("apt-get update -q >/dev/null 2>&1; apt-get install -y -q gh 2>&1 | tail -n 3", timeoutSec = 600)
        log(r.output.trim())
        return installed
    }

    /**
     * Runs the device flow. [onCode] gets the one-time code as soon as gh prints it;
     * [onDone] gets the result (on a background thread).
     */
    fun signIn(onCode: (code: String) -> Unit, onDone: (ok: Boolean, message: String) -> Unit) = thread(name = "gh-login") {
        try {
            val pb = ProcessBuilder(gh.path, "auth", "login", "--hostname", "github.com", "--git-protocol", "https", "--web", "--insecure-storage", "--scopes", "repo,read:org,workflow")
                .redirectErrorStream(true)
                .directory(rt.home)
            pb.environment().clear()
            pb.environment().putAll(rt.env())
            // gh would try to open a browser itself; the app does that, so give it a no-op.
            pb.environment()["GH_BROWSER"] = "true"
            pb.environment()["BROWSER"] = "true"
            val p = pb.start()
            // gh waits for Enter before "opening the browser".
            p.outputStream.use { it.write("\n".toByteArray()) }
            val out = StringBuilder()
            val codeRegex = Regex("""\b([A-Z0-9]{4}-[A-Z0-9]{4})\b""")
            var sent = false
            p.inputStream.bufferedReader().forEachLine { line ->
                out.appendLine(line)
                if (!sent) codeRegex.find(line)?.let { sent = true; onCode(it.groupValues[1]) }
            }
            val finished = p.waitFor(16, TimeUnit.MINUTES)
            if (!finished) p.destroyForcibly()
            if (finished && p.exitValue() == 0) {
                rt.run(listOf(gh.path, "auth", "setup-git"), timeoutSec = 30)
                onDone(true, login()?.let { "Signed in as $it" } ?: "Signed in")
            } else {
                onDone(false, out.toString().trim().lines().takeLast(4).joinToString("\n").ifEmpty { "Sign-in did not finish" })
            }
        } catch (t: Throwable) {
            onDone(false, t.message ?: t.toString())
        }
    }

    fun signOut(): Boolean =
        rt.run(listOf(gh.path, "auth", "logout", "--hostname", "github.com"), timeoutSec = 20, stdin = "Y\n").exit == 0

    companion object {
        const val DEVICE_URL = "https://github.com/login/device"
    }
}
