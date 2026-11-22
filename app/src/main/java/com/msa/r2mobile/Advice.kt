package com.msa.r2mobile

/** "What next" guidance shown after every action. Keep texts short and concrete. */
object Advice {
    const val GENERIC = "type a number (see 'functions'), or 'help' for shortcuts."
    const val FAILED = "the command did not finish cleanly. Read the message above, or type 'help'."

    private val hints: Map<Int, String> = mapOf(
        1 to "run 11 (auto-analyze) so that 12 to 14 have data. Then 2 for the summary.",
        2 to "note the architecture. Next: 11 (auto-analyze), then 12 (list functions).",
        3 to "this is where the program starts. Next: 13 to read the code there.",
        4 to "code is in .text. Next: 9 for readable text, or 13 for the code.",
        5 to "these libraries are needed to run. Next: 6 to see the functions it calls.",
        6 to "look for network (connect), files (open) and crypto calls. Next: 18 to search for a word.",
        7 to "these are functions it offers to others. Next: 8 for all symbol names.",
        8 to "few names means the file is stripped. Run 11 first, then 12.",
        9 to "look for web addresses, keys and messages. Next: 18 to search one word you noticed.",
        10 to "found something interesting? Next: 18 to search for that word.",
        11 to "analysis finished. Next: 12 to list the functions found.",
        12 to "copy an address from this list. Next: type  s ADDRESS;pdf  in the box and Run.",
        13 to "read the left column for addresses. Next: 14 to see the branches as a chart.",
        14 to "boxes are steps, arrows are jumps. Next: 17 to see who calls this code.",
        15 to "the first bytes show the file type. Next: 2 for the summary.",
        16 to "next: 17 to see which code points here.",
        17 to "pick an address from the list and type  s ADDRESS;pdf  in the box, then Run.",
        18 to "note the address next to a match. Next: type  s ADDRESS;px 64  in the box to see the bytes.",
        19 to "matches show addresses. Next: 15 or 16 to see the bytes there.",
        20 to "the list shows what is inside. Next: 21 to extract classes.dex, which holds the app code.",
        21 to "classes.dex is now the target. Next: 1 (file info), then 11 (auto-analyze), then 12.",
    )

    fun afterFunction(number: Int): String = hints[number] ?: GENERIC

    fun afterOpen(category: FileCategory): String = when (category) {
        FileCategory.APK -> "Next: 20 to list what is inside the APK, then 21 to extract classes.dex."
        FileCategory.DEX -> "Next: 1 (file info), then 11 (auto-analyze)."
        FileCategory.ELF_SHARED, FileCategory.ELF_EXEC, FileCategory.PE, FileCategory.MACHO ->
            "Next: 1 (file info), then 11 (auto-analyze)."
        FileCategory.JAR, FileCategory.ZIP -> "Next: 20 to list the archive contents."
        FileCategory.IMAGE, FileCategory.PDF, FileCategory.UNKNOWN ->
            "Next: 15 (hex dump) to see the first bytes, or 9 for readable text."
    }

    fun wrongType(category: FileCategory): String =
        "your file is ${category.label}. Type 'functions' to see only the steps that work on it."
}
