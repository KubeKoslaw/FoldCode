package io.fold.runtime

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the bootstrap zip over HTTPS, resuming a partial download, and accepts it only if
 * its size and SHA-256 match the values built into the app (BuildConfig, from
 * app/bootstrap.properties written by scripts/publish-packages.sh).
 */
class BootstrapDownloader(
    private val url: String,
    private val sha256: String,
    private val size: Long,
) {
    fun download(dest: File, progress: (done: Long, total: Long) -> Unit) {
        require(url.startsWith("https://")) { "bootstrap URL must be https" }
        if (dest.exists() && dest.length() == size && sha256Of(dest).equals(sha256, true)) return
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (have >= size) {
            part.delete()
            have = 0
        }

        val c = open(url, have)
        val append = when (c.responseCode) {
            206 -> true
            200 -> { have = 0; false }
            else -> error("download failed: HTTP ${c.responseCode}")
        }
        c.inputStream.use { input ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = have
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 512 * 1024) {
                        progress(done, size)
                        lastReport = done
                    }
                }
                progress(done, size)
            }
        }
        check(part.length() == size) { "size mismatch: got ${part.length()}, expected $size" }
        val got = sha256Of(part)
        if (!got.equals(sha256, true)) {
            part.delete()
            error("SHA-256 mismatch: got $got")
        }
        dest.delete()
        check(part.renameTo(dest)) { "could not move download into place" }
    }

    /** Follows redirects by hand (GitHub → its CDN) so the Range header survives every hop. */
    private fun open(start: String, from: Long): HttpURLConnection {
        var u = start
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                val next = c.getHeaderField("Location") ?: error("redirect without Location")
                c.disconnect()
                u = URL(URL(u), next).toString()
                require(u.startsWith("https://")) { "refusing non-https redirect" }
                return@repeat
            }
            return c
        }
        error("too many redirects")
    }

    companion object {
        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
