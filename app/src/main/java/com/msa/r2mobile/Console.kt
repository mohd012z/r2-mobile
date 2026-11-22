package com.msa.r2mobile

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Command-prompt style console: every piece of text has a colour, so commands, output,
 * addresses, errors and advice are easy to tell apart. Only use from the UI thread.
 */
class Console(private val maxChars: Int = 200_000) {

    enum class Style(val hex: String, val bold: Boolean = false) {
        PROMPT("#00E676", true),   // "r2>" prompt
        COMMAND("#FFFFFF", true),  // the command you ran
        OUTPUT("#D4D4D4"),         // normal result text
        ADDRESS("#FFD166"),        // 0x... addresses
        HEADER("#C586C0", true),   // section titles
        ERROR("#F44747", true),
        WARN("#FFCC66", true),
        OK("#6A9955", true),
        ADVICE("#4FC1FF", true),   // "NEXT:" guidance
        INFO("#9CDCFE"),
    }

    private val buf = SpannableStringBuilder()
    private val addressRx = Regex("0x[0-9a-fA-F]+")

    fun write(style: Style, text: String, newline: Boolean = true) {
        val start = buf.length
        buf.append(text)
        if (buf.length > start) {
            buf.setSpan(
                ForegroundColorSpan(Color.parseColor(style.hex)),
                start, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (style.bold) {
                buf.setSpan(StyleSpan(Typeface.BOLD), start, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (newline) buf.append('\n')
        trim()
    }

    /** Writes radare2 output line by line, colouring addresses so they stand out. */
    fun writeOutput(text: String) {
        text.trimEnd('\n').lines().forEach { line ->
            var last = 0
            addressRx.findAll(line).forEach { m ->
                write(Style.OUTPUT, line.substring(last, m.range.first), newline = false)
                write(Style.ADDRESS, m.value, newline = false)
                last = m.range.last + 1
            }
            write(Style.OUTPUT, line.substring(last))
        }
    }

    fun clear() {
        buf.clear()
        buf.clearSpans()
    }

    fun snapshot(): CharSequence = SpannableStringBuilder(buf)

    fun plainText(): String = buf.toString()

    /** Keeps memory and rendering fast: drops the oldest lines once the limit is passed. */
    private fun trim() {
        if (buf.length <= maxChars) return
        val nl = buf.indexOf('\n', buf.length - maxChars)
        val cut = if (nl < 0) buf.length else nl + 1
        buf.delete(0, cut)
        val note = "[older output removed to keep the app fast]\n"
        buf.insert(0, note)
        buf.setSpan(
            ForegroundColorSpan(Color.parseColor(Style.WARN.hex)),
            0, note.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}
