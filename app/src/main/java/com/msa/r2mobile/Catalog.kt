package com.msa.r2mobile

/** How a feature runs: through radare2, or natively in the app. */
enum class Action { R2, NATIVE_INFO, NATIVE_ZIP_LIST, NATIVE_EXTRACT_DEX, NATIVE_MANIFEST }

/**
 * One numbered function. [number] is what the user types or taps.
 * [appliesTo] lists the file types it works on.
 */
data class Feature(
    val number: Int,
    val group: String,
    val name: String,
    val action: Action,
    val command: String,
    val appliesTo: Set<FileCategory>,
    val tutorial: String,
)

object Catalog {
    private val BINARY = setOf(
        FileCategory.ELF_SHARED, FileCategory.ELF_EXEC, FileCategory.PE,
        FileCategory.MACHO, FileCategory.DEX,
    )
    private val EVERYTHING = FileCategory.values().toSet()
    private val ARCHIVES = setOf(FileCategory.APK, FileCategory.JAR, FileCategory.ZIP)

    private val list = mutableListOf<Feature>()

    private fun add(
        group: String, name: String, action: Action, command: String,
        appliesTo: Set<FileCategory>, tutorial: String,
    ) {
        list += Feature(list.size + 1, group, name, action, command, appliesTo, tutorial)
    }

    init {
        add("Info", "File info: type, size, SHA-256", Action.NATIVE_INFO, "", EVERYTHING,
            "Shows what kind of file you loaded, its size, a SHA-256 fingerprint (a unique ID for the exact bytes) " +
            "and the first 16 bytes in hex. Do this first to confirm the file is what you expect.")
        add("Info", "Binary summary (r2 i)", Action.R2, "i", BINARY,
            "Shows the CPU type, bit width, operating system, byte order and whether the file is stripped " +
            "(no function names). Works on ELF, PE, Mach-O and DEX files.")
        add("Info", "Entry point (r2 ie)", Action.R2, "ie", BINARY,
            "The address where the program starts running. It is the natural place to start reading code.")
        add("Info", "Sections (r2 iS)", Action.R2, "iS", BINARY,
            "Lists the file's parts: .text holds code, .data holds variables, .rodata holds constants.")
        add("Info", "Libraries needed (r2 il)", Action.R2, "il", BINARY,
            "Lists the shared libraries this file needs at runtime, such as libc.so.")
        add("Symbols", "Imports: functions it calls from elsewhere (r2 ii)", Action.R2, "ii", BINARY,
            "Functions the file uses but does not contain, such as open or connect. They reveal what the file does " +
            "(files, network, crypto).")
        add("Symbols", "Exports: functions it offers (r2 iE)", Action.R2, "iE", BINARY,
            "Functions this file provides to other programs. Common in libraries (.so and .dll).")
        add("Symbols", "Symbols: named functions and variables (r2 is)", Action.R2, "is", BINARY,
            "Names stored in the file. A stripped file has few or none. If so, run Auto-analyze (function 11).")
        add("Strings", "Readable text, data sections (r2 iz)", Action.R2, "iz", EVERYTHING,
            "Finds readable text: URLs, messages, error text, keys. Read them to guess what the file does. " +
            "Long output is cut to the last lines the viewer shows.")
        add("Strings", "Readable text, whole file (r2 izz)", Action.R2, "izz", EVERYTHING,
            "Like function 9 but searches the whole file. Slower. Use it when 9 finds too little.")
        add("Analysis", "Auto-analyze everything (r2 aaa)", Action.R2, "aaa", BINARY,
            "Asks radare2 to find functions, calls and references. Needed before functions 12 to 14 on stripped files. " +
            "Big files can take a minute. Wait until the output stops.")
        add("Analysis", "List functions (r2 afl)", Action.R2, "afl", BINARY,
            "Shows every function found, with address and size. Run function 11 first. " +
            "Copy an address and type it in the box as: s ADDRESS;pdf")
        add("Disassembly", "Assembly at entry point (r2 pdf)", Action.R2, "s entry0;pdf", BINARY,
            "Shows the processor instructions where the program starts, one per line. " +
            "The left column is the address, the right column is the instruction. If it is empty, run function 11 first.")
        add("Disassembly", "Flow chart at entry point (r2 agf)", Action.R2, "s entry0;agf", BINARY,
            "Draws the branches of the code (if, else, loops) as ASCII boxes. Good for understanding the logic.")
        add("Hex", "Hex dump: first 256 bytes (r2 px)", Action.R2, "px 256 @ 0", EVERYTHING,
            "Shows the raw bytes in hex with text on the side. The first bytes identify the file: " +
            "7f 45 4c 46 means ELF, 4d 5a means PE, 50 4b means ZIP/APK.")
        add("Hex", "Hex dump: entry point (r2 px)", Action.R2, "s entry0;px 128", BINARY,
            "Raw bytes where the program starts. Compare with function 15 to see the difference.")
        add("Xrefs", "Who uses the entry point (r2 axt)", Action.R2, "s entry0;axt", BINARY,
            "Lists places in the file that point to the entry point address. Shows where that code is used.")
        add("Search", "Search for text: default \"http\"", Action.R2, "/ http", EVERYTHING,
            "Finds a word in the file. The default looks for web addresses. " +
            "To search your own word, type in the box: / yourword   then tap Run (for example: / license).")
        add("Search", "Search for bytes: default ELF header", Action.R2, "/x 7f454c46", EVERYTHING,
            "Finds a byte pattern in hex. The default is the ELF header. " +
            "To search your own bytes, type in the box: /x followed by hex, for example /x 4d5a for a PE header.")
        add("Archive", "List archive contents", Action.NATIVE_ZIP_LIST, "", ARCHIVES,
            "Lists every file inside the APK, JAR or ZIP with its size. An APK holds AndroidManifest.xml " +
            "(app settings), classes.dex (code), res/ (images and text) and lib/ (native libraries).")
        add("APK", "Extract classes.dex from APK", Action.NATIVE_EXTRACT_DEX, "", setOf(FileCategory.APK),
            "Pulls out the app's Dalvik bytecode (classes.dex). Afterwards the DEX file becomes the target, " +
            "so functions 2 to 19 work on it. Extracted files stay in the app's private storage.")
        add("APK", "Decode AndroidManifest.xml", Action.NATIVE_MANIFEST, "", setOf(FileCategory.APK),
            "Turns the app's binary manifest into readable XML: package name, version, permissions, " +
            "activities and services. Permissions show what the app is allowed to access, such as the internet or contacts. " +
            "Labels show as @0x ids because the text is stored separately and is not decoded yet.")
    }

    val all: List<Feature> get() = list

    fun byNumber(n: Int): Feature? = list.firstOrNull { it.number == n }
}
