package com.msa.r2mobile

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Archives with the standard library only: zip, tar, tar.gz (tgz) and single-file gz. */
object Archive {

    enum class Format(val label: String, val suffix: String) {
        ZIP("zip", ".zip"),
        TAR_GZ("tar.gz", ".tar.gz"),
        GZ("gz (one file only)", ".gz"),
    }

    private val SUFFIXES = listOf(".tar.gz", ".tgz", ".tar", ".zip", ".gz")

    /** Limits against zip bombs and runaway archives. Exceeding one refuses the whole extraction. */
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    private const val MAX_RATIO = 200L
    private const val RATIO_CHECK_FROM = 10L * 1024 * 1024

    /** Running totals for one extraction. */
    private class Budget {
        var entries = 0
        var bytes = 0L
        fun entry() {
            if (++entries > MAX_ENTRIES) throw IOException("archive has more than $MAX_ENTRIES entries: refused")
        }
        fun add(n: Long) {
            bytes += n
            if (bytes > MAX_TOTAL_BYTES) throw IOException("archive would unpack to more than 2 GB: refused")
        }
    }

    fun isArchive(name: String): Boolean =
        SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    /** The name without its archive suffix: "photos.tar.gz" -> "photos". */
    fun stem(name: String): String {
        val suffix = SUFFIXES.firstOrNull { name.endsWith(it, ignoreCase = true) } ?: return name
        return name.dropLast(suffix.length).ifEmpty { name }
    }

    /** Packs [files] (folders included, recursively) into [out] as [format]. */
    fun create(files: List<File>, out: File, format: Format) {
        when (format) {
            Format.ZIP -> ZipOutputStream(out.outputStream().buffered()).use { zos ->
                files.forEach { addZip(zos, it, it.name) }
            }
            Format.TAR_GZ -> GZIPOutputStream(out.outputStream().buffered()).use { gz ->
                val tar = TarWriter(gz)
                files.forEach { tar.add(it, it.name) }
                tar.finish()
            }
            Format.GZ -> {
                val f = files.singleOrNull()?.takeIf { it.isFile }
                    ?: throw IOException("gz works on one file. Use tar.gz for several items or folders.")
                GZIPOutputStream(out.outputStream().buffered()).use { gz -> f.inputStream().use { it.copyTo(gz) } }
            }
        }
    }

    /** Unpacks [file] into the folder [dest], which is created if needed. */
    fun extract(file: File, dest: File) {
        dest.mkdirs()
        val name = file.name.lowercase()
        when {
            name.endsWith(".zip") -> extractZip(file, dest, Budget())
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                GZIPInputStream(file.inputStream().buffered()).use { untar(it, dest, Budget()) }
            name.endsWith(".tar") -> file.inputStream().buffered().use { untar(it, dest, Budget()) }
            name.endsWith(".gz") -> {
                val target = safeFile(dest, stem(file.name))
                GZIPInputStream(file.inputStream().buffered()).use { input ->
                    target.outputStream().use { copyBudget(input, it, Budget()) }
                }
            }
            else -> throw IOException("not a supported archive: ${file.name}")
        }
    }

    private fun addZip(zos: ZipOutputStream, f: File, entry: String) {
        if (f.isDirectory) {
            f.listFiles()?.forEach { addZip(zos, it, "$entry/${it.name}") }
        } else {
            zos.putNextEntry(ZipEntry(entry))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    private fun extractZip(file: File, dest: File, budget: Budget) {
        ZipFile(file).use { zip ->
            for (e in Collections.list(zip.entries())) {
                budget.entry()
                if (e.size >= RATIO_CHECK_FROM && e.compressedSize > 0 && e.size / e.compressedSize > MAX_RATIO) {
                    throw IOException("${e.name} expands too much (zip bomb): refused")
                }
                val out = safeFile(dest, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { copyBudget(input, it, budget) } }
                }
            }
        }
    }

    private fun untar(input: InputStream, dest: File, budget: Budget) {
        val header = ByteArray(512)
        while (true) {
            if (!readBlock(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000').trim()
            val size = String(header, 124, 12, Charsets.US_ASCII).substringBefore('\u0000').trim()
                .ifEmpty { "0" }.toLong(8)
            val type = header[156].toInt().toChar()
            val target = safeFile(dest, name)
            budget.entry()
            budget.add(size)
            when (type) {
                '5' -> target.mkdirs()
                '0', '\u0000' -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { copyN(input, it, size) }
                }
                else -> copyN(input, OutputStream.nullOutputStream(), size)
            }
            copyN(input, OutputStream.nullOutputStream(), (512 - size % 512) % 512)
        }
    }

    /** A file path inside [dest] only. Blocks entries like ../../x that would write outside it. */
    private fun safeFile(dest: File, name: String): File {
        val f = File(dest, name)
        val base = dest.canonicalPath
        val path = f.canonicalPath
        if (path != base && !path.startsWith(base + File.separator)) {
            throw SecurityException("unsafe entry $name")
        }
        return f
    }

    private fun copyBudget(input: InputStream, out: OutputStream, budget: Budget) {
        val buf = ByteArray(8192)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            budget.add(r.toLong())
            out.write(buf, 0, r)
        }
    }

    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) {
                if (n == 0) return false
                throw IOException("archive ends in the middle of a header")
            }
            n += r
        }
        return true
    }

    private fun copyN(input: InputStream, out: OutputStream, count: Long) {
        val buf = ByteArray(8192)
        var left = count
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("archive ends too early")
            out.write(buf, 0, r)
            left -= r
        }
    }

    /** Writes a tar stream (ustar headers, 512-byte blocks). */
    private class TarWriter(private val out: OutputStream) {
        fun add(f: File, entry: String) {
            if (f.isDirectory) {
                out.write(header("$entry/", 0, '5'))
                f.listFiles()?.forEach { add(it, "$entry/${it.name}") }
            } else {
                val size = f.length()
                out.write(header(entry, size, '0'))
                f.inputStream().use { it.copyTo(out) }
                out.write(ByteArray(((512 - size % 512) % 512).toInt()))
            }
        }

        fun finish() {
            out.write(ByteArray(1024))
        }

        private fun header(name: String, size: Long, type: Char): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 100) throw IOException("name too long for tar (over 100 bytes): $name")
            val h = ByteArray(512)
            nameBytes.copyInto(h, 0)
            octal(if (type == '5') 493L else 420L, h, 100, 8)
            octal(0L, h, 108, 8)
            octal(0L, h, 116, 8)
            octal(size, h, 124, 12)
            octal(System.currentTimeMillis() / 1000, h, 136, 12)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = type.code.toByte()
            "ustar".toByteArray(Charsets.US_ASCII).copyInto(h, 257)
            h[263] = '0'.code.toByte()
            h[264] = '0'.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xFF }
            octal(sum.toLong(), h, 148, 7)
            h[155] = ' '.code.toByte()
            return h
        }

        private fun octal(v: Long, buf: ByteArray, off: Int, len: Int) {
            val s = java.lang.Long.toOctalString(v).padStart(len - 1, '0')
            for (i in 0 until len - 1) buf[off + i] = s[i].code.toByte()
            buf[off + len - 1] = 0
        }
    }
}
