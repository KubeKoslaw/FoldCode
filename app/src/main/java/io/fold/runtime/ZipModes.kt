package io.fold.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * Unix permission bits of each entry in a zip, read from its central directory.
 * `ZipInputStream`/`ZipEntry` don't expose them, but bootstrap zips (Info-ZIP, "made by Unix")
 * store them, and many files outside bin/ must stay executable (npm-cli.js, git hooks, …).
 */
object ZipModes {
    private const val EOCD_SIG = 0x06054b50
    private const val CDH_SIG = 0x02014b50
    private const val HOST_UNIX = 3
    /** Octal 0111: any execute bit. */
    const val ANY_EXEC = 0x49

    /** Entry name → `st_mode` (type + permission bits). Empty if the zip has no Unix modes or is zip64. */
    fun read(zip: File): Map<String, Int> = RandomAccessFile(zip, "r").use { raf ->
        val len = raf.length()
        val tailLen = minOf(len, 22L + 65_535L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen)
        raf.readFully(tail)
        val eocd = (tailLen - 22 downTo 0).firstOrNull { le32(tail, it) == EOCD_SIG } ?: return emptyMap()
        val count = le16(tail, eocd + 10)
        val cdSize = le32(tail, eocd + 12).toLong() and 0xffffffffL
        val cdOffset = le32(tail, eocd + 16).toLong() and 0xffffffffL
        if (count == 0xffff || cdOffset == 0xffffffffL) return emptyMap() // zip64: never used for bootstraps

        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)
        val modes = HashMap<String, Int>(count * 2)
        var p = 0
        repeat(count) {
            if (p + 46 > cd.size || le32(cd, p) != CDH_SIG) return modes
            val host = le16(cd, p + 4) ushr 8
            val nameLen = le16(cd, p + 28)
            val extraLen = le16(cd, p + 30)
            val commentLen = le16(cd, p + 32)
            val external = le32(cd, p + 38)
            if (host == HOST_UNIX) {
                modes[String(cd, p + 46, nameLen, Charsets.UTF_8)] = (external ushr 16) and 0xffff
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        modes
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)
}
