package io.fold.vscode

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.fold.runtime.Runtime
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * FoldCode's built-in VS Code extension (assets/foldcode-ext) and the file-based link to it:
 *   ~/.foldcode/requests   VS Code → app (settings, GitHub sign-in)
 *   ~/.foldcode/to-vscode  app → VS Code (run a command in a new terminal, open a folder)
 * Both sides run as the same Android user; nothing goes over the network.
 */
object FoldExtension {
    private const val TAG = "FoldExtension"
    private const val ASSET_DIR = "foldcode-ext"

    private fun version(ctx: Context): String =
        JSONObject(ctx.assets.open("$ASSET_DIR/package.json").bufferedReader().readText()).getString("version")

    /** Installs the extension into code-server when it isn't there yet at this version. */
    fun ensureInstalled(ctx: Context, rt: Runtime) {
        val v = version(ctx)
        val marker = File(rt.home, ".foldcode/extension-version")
        if (marker.exists() && marker.readText().trim() == v) return
        val vsix = File(ctx.cacheDir, "foldcode-$v.vsix")
        writeVsix(ctx, vsix, v)
        val r = rt.run(listOf(File(rt.prefix, "bin/code-server").path, "--install-extension", vsix.path, "--force"), timeoutSec = 120)
        Log.i(TAG, "install exit=${r.exit} ${r.output.takeLast(300)}")
        vsix.delete()
        if (r.exit == 0) {
            marker.parentFile?.mkdirs()
            marker.writeText(v)
        }
    }

    private fun writeVsix(ctx: Context, out: File, v: String) {
        val files = ctx.assets.list(ASSET_DIR).orEmpty().map { it to ctx.assets.open("$ASSET_DIR/$it").readBytes() }
        Vsix.write(out, v, files)
    }
}

/** A .vsix is a zip with a manifest, a content-types file and the extension under extension/. */
object Vsix {
    fun write(out: File, version: String, files: List<Pair<String, ByteArray>>) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
              <Metadata>
                <Identity Language="en-US" Id="foldcode" Version="$version" Publisher="foldcode"/>
                <DisplayName>FoldCode</DisplayName>
                <Description xml:space="preserve">Connects VS Code with the FoldCode Android app.</Description>
                <Properties><Property Id="Microsoft.VisualStudio.Code.Engine" Value="^1.80.0"/></Properties>
              </Metadata>
              <Installation><InstallationTarget Id="Microsoft.VisualStudio.Code"/></Installation>
              <Dependencies/>
              <Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true"/></Assets>
            </PackageManifest>
        """.trimIndent()
        val types = """<?xml version="1.0" encoding="utf-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension=".json" ContentType="application/json"/><Default Extension=".js" ContentType="application/javascript"/>""" +
            """<Default Extension=".vsixmanifest" ContentType="text/xml"/></Types>"""
        ZipOutputStream(out.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("extension.vsixmanifest", manifest.toByteArray())
            put("[Content_Types].xml", types.toByteArray())
            for ((name, bytes) in files) put("extension/$name", bytes)
        }
    }
}

/** Messages from VS Code (requests) and to it (to-vscode). */
class VsCodeLink(rt: Runtime, private val onRequest: (cmd: String, arg: String?) -> Unit) {
    private val requests = File(rt.home, ".foldcode/requests").apply { mkdirs() }
    private val outbox = File(rt.home, ".foldcode/to-vscode").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private val observer = object : FileObserver(requests.path, MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == null || path.startsWith(".") || !path.endsWith(".json")) return
            val f = File(requests, path)
            val msg = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
            f.delete()
            main.post { onRequest(msg.optString("cmd"), msg.optString("arg").ifEmpty { null }) }
        }
    }

    fun start() {
        // Requests that arrived while the app wasn't watching.
        requests.listFiles { f -> f.name.endsWith(".json") && !f.name.startsWith(".") }?.sortedBy { it.name }?.forEach { f ->
            runCatching { JSONObject(f.readText()) }.getOrNull()?.let { msg ->
                f.delete()
                main.post { onRequest(msg.optString("cmd"), msg.optString("arg").ifEmpty { null }) }
            }
        }
        observer.startWatching()
    }

    fun stop() = observer.stopWatching()

    /** Opens a new VS Code terminal named [name] and runs [command] in it. */
    fun runInTerminal(name: String, command: String) =
        post(JSONObject().put("cmd", "terminal").put("name", name).put("text", command))

    fun openFolder(path: String) = post(JSONObject().put("cmd", "openFolder").put("path", path))

    private fun post(msg: JSONObject) {
        val name = "${System.currentTimeMillis()}-${(0..9999).random()}.json"
        val tmp = File(outbox, ".$name")
        tmp.writeText(msg.toString())
        tmp.renameTo(File(outbox, name))
    }
}
