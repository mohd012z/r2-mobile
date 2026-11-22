package com.msa.r2mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns a binary AndroidManifest.xml (AXML, as stored inside an APK) into readable XML text.
 * Follows the chunk layout in AOSP androidfw/ResourceTypes.h. Resource tables (resources.arsc)
 * are not decoded, so references such as @string/app_name appear as @0x... ids.
 */
object ManifestDecoder {
    private const val TYPE_XML = 0x0003
    private const val TYPE_STRING_POOL = 0x0001
    private const val TYPE_START_NS = 0x0100
    private const val TYPE_START_ELEMENT = 0x0102
    private const val TYPE_END_ELEMENT = 0x0103
    private const val UTF8_FLAG = 1 shl 8
    private const val NO_ENTRY = -1

    fun decode(bytes: ByteArray): String {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || u16(buf, 0) != TYPE_XML) {
            throw IllegalArgumentException("not a binary AndroidManifest.xml")
        }
        var strings: List<String> = emptyList()
        val prefixes = HashMap<String, String>()
        val pendingNs = ArrayList<String>()
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        var depth = 0
        var pos = u16(buf, 2)
        while (pos + 8 <= bytes.size) {
            val type = u16(buf, pos)
            val size = buf.getInt(pos + 4)
            if (size < 8 || pos + size > bytes.size) break
            when (type) {
                TYPE_STRING_POOL -> strings = readStrings(buf, pos)
                TYPE_START_NS -> {
                    val prefix = strings.str(buf.getInt(pos + 16))
                    val uri = strings.str(buf.getInt(pos + 20))
                    prefixes[uri] = prefix
                    pendingNs += "xmlns:$prefix=\"${esc(uri)}\""
                }
                TYPE_START_ELEMENT -> {
                    val name = strings.str(buf.getInt(pos + 20))
                    val attrStart = u16(buf, pos + 24)
                    val attrCount = u16(buf, pos + 28)
                    out.append("  ".repeat(depth)).append('<').append(name)
                    pendingNs.forEach { out.append(' ').append(it) }
                    pendingNs.clear()
                    for (i in 0 until attrCount) {
                        val a = pos + 16 + attrStart + i * 20
                        val ns = buf.getInt(a)
                        val local = strings.str(buf.getInt(a + 4))
                        val key = if (ns == NO_ENTRY) local else "${prefixes[strings.str(ns)] ?: "ns"}:$local"
                        out.append(' ').append(key).append("=\"").append(esc(attrValue(buf, a, strings))).append('"')
                    }
                    out.append(">\n")
                    depth++
                }
                TYPE_END_ELEMENT -> {
                    depth = maxOf(depth - 1, 0)
                    out.append("  ".repeat(depth)).append("</")
                        .append(strings.str(buf.getInt(pos + 20))).append(">\n")
                }
            }
            pos += size
        }
        return out.toString()
    }

    private fun u16(buf: ByteBuffer, at: Int): Int = buf.getShort(at).toInt() and 0xFFFF

    private fun List<String>.str(i: Int): String = if (i < 0 || i >= size) "" else this[i]

    private fun readStrings(buf: ByteBuffer, pos: Int): List<String> {
        val count = buf.getInt(pos + 8)
        val flags = buf.getInt(pos + 16)
        val stringsStart = buf.getInt(pos + 20)
        val utf8 = (flags and UTF8_FLAG) != 0
        val offsetsAt = pos + u16(buf, pos + 2)
        val base = pos + stringsStart
        return List(count) { i ->
            val at = base + buf.getInt(offsetsAt + i * 4)
            if (utf8) readUtf8(buf, at) else readUtf16(buf, at)
        }
    }

    private fun readUtf8(buf: ByteBuffer, at: Int): String {
        var p = at
        // First length is the character count; skip it (two bytes when the high bit is set).
        p += if ((buf.get(p).toInt() and 0x80) != 0) 2 else 1
        var len = buf.get(p).toInt() and 0xFF
        p++
        if ((len and 0x80) != 0) {
            len = ((len and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF)
            p++
        }
        return String(buf.array(), p, len, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, at: Int): String {
        var p = at
        var len = u16(buf, p)
        p += 2
        if ((len and 0x8000) != 0) {
            len = ((len and 0x7FFF) shl 16) or u16(buf, p)
            p += 2
        }
        return String(buf.array(), p, len * 2, Charsets.UTF_16LE)
    }

    private fun attrValue(buf: ByteBuffer, a: Int, strings: List<String>): String {
        val raw = buf.getInt(a + 8)
        if (raw != NO_ENTRY) return strings.str(raw)
        val type = buf.get(a + 15).toInt() and 0xFF
        val data = buf.getInt(a + 16)
        return when (type) {
            0x03 -> strings.str(data)
            0x10 -> data.toString()
            0x11 -> "0x" + Integer.toHexString(data)
            0x12 -> if (data != 0) "true" else "false"
            0x01 -> "@0x" + String.format("%08x", data)
            0x1c, 0x1d, 0x1e, 0x1f -> String.format("#%08x", data)
            else -> "0x" + Integer.toHexString(data)
        }
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
