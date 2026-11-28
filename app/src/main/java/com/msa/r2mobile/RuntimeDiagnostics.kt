package com.msa.r2mobile

/** Offline diagnostic parser. Findings are candidates, not confirmed root causes. */
object RuntimeDiagnostics {
    data class Frame(val owner: String, val method: String, val file: String, val line: Int)
    data class Finding(val category: String, val advice: String, val required: List<String>)
    private val frame = Regex("""\bat\s+([\w.$]+)\.([\w$<>]+)\(([^():]+):(\d+)\)""")
    fun frames(log: String): List<Frame> {
        require(log.length <= 2_097_152) { "Diagnostic input too large" }
        return frame.findAll(log).take(200).mapNotNull {
            val (owner, method, file, line) = it.destructured
            line.toIntOrNull()?.takeIf { n -> n > 0 }?.let { n -> Frame(owner, method, file, n) }
        }.toList()
    }
    fun findings(log: String): List<Finding> {
        require(log.length <= 2_097_152)
        return listOf(
            Triple("NullPointerException", "Inspect initialization and nullability", listOf("Matching Java/Kotlin source")),
            Triple("UnsatisfiedLinkError", "Check JNI signatures and ABI", listOf("Native library", "JNI source", "Gradle configuration")),
            Triple("OutOfMemoryError", "Inspect allocation and retained objects", listOf("Heap profile", "Allocation source")),
            Triple("SIGSEGV", "Symbolize native stack before repair", listOf("Tombstone", "Matching unstripped SO")),
            Triple("ANR in", "Inspect main-thread blocking and lock contention", listOf("ANR trace", "Worker source")),
            Triple("SQLiteException", "Inspect schema and transactions", listOf("DAO", "Migration source")),
            Triple("SecurityException", "Inspect permission handling", listOf("Manifest", "Permission source"))
        ).filter { log.contains(it.first) }.map { Finding(it.first, it.second, it.third) }
    }
    /** Return candidates only; caller must verify revision and package. */
    fun sourceCandidates(frame: Frame, paths: List<String>): List<String> =
        paths.filter { it.substringAfterLast('/') == frame.file }.sorted()
}
