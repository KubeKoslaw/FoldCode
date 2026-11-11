package io.fold.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom

/**
 * The VS Code backend (code-server) running inside the app: a local process bound to
 * 127.0.0.1 on a random port, protected by a random per-launch password. It is never reachable
 * from the network, and other apps on the phone can't use it without the password.
 */
class Backend(private val rt: Runtime) {
    val port: Int = freePort()
    private val password: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var process: Process? = null

    val baseUrl get() = "http://127.0.0.1:$port"
    val isRunning get() = process?.isAlive == true
    val executable get() = File(rt.prefix, "bin/code-server")

    /** Starts code-server; its output goes to [log]. Opens [folder] as the workspace. */
    fun start(folder: File, log: File) {
        check(executable.exists()) { "code-server is not installed (${executable.path})" }
        val cmd = listOf(
            executable.path,
            "--bind-addr", "127.0.0.1:$port",
            "--auth", "password",
            "--disable-telemetry",
            "--disable-update-check",
            folder.path,
        )
        val pb = ProcessBuilder(cmd)
            .directory(rt.home)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        pb.environment().clear()
        pb.environment().putAll(rt.env())
        pb.environment()["PASSWORD"] = password
        process = pb.start().also { it.outputStream.close() }
    }

    /** Polls /healthz until the backend answers or [timeoutMs] passes. */
    fun waitUntilHealthy(timeoutMs: Long = 90_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!isRunning) return false
            try {
                val c = URL("$baseUrl/healthz").openConnection() as HttpURLConnection
                c.connectTimeout = 1000
                c.readTimeout = 2000
                if (c.responseCode == 200) return true
            } catch (_: Exception) {
            }
            Thread.sleep(300)
        }
        return false
    }

    /** Logs in with the per-launch password and returns the session cookie (Set-Cookie value). */
    fun login(): String? {
        val c = URL("$baseUrl/login").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.instanceFollowRedirects = false
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write("password=$password".toByteArray()) }
        c.responseCode
        return c.headerFields["Set-Cookie"]?.firstOrNull { it.startsWith("code-server-session=") }
    }

    fun stop() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
