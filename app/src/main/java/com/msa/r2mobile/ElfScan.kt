package com.msa.r2mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ELF security scan engine — the "ELFTrace method" implemented natively on top of radare2.
 *
 * A scanner loads JSON plugins (bundled in the assets plugins dir) and runs them against a
 * loaded ELF (.so). Two rule kinds:
 *   - declarative `rules[]`  : {type: symbol|string|byte_pattern, pattern, severity,
 *                              [case_sensitive, match_mode, patch{mode,value,offset}]}
 *   - `script` plugins        : transcribed to Kotlin (the 7 ELFTrace Rhai scripts — LIAPP,
 *                              direct syscalls, packers, secrets, JNI table, RegisterNatives,
 *                              JNI_OnLoad calls, watchdog/abort, anti-debug xrefs).
 *
 * Everything reads through radare2 (`is`/`ii`/`iz`/`iSS`/`pd`/`axt`) so the app stays
 * on its existing engine — no new native lib. See ELFTRACE_STUDY.md for the source study.
 */
object ElfScan {

    // ------------------------------------------------------------------ data model

    data class Sym(val name: String, val vaddr: Long, val paddr: Long, val size: Long)
    data class Str(val value: String, val vaddr: Long, val paddr: Long, val section: String)
    data class Section(val name: String, val paddr: Long, val size: Long, val vaddr: Long,
                       val vsize: Long, val perm: String, val type: String)

    data class PatchSuggestion(val mode: String, val value: String, val offset: Int)

    data class Finding(
        val plugin: String,
        val rule: String,
        val severity: String,      // INFO | LOW | MEDIUM | HIGH
        val vaddr: Long,           // 0 when unknown
        val offset: Long,          // file offset (byte_pattern / strings)
        val description: String,
        val matched: String,
        val symbol: String,
        val ruleType: String,      // symbol | string | byte_pattern | script
        val patch: PatchSuggestion?,
    ) {
        fun sevRank(): Int = when (severity.uppercase()) {
            "HIGH" -> 3; "MEDIUM" -> 2; "LOW" -> 1; else -> 0
        }
    }

    data class ScanResult(
        val arch: String,
        val bits: Int,
        val plugins: Int,
        val rulesRun: Int,
        val findings: List<Finding>,
        val elapsedMs: Long,
    ) {
        fun high(): List<Finding> = findings.filter { it.severity == "HIGH" }
        fun patchable(): List<Finding> = findings.filter { it.patch != null }
    }

    data class PluginSpec(
        val id: String,
        val name: String,
        val arches: List<String>,     // empty = all
        val rules: List<Rule>,
        val script: String,
    )
    data class Rule(
        val name: String,
        val type: String,             // symbol | string | byte_pattern
        val pattern: String,
        val severity: String,
        val description: String,
        val caseSensitive: Boolean,
        val matchMode: String,        // substring | word_boundary
        val patch: PatchSuggestion?,
    )

    /** Loads every plugin JSON in assets/plugins. [assetsOpen] returns the raw text of each file. */
    fun loadPlugins(openAll: () -> List<Pair<String, String>>): List<PluginSpec> {
        val out = mutableListOf<PluginSpec>()
        for ((fname, text) in openAll().sortedBy { it.first }) {
            try {
                val o = JSONObject(text)
                val rules = mutableListOf<Rule>()
                val rarr = o.optJSONArray("rules")
                if (rarr != null) {
                    for (i in 0 until rarr.length()) {
                        val r = rarr.getJSONObject(i)
                        val p = r.optJSONObject("patch")
                        rules += Rule(
                            name = r.optString("name", "rule$i"),
                            type = r.optString("type", "string"),
                            pattern = r.optString("pattern", ""),
                            severity = r.optString("severity", "INFO"),
                            description = r.optString("description", ""),
                            caseSensitive = if (r.has("case_sensitive")) r.optBoolean("case_sensitive") else false,
                            matchMode = r.optString("match_mode", "substring"),
                            patch = if (p != null)
                                PatchSuggestion(p.optString("mode", "quick"), p.optString("value", ""),
                                    p.optInt("offset", 0)) else null,
                        )
                    }
                }
                out += PluginSpec(
                    id = o.optString("id", fname.removeSuffix(".json")),
                    name = o.optString("name", o.optString("id", fname)),
                    arches = o.optJSONArray("arch")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    rules = rules,
                    script = o.optString("script", ""),
                )
            } catch (e: Exception) {
                // skip a malformed plugin rather than abort the whole scan
            }
        }
        return out
    }

    // ------------------------------------------------------------------ r2 gather

    private data class Gather(
        val symbols: List<Sym>,   // exports (is)
        val imports: List<Sym>,   // ii
        val strings: List<Str>,
        val sections: List<Section>,
    )

    private fun parseSymbols(text: String): List<Sym> {
        val out = ArrayList<Sym>()
        for (line in text.lines()) {
            // "is" export:  "82  0x006d0484 0x006d1484 GLOBAL FUNC   336      Java_..."
            //   nth paddr  vaddr    bind   type  size lib name
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("nth") || t.startsWith("---")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 7) continue
            if (!parts[1].startsWith("0x")) continue
            val name = parts.subList(6, parts.size).joinToString(" ")
            if (name.isBlank()) continue
            out += Sym(
                name = name,
                vaddr = parts[2].toLongOrNull(16) ?: 0L,
                paddr = parts[1].toLongOrNull(16) ?: 0L,
                size = parts[5].toLongOrNull(16) ?: 0L,
            )
        }
        return out
    }

    private fun parseImports(text: String): List<Sym> {
        val out = ArrayList<Sym>()
        for (line in text.lines()) {
            // "ii" import:  "38  0x00a149c0 GLOBAL FUNC       open"   (vaddr only, no size)
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("nth") || t.startsWith("---")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 5) continue
            if (!parts[1].startsWith("0x")) continue
            val name = parts.last()
            if (name.isBlank()) continue
            out += Sym(name = name, vaddr = parts[1].toLongOrNull(16) ?: 0L,
                paddr = parts[1].toLongOrNull(16) ?: 0L, size = 0L)
        }
        return out
    }

    private fun parseStrings(text: String): List<Str> {
        val out = ArrayList<Str>()
        for (line in text.lines()) {
            // "11759 0x0026df03 0x0026df03 100 101 .rodata ascii /home/..."
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("nth") || t.startsWith("---")) continue
            val m = Regex("^(\\d+)\\s+(0x[0-9a-fA-F]+)\\s+(0x[0-9a-fA-F]+)\\s+\\d+\\s+\\d+\\s+(\\S+)\\s+\\S+\\s+(.*)$")
                .find(t) ?: continue
            val (_, paddr, vaddr, section, value) = m.destructured
            if (value.isBlank()) continue
            out += Str(value = value, vaddr = vaddr.toLong(16), paddr = paddr.toLong(16), section = section)
        }
        return out
    }

    private fun parseSections(text: String): List<Section> {
        val out = ArrayList<Section>()
        for (line in text.lines()) {
            // "0  0x00000040  0x230 0x00000040 0x230 -r-- 0x0   MAP  PHDR"
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("nth") || t.startsWith("---")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 9) continue
            if (!parts[1].startsWith("0x")) continue
            out += Section(
                name = parts[8], paddr = parts[1].toLongOrNull(16) ?: 0,
                size = parts[2].toLongOrNull(16) ?: 0, vaddr = parts[3].toLongOrNull(16) ?: 0,
                vsize = parts[4].toLongOrNull(16) ?: 0, perm = parts[5], type = parts[7],
            )
        }
        return out
    }

    /** Read arch/bits straight from the ELF header (most reliable; r2 "arch arm" hides 64 vs 32). */
    fun elfArch(file: File): Pair<String, Int> {
        return try {
            val b = file.readBytes()
            if (b.size < 20 || b[0].toInt() != 0x7f || b[1].toInt() != 'E'.code) return "unknown" to 0
            val cls = b[4].toInt()      // 1 = 32, 2 = 64
            val bits = if (cls == 2) 64 else 32
            val em = if (cls == 2) (b[18].toInt() or (b[19].toInt() shl 8)) else (b[16].toInt() or (b[17].toInt() shl 8))
            val arch = when (em) {
                0xB7 -> "aarch64"; 0x28 -> "arm"; 0x3E -> "x86_64"; 0x03 -> "x86"
                0x0C -> "mips"; else -> "e$em"
            }
            arch to bits
        } catch (e: Exception) { "unknown" to 0 }
    }

    // ------------------------------------------------------------------ run scan

    /**
     * @param r2      run one radare2 command against [file], return full output.
     * @param analyze when true, run `aa` once (needed only by xref-based plugins).
     */
    fun scan(file: File, assetsOpen: () -> List<Pair<String, String>>, r2: (String) -> String): ScanResult {
        val t0 = System.currentTimeMillis()
        val (arch, bits) = elfArch(file)
        val plugins = loadPlugins(assetsOpen).filter { it.arches.isEmpty() || it.arches.contains(arch) }

        val symText = r2("is")
        val impText = r2("ii")
        val strText = r2("iz")
        val secText = r2("iSS")
        val g = Gather(parseSymbols(symText), parseImports(impText), parseStrings(strText), parseSections(secText))

        // one shared analysis pass, only if an xref-based plugin has a target present
        val xrefTargets = listOf("inotify_rm_watch", "dl_iterate_phdr", "fork", "prctl",
            "pthread_create", "abort", "kill", "raise", "_exit", "exit", "ptrace")
        val needXref = xrefTargets.any { t -> g.imports.any { it.name == t } }
        if (needXref) { try { r2("aa") } catch (e: Exception) { } }

        val findings = ArrayList<Finding>()
        var rulesRun = 0

        for (p in plugins) {
            // declarative rules
            for (r in p.rules) {
                rulesRun++
                when (r.type) {
                    "symbol" -> matchSymbol(r, p, g.symbols, g.imports, findings)
                    "string" -> matchString(r, p, g.strings, findings)
                    "byte_pattern" -> matchBytes(r, p, file, findings)
                }
            }
            // script plugins (Kotlin transcriptions)
            when (p.id) {
                "builtin-liapp" -> scriptLiapp(p, g, r2, findings)
                "builtin-direct-syscalls" -> scriptSyscalls(p, file, g, r2, arch, findings)
                "builtin-packers" -> scriptPackers(p, g, file, findings)
                "builtin-rhai-secrets" -> scriptSecrets(p, g.strings, findings)
                "builtin-rhai-jni-table" -> scriptJniTable(p, g, findings)
                "builtin-rhai-register-natives" -> scriptRegisterNatives(p, g, r2, findings)
                "builtin-rhai-jni-inspector" -> scriptJniOnLoadCalls(p, g, r2, findings)
                "builtin-rhai-watchdog" -> scriptWatchdog(p, g, r2, arch, findings)
                "builtin-rhai-xref-tracer" -> scriptAntiDebugXrefs(p, g, r2, findings)
            }
        }

        findings.sortWith(compareByDescending<Finding> { it.sevRank() }.thenBy { it.rule })
        return ScanResult(arch, bits, plugins.size, rulesRun, findings, System.currentTimeMillis() - t0)
    }

    @Suppress("unused")
    private val XREF_PLUGINS = setOf("builtin-liapp", "builtin-rhai-watchdog", "builtin-rhai-xref-tracer")

    // ------------------------------------------------------------------ declarative matchers

    private fun matchSymbol(r: Rule, p: PluginSpec, symbols: List<Sym>, imports: List<Sym>, out: MutableList<Finding>) {
        val needle = r.pattern
        val hit = (symbols + imports).firstOrNull { s ->
            if (r.caseSensitive) s.name.contains(needle) else s.name.contains(needle, true)
        } ?: return
        out += Finding(p.name, r.name, r.severity, hit.vaddr, hit.paddr, r.description,
            needle, hit.name, "symbol", r.patch)
    }

    private fun matchString(r: Rule, p: PluginSpec, strings: List<Str>, out: MutableList<Finding>) {
        val needle = r.pattern
        var count = 0
        for (s in strings) {
            if (count >= 8) break
            val ok = when {
                r.caseSensitive && r.matchMode == "word_boundary" ->
                    s.value == needle || Regex("\\b" + Regex.escape(needle) + "\\b").containsMatchIn(s.value)
                r.matchMode == "word_boundary" ->
                    Regex("(?i)\\b" + Regex.escape(needle) + "\\b").containsMatchIn(s.value)
                r.caseSensitive -> s.value.contains(needle)
                else -> s.value.contains(needle, true)
            }
            if (ok) {
                out += Finding(p.name, r.name, r.severity, s.vaddr, s.paddr, r.description,
                    s.value.take(120), "", "string", r.patch)
                count++
            }
        }
    }

    private fun matchBytes(r: Rule, p: PluginSpec, file: File, out: MutableList<Finding>) {
        val pat = r.pattern.trim().split(Regex("[\\s,]+")).filter { it.length == 2 }.map { it.toByteOrNull(16) ?: return }.toByteArray()
        if (pat.isEmpty()) return
        val data = file.readBytes()
        var off = 0
        var count = 0
        outer@ while (off <= data.size - pat.size) {
            for (i in pat.indices) if (data[off + i] != pat[i]) { off++; continue@outer }
            out += Finding(p.name, r.name, r.severity, off.toLong(), off.toLong(), r.description,
                r.pattern, "", "byte_pattern", r.patch)
            off += pat.size
            if (++count >= 8) return
        }
    }

    // ------------------------------------------------------------------ script transcriptions

    private fun scriptLiapp(p: PluginSpec, g: Gather, r2: (String) -> String, out: MutableList<Finding>) {
        val liapp = g.strings.any { it.value.contains("libliapp.so", true) }
        if (!liapp) return
        out += Finding(p.name, "LIAPP Identifier", "HIGH", 0, 0, "Lockin LIAPP loader present", "libliapp.so", "", "script", null)
        for (s in g.symbols + g.imports) {
            if (s.name.startsWith("init_array_"))
                out += Finding(p.name, "LIAPP Loader Entry (${s.name})", "HIGH", s.vaddr, s.paddr,
                    "Constructor in .init_array (unpacks DEX & starts checks)", s.name, s.name, "script", null)
        }
        for (api in listOf("inotify_rm_watch", "dl_iterate_phdr", "fork", "prctl")) {
            val imp = g.imports.firstOrNull { it.name == api } ?: continue
            val xrefs = parseXrefs(r2("axt @0x" + imp.vaddr.toString(16)))
            if (xrefs.isEmpty())
                out += Finding(p.name, "LIAPP Import: $api", "MEDIUM", imp.vaddr, imp.paddr,
                    "PLT stub (called indirectly or via wrapper)", api, api, "script", null)
            for (x in xrefs.take(6))
                out += Finding(p.name, "LIAPP Calls $api", "HIGH", x.from, x.from,
                    "xref ${x.instr}", api, api, "script", quickPatch("nop", 0))
        }
    }

    private fun scriptSyscalls(p: PluginSpec, file: File, g: Gather, r2: (String) -> String, arch: String, out: MutableList<Finding>) {
        // raw svc pattern in the file bytes, then confirm via r2 disasm
        val pat = when (arch) {
            "aarch64" -> byteArrayOf(0x01, 0x00, 0x00, 0xD4.toByte())
            "arm" -> byteArrayOf(0x00, 0x00, 0x00, 0xEF.toByte())
            else -> return
        }
        val data = file.readBytes()
        var off = 0
        var count = 0
        while (off + pat.size <= data.size && count < 40) {
            if (off % 4 == 0 && data[off] == pat[0] && data[off+1] == pat[1] && data[off+2] == pat[2] && data[off+3] == pat[3]) {
                // baddr 0 for PIE .so -> paddr == vaddr
                val vaddr = off
                val ins = r2("pd 1 @0x" + vaddr.toString(16)).trim().lines().firstOrNull { it.isNotBlank() } ?: ""
                val mnem = ins.substringAfter(' ', "").substringBefore(' ')
                if (arch == "aarch64" && mnem == "svc") {
                    // try to decode the syscall nr from a preceding mov to w8/x8
                    val nr = decodeSyscallNr(r2("pd 8 @0x" + (vaddr - 24).toString(16)), arch)
                    out += Finding(p.name, "Direct syscall ($nr)", "HIGH", vaddr.toLong(), off.toLong(),
                        "svc #0 bypasses libc hooks${if (nr >= 0) " — syscall nr $nr (${sysName(nr)})" else ""}",
                        ins.trim().take(60), "", "script", null)
                    count++
                }
                off += 4
            } else off++
        }
    }

    private fun decodeSyscallNr(disasmText: String, arch: String): Int {
        for (line in disasmText.lines().reversed()) {
            val t = line.trim()
            if (t.contains("svc")) continue
            val m = Regex("(mov(z)?\\s+(w8|x8|r7|eax)\\s*,\\s*(?:#)?(0x[0-9a-fA-F]+|\\d+))").find(t) ?: continue
            val imm = m.groupValues[4]
            return if (imm.startsWith("0x")) imm.toLong(16).toInt() else imm.toIntOrNull() ?: -1
        }
        return -1
    }

    private fun sysName(nr: Int): String = when (nr) {
        0 -> "read"; 1 -> "write"; 2 -> "open"; 3 -> "close"; 57 -> "fstat"; 60 -> "exit"; 61 -> "exit_group"
        102 -> "mmap"; 105 -> "munmap"; 107 -> "kill"; 108 -> "clone"; 110 -> "rt_sigaction"; 113 -> "rt_sigreturn"
        158 -> "arch_specific_syscall"; 218 -> "futex"; 228 -> "epoll_wait"; 270 -> "set_robust_list"; 274 -> "semtimedop"
        else -> "unknown"
    }

    private fun scriptPackers(p: PluginSpec, g: Gather, file: File, out: MutableList<Finding>) {
        // DPLF magic as a byte pattern
        matchBytes(Rule("DPLF", "byte_pattern", "44 50 4C 46", "HIGH", "Embedded DPLF packed-library magic",
            false, "substring", null), p, file, out)
        val sigs = listOf(
            "libjiagu" to "360 Jiagu packer", "libshell" to "Tencent Legu packer stub",
            "libsecexe" to "Bangcle / SecNeo packer", "libDexHelper" to "Alibaba DexHelper",
            "libmobisec" to "Alibaba MobiSec", "libnesec" to "NetEase Sec",
            "libbaiduprotect" to "Baidu Protect", "DexClassLoader" to "ART dynamic DEX loading",
        )
        for ((sig, desc) in sigs) {
            for (s in g.strings.filter { it.value.contains(sig, true) }.take(3))
                out += Finding(p.name, "${desc} indicator", "HIGH", s.vaddr, s.paddr,
                    "Packer/protector reference: $sig", s.value.take(80), "", "script", null)
        }
        for (s in (g.symbols + g.imports).filter { it.name == "ExecuteProgram" }.take(3))
            out += Finding(p.name, "PairIP VM Entrypoint (ExecuteProgram)", "HIGH", s.vaddr, s.paddr,
                "PairIP bytecode VM interpreter called by VMRunner", s.name, s.name, "script", null)
    }

    private fun scriptSecrets(p: PluginSpec, strings: List<Str>, out: MutableList<Finding>) {
        val exact = listOf("sk_live_", "sk_test_", "AKIA", "AIza", "PRIVATE KEY", "Bearer ")
        for (k in exact) {
            for (s in strings.filter { it.value.contains(k) }.take(3))
                if (s.value.length <= 200)
                    out += Finding(p.name, "Secret key ($k)", "HIGH", s.vaddr, s.paddr,
                        "Hardcoded credential material", s.value.take(80), "", "script", null)
        }
        for (k in listOf("api_key", "apikey")) {
            for (s in strings.filter { it.value.length <= 128 && it.value.contains(k, true) && (it.value.contains('=') || it.value.contains(':')) }.take(3))
                out += Finding(p.name, "Possible key ($k)", "MEDIUM", s.vaddr, s.paddr,
                    "Possible API key assignment", s.value.take(80), "", "script", null)
        }
    }

    private fun scriptJniTable(p: PluginSpec, g: Gather, out: MutableList<Finding>) {
        for (s in g.symbols.filter { it.name.startsWith("Java_") }.take(12))
            out += Finding(p.name, "JNI: ${shortJni(s.name)}", "HIGH", s.vaddr, s.paddr,
                "Exported JNI method", s.name, s.name, "script", null)
    }

    private fun shortJni(name: String): String =
        name.substringAfter("Java_").replace('_', ' ').take(40)

    private fun scriptRegisterNatives(p: PluginSpec, g: Gather, r2: (String) -> String, out: MutableList<Finding>) {
        val on = (g.symbols + g.imports).firstOrNull { it.name == "JNI_OnLoad" } ?: return
        val dis = r2("pd 512 @0x" + on.vaddr.toString(16))
        for (line in dis.lines()) {
            val t = line.trim()
            if (t.contains("0x6b8") || t.contains("0x35c"))
                out += Finding(p.name, "RegisterNatives VTable", "HIGH", 0, 0,
                    "JNI vtable offset in JNI_OnLoad: " + t.take(60), t.take(60), "", "script", null)
        }
        for (s in g.strings.filter { it.value.contains("RegisterNatives") }.take(3))
            out += Finding(p.name, "RegisterNatives String", "INFO", s.vaddr, s.paddr,
                "RegisterNatives reference", s.value.take(60), "", "script", null)
    }

    private fun scriptJniOnLoadCalls(p: PluginSpec, g: Gather, r2: (String) -> String, out: MutableList<Finding>) {
        val on = (g.symbols + g.imports).firstOrNull { it.name == "JNI_OnLoad" } ?: return
        out += Finding(p.name, "JNI_OnLoad Entry", "INFO", on.vaddr, on.paddr,
            "Native init entrypoint", "JNI_OnLoad", "JNI_OnLoad", "script", null)
        val dis = r2("pd 128 @0x" + on.vaddr.toString(16))
        for (line in dis.lines()) {
            val t = line.trim()
            if (t.isEmpty() || !t.startsWith("0x")) continue
            val mn = t.substringAfter(' ', "").substringBefore(' ')
            if (mn == "bl" || mn == "blr" || mn == "call")
                out += Finding(p.name, "JNI_OnLoad Call", "INFO", 0, 0,
                    t.take(60), t.take(60), "", "script", null)
        }
    }

    private fun scriptWatchdog(p: PluginSpec, g: Gather, r2: (String) -> String, arch: String, out: MutableList<Finding>) {
        for (t in listOf("pthread_create", "abort", "kill", "raise", "_exit", "exit")) {
            val imp = g.imports.firstOrNull { it.name == t } ?: continue
            val xrefs = parseXrefs(r2("axt @0x" + imp.vaddr.toString(16)))
            val sev = if (t == "pthread_create") "INFO" else "HIGH"
            for (x in xrefs.take(8)) {
                val canNop = (t in setOf("kill", "raise", "_exit", "exit")) && nextIsTerminator(r2, x.from, arch)
                if (canNop)
                    out += Finding(p.name, "Calls $t (NOP-able)", "HIGH", x.from, x.from,
                        "Terminates the process; next insn is a branch/ret", x.instr, t, "script", quickPatch("nop", 0))
                else
                    out += Finding(p.name, "Calls $t", sev, x.from, x.from,
                        x.instr, x.instr, t, "script", null)
            }
        }
    }

    private fun scriptAntiDebugXrefs(p: PluginSpec, g: Gather, r2: (String) -> String, out: MutableList<Finding>) {
        for (t in listOf("ptrace", "prctl")) {
            val imp = g.imports.firstOrNull { it.name == t } ?: continue
            for (x in parseXrefs(r2("axt @0x" + imp.vaddr.toString(16))).take(8))
                out += Finding(p.name, "Caller of $t", "HIGH", x.from, x.from,
                    "Anti-debug call site — NOP candidate", x.instr, t, "script", quickPatch("nop", 0))
        }
    }

    private fun nextIsTerminator(r2: (String) -> String, addr: Long, arch: String): Boolean {
        val next = r2("pd 3 @0x" + (addr + if (arch == "arm" || arch == "x86") 4 else 4)).trim().lines().firstOrNull { it.isNotBlank() } ?: return false
        val mn = next.substringAfter(' ', "").substringBefore(' ')
        return mn in setOf("ret", "b", "jmp", "bx") || next.contains("bx lr", true) || next.contains("pop {", true)
    }

    private fun quickPatch(value: String, offset: Int) = PatchSuggestion("quick", value, offset)

    // ------------------------------------------------------------------ xref parse

    private data class Xref(val from: Long, val instr: String)
    private fun parseXrefs(text: String): List<Xref> {
        val out = ArrayList<Xref>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("from") || t.startsWith("to") || t.startsWith("---")) continue
            val m = Regex("^(0x[0-9a-fA-F]+)\\s+\\d+\\s+.*?(\\S.*)$").find(t) ?: continue
            val from = m.groupValues[1].toLongOrNull(16) ?: continue
            out += Xref(from, m.groupValues[2].take(60))
        }
        return out
    }

    // ------------------------------------------------------------------ quick patch bytes

    object QuickPatch {
        /** Encode a quick-patch value for [arch]/[bits]. Returns raw bytes or null if unsupported. */
        fun bytes(value: String, arch: String, bits: Int): ByteArray? = when (value) {
            "ret_true", "return_true", "true" -> when (arch) {
                "aarch64" -> le16(0x52800020L) + le32(0xD65F03C0L)   // mov w0,#1; ret
                "arm" -> le16(0x0420L) + le16(0x7047L)               // movs r0,#1; bx lr
                "x86_64" -> byteArrayOf(0xB8.toByte(), 1, 0, 0, 0xC3.toByte())
                "x86" -> byteArrayOf(0xB8.toByte(), 1, 0xC3.toByte())
                else -> null
            }
            "ret_false", "return_false", "false" -> when (arch) {
                "aarch64" -> le16(0x52800000L) + le32(0xD65F03C0L)   // mov w0,#0; ret
                "arm" -> le16(0x0020L) + le16(0x7047L)               // movs r0,#0; bx lr
                "x86_64" -> byteArrayOf(0xB8.toByte(), 0, 0, 0, 0xC3.toByte())
                "x86" -> byteArrayOf(0xB8.toByte(), 0, 0xC3.toByte())
                else -> null
            }
            "ret_void", "return_void", "void" -> when (arch) {
                "aarch64" -> le32(0xD65F03C0L)
                "arm" -> le16(0x7047L)
                "x86_64", "x86" -> byteArrayOf(0xC3.toByte())
                else -> null
            }
            "nop" -> when (arch) {
                "aarch64" -> le32(0xD503201FL)
                "arm" -> le16(0xBFL)
                "x86_64", "x86" -> byteArrayOf(0x90.toByte())
                else -> null
            }
            else -> null
        }
        private fun le16(v: Long) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        private fun le32(v: Long) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        fun toHex(b: ByteArray) = b.joinToString(" ") { String.format("%02X", it) }
    }

    // ------------------------------------------------------------------ Frida hook generator

    fun fridaHook(module: String, vaddr: Long, onEnter: String = "console.log('[*] hit " + module + " + 0x" + vaddr.toString(16) + "')") =
        """
        var base = Module.findBaseAddress("$module");
        if (base) {
            var target = base.add(0x${vaddr.toString(16)});
            Interceptor.attach(target, {
                onEnter(args) { $onEnter },
                onLeave(ret) { console.log('[*] ret = ' + ret); }
            });
            console.log("[*] Attached hook to $module + 0x${vaddr.toString(16)}");
        } else {
            console.log("[-] Module $module not found");
        }
        """.trimIndent()
    // end object ElfScan
}
