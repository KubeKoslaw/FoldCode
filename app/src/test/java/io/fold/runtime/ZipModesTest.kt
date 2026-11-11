package io.fold.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs against the real bootstrap from scripts/build-bootstrap.sh when it has been built. */
class ZipModesTest {
    private val zip = File("../out/bootstrap/bootstrap-aarch64.zip")

    private fun isExec(mode: Int?) = mode != null && (mode and ZipModes.ANY_EXEC) != 0

    @Test
    fun readsUnixModesFromBootstrap() {
        assumeTrue("bootstrap not built", zip.exists())
        val modes = ZipModes.read(zip)
        val entries = java.util.zip.ZipFile(zip).use { z -> z.entries().asSequence().count() }

        assertEquals("every entry has a unix mode", entries, modes.size)
        assertTrue("bin/bash is executable", isExec(modes["bin/bash"]))
        assertTrue("npm entry point is executable", isExec(modes["lib/node_modules/npm/bin/npm-cli.js"]))
        assertTrue("bash.bashrc is not executable", !isExec(modes["etc/bash.bashrc"]))
        val execOutsideBin = modes.count { (name, mode) -> isExec(mode) && !name.startsWith("bin/") && !name.startsWith("libexec") }
        assertTrue("executables outside bin/ are found ($execOutsideBin)", execOutsideBin > 100)
    }
}
