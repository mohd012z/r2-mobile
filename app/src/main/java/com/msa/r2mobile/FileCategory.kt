package com.msa.r2mobile

import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/** File types the app recognises, detected from magic bytes (the first bytes of the file). */
enum class FileCategory(val label: String) {
    APK("APK (Android app)"),
    DEX("DEX (Android bytecode)"),
    ELF_SHARED("ELF shared library (.so)"),
    ELF_EXEC("ELF executable"),
    PE("PE (Windows .exe/.dll)"),
    MACHO("Mach-O (Apple binary)"),
    JAR("JAR (Java archive)"),
    ZIP("ZIP archive"),
    IMAGE("Image (PNG/JPEG/GIF)"),
    PDF("PDF document"),
    UNKNOWN("Unknown / raw data");

    companion object {
        /** Reads the first bytes of [file] and returns its category. */
        fun detect(file: File): FileCategory {
            val head = ByteArray(20)
            val read = file.inputStream().use { it.read(head) }
            if (read < 4) return UNKNOWN

            fun at(vararg sig: Int): Boolean =
                sig.size <= read && sig.indices.all { (head[it].toInt() and 0xFF) == sig[it] }

            return when {
                // ELF: byte 16 is e_type; 3 = ET_DYN (shared object or PIE)
                at(0x7F, 0x45, 0x4C, 0x46) ->
                    if (read >= 17 && head[16].toInt() == 3) ELF_SHARED else ELF_EXEC
                at(0x64, 0x65, 0x78, 0x0A) -> DEX                       // "dex\n"
                at(0x50, 0x4B, 0x03, 0x04) -> zipKind(file)             // "PK\x03\x04"
                at(0x4D, 0x5A) -> PE                                    // "MZ"
                at(0xFE, 0xED, 0xFA, 0xCE) || at(0xFE, 0xED, 0xFA, 0xCF) ||
                    at(0xCE, 0xFA, 0xED, 0xFE) || at(0xCF, 0xFA, 0xED, 0xFE) ||
                    at(0xCA, 0xFE, 0xBA, 0xBE) -> MACHO
                at(0x89, 0x50, 0x4E, 0x47) || at(0xFF, 0xD8, 0xFF) ||
                    at(0x47, 0x49, 0x46, 0x38) -> IMAGE
                at(0x25, 0x50, 0x44, 0x46) -> PDF                       // "%PDF"
                else -> UNKNOWN
            }
        }

        /** A ZIP-format file is an APK if it has AndroidManifest.xml, a JAR if it has META-INF/MANIFEST.MF. */
        private fun zipKind(file: File): FileCategory = try {
            ZipFile(file).use { zf ->
                val names = Collections.list(zf.entries()).map { it.name }
                when {
                    "AndroidManifest.xml" in names -> APK
                    "META-INF/MANIFEST.MF" in names -> JAR
                    else -> ZIP
                }
            }
        } catch (e: Exception) {
            ZIP
        }
    }
}
