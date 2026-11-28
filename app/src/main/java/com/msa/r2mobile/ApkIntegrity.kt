package com.msa.r2mobile

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.CRC32
import java.security.MessageDigest

/** Read-only archive checks, not Android signature verification. */
object ApkIntegrity {
    data class Report(val sha256: String, val entries: Int, val expandedBytes: Long, val dexFiles: List<String>)
    fun inspect(file: File): Report {
        require(file.isFile && file.length() <= 512L * 1024 * 1024)
        val before = hash(file)
        val result = ZipFile(file).use { zip ->
            require(zip.size() <= 10000) { "Too many entries" }
            val names = HashSet<String>(); var total = 0L
            val dex = mutableListOf<String>(); val buffer = ByteArray(65536)
            for (entry in zip.entries()) {
                val name = entry.name.removeSuffix("/")
                require(name.isNotEmpty() && !name.contains('\\') && !name.contains(':') && name.none { it.code < 32 } && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe entry path" }
                require(names.add(name)) { "Duplicate entry" }
                require(entry.size in 0..(128L * 1024 * 1024)) { "Entry size limit" }
                if (entry.isDirectory) { require(entry.size == 0L); continue }
                val crc = CRC32(); var count = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) { val n = input.read(buffer); if (n < 0) break
                        count += n; total += n
                        require(count <= entry.size && total <= 512L * 1024 * 1024) { "Expansion limit" }
                        crc.update(buffer, 0, n)
                    }
                }
                require(count == entry.size && crc.value == entry.crc) { "Entry integrity mismatch" }
                if (name.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))) dex.add(name)
            }
            require(zip.getEntry("AndroidManifest.xml")?.let { !it.isDirectory && it.size > 0 } == true) { "Missing manifest" }
            Report(before, names.size, total, dex.sorted())
        }
        require(before == hash(file)) { "Artifact changed during inspection" }
        return result
    }
    private fun hash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256"); var total = 0L
        file.inputStream().use { input -> val b = ByteArray(65536); while(true) { val n = input.read(b); if(n < 0) break; total += n; require(total <= 512L * 1024 * 1024); md.update(b,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}