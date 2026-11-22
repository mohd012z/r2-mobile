package com.msa.r2mobile

/**
 * "What next" guidance shown after every action. Each line says exactly where to act:
 * the box at the bottom (type a number or paste an address, then press RUN) or a button.
 */
object Advice {
    const val GENERIC = "tap FUNCTIONS to pick a step, or type a number in the box and press RUN."
    const val FAILED = "the command did not finish. Read the message above, then try again or type help."

    private val hints: Map<Int, String> = mapOf(
        1 to "type 11 in the box and press RUN (auto-analyze). Then 12 lists the functions.",
        2 to "type 11 in the box and press RUN (auto-analyze), then 12 to list the functions.",
        3 to "the program starts here. Type 13 in the box to read the code at this point.",
        4 to "code is in .text. Type 9 for readable text, or 13 for the code.",
        5 to "these libraries are needed to run. Type 6 to see the functions it calls.",
        6 to "look for network (connect), files (open) and crypto calls. Type 18 to search a word.",
        7 to "these are functions it offers to other programs. Type 8 for all names.",
        8 to "few names means the file is stripped. Type 11 (auto-analyze), then 12.",
        9 to "look for web addresses, keys and messages. Type 18 and a word you noticed.",
        10 to "found something interesting? Type 18 and that word to find where it is.",
        11 to "analysis finished. Type 12 in the box to list the functions found.",
        12 to "copy one address from this list, paste it in the box and press RUN to see its code.",
        13 to "the left column shows addresses. Type 14 to see the branches as a chart.",
        14 to "boxes are steps, arrows are jumps. Type 17 to see who calls this code.",
        15 to "the first bytes show the file type. Type 2 for the summary.",
        16 to "type 17 to see which code points here.",
        17 to "paste an address from the list in the box and press RUN to see its code.",
        18 to "note an address next to a match. Paste it in the box and press RUN to see the code there.",
        19 to "matches show addresses. Paste one in the box and press RUN.",
        20 to "the list shows what is inside. Type 21 to extract classes.dex, which holds the app code.",
        21 to "classes.dex is now the target. Type 1 (file info), then 11 (auto-analyze).",
    )

    fun afterFunction(number: Int): String = hints[number] ?: GENERIC

    fun afterOpen(category: FileCategory): String = when (category) {
        FileCategory.APK -> "type 20 in the box to list what is inside the APK, then 21 to extract classes.dex."
        FileCategory.DEX -> "type 1 (file info) in the box, then 11 (auto-analyze)."
        FileCategory.ELF_SHARED, FileCategory.ELF_EXEC, FileCategory.PE, FileCategory.MACHO ->
            "type 1 (file info) in the box, then 11 (auto-analyze)."
        FileCategory.JAR, FileCategory.ZIP -> "type 20 in the box to list the archive contents."
        FileCategory.IMAGE, FileCategory.PDF, FileCategory.UNKNOWN ->
            "type 15 (hex dump) in the box to see the first bytes, or 9 for readable text."
    }

    fun wrongType(category: FileCategory): String =
        "your file is ${category.label}. Tap FUNCTIONS to see only the steps that work on it."
}
