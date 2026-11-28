package com.msa.r2mobile

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Private working copies only; no shell execution or original APK mutation. */
class EmbeddedCore(private val root: File) {
    companion object { const val MAX_FILE = 1048576; const val MAX_PROJECT = 8388608; const val MAX_FILES = 200 }
    init { require(root.isDirectory || root.mkdirs()) }
    private fun target(path: String): File {
        require(path.length <= 240 && path.split('/').all { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_. -]{0,79}")) && !it.endsWith('.') && !it.endsWith(' ') }) { "Invalid relative path" }
        val f = File(root, path)
        require(f.canonicalPath == root.canonicalPath + File.separator + path) { "Unsafe path" }
        return f
    }
    @Synchronized fun save(path: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE)
        val f = target(path)
        val existing = root.walkTopDown().filter { it.isFile }.toList()
        require(existing.size + (if (f.exists()) 0 else 1) <= MAX_FILES)
        require(existing.sumOf { it.length() } - (if (f.isFile) f.length() else 0L) + bytes.size <= MAX_PROJECT)
        require(f.parentFile!!.isDirectory || f.parentFile!!.mkdirs())
        val tmp = File.createTempFile(".save-", ".tmp", f.parentFile)
        try { tmp.outputStream().use { it.write(bytes); it.fd.sync() }; Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } finally { tmp.delete() }
    }
    @Synchronized fun read(path: String): String { val f = target(path); require(f.isFile && f.length() <= MAX_FILE); return f.readText() }
    @Synchronized fun list(): List<String> = root.walkTopDown().filter { it.isFile && !it.name.startsWith(".save-") }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    fun fingerprint(file: File): String { require(file.isFile && file.length() <= 536870912L); val md = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val b = ByteArray(65536); var total = 0L; while (true) { val n = input.read(b); if (n < 0) break; total += n; require(total <= 536870912L); md.update(b,0,n) } }; return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) } }
    data class Entry(val name: String, val size: Long)
    fun inventory(file: File): List<Entry> { require(file.isFile && file.length() <= 536870912L); return ZipFile(file).use { z -> require(z.size() <= 10000); z.entries().asSequence().map { Entry(it.name, it.size) }.toList() } }
    fun enginePresent(nativeDir: File): Boolean = File(nativeDir,"libr2.so").let { it.isFile && it.canExecute() }
}