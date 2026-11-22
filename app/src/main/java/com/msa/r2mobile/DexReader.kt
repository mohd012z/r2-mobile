package com.msa.r2mobile

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the index tables of a DEX file (classes.dex): version, counts, class names, method names and strings.
 * Pure Kotlin, read-only, and no code in the file is run.
 */
object DexReader {

    private const val MAX_FILE = 256L * 1024 * 1024
    private const val MAX_LIST = 2_000

    class Summary(
        val version: String,
        val strings: Int,
        val types: Int,
        val methods: Int,
        val fields: Int,
        val classes: Int,
        val classNames: List<String>,
        val methodNames: List<String>,
        val stringSamples: List<String>,
    )

    fun read(file: File): Summary {
        if (file.length() > MAX_FILE) throw IOException("DEX larger than 256 MB: refused")
        val bytes = file.readBytes()
        if (bytes.size < 112 || String(bytes, 0, 4, Charsets.US_ASCII) != "dex\n") {
            throw IOException("not a DEX file (no 'dex' header). Extract classes.dex first with 21.")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = String(bytes, 4, 3, Charsets.US_ASCII)

        // Header: each table is (count, offset). Offsets are from the header layout of the DEX format.
        val stringCount = buf.getInt(56)
        val stringOff = buf.getInt(60)
        val typeCount = buf.getInt(64)
        val typeOff = buf.getInt(68)
        val fieldCount = buf.getInt(80)
        val fieldOff = buf.getInt(84)
        val methodCount = buf.getInt(88)
        val methodOff = buf.getInt(92)
        val classCount = buf.getInt(96)
        val classOff = buf.getInt(100)

        fun table(off: Int, count: Int, entry: Int, name: String) {
            if (count < 0 || off < 0 || off.toLong() + count.toLong() * entry > bytes.size) {
                throw IOException("malformed DEX: $name table runs past the end of the file")
            }
        }
        table(stringOff, stringCount, 4, "string")
        table(typeOff, typeCount, 4, "type")
        table(fieldOff, fieldCount, 8, "field")
        table(methodOff, methodCount, 8, "method")
        table(classOff, classCount, 32, "class")

        fun stringAt(i: Int): String = try {
            var off = buf.getInt(stringOff + i * 4)
            var len = 0
            var shift = 0
            while (true) {
                val b = bytes[off++].toInt() and 0xFF
                len = len or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            String(bytes, off, len, Charsets.UTF_8)
        } catch (e: RuntimeException) {
            "?"
        }

        fun typeName(idx: Int): String = stringAt(buf.getInt(typeOff + idx * 4))

        val classNames = (0 until minOf(classCount, MAX_LIST)).map { i ->
            typeName(buf.getInt(classOff + i * 32))
        }
        val methodNames = (0 until minOf(methodCount, MAX_LIST)).map { i ->
            val base = methodOff + i * 8
            val cls = buf.getShort(base).toInt() and 0xFFFF
            val name = buf.getInt(base + 4)
            "${typeName(cls)}->${stringAt(name)}"
        }
        val samples = ArrayList<String>()
        for (i in 0 until stringCount) {
            if (samples.size >= 100) break
            val s = stringAt(i)
            if (s.length >= 4 && s.all { it.code in 32..126 }) samples += s
        }
        return Summary(version, stringCount, typeCount, methodCount, fieldCount, classCount, classNames, methodNames, samples)
    }

    /** The lines the console prints for a DEX file. */
    fun report(file: File): List<String> {
        val s = read(file)
        val out = mutableListOf<String>()
        out += "file: ${file.name}, ${file.length()} bytes, DEX version ${s.version}"
        out += "strings ${s.strings}, types ${s.types}, methods ${s.methods}, fields ${s.fields}, classes ${s.classes}"
        out += "--- classes (first ${s.classNames.size}) ---"
        out += s.classNames
        out += "--- methods (first ${s.methodNames.size}) ---"
        out += s.methodNames
        out += "--- sample readable strings (first ${s.stringSamples.size}) ---"
        out += s.stringSamples
        return out
    }
}
