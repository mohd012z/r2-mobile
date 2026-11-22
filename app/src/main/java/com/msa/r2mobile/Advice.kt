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

    /** What to read and what to find after a file of this type is loaded. */
    fun plan(category: FileCategory): List<String> = when (category) {
        FileCategory.APK -> listOf(
            "READ 1: type 1 for file info, then 20 to list what is inside the APK.",
            "READ 2: type 22 to decode AndroidManifest.xml. Read the permissions (INTERNET, CAMERA, SMS, LOCATION).",
            "READ 3: type 21 to extract classes.dex. Then use DEX: list classes, filter by method, translate.",
            "FIND: type 18 (search http) for web addresses, then 9 for readable text such as messages and keys.",
            "FIND: type  / license  or  / signature  or  / verify  to find licence and signature checks.",
        )
        FileCategory.DEX -> listOf(
            "READ 1: type 1 for file info, then 11 (auto-analyze) before the function lists.",
            "READ 2: DEX menu > list classes (ic). Pick a class, then read its methods.",
            "FIND: type  method NAME  or  class NAME  or  regex PATTERN  to narrow the output.",
            "FIND: type  translate  to show Java names instead of Ljava/lang/String; style names.",
        )
        FileCategory.ELF_SHARED, FileCategory.ELF_EXEC, FileCategory.PE, FileCategory.MACHO -> listOf(
            "READ 1: type 2 (binary summary) to see the CPU, bit width and whether it is stripped.",
            "READ 2: type 4 (sections) and 3 (entry point). The .text section holds the code.",
            "READ 3: type 11 (auto-analyze), then 12 (list functions). Wait until the output stops.",
            "READ 4: type 13 to read the first instructions. Tap any address to read that code.",
            "FIND: type 6 (imports) to see what it calls, e.g. open, connect, SSL. This shows files and network use.",
            "FIND: type 9 (readable text), then  / http  or  / key  or  / error  to find words in the file.",
            "FIND: type 27 (block diagram) to see the if and loop structure at the entry point.",
        )
        FileCategory.JAR, FileCategory.ZIP -> listOf(
            "READ 1: type 20 to list the archive contents.",
            "READ 2: use the file manager (Actions > Extract archive) to unpack it, then load the file you need.",
            "FIND: type 9 for readable text, then  / word  to search for a name or a key.",
        )
        FileCategory.IMAGE, FileCategory.PDF, FileCategory.UNKNOWN -> listOf(
            "READ 1: type 15 (hex dump) for the first bytes. They name the file type, e.g. 89 50 4e 47 is PNG, 25 50 44 46 is PDF.",
            "READ 2: type 9 for readable text such as messages, web addresses and keys.",
            "FIND: type  / http  or  / yourword  to search the file for a word.",
        )
    }

    fun wrongType(category: FileCategory): String =
        "your file is ${category.label}. Tap FUNCTIONS to see only the steps that work on it."
}
