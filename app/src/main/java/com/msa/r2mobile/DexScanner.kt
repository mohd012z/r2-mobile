package com.msa.r2mobile

import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/**
 * Finds the ROOT CODE that decides ads, VIP, premium, pro, trial and licence in an APK.
 *
 * "Root code" = the Dalvik method whose name OR whose const-string references match a
 * monetisation signal. We walk the DEX index tables directly (string_ids, type_ids,
 * method_ids, class_defs, class_data, code_item) and collect the string constants each
 * method actually references, so a hit points at a real method the user can open in radare2.
 *
 * Pure Kotlin, read-only, offline. No dex library is needed. The byte layout was verified
 * against androguard 4.1.4 on real DEX: string/type/method/class counts, per-class method
 * code offsets and method names all match exactly.
 *
 * The app's own logic lives in DEX (Java/Kotlin). When this finds little, the code is in a
 * native .so (use 31), a WebView/JS bundle, or a packed DEX (see the identify guide).
 */
object DexScanner {

    /** One ranked hit: a method that is (very likely) the ads/VIP/premium gate. */
    data class Hit(
        val score: Int,
        val className: String,
        val methodName: String,
        val codeOff: Int,
        val why: List<String>,
        val strings: List<String>,
        val dexName: String,
    )

    /** Aggregate result for an APK (or single DEX): stats + ranked hits + the raw string hits. */
    class Result(
        val dexFiles: List<String>,
        val totalStrings: Int,
        val totalClasses: Int,
        val totalMethods: Int,
        val hits: List<Hit>,
        val stringHits: List<String>,
        val elapsedMs: Long,
    )

    private const val MAX_DEX_BYTES = 256L * 1024 * 1024
    private const val MAX_STRING_REPORT = 400
    private const val MAX_HIT_REPORT = 200
    /** Hits scoring below this are not shown as root code. */
    const val MIN_SCORE = 4

    // ---- keyword weights ----------------------------------------------------
    // Name match (method or class) is the strongest signal; a const-string reference is
    // weaker. Single short words like "pro"/"key" are too broad to score on their own.
    private val NAME_KW = linkedMapOf(
        "vip" to 6, "isvip" to 6, "_vip" to 6, "vip_" to 6, "vipstatus" to 6, "is_vip" to 6,
        "premium" to 5, "ispremium" to 5, "_premium" to 5,
        "ispro" to 6, "is_pro" to 6, "_pro" to 4, "pro_" to 3,
        "trial" to 4, "istrial" to 5, "is_trial" to 5,
        "license" to 5, "licence" to 5, "islicensed" to 5,
        "subscri" to 5, "subscription" to 5, "is_subscribed" to 5,
        "purchase" to 4, "buy_vip" to 6, "paywall" to 5, "unlock" to 3,
        "entitl" to 4, "upgrade" to 3,
    )
    private val ADS_KW = linkedMapOf(
        "admob" to 8, "interstitial" to 8, "showad" to 8, "hidead" to 8, "removead" to 8,
        "showbanner" to 8, "rewarded" to 7, "loadad" to 7, "applovin" to 7, "ironsource" to 7,
        "vungle" to 7, "chartboost" to 7, "adsense" to 7, "unityads" to 8, "onadshowed" to 8,
        "banner" to 5, "adview" to 6, "adnetwork" to 5, "adsdk" to 6, "mediation" to 5,
        "monetiz" to 5, "onadfailed" to 6,
    )
    private val STR_KW = linkedMapOf(
        "vip" to 4, "premium" to 4, "is_vip" to 4, "is_pro" to 4, "pro_plan" to 4,
        "trial" to 3, "license" to 4, "licence" to 4, "subscri" to 4, "entitlement" to 3,
        "admob" to 5, "interstitial" to 5, "rewarded" to 5, "banner" to 3, "purchase" to 3,
        "upgrade" to 2, "paywall" to 4, "show_vip" to 3,
    )
    // Framework / SDK prefixes whose "vip"/"pro" is usually unrelated to the app's gate.
    private val FW_PREFIXES = listOf(
        "androidx/", "android/", "kotlin/", "java/", "com/google/gson", "com/google/android",
        "com/facebook", "com/bumptech", "com/airbnb", "retrofit2", "okhttp3", "org/json",
        "org/w3c", "com/unity3d", "com/ironsource", "com/applovin", "com/vungle",
        "com/chartboost", "com/adjust", "net/i2p/", "com/bytedance", "com/jakewharton",
        "androidx/billing", "com/google/android/billing",
    )

    private val DEX_NAME = Regex("classes\\d*\\.dex")

    // ---- entry points -------------------------------------------------------

    /** Scan every classes*.dex inside an APK (a ZIP). */
    fun scanApk(apk: File, progress: (String) -> Unit = {}): Result {
        val start = System.currentTimeMillis()
        val hits = ArrayList<Hit>()
        val stringHits = ArrayList<String>()
        var strings = 0; var classes = 0; var methods = 0
        val dexNames = ArrayList<String>()
        ZipFile(apk).use { zf ->
            val dexEntries = Collections.list(zf.entries())
                .filter { DEX_NAME.matches(it.name) && it.size <= MAX_DEX_BYTES }
                .sortedBy { dexOrder(it.name) }
            dexNames += dexEntries.map { it.name }
            for (e in dexEntries) {
                progress("reading ${e.name}")
                val bytes = zf.getInputStream(e).use { it.readBytes() }
                try {
                    val r = scanDex(bytes, e.name)
                    strings += r.strings; classes += r.classes; methods += r.methods
                    hits += r.hits
                    stringHits += r.stringHits
                } catch (ex: Exception) {
                    hits += Hit(0, "${e.name}", "(unreadable)", 0,
                        listOf("dex read failed: ${ex.message}"), listOf(), e.name)
                }
            }
        }
        if (dexNames.isEmpty()) {
            hits += Hit(0, "(no classes*.dex in this APK)", "-", 0,
                listOf("no dex"), listOf(), "-")
        }
        hits.sortByDescending { it.score }
        return Result(dexNames, strings, classes, methods,
            hits.take(MAX_HIT_REPORT), stringHits.take(MAX_STRING_REPORT),
            System.currentTimeMillis() - start)
    }

    /** Scan a single DEX file. */
    fun scanDexFile(file: File): Result {
        val start = System.currentTimeMillis()
        if (file.length() > MAX_DEX_BYTES) throw java.io.IOException("DEX larger than 256 MB")
        val r = scanDex(file.readBytes(), file.name)
        return Result(listOf(file.name), r.strings, r.classes, r.methods,
            r.hits.take(MAX_HIT_REPORT), r.stringHits.take(MAX_STRING_REPORT),
            System.currentTimeMillis() - start)
    }

    private fun scanDex(bytes: ByteArray, dexName: String): DexInfo {
        val d = Dex(bytes, dexName)
        val hits = ArrayList<Hit>()
        val stringHits = ArrayList<String>()
        // Raw string pool: report the strongest monetisation strings verbatim.
        for (i in 0 until d.stringCount) {
            val s = d.string(i)
            val sl = s.lowercase()
            if (NAME_KW.keys.any { it in sl } || ADS_KW.keys.any { it in sl } ||
                STR_KW.keys.any { it in sl }
            ) {
                if (stringHits.size < MAX_STRING_REPORT) stringHits += s
            }
        }
        for (ci in 0 until d.classCount) {
            val cname = d.className(ci)
            val clsTail = cname.substringAfterLast('/').lowercase()
            val fw = cname.startsWith("L") &&
                FW_PREFIXES.any { cname.lowercase().contains(it) }
            for (m in d.methodsOfClass(ci)) {
                if (m.idx >= d.methodCount) continue
                val mname = d.methodName(m.idx)
                val ml = mname.lowercase()
                val cstrings = d.constStrings(m.code)
                val cstr = cstrings.joinToString(" ").lowercase()
                val why = ArrayList<String>()
                var score = 0
                for ((k, w) in NAME_KW) {
                    if (k in ml) { score += w; why += "name:$k" }
                    if (k in clsTail) { score += w / 2; why += "class:$k" }
                }
                for ((k, w) in ADS_KW) {
                    if (k in ml) { score += w; why += "ads:$k" }
                    else if (k in cstr) { score += (w * 2) / 3; why += "ads-str:$k" }
                }
                for ((k, w) in STR_KW) {
                    if (k in cstr) { score += w / 2; why += "str:$k" }
                }
                if (fw) score -= 3
                if (score >= MIN_SCORE) {
                    hits += Hit(score, cname, mname, m.code, why, cstrings, dexName)
                }
            }
        }
        hits.sortByDescending { it.score }
        return DexInfo(d.stringCount, d.classCount, d.methodCount, hits, stringHits)
    }

    private data class DexInfo(
        val strings: Int, val classes: Int, val methods: Int,
        val hits: List<Hit>, val stringHits: List<String>,
    )

    private fun dexOrder(name: String): Int =
        Regex("classes(\\d*)\\.dex").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // ---- DEX parsing (byte layout verified against androguard 4.1.4) --------

    private class Dex(val b: ByteArray, val name: String) {
        init {
            require(b.size >= 112 &&
                (b[0].toInt() and 0xFF) == 0x64 &&
                (b[1].toInt() and 0xFF) == 0x65 &&
                (b[2].toInt() and 0xFF) == 0x78 &&
                (b[3].toInt() and 0xFF) == 0x0A
            ) { "not a DEX file: $name" }
        }
        val version: String = String(b, 4, 3, Charsets.US_ASCII)
        val stringCount = u32(56); val stringOff = u32(60)
        val typeCount = u32(64); val typeOff = u32(68)
        val methodCount = u32(88); val methodOff = u32(92)
        val classCount = u32(96); val classOff = u32(100)

        private fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        private val sCache = arrayOfNulls<String>(stringCount)
        private val tCache = arrayOfNulls<String>(typeCount)

        fun string(i: Int): String {
            sCache[i]?.let { return it }
            val off = u32(stringOff + i * 4)
            // ULEB128 length, then MUTF-8 (treated as UTF-8, lenient) bytes.
            var pos = off
            var n = 0
            var s = 0
            while (true) {
                val x = b[pos].toInt() and 0xFF
                pos++
                n = n or ((x and 0x7F) shl s)
                s += 7
                if (x and 0x80 == 0) break
            }
            val text = try {
                String(b, pos, n.coerceAtMost(b.size - pos), Charsets.UTF_8)
            } catch (e: Exception) { "" }
            sCache[i] = text
            return text
        }

        fun typeName(i: Int): String {
            tCache[i]?.let { return it }
            val t = string(u32(typeOff + i * 4))
            tCache[i] = t
            return t
        }

        fun className(ci: Int): String = typeName(u32(classOff + ci * 32))
        fun methodName(midx: Int): String = string(u32(methodOff + midx * 8 + 4))

        /**
         * The methods of class [ci]: (method_idx, access_flags, code_item offset).
         * class_data_item = uleb statics, uleb instances, uleb direct, uleb virtual,
         * then statics fields, instances fields, direct methods, virtual methods.
         * Each field = uleb idx_diff, uleb access. Each method = uleb idx_diff, uleb access,
         * uleb code_off. method_idx accumulates from 0 separately for direct and virtual.
         */
        fun methodsOfClass(ci: Int): List<MethodRef> {
            val out = ArrayList<MethodRef>()
            val cd = u32(classOff + ci * 32 + 24)
            if (cd == 0) return out
            var o = cd
            val ssz = uleb(o); o += ulebSize(o)
            val isz = uleb(o); o += ulebSize(o)
            val dmsz = uleb(o); o += ulebSize(o)
            val vmsz = uleb(o); o += ulebSize(o)
            repeat(ssz) { o += ulebSize(o); o += ulebSize(o) }
            repeat(isz) { o += ulebSize(o); o += ulebSize(o) }
            var midx = 0
            repeat(dmsz) {
                midx += uleb(o); o += ulebSize(o)
                val acc = uleb(o); o += ulebSize(o)
                val code = uleb(o)
                out += MethodRef(midx, acc, code)
            }
            midx = 0
            repeat(vmsz) {
                midx += uleb(o); o += ulebSize(o)
                val acc = uleb(o); o += ulebSize(o)
                val code = uleb(o)
                out += MethodRef(midx, acc, code)
            }
            return out
        }

        /** Decode one ULEB128 at [o]; does not advance a shared cursor. */
        private fun uleb(o: Int): Int {
            var r = 0; var s = 0; var p = o
            while (true) {
                val x = b[p].toInt() and 0xFF; p++
                r = r or ((x and 0x7F) shl s); s += 7
                if (x and 0x80 == 0) return r
            }
        }
        /** Number of bytes a ULEB128 at [o] occupies. */
        private fun ulebSize(o: Int): Int {
            var p = o
            while (true) {
                val x = b[p].toInt() and 0xFF; p++
                if (x and 0x80 == 0) return p - o
            }
        }

        /** The string constants a method references, by scanning its Dalvik instructions. */
        fun constStrings(codeOff: Int): List<String> {
            if (codeOff == 0 || codeOff + 16 > b.size) return emptyList()
            val insns = u32(codeOff + 12)
            var o = codeOff + 16
            var end = o + insns * 2
            if (end > b.size) end = b.size
            val list = ArrayList<String>()
            // o+3 keeps the jumbo op's u32(o+2) in range at every step.
            while (o + 3 < end && o + 3 < b.size) {
                val word = u16(o)
                when (word and 0xFF) {
                    0x1A -> { // const-string vA, stringIdB
                        val idx = word ushr 8
                        if (idx in 0 until stringCount) list += string(idx)
                    }
                    0x12 -> { // const-string/jumbo vAA, stringIdBBBBBBBB
                        val idx = u32(o + 2) ushr 8
                        if (idx in 0 until stringCount) list += string(idx)
                    }
                }
                o += 2
            }
            return list
        }
    }

    /** method_idx, access_flags, code_item offset. */
    private data class MethodRef(val idx: Int, val acc: Int, val code: Int)
}
