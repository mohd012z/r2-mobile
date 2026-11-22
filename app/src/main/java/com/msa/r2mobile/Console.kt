package com.msa.r2mobile

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View

/**
 * Command-prompt style console: every piece of text has a colour, so commands, output,
 * addresses, errors and advice are easy to tell apart. Only use from the UI thread.
 */
class Console(private val maxChars: Int = 1_000_000) {

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
        LOOP("#FF7AB2", true),        // jump back to an earlier address: a loop
        BRANCH("#82AAFF"),            // jump forward: an if, else or switch
        SAME_BLOCK("#C3E88D"),        // short jump ahead inside the same block
        CALL("#F78C6C", true),        // call: runs another function
        RETURN("#7FDBCA", true),      // ret: leaves the function
    }

    private val buf = SpannableStringBuilder()
    private val addressRx = Regex("0x[0-9a-fA-F]+")
    /** A disassembly line starts with its address. */
    private val lineAddrRx = Regex("^\\s*0x[0-9a-fA-F]+")
    /** Instruction names that change the flow of the program. */
    private val mnemonicRx = Regex(
        "\\s(ret|call|bl|jmp|je|jne|jz|jnz|ja|jae|jb|jbe|jg|jge|jl|jle|js|jns|cbz|cbnz|tbz|tbnz|b\\.[a-z]{2}|b)\\b"
    )

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

    /**
     * Writes radare2 output line by line. Every address is a tap target that calls [onAddress].
     * Disassembly lines are coloured by kind: loop, forward branch, same-block jump, call, return.
     */
    fun writeOutput(text: String, onAddress: (String) -> Unit = {}) {
        text.trimEnd('\n').lines().forEach { line ->
            val style = lineStyle(line)
            var last = 0
            addressRx.findAll(line).forEach { m ->
                if (m.range.first > last) write(style, line.substring(last, m.range.first), newline = false)
                writeTap(m.value, onAddress)
                last = m.range.last + 1
            }
            write(style, line.substring(last))
        }
    }

    private fun lineStyle(line: String): Style {
        if (!lineAddrRx.containsMatchIn(line)) return Style.OUTPUT
        val op = mnemonicRx.find(line)?.groupValues?.get(1) ?: return Style.OUTPUT
        if (op == "ret") return Style.RETURN
        if (op == "call" || op == "bl") return Style.CALL
        val addrs = addressRx.findAll(line).map { it.value }.toList()
        val here = addrs.firstOrNull()?.let(::parseAddr)
        val target = addrs.lastOrNull()?.let(::parseAddr)
        if (addrs.size < 2 || here == null || target == null) return Style.BRANCH
        return when {
            target < here -> Style.LOOP
            (target - here) in 0L..16L -> Style.SAME_BLOCK
            else -> Style.BRANCH
        }
    }

    private fun parseAddr(s: String): Long? = s.removePrefix("0x").toLongOrNull(16)

    /** An address that runs [onAddress] when tapped. */
    private fun writeTap(addr: String, onAddress: (String) -> Unit) {
        val start = buf.length
        buf.append(addr)
        buf.setSpan(
            ForegroundColorSpan(Color.parseColor(Style.ADDRESS.hex)),
            start, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        buf.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = onAddress(addr)
            override fun updateDrawState(ds: TextPaint) {
                ds.isUnderlineText = true
            }
        }, start, buf.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
