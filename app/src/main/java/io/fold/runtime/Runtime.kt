package io.fold.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The app's Linux userland: a bionic prefix built from termux-packages for `io.fold`
 * (see plan.md, phase 1). Paths match what the packages were compiled for.
 */
class Runtime(private val ctx: Context) {
    val filesDir: File = ctx.filesDir // /data/data/io.fold/files (also /data/user/0/io.fold/files)
    val prefix = File("/data/data/${ctx.packageName}/files/usr")
    val home = File("/data/data/${ctx.packageName}/files/home")
    private val staging = File("/data/data/${ctx.packageName}/files/usr-staging")

    val isInstalled: Boolean get() = File(prefix, "bin/bash").exists()

    /**
     * Installs a Termux-format bootstrap zip, the way the Termux app does it:
     * extract into a staging dir, make bin/libexec/apt helpers executable, create the symlinks
     * listed in SYMLINKS.txt, then swap staging in as the prefix and run the second stage.
     */
    fun installBootstrap(zip: InputStream, log: (String) -> Unit) {
        staging.deleteRecursively()
        staging.mkdirs()
        val symlinks = mutableListOf<Pair<String, String>>()
        var files = 0
        ZipInputStream(zip.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name == "SYMLINKS.txt") {
                    zin.bufferedReader().readText().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        val parts = line.split('←')
                        require(parts.size == 2) { "bad SYMLINKS.txt line: $line" }
                        symlinks += parts[0] to File(staging, parts[1]).path
                    }
                    continue
                }
                val out = File(staging, e.name)
                require(out.canonicalPath.startsWith(staging.canonicalPath)) { "zip entry escapes prefix: ${e.name}" }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    val n = e.name
                    if (n.startsWith("bin/") || n.startsWith("libexec") || n.startsWith("lib/apt/apt-helper") || n.startsWith("lib/apt/methods")) {
                        Os.chmod(out.path, "700".toInt(8))
                    }
                    files++
                }
            }
        }
        for ((target, link) in symlinks) {
            File(link).parentFile?.mkdirs()
            Os.symlink(target, link)
        }
        File(staging, "tmp").mkdirs()
        log("extracted $files files, ${symlinks.size} symlinks")

        prefix.deleteRecursively()
        require(staging.renameTo(prefix)) { "could not move staging into $prefix" }
        home.mkdirs()

        configureApt(DEV_REPO_URL)
        log("apt → $DEV_REPO_URL")

        val second = File(prefix, "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh")
        if (second.exists()) {
            val r = run(listOf(second.path), timeoutSec = 300)
            log("second stage exit=${r.exit}\n${r.output.trim()}")
            require(r.exit == 0) { "bootstrap second stage failed" }
        } else {
            log("no second stage script in this bootstrap")
        }
    }

    /**
     * Points apt at the FoldCode repo (never Termux's: those packages are built for com.termux),
     * trusts our repo key (shipped in the APK assets), and stops `pkg` from picking Termux mirrors.
     */
    fun configureApt(repoUrl: String) {
        File(prefix, "etc/apt/sources.list").writeText(
            "# Managed by the FoldCode app.\ndeb ${repoUrl.trimEnd('/')}/ stable main\n",
        )
        File(prefix, "etc/apt/sources.list.d").listFiles()?.forEach { it.delete() }
        val keyring = File(prefix, "etc/apt/trusted.gpg.d/foldcode.gpg")
        keyring.parentFile?.mkdirs()
        ctx.assets.open("foldcode-repo.gpg").use { input -> keyring.outputStream().use { input.copyTo(it) } }
        val profile = File(prefix, "etc/profile.d/00-foldcode.sh")
        profile.parentFile?.mkdirs()
        profile.writeText("# Managed by the FoldCode app.\nexport TERMUX_PKG_NO_MIRROR_SELECT=1\n")
    }

    /** Environment for every process we start, modelled on the Termux app's shell environment. */
    fun env(): Map<String, String> {
        val e = linkedMapOf<String, String>()
        // Android runtime variables that tools like `am` and `getprop` rely on.
        for (k in listOf(
            "ANDROID_ART_ROOT", "ANDROID_ASSETS", "ANDROID_DATA", "ANDROID_I18N_ROOT", "ANDROID_ROOT",
            "ANDROID_RUNTIME_ROOT", "ANDROID_STORAGE", "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH",
            "DEX2OATBOOTCLASSPATH", "EXTERNAL_STORAGE", "SYSTEMSERVERCLASSPATH",
        )) System.getenv(k)?.let { e[k] = it }
        e["HOME"] = home.path
        e["PREFIX"] = prefix.path
        e["TMPDIR"] = "${prefix.path}/tmp"
        e["PATH"] = "${prefix.path}/bin"
        e["LANG"] = "en_US.UTF-8"
        e["TERM"] = "xterm-256color"
        e["COLORTERM"] = "truecolor"
        e["SHELL"] = "${prefix.path}/bin/bash"
        e["TERMUX__PREFIX"] = prefix.path
        e["TERMUX__ROOTFS"] = "/data/data/${ctx.packageName}/files"
        e["TERMUX__HOME"] = home.path
        e["TERMUX_APP__DATA_DIR"] = "/data/data/${ctx.packageName}"
        e["TERMUX_APP__PACKAGE_NAME"] = ctx.packageName
        e["TERMUX__USER_ID"] = "0"
        e["TERMUX_PKG_NO_MIRROR_SELECT"] = "1"
        // termux-exec makes `#!/usr/bin/env ...` shebangs and similar work inside the prefix.
        listOf("lib/libtermux-exec-ld-preload.so", "lib/libtermux-exec.so")
            .map { File(prefix, it) }.firstOrNull { it.exists() }
            ?.let { e["LD_PRELOAD"] = it.path }
        return e
    }

    data class Result(val exit: Int, val output: String)

    fun run(cmd: List<String>, timeoutSec: Long = 60, stdin: String? = null): Result {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(home.takeIf { it.isDirectory } ?: filesDir)
        pb.environment().clear()
        pb.environment().putAll(env())
        val p = try {
            pb.start()
        } catch (t: Throwable) {
            return Result(-1, "start failed: ${t.message}")
        }
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(2000)
        return Result(if (finished) p.exitValue() else -2, out.toString())
    }

    /** Runs a bash script (login shell, so profile.d runs too). */
    fun bash(script: String, timeoutSec: Long = 120): Result =
        run(listOf("${prefix.path}/bin/bash", "-l"), timeoutSec, stdin = script)

    companion object {
        /**
         * Development repo: served from the PC by scripts/serve-repo.sh and reached through
         * `adb reverse tcp:8686 tcp:8686`. The public repo URL replaces this once hosting is decided.
         */
        const val DEV_REPO_URL = "http://127.0.0.1:8686"
    }
}
