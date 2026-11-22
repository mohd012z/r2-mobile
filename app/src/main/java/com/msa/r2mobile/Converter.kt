package com.msa.r2mobile

import android.util.Base64
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Converters typed as  conv KIND VALUE  in the box. Pure functions: they return result lines,
 * or throw IllegalArgumentException with a message the user can act on.
 */
object Converter {

    val HELP = """
conv dec N         decimal -> hex, binary, octal, ASCII character
conv hex 0x1F      hex -> decimal, binary, octal
conv bin 1011      binary -> decimal, hex
conv b64e TEXT     text -> Base64
conv b64d BASE64   Base64 -> text and hex bytes
conv epoch N       Unix seconds -> UTC date and time
conv date 2024-01-31 12:00:00   UTC date and time -> Unix seconds
conv color #FF8800 hex colour -> RGB numbers and Android ARGB int
conv java java.lang.String      Java class name -> smali descriptor
conv smali Ljava/lang/String;   smali descriptor -> Java class name
conv now           current time in Unix seconds and UTC
""".trimIndent()

    private val UTC_TEXT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun run(args: String): List<String> {
        val parts = args.trim().split(Regex("\\s+"), limit = 2)
        val kind = parts.getOrElse(0) { "" }.lowercase()
        val value = parts.getOrElse(1) { "" }.trim()
        if (kind.isEmpty()) throw IllegalArgumentException("say which converter, e.g. conv hex 0x1F")
        return when (kind) {
            "dec" -> fromNumber(value.toLong(), "decimal")
            "hex" -> fromNumber(value.removePrefix("0x").removePrefix("0X").toLong(16), "hex")
            "bin" -> fromNumber(value.toLong(2), "binary")
            "b64e" -> {
                if (value.isEmpty()) throw IllegalArgumentException("give the text after b64e")
                listOf("base64 ${Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}")
            }
            "b64d" -> {
                val bytes = Base64.decode(value, Base64.DEFAULT)
                listOf(
                    "text ${String(bytes, Charsets.UTF_8)}",
                    "hex  ${bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }}",
                )
            }
            "epoch" -> {
                val secs = value.toLong()
                listOf("utc  ${UTC_TEXT.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(secs))}")
            }
            "date" -> {
                val when_ = LocalDateTime.parse(value, UTC_TEXT)
                listOf("epoch ${when_.toEpochSecond(ZoneOffset.UTC)}")
            }
            "color" -> {
                val clean = value.removePrefix("#")
                val argb = when (clean.length) {
                    6 -> 0xFF000000L or clean.toLong(16)
                    8 -> clean.toLong(16)
                    else -> throw IllegalArgumentException("colour must be #RRGGBB or #AARRGGBB")
                }
                val r = (argb shr 16) and 0xFFL
                val g = (argb shr 8) and 0xFFL
                val b = argb and 0xFFL
                listOf(
                    "rgb   $r, $g, $b",
                    "argb  0x%08X (Android Color.parseColor value: #%08X)".format(argb, argb),
                    "hex   #%02X%02X%02X".format(r, g, b),
                )
            }
            "java" -> listOf("smali ${javaToSmali(value)}")
            "smali" -> listOf("java  ${smaliToJava(value)}")
            "now" -> {
                val now = Instant.now().epochSecond
                listOf("epoch $now", "utc   ${UTC_TEXT.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(now))}")
            }
            else -> throw IllegalArgumentException("unknown converter '$kind'. Type conv help.")
        }
    }

    private fun fromNumber(n: Long, from: String): List<String> {
        val out = mutableListOf(
            "decimal $n",
            "hex     0x${java.lang.Long.toHexString(n).uppercase()}",
            "binary  ${java.lang.Long.toBinaryString(n)}",
            "octal   ${java.lang.Long.toOctalString(n)}",
        )
        if (n in 32L..126L) out += "ascii   '${n.toInt().toChar()}'"
        return out
    }

    private val PRIMITIVES = mapOf(
        "void" to "V", "boolean" to "Z", "byte" to "B", "short" to "S",
        "char" to "C", "int" to "I", "long" to "J", "float" to "F", "double" to "D",
    )

    /** java.lang.String -> Ljava/lang/String;   int[] -> [I   */
    fun javaToSmali(name: String): String {
        val trimmed = name.trim()
        val dims = trimmed.count { it == '[' }
        val base = trimmed.substringBefore('[').trim()
        val desc = PRIMITIVES[base] ?: "L${base.replace('.', '/')};"
        return "[".repeat(dims) + desc
    }

    /** Ljava/lang/String; -> java.lang.String   [I -> int[] */
    fun smaliToJava(desc: String): String {
        val trimmed = desc.trim()
        val dims = trimmed.takeWhile { it == '[' }.length
        val rest = trimmed.drop(dims)
        val base = PRIMITIVES.entries.firstOrNull { it.value == rest }?.key
            ?: if (rest.startsWith("L") && rest.endsWith(";")) rest.substring(1, rest.length - 1).replace('/', '.')
            else throw IllegalArgumentException("not a smali type. Example: Ljava/lang/String;")
        return base + "[]".repeat(dims)
    }
}
