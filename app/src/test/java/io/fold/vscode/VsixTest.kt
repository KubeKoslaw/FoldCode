package io.fold.vscode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class VsixTest {
    private val assets = File("src/main/assets/foldcode-ext")

    @Test
    fun packsTheExtensionLikeVsce() {
        val files = assets.listFiles()!!.sortedBy { it.name }.map { it.name to it.readBytes() }
        val out = File("build/tmp/foldcode-test.vsix").apply { parentFile.mkdirs() }
        Vsix.write(out, "0.1.0", files)
        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            assertTrue("extension.vsixmanifest" in names)
            assertTrue("[Content_Types].xml" in names)
            assertTrue("extension/package.json" in names)
            assertTrue("extension/extension.js" in names)
            val manifest = zip.getInputStream(zip.getEntry("extension.vsixmanifest")).reader().readText()
            assertTrue(manifest.startsWith("<?xml"))
            assertTrue("Version=\"0.1.0\"" in manifest)
        }
        assertEquals(files.size + 2, ZipFile(out).use { it.size() })
    }
}
