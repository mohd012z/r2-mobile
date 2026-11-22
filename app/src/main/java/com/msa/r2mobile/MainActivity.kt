package com.msa.r2mobile

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Command-prompt style front end for radare2 and the built-in APK/DEX helpers.
 * Work runs on [worker]; every change to the console happens on the UI thread via [ui].
 */
class MainActivity : ComponentActivity() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val console = Console()
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var cmdInput: EditText

    @Volatile private var targetPath: String? = null
    @Volatile private var loadedName: String? = null
    @Volatile private var loaded: FileCategory? = null
    /** Type filter for the Functions menu. null shows every function. */
    @Volatile private var filter: FileCategory? = null
    /** The radare2 process currently running, if any. */
    @Volatile private var running: Process? = null

    private val r2Binary: File by lazy { File(applicationInfo.nativeLibraryDir, "libr2.so") }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        stopRunning(quiet = true)
        worker.execute { loadFile(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        outputText = findViewById(R.id.outputText)
        scrollView = findViewById(R.id.scrollView)
        cmdInput = findViewById(R.id.cmdInput)

        findViewById<Button>(R.id.openButton).setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.functionsButton).setOnClickListener { showFunctionMenu() }
        findViewById<Button>(R.id.filterButton).setOnClickListener { showFilterMenu() }
        findViewById<Button>(R.id.runButton).setOnClickListener { runTyped() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopRunning(quiet = false) }
        findViewById<Button>(R.id.helpButton).setOnClickListener { showHelp() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveLog() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { clearConsole() }
        cmdInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                runTyped()
                true
            } else {
                false
            }
        }

        ui {
            write(Console.Style.HEADER, "R2 Mobile console")
            write(Console.Style.INFO, "Type a function number (e.g. 11), or a shortcut such as 'help'.")
        }
        advice("tap OPEN and pick a file. An APK, a .so or any program works.")
        if (!r2Binary.exists()) {
            say(Console.Style.ERROR, "The radare2 engine (libr2.so) is missing from this build.")
            advice("rebuild the APK with GitHub Actions and install it again.")
        }
    }

    override fun onDestroy() {
        stopRunning(quiet = true)
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- console helpers ---------------------------------------------------

    /** Runs [block] against the console on the UI thread, then refreshes the screen. */
    private fun ui(block: Console.() -> Unit) {
        runOnUiThread {
            console.block()
            outputText.text = console.snapshot()
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun say(style: Console.Style, text: String) = ui { write(style, text) }

    private fun advice(text: String) = ui { write(Console.Style.ADVICE, "NEXT: $text") }

    private fun clearConsole() {
        ui { clear() }
        advice("tap OPEN to load a file, or type 'help'.")
    }

    // ---- loading -----------------------------------------------------------

    private fun loadFile(uri: Uri) {
        try {
            val name = displayName(uri)
            val dest = File(cacheDir, "target.bin")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("cannot open the selected file")
            val category = FileCategory.detect(dest)
            targetPath = dest.absolutePath
            loadedName = name
            loaded = category
            filter = category
            say(Console.Style.OK, "loaded $name (${dest.length()} bytes)")
            say(Console.Style.INFO, "type: ${category.label}. Filter set to this type.")
            advice(Advice.afterOpen(category))
        } catch (e: Exception) {
            say(Console.Style.ERROR, "load error: ${e.message}")
            advice("try OPEN again. Very large files need free memory on the phone.")
        }
    }

    /** The file's real name, as the picker reports it. */
    private fun displayName(uri: Uri): String {
        val fromProvider = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
        return fromProvider ?: uri.lastPathSegment ?: "file"
    }

    // ---- menus -------------------------------------------------------------

    private fun visibleFeatures(): List<Feature> {
        val f = filter ?: return Catalog.all
        return Catalog.all.filter { f in it.appliesTo }
    }

    private fun showFunctionMenu() {
        val features = visibleFeatures()
        if (features.isEmpty()) {
            say(Console.Style.WARN, "No functions for this file type.")
            advice(Advice.GENERIC)
            return
        }
        val labels = features.map { "${it.number}. [${it.group}] ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Functions: ${filter?.label ?: "all file types"}")
            .setItems(labels) { _, which -> showTutorial(features[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showFilterMenu() {
        val options: List<FileCategory?> = listOf<FileCategory?>(null) + FileCategory.values().toList()
        val labels = options.map { it?.label ?: "All file types" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Filter by file type")
            .setItems(labels) { _, which ->
                filter = options[which]
                say(Console.Style.INFO, "filter: ${options[which]?.label ?: "All file types"}")
                advice("tap FUNCTIONS to see the steps for this filter.")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** The tutorial for one function, with a Run button. */
    private fun showTutorial(feature: Feature) {
        val current = loaded
        val status = when {
            current == null -> "No file loaded yet. Tap OPEN first."
            current in feature.appliesTo -> "Works with your file (${current.label})."
            else -> "Not for your file (${current.label}). Pick another function."
        }
        AlertDialog.Builder(this)
            .setTitle("${feature.number}. ${feature.name}")
            .setMessage("${feature.tutorial}\n\n$status")
            .setPositiveButton("Run") { _, _ -> executeFeature(feature) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHelp() {
        ui {
            write(Console.Style.HEADER, "Shortcuts")
            write(Console.Style.INFO, "  <number>     tutorial for a function, e.g. 11, then Run")
            write(Console.Style.INFO, "  functions    open the function menu (or: f)")
            write(Console.Style.INFO, "  filter       choose which file types the menu shows")
            write(Console.Style.INFO, "  find WORD    search the file for WORD")
            write(Console.Style.INFO, "  status       show the loaded file, type and filter")
            write(Console.Style.INFO, "  stop         stop a running command")
            write(Console.Style.INFO, "  save         save this screen to a text file")
            write(Console.Style.INFO, "  clear        clear this screen")
            write(Console.Style.INFO, "  help         this list")
            write(Console.Style.INFO, "Anything else goes to radare2 as a command, e.g. iI or afl~main")
        }
        advice("type a number from 'functions' to start, e.g. 1 for file info.")
    }

    private fun showStatus() {
        val current = loaded
        ui {
            write(Console.Style.HEADER, "Status")
            write(Console.Style.INFO, "  file:   ${loadedName ?: "none"}")
            write(Console.Style.INFO, "  type:   ${current?.label ?: "none"}")
            write(Console.Style.INFO, "  filter: ${filter?.label ?: "All file types"}")
            write(Console.Style.INFO, "  busy:   ${if (running != null) "yes, a command is running" else "no"}")
        }
        advice(if (current == null) "tap OPEN to load a file." else Advice.GENERIC)
    }

    // ---- typed input -------------------------------------------------------

    private fun runTyped() {
        val text = cmdInput.text.toString().trim()
        if (text.isEmpty()) return
        cmdInput.setText("")
        val lower = text.lowercase()
        when {
            text.toIntOrNull() != null -> numbered(text.toInt())
            lower == "help" || lower == "?" -> showHelp()
            lower == "clear" -> clearConsole()
            lower == "stop" -> stopRunning(quiet = false)
            lower == "status" -> showStatus()
            lower == "functions" || lower == "f" -> showFunctionMenu()
            lower == "filter" -> showFilterMenu()
            lower == "save" -> saveLog()
            lower.startsWith("find ") -> {
                val word = text.substring(5).trim()
                if (word.isEmpty()) {
                    say(Console.Style.WARN, "Type a word after find, e.g.  find http")
                    advice("try  find http  or  find password")
                } else {
                    runR2("/ $word", "note the address next to each match, then type  s ADDRESS;px 64  to see its bytes.")
                }
            }
            else -> runR2(text, Advice.GENERIC)
        }
    }

    private fun numbered(n: Int) {
        val feature = Catalog.byNumber(n)
        if (feature == null) {
            say(Console.Style.WARN, "No function #$n. Choose 1 to ${Catalog.all.size}.")
            advice("type 'functions' to see the list.")
        } else {
            showTutorial(feature)
        }
    }

    // ---- running -----------------------------------------------------------

    private fun executeFeature(feature: Feature) {
        val current = loaded
        val target = targetPath
        if (current == null || target == null) {
            say(Console.Style.WARN, "No file loaded.")
            advice("tap OPEN and pick a file first.")
            return
        }
        if (current !in feature.appliesTo) {
            say(Console.Style.WARN, "#${feature.number} does not apply to ${current.label}.")
            advice(Advice.wrongType(current))
            return
        }
        say(Console.Style.HEADER, "== #${feature.number} ${feature.name}")
        val next = Advice.afterFunction(feature.number)
        when (feature.action) {
            Action.R2 -> runR2(feature.command, next)
            Action.NATIVE_INFO -> background(next) { nativeInfo(target, current) }
            Action.NATIVE_ZIP_LIST -> background(next) { listArchive(target) }
            Action.NATIVE_EXTRACT_DEX -> background(next) { extractDex(target) }
        }
    }

    /** Runs a built-in (non radare2) task on the worker, then gives advice. */
    private fun background(next: String, block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                say(Console.Style.ERROR, "error: ${e.message}")
            } finally {
                advice(next)
            }
        }
    }

    /** Runs one radare2 command with a prompt line, a timeout, a size limit and a Stop button. */
    private fun runR2(cmd: String, next: String) {
        val target = targetPath
        if (target == null) {
            say(Console.Style.WARN, "No file loaded.")
            advice("tap OPEN and pick a file first.")
            return
        }
        if (!r2Binary.exists()) {
            say(Console.Style.ERROR, "The radare2 engine is missing from this build.")
            advice("rebuild the APK with GitHub Actions.")
            return
        }
        if (running != null) {
            say(Console.Style.WARN, "A command is already running.")
            advice("wait for it to finish, or type 'stop'.")
            return
        }
        worker.execute {
            ui {
                write(Console.Style.PROMPT, "r2> ", newline = false)
                write(Console.Style.COMMAND, cmd)
            }
            try {
                val process = ProcessBuilder(r2Binary.absolutePath, "-N", "-e", "scr.color=0", "-qc", cmd, target)
                    .redirectErrorStream(true)
                    .directory(filesDir)
                    .apply { environment()["HOME"] = filesDir.absolutePath }
                    .start()
                running = process
                say(Console.Style.INFO, "running... (type 'stop' or tap STOP to cancel)")
                startWatchdog(process, TIMEOUT_SECONDS)
                val (text, truncated) = readCapped(process.inputStream)
                if (truncated) process.destroy()
                val code = process.waitFor()
                running = null
                ui {
                    if (text.isBlank()) write(Console.Style.INFO, "(no output)") else writeOutput(text)
                    if (truncated) {
                        write(
                            Console.Style.WARN,
                            "output was cut at $MAX_OUTPUT characters. Narrow the command, e.g. afl~main"
                        )
                    }
                    write(if (code == 0) Console.Style.OK else Console.Style.WARN, "done (exit $code)")
                }
                advice(if (code == 0) next else Advice.FAILED)
            } catch (e: Exception) {
                running = null
                say(Console.Style.ERROR, "could not start radare2: ${e.message}")
                advice(Advice.FAILED)
            }
        }
    }

    /** Reads at most [MAX_OUTPUT] characters. Returns the text and whether it was cut short. */
    private fun readCapped(stream: InputStream): Pair<String, Boolean> {
        val sb = StringBuilder()
        val reader = stream.bufferedReader()
        val chunk = CharArray(4096)
        while (true) {
            val n = reader.read(chunk)
            if (n < 0) return sb.toString() to false
            sb.append(chunk, 0, n)
            if (sb.length > MAX_OUTPUT) return sb.substring(0, MAX_OUTPUT) to true
        }
    }

    /** Kills the command if it runs longer than [seconds]. */
    private fun startWatchdog(process: Process, seconds: Long) {
        Thread {
            try {
                Thread.sleep(seconds * 1000)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (process.isAlive) {
                process.destroy()
                say(
                    Console.Style.WARN,
                    "stopped after $seconds seconds. Use a smaller file, or a narrower command such as afl~main."
                )
            }
        }.apply { isDaemon = true }.start()
    }

    private fun stopRunning(quiet: Boolean) {
        val process = running
        if (process == null) {
            if (!quiet) {
                say(Console.Style.INFO, "nothing is running.")
                advice(Advice.GENERIC)
            }
            return
        }
        process.destroy()
        running = null
        if (!quiet) {
            say(Console.Style.WARN, "stopped by you.")
            advice("try a narrower command, or another function.")
        }
    }

    // ---- saving ------------------------------------------------------------

    private fun saveLog() {
        val text = console.plainText()
        val dir = getExternalFilesDir(null) ?: filesDir
        worker.execute {
            try {
                val out = File(dir, "r2-log-${System.currentTimeMillis()}.txt")
                out.writeText(text)
                say(Console.Style.OK, "saved: ${out.name}")
                say(Console.Style.INFO, "find it in Files > Android > data > com.msa.r2mobile > files")
                advice("open the file with any text viewer to copy or share it.")
            } catch (e: Exception) {
                say(Console.Style.ERROR, "could not save: ${e.message}")
                advice("check that the phone has free storage.")
            }
        }
    }

    // ---- native functions --------------------------------------------------

    private fun nativeInfo(path: String, category: FileCategory) {
        val f = File(path)
        ui {
            writeOutput(
                "name: ${loadedName ?: f.name}\n" +
                    "size: ${f.length()} bytes\n" +
                    "type: ${category.label}\n" +
                    "sha256: ${sha256(f)}\n" +
                    "first 16 bytes: ${headHex(f, 16)}"
            )
        }
    }

    private fun listArchive(path: String) {
        ZipFile(File(path)).use { zf ->
            val entries = Collections.list(zf.entries())
            val shown = entries.take(300)
            val text = StringBuilder("${entries.size} entries:\n")
            shown.forEach { e -> text.append("%10d  %s\n".format(e.size, e.name)) }
            if (entries.size > shown.size) text.append("... ${entries.size - shown.size} more not shown\n")
            ui { writeOutput(text.toString()) }
        }
    }

    private fun extractDex(path: String) {
        val outDir = File(filesDir, "extracted").apply { mkdirs() }
        ZipFile(File(path)).use { zf ->
            val dexEntries = Collections.list(zf.entries()).filter { DEX_NAME.matches(it.name) }
            if (dexEntries.isEmpty()) {
                say(Console.Style.WARN, "No classes*.dex found in this file.")
                return@use
            }
            dexEntries.forEach { e ->
                val out = File(outDir, e.name)
                zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                say(Console.Style.OK, "extracted ${e.name} (${out.length()} bytes)")
            }
            val primary = File(outDir, "classes.dex")
            if (primary.exists()) {
                targetPath = primary.absolutePath
                loaded = FileCategory.DEX
                filter = FileCategory.DEX
                say(Console.Style.INFO, "target is now classes.dex. Functions 2 to 19 work on it.")
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun headHex(f: File, count: Int): String {
        val buf = ByteArray(count)
        val read = f.inputStream().use { it.read(buf) }
        return (0 until maxOf(read, 0)).joinToString(" ") { "%02x".format(buf[it].toInt() and 0xFF) }
    }

    companion object {
        private val DEX_NAME = Regex("classes\\d*\\.dex")
        private const val MAX_OUTPUT = 200_000
        private const val TIMEOUT_SECONDS = 300L
    }
}
