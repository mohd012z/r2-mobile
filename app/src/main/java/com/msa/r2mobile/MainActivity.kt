package com.msa.r2mobile

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import java.util.concurrent.TimeUnit
import android.view.ScaleGestureDetector
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONTokener
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
    private var console = Console()
    /** One console per tab. [console] and [lastOutput] belong to the active tab. */
    private class Tab(val name: String, var console: Console, var output: File?)
    private val tabs = mutableListOf<Tab>()
    private var activeTab = 0
    /** EDIT mode: tapping an address opens the byte editor instead of reading code. */
    @Volatile private var editMode = false
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var cmdInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var runLabel = ""
    @Volatile private var runStarted = 0L
    /** What the running command is doing right now, shown on the status line. */
    @Volatile private var phase = ""
    /** Text of the last radare2 run, kept so the output filters can reuse it. */
    @Volatile private var lastOutput: File? = null

    /** Updates the elapsed-time line once a second while a command runs. */
    private val ticker: Runnable = object : Runnable {
        override fun run() {
            if (running == null) return
            val secs = (System.currentTimeMillis() - runStarted) / 1000
            statusText.text = "Running: $runLabel (${secs}s). $phase"
            handler.postDelayed(this, 1000)
        }
    }

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

    override fun onResume() {
        super.onResume()
        applyView()
    }

    /** Applies the View settings: console text size and button text size. */
    private fun applyView() {
        outputText.textSize = ViewConfig.consoleText
        ViewConfig.applyButtons(window.decorView)
    }

    /** Returns from the file manager with the file the user chose to open in R2. */
    private val fileManagerResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringExtra(FileManagerActivity.EXTRA_PATH)?.let { loadPath(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewConfig.load(this)

        outputText = findViewById(R.id.outputText)
        scrollView = findViewById(R.id.scrollView)
        cmdInput = findViewById(R.id.cmdInput)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        outputText.movementMethod = LinkMovementMethod.getInstance()

        // Pinch with two fingers to change the text size. The size is applied at most about
        // 12 times a second: every change re-lays out the whole console, so applying it on
        // every touch event is what made the zoom lag.
        val density = resources.displayMetrics.scaledDensity
        var pendingPx = -1f
        var zoomScheduled = false
        val applyZoom = Runnable {
            zoomScheduled = false
            if (pendingPx > 0f) {
                outputText.setTextSize(TypedValue.COMPLEX_UNIT_PX, pendingPx)
                pendingPx = -1f
            }
        }
        val zoom = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val base = if (pendingPx > 0f) pendingPx else outputText.textSize
                pendingPx = (base * detector.scaleFactor).coerceIn(8f * density, 40f * density)
                if (!zoomScheduled) {
                    zoomScheduled = true
                    handler.postDelayed(applyZoom, 80)
                }
                return true
            }
        })
        scrollView.setOnTouchListener { _, event ->
            zoom.onTouchEvent(event)
            false
        }

        findViewById<Button>(R.id.runButton).setOnClickListener { runTyped() }
        findViewById<Button>(R.id.functionsPickButton).setOnClickListener { showFunctionMenu() }
        findViewById<Button>(R.id.fullscreenButton).setOnClickListener { toggleFullscreen() }
        colourButtons()
        findViewById<Button>(R.id.fileMenuButton).setOnClickListener { dropdown(it, fileMenu()) }
        findViewById<Button>(R.id.runMenuButton).setOnClickListener { dropdown(it, runMenu()) }
        findViewById<Button>(R.id.findMenuButton).setOnClickListener { dropdown(it, findMenu()) }
        tabs += Tab("Tab 1", console, null)
        updateTabButton()
        findViewById<Button>(R.id.tabMenuButton).setOnClickListener { dropdown(it, tabMenu()) }
        findViewById<Button>(R.id.addTabButton).setOnClickListener { addTab() }
        findViewById<Button>(R.id.viewMenuButton).setOnClickListener { dropdown(it, viewMenu()) }
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
        handler.removeCallbacks(ticker)
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
            val total = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { copyWithProgress(input, it, total) }
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

    /** Copies the picked file. Shows a real percentage on the progress bar when the size is known. */
    private fun copyWithProgress(input: InputStream, out: java.io.OutputStream, total: Long) {
        val buf = ByteArray(65_536)
        var done = 0L
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = total <= 0
            progressBar.max = 100
            progressBar.progress = 0
        }
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total > 0) {
                val pct = (done * 100 / total).toInt()
                runOnUiThread {
                    progressBar.progress = pct
                    statusText.text = "Copying the file: $pct% (${done / 1024} of ${total / 1024} KB)"
                }
            }
        }
        runOnUiThread {
            progressBar.visibility = View.GONE
            progressBar.isIndeterminate = true
            statusText.text = "Idle"
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
            write(Console.Style.INFO, "  only WORD    show only output lines containing WORD (no WORD = all)")
            write(Console.Style.INFO, "  range S E    show only lines whose address is between S and E (hex)")
            write(Console.Style.INFO, "  tap address  any address in the output shows 24 instructions from there")
            write(Console.Style.INFO, "  colours      pink loop, blue forward branch, green short jump,")
            write(Console.Style.INFO, "               orange call, teal return")
            write(Console.Style.INFO, "  0x...        paste an address: shows its function, bytes, code and callers")
            write(Console.Style.INFO, "  analyze      same as pasting, using the address in the box (button ANALYZE)")
            write(Console.Style.INFO, "  guide        step-by-step guide to addresses, offsets and patching")
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
            ADDRESS_INPUT.matches(text) -> analyzeAddress(text)
            text.toIntOrNull() != null -> numbered(text.toInt())
            lower == "help" || lower == "?" -> showHelp()
            lower == "conv" || lower.startsWith("conv ") -> convert(text.substring(4))
            lower.startsWith("page") -> showPage(lower.removePrefix("page").trim().toIntOrNull() ?: 0)
            lower == "clear" -> clearConsole()
            lower == "stop" -> stopRunning(quiet = false)
            lower == "status" -> showStatus()
            lower == "functions" || lower == "f" -> showFunctionMenu()
            lower == "filter" -> showFilterMenu()
            lower == "only" || lower.startsWith("only ") -> onlyFilter(text.substring(4).trim())
            lower.startsWith("regex ") -> regexFilter(text.substring(6).trim())
            lower.startsWith("method ") -> containsFilter("method", text.substring(7).trim())
            lower.startsWith("class ") -> containsFilter("class", text.substring(6).trim())
            lower == "translate" -> translateLast()
            lower.startsWith("range ") -> rangeFilter(text.substring(6).trim())
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
        if (feature.action == Action.GUIDE) {
            showGuide()
            return
        }
        if (feature.action == Action.R2_REFERENCE) {
            showReference()
            return
        }
        if (feature.action == Action.TROUBLESHOOT) {
            troubleshoot()
            return
        }
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
            Action.NATIVE_MANIFEST -> background(next) { decodeManifest(target) }
            Action.NATIVE_ENCRYPT -> encryptLoaded(target, loadedName ?: "file")
            Action.NATIVE_DECRYPT -> decryptLoaded(target, loadedName ?: "file")
            Action.NATIVE_PATCH -> askPatch(target, loadedName ?: "file")
            Action.GUIDE -> showGuide()
            Action.R2_BLOCKS -> runBlocks(next)
            Action.R2_REFERENCE -> showReference()
            Action.TROUBLESHOOT -> troubleshoot()
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
    private fun runR2(cmd: String, next: String, onDone: ((String) -> Unit)? = null) {
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
                val started = System.currentTimeMillis()
                say(Console.Style.INFO, "[1/3] radare2 started, analysing the file")
                startBusy(cmd)
                startWatchdog(process, TIMEOUT_SECONDS)
                phase = "[2/3] reading output"
                val outFile = File(outputDir(), "msar2_output.txt")
                lastOutput = outFile
                val lineCount = readToFile(process.inputStream, outFile)
                val code = process.waitFor()
                running = null
                phase = ""
                val secs = (System.currentTimeMillis() - started) / 1000
                say(Console.Style.INFO, "[3/3] finished in ${secs}s, $lineCount lines, exit $code")
                val head = if (lineCount == 0 || onDone != null) "" else displayHead(outFile)
                ui {
                    if (onDone != null) {
                        write(Console.Style.INFO, "(building the diagram from this data, not shown raw)")
                    } else if (lineCount == 0) {
                        write(Console.Style.INFO, "(no output)")
                    } else {
                        writeOutput(head) { tapAddress(it) }
                        if (lineCount > DISPLAY_LINES) {
                            write(
                                Console.Style.WARN,
                                "showing lines 1-$DISPLAY_LINES of $lineCount. Type  page 2  for the next part, " +
                                    "or  only WORD  to search all lines."
                            )
                        }
                    }
                    write(if (code == 0) Console.Style.OK else Console.Style.WARN, "done (exit $code)")
                }
                setIdle()
                advice(if (code == 0) next else Advice.FAILED)
                onDone?.invoke(if (outFile.length() < 8_000_000L) outFile.readText() else "")
            } catch (e: Exception) {
                running = null
                setIdle()
                say(Console.Style.ERROR, "could not start radare2: ${e.message}")
                advice(Advice.FAILED)
            }
        }
    }

    // ---- analyze an address --------------------------------------------------

    /** The ANALYZE button: uses the address in the box. */
    private fun analyzeInput() {
        val text = cmdInput.text.toString().trim()
        if (ADDRESS_INPUT.matches(text)) {
            cmdInput.setText("")
            analyzeAddress(text)
        } else {
            say(Console.Style.WARN, "Paste an address such as 0x00806de7 in the box first.")
            advice("copy an address from function 12 or from the output, paste it in the box, then tap ANALYZE.")
        }
    }

    /**
     * One combined pass over an address: the function it belongs to, its bytes,
     * the code there, and the places that jump or call to it. Each part has a numbered label.
     */
    private fun analyzeAddress(addr: String) {
        runR2(
            "s $addr;?e 1 function at this address;afi;?e 2 bytes at this address;px 64;" +
                "?e 3 code from this address;pd 12;?e 4 places that jump or call here;axt",
            "tap any address in the code to follow it, or paste another address in the box."
        )
    }

    // ---- progress, taps and output filters ---------------------------------

    /** Shows the progress bar and the elapsed-time line while radare2 runs. */
    private fun startBusy(cmd: String) {
        runLabel = cmd
        runStarted = System.currentTimeMillis()
        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
    }

    private fun setIdle() {
        runOnUiThread {
            handler.removeCallbacks(ticker)
            progressBar.visibility = View.GONE
            statusText.text = "Idle"
        }
    }

    /** Tapping an address shows the 24 instructions that start there. */
    private fun tapAddress(addr: String) {
        if (editMode) { openEditor(addr); return }
        runR2("s $addr;pd 24", "tap another address, or type  only WORD  to filter this output.")
    }

    /** Shows only the lines of the last output that contain WORD. An empty WORD shows everything. */
    private fun onlyFilter(word: String) {
        filterLast(if (word.isEmpty()) "all lines" else "lines with '$word'") { line ->
            word.isEmpty() || line.contains(word, ignoreCase = true)
        }
    }

    /** Shows only the lines of the last output whose address is inside START..END (hex). */
    private fun rangeFilter(args: String) {
        val parts = args.split(Regex("\\s+"))
        val start = parts.getOrNull(0)?.let(::parseHex)
        val end = parts.getOrNull(1)?.let(::parseHex)
        if (start == null || end == null || end < start) {
            say(Console.Style.WARN, "Use: range START END, in hex, e.g.  range 0x401000 0x401100")
            advice("take the start and end from the addresses shown in the output.")
            return
        }
        filterLast("addresses ${args.trim()}") { line ->
            val a = Regex("^\\s*0x([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.toLongOrNull(16)
            a != null && a in start..end
        }
    }

    /** Streams the last output file and keeps the lines that [keep] accepts. */
    private fun filterLast(label: String, keep: (String) -> Boolean) {
        val file = lastOutput
        if (file == null || !file.exists() || file.length() == 0L) {
            say(Console.Style.WARN, "No output to filter yet. Run a command first.")
            advice("type  i  or  afl  first, then  only WORD  or  range START END.")
            return
        }
        var total = 0
        val picked = ArrayList<String>()
        file.useLines { seq ->
            seq.forEach { line ->
                total++
                if (picked.size < MAX_PICK && keep(line)) picked += line
            }
        }
        showFiltered(picked, total, label)
    }

    private fun showFiltered(picked: List<String>, total: Int, label: String) {
        if (picked.isEmpty()) {
            say(Console.Style.WARN, "No lines match $label (of $total).")
            advice("try another word or range, or type  only  to see everything again.")
            return
        }
        val shown = picked.take(DISPLAY_LINES)
        ui {
            write(Console.Style.HEADER, "Filter: $label, ${picked.size} matching of $total lines")
            writeOutput(shown.joinToString("\n")) { tapAddress(it) }
            if (picked.size > DISPLAY_LINES) {
                write(Console.Style.WARN, "showing the first $DISPLAY_LINES matches. Narrow with a longer word or a smaller range.")
            }
        }
        advice("tap an address to show its code, or filter again with  only WORD  or  range START END.")
    }

    /** page N: shows lines N-1 of the last output, [DISPLAY_LINES] at a time. */
    private fun showPage(n: Int) {
        val file = lastOutput
        if (n < 1 || file == null || !file.exists()) {
            say(Console.Style.WARN, "Type  page 2  after a command has printed a long output.")
            advice("run a command first, then  page 2  for the next part.")
            return
        }
        val from = (n - 1) * DISPLAY_LINES
        var total = 0
        val picked = ArrayList<String>()
        file.useLines { seq ->
            seq.forEach { line ->
                if (total >= from && picked.size < DISPLAY_LINES) picked += line
                total++
            }
        }
        if (picked.isEmpty()) {
            say(Console.Style.WARN, "No page $n. The output has $total lines.")
            advice("type  page 1  to go back to the start.")
            return
        }
        ui {
            write(Console.Style.HEADER, "Page $n: lines ${from + 1}-${from + picked.size} of $total")
            writeOutput(picked.joinToString("\n")) { tapAddress(it) }
        }
        advice(if (from + picked.size < total) "type  page ${n + 1}  for the next part." else "that was the end of the output.")
    }

    private fun parseHex(s: String): Long? = s.removePrefix("0x").removePrefix("0X").toLongOrNull(16)

    /** Streams radare2 output into [out] with no size limit. Returns the number of lines. */
    private fun readToFile(stream: InputStream, out: File): Int {
        var lines = 0
        var bytes = 0L
        var reportedAt = 0L
        stream.use { input ->
            out.outputStream().buffered().use { file ->
                val buf = ByteArray(65_536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    file.write(buf, 0, n)
                    bytes += n
                    for (i in 0 until n) if (buf[i] == NEWLINE) lines++
                    if (bytes - reportedAt >= 262_144) {
                        reportedAt = bytes
                        phase = "[2/3] reading output: $lines lines, ${bytes / 1024} KB so far"
                    }
                }
            }
        }
        return lines
    }

    /** The first [DISPLAY_LINES] lines of [file], for the screen. */
    private fun displayHead(file: File): String =
        file.useLines { seq -> seq.take(DISPLAY_LINES).joinToString("\n") }

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
        say(Console.Style.INFO, "[1/2] reading file info (${f.length()} bytes)")
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

    private fun decodeManifest(path: String) {
        ZipFile(File(path)).use { zf ->
            val entry = zf.getEntry("AndroidManifest.xml")
            if (entry == null) {
                say(Console.Style.WARN, "No AndroidManifest.xml in this file.")
                return@use
            }
            if (entry.size > MAX_MANIFEST) {
                say(Console.Style.WARN, "Manifest is too large to decode here (${entry.size} bytes).")
                return@use
            }
            say(Console.Style.INFO, "[1/3] extracting AndroidManifest.xml (${entry.size} bytes)")
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            say(Console.Style.INFO, "[2/3] decoding binary XML")
            val xml = ManifestDecoder.decode(bytes)
            say(Console.Style.INFO, "[3/3] decoded ${xml.lines().size} lines")
            ui {
                write(Console.Style.HEADER, "AndroidManifest.xml (decoded)")
                writeOutput(xml)
            }
        }
    }

    // ---- colours and fullscreen -----------------------------------------------

    /** Each category has its own colour, so the buttons are easy to tell apart. */
    private fun colourButtons() {
        val colours = mapOf(
            R.id.fileMenuButton to "#2979FF",       // FILE: blue
            R.id.runButton to "#00C853",            // RUN: green
            R.id.functionsPickButton to "#00B8D4",  // FUNCTIONS: cyan
            R.id.runMenuButton to "#00C853",        // RUN menu: green
            R.id.findMenuButton to "#FF9100",       // FIND: orange
            R.id.viewMenuButton to "#AA00FF",       // VIEW: purple
            R.id.fullscreenButton to "#607D8B",     // FULL: slate
            R.id.tabMenuButton to "#00897B",        // TAB: teal
            R.id.addTabButton to "#00897B",         // +: teal
        )
        for ((id, hex) in colours) {
            findViewById<Button>(id).apply {
                // A coloured shape, not a tint: the tint did not show on this theme.
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18f
                    setColor(Color.parseColor(hex))
                }
                stateListAnimator = null
                elevation = 0f
                setTextColor(Color.WHITE)
            }
        }
    }

    private var fullscreen = false

    /** Hides the controls and the system bars so the console fills the screen. Back leaves it. */
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        findViewById<View>(R.id.controlsPanel).visibility = if (fullscreen) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility = if (fullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
        if (fullscreen) say(Console.Style.INFO, "fullscreen on. Press BACK to show the controls again.")
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (fullscreen) {
            toggleFullscreen()
        } else {
            super.onBackPressed()
        }
    }

    // ---- troubleshooting -----------------------------------------------------

    /** Runs a short command and returns its output, or null if it fails or takes over a minute. */
    private fun quickRun(vararg args: String): String? {
        return try {
            val process = ProcessBuilder(*args).redirectErrorStream(true).directory(filesDir)
                .apply { environment()["HOME"] = filesDir.absolutePath }
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroy()
                null
            } else if (process.exitValue() == 0) {
                text
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Checks the engine, the file, the analysis and the storage, and says what to do for each failure. */
    private fun troubleshoot() {
        worker.execute {
            say(Console.Style.HEADER, "Troubleshooting: checking the tool, the file and storage")
            val checks = mutableListOf<Triple<String, Boolean, String>>()
            val engineOk = r2Binary.exists()
            checks += Triple("radare2 engine is in this build", engineOk,
                "the APK was built without the engine. Rebuild it with GitHub Actions.")
            val version = if (engineOk) quickRun(r2Binary.absolutePath, "-v") else null
            val versionLine = version?.lineSequence()?.firstOrNull()?.trim()?.take(50)
            checks += Triple("radare2 engine starts (${versionLine ?: "no answer"})", version != null,
                "the engine did not start on this phone. Check the Android version and rebuild the APK.")
            val file = targetPath?.let { File(it) }
            val fileOk = file != null && file.exists() && file.length() > 0
            checks += Triple("a file is loaded (${loadedName ?: "none"})", fileOk,
                "tap OPEN and pick a file. Then run troubleshooting again.")
            if (engineOk && fileOk) {
                val info = quickRun(r2Binary.absolutePath, "-N", "-qc", "i", file!!.absolutePath) ?: ""
                val readable = info.contains("arch")
                checks += Triple("radare2 can read this file format", readable,
                    "radare2 does not know this format. For an APK use 20 and 21; for other files use 2 and 9.")
            }
            val storageOk = outputDir().canWrite() && outputDir().usableSpace > 50L * 1024 * 1024
            checks += Triple("output folder is writable with 50 MB free", storageOk,
                "free some phone storage. Outputs are saved in Android > data > com.msa.r2mobile > files.")
            val lastOk = lastOutput?.exists() == true
            checks += Triple("last command output was saved", lastOk,
                "no output yet. Run a command first, for example 1 or i.")

            val failed = checks.filter { !it.second }
            checks.forEach { (name, ok, _) ->
                say(if (ok) Console.Style.OK else Console.Style.ERROR, "${if (ok) "PASS" else "FAIL"}  $name")
            }
            if (failed.isEmpty()) {
                advice("all checks passed. If one function still fails, read its message, then type 'help'.")
            } else {
                say(Console.Style.WARN, "${failed.size} check(s) failed. Fix the first one:")
                advice(failed.first().third)
            }
        }
    }

    // ---- converters ---------------------------------------------------------

    private fun convert(args: String) {
        if (args.isBlank() || args.trim() == "help") {
            ui {
                write(Console.Style.HEADER, "Converters")
                writeOutput(Converter.HELP)
            }
            advice("try  conv hex 0x1F  or  conv epoch 1700000000")
            return
        }
        try {
            val lines = Converter.run(args)
            ui {
                write(Console.Style.HEADER, "conv ${args.trim()}")
                lines.forEach { write(Console.Style.OUTPUT, it) }
            }
            advice("type  conv help  for all converters. Results are in the console above.")
        } catch (e: Exception) {
            say(Console.Style.ERROR, "conv: ${e.message ?: "that value is not valid"}")
            advice("examples: conv hex 0x1F,  conv epoch 1700000000,  conv b64e hello")
        }
    }

    // ---- EDIT mode, byte editor, tabs, NEXT helper ----------------------------

    private fun toggleEdit() {
        editMode = !editMode
        if (editMode) {
            say(Console.Style.OK, "EDIT mode on. Tap an address to open the byte editor.")
            advice("tap an address from the output, change the hex, then press Compile copy. Full steps: FIND > Step-by-step patching guide.")
        } else {
            say(Console.Style.INFO, "READ mode on. Tapping an address reads the code again.")
            advice("tap an address to read it, or open FIND > NEXT step.")
        }
    }

    /** Reads up to [count] bytes at [offset] of [file]. Returns null when the offset is outside the file. */
    private fun readBytesAt(file: File, offset: Long, count: Int): ByteArray? {
        if (offset < 0 || offset >= file.length()) return null
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(count)
            val n = raf.read(buf)
            return if (n <= 0) null else buf.copyOf(n)
        }
    }

    /** EDIT mode: shows the 16 bytes at an address, so they can be changed and compiled into a copy. */
    private fun openEditor(addr: String) {
        val path = targetPath
        val name = loadedName
        if (path == null || name == null) {
            say(Console.Style.WARN, "EDIT needs a loaded file.")
            advice("open a file with FILE > Open a file, then tap an address.")
            return
        }
        val current = parseHex(addr)?.let { readBytesAt(File(path), it, 16) }
        val offsetBox = EditText(this).apply {
            setText(addr)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val bytesBox = EditText(this).apply {
            hint = "new bytes in hex, e.g. 1f 20 03 d5"
            inputType = InputType.TYPE_CLASS_TEXT
            if (current != null) setText(BytePatch.hex(current))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(offsetBox)
            addView(bytesBox)
        }
        val note = if (current == null) {
            "No bytes found at that offset. Type a valid offset in the first box."
        } else {
            "The second box shows the 16 bytes at this offset. Change the hex, then Compile copy. " +
                "The original file is not changed. For ELF and PE files the address is not the file offset: see EDIT CODE in the guide."
        }
        AlertDialog.Builder(this)
            .setTitle("Edit code in $name")
            .setMessage(note)
            .setView(box)
            .setPositiveButton("Compile copy") { _, _ ->
                patchLoaded(path, name, offsetBox.text.toString(), bytesBox.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Looks at what has been done so far and names the most useful next action. */
    private fun nextStep() {
        val hasOutput = lastOutput?.exists() == true
        when {
            targetPath == null -> advice("no file loaded. Tap FILE > Open a file and pick the file you want to study.")
            !hasOutput -> advice("file is loaded. Type 1 (file info), then 11 (auto-analyze) so functions appear.")
            editMode -> advice("EDIT mode is on. Tap an address in the output to open the byte editor.")
            else -> advice("output is ready. Tap an address to read that code, or FIND > Find a word to search for text such as license.")
        }
    }

    // Tabs: each tab keeps its own console and last output. [console] and [lastOutput] always belong to the active tab.

    private fun saveTab() {
        val t = tabs[activeTab]
        t.console = console
        t.output = lastOutput
    }

    private fun showTab(i: Int) {
        activeTab = i
        val t = tabs[i]
        console = t.console
        lastOutput = t.output
        updateTabButton()
        ui { }
    }

    private fun updateTabButton() {
        findViewById<Button>(R.id.tabMenuButton).text = "${tabs[activeTab].name} \u25BC"
    }

    private fun switchTab(i: Int) {
        if (running != null) {
            say(Console.Style.WARN, "A command is running. Wait for it, or press STOP, before switching tabs.")
            return
        }
        if (i == activeTab) return
        saveTab()
        showTab(i)
        advice("this tab has its own output. Use the TAB menu to go back to another tab.")
    }

    private fun addTab() {
        if (running != null) {
            say(Console.Style.WARN, "A command is running. Wait for it, or press STOP, before adding a tab.")
            return
        }
        saveTab()
        tabs += Tab("Tab ${tabs.size + 1}", Console(), null)
        showTab(tabs.size - 1)
        say(Console.Style.HEADER, "New ${tabs[activeTab].name}. Type a number, a 0x address, or a command, or open FUNCTIONS.")
        advice("research in this tab; the TAB menu switches back to the others.")
    }

    private fun tabMenu(): List<Pair<String, () -> Unit>> =
        tabs.mapIndexed { i, t -> item((if (i == activeTab) "> " else "   ") + t.name) { switchTab(i) } } +
            listOf(item("+ Add a new tab") { addTab() })

    /** Loads a file chosen in the file manager, the same way FILE > Open does. */
    private fun loadPath(path: String) {
        val f = File(path)
        if (!f.isFile) {
            say(Console.Style.WARN, "Not a file: $path")
            return
        }
        targetPath = f.absolutePath
        loadedName = f.name
        val category = FileCategory.detect(f)
        loaded = category
        filter = category
        say(Console.Style.OK, "loaded ${f.name} (${category.label})")
        advice("tap FUNCTIONS to pick a step, or type a number in the box and press RUN.")
    }

    // ---- DEX tools: regex, method and class filters, Java name translation ----

    private fun typeInBox(text: String) {
        cmdInput.setText(text)
        cmdInput.setSelection(cmdInput.text.length)
        cmdInput.requestFocus()
    }

    /** Keeps the lines of the last output that match a regular expression (case-insensitive). */
    private fun regexFilter(pattern: String) {
        if (pattern.isEmpty()) {
            say(Console.Style.WARN, "Use: regex PATTERN, e.g.  regex Ljava/net/.*")
            advice("type  regex  then your pattern. It is checked against each line of the last output.")
            return
        }
        val rx = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            say(Console.Style.WARN, "Not a valid pattern: ${e.message?.lineSequence()?.firstOrNull() ?: pattern}")
            advice("examples:  regex Lcom/.*  or  regex (?i)password  or  regex invoke-.*")
            return
        }
        filterLast("regex '$pattern'") { rx.containsMatchIn(it) }
    }

    /** Keeps the lines that contain a method or class name, ignoring case. */
    private fun containsFilter(kind: String, name: String) {
        if (name.isEmpty()) {
            say(Console.Style.WARN, "Use: $kind NAME, e.g.  $kind onCreate")
            advice("type  $kind  then the name to look for.")
            return
        }
        filterLast("$kind '$name'") { it.contains(name, ignoreCase = true) }
    }

    /** Replaces smali class descriptors (Ljava/lang/String;) with Java names (java.lang.String) in the last output. */
    private fun translateLast() {
        val file = lastOutput
        if (file == null || !file.exists() || file.length() == 0L) {
            say(Console.Style.WARN, "No output to translate yet. Run a command first.")
            advice("run  ic  or  afl  first, then type  translate.")
            return
        }
        val rx = Regex("L[A-Za-z0-9_\\$/]+;")
        var total = 0
        val picked = ArrayList<String>()
        file.useLines { seq ->
            seq.forEach { line ->
                total++
                if (picked.size < MAX_PICK) {
                    picked += rx.replace(line) { m ->
                        try { Converter.smaliToJava(m.value) } catch (e: Exception) { m.value }
                    }
                }
            }
        }
        showFiltered(picked, total, "translated to Java names")
    }

    // ---- dropdown categories ----------------------------------------------

    /** Opens a list of actions under [anchor]. Each label runs its action when chosen. */
    private fun dropdown(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val popup = PopupMenu(this, anchor)
        items.forEachIndexed { i, item -> popup.menu.add(0, i, i, item.first) }
        popup.setOnMenuItemClickListener { mi ->
            items[mi.itemId].second.invoke()
            true
        }
        popup.show()
    }

    private fun item(label: String, action: () -> Unit): Pair<String, () -> Unit> = Pair(label, action)

    private fun fileMenu(): List<Pair<String, () -> Unit>> = listOf(
        item("Open a file") { pickFile.launch(arrayOf("*/*")) },
        item("Save console to a file") { saveLog() },
        item("Clear the screen") { clearConsole() },
        item("Encrypt the file with a password (23)") { numbered(23) },
        item("Decrypt a .r2enc file (24)") { numbered(24) },
        item("Patch bytes, writes a copy (25)") { numbered(25) },
        item("Dual-pane file manager") { fileManagerResult.launch(Intent(this, FileManagerActivity::class.java)) },
    )

    private fun runMenu(): List<Pair<String, () -> Unit>> = listOf(
        item("Run what is in the box") { runTyped() },
        item("Analyze the address in the box") { analyzeInput() },
        item("Block diagram of the code flow (27)") { numbered(27) },
        item("Stop a running command") { stopRunning(quiet = false) },
    )

    private fun findMenu(): List<Pair<String, () -> Unit>> = listOf(
        item("List the functions (12)") { showFunctionMenu() },
        item("Filter by file type") { showFilterMenu() },
        item("Find a word: type it after find") {
            cmdInput.setText("find ")
            cmdInput.setSelection(cmdInput.text.length)
            cmdInput.requestFocus()
        },
        item("DEX: list classes (ic)") { runR2("ic", "type  regex Lcom/  or  class NAME  to narrow the list.") },
        item("DEX: filter by method name") { typeInBox("method ") },
        item("DEX: filter by class name") { typeInBox("class ") },
        item("DEX: filter by regex") { typeInBox("regex ") },
        item("DEX: translate smali names to Java") { translateLast() },
        item("NEXT step: what should I do now?") { nextStep() },
        item("Step-by-step patching guide") { showGuide() },
        item("radare2 reference with examples (28)") { showReference() },
    )

    private fun viewMenu(): List<Pair<String, () -> Unit>> = listOf(
        item("View settings: text size, hidden files, pane layout") { ViewConfig.dialog(this@MainActivity) { applyView() } },
        item(if (editMode) "Mode: EDIT is on. Tap for READ" else "Mode: READ is on. Tap for EDIT") { toggleEdit() },
        item("Scroll to the top") { scrollView.fullScroll(ScrollView.FOCUS_UP) },
        item("Scroll to the bottom") { scrollView.fullScroll(ScrollView.FOCUS_DOWN) },
        item("Help: list of shortcuts") { showHelp() },
    )

    // ---- block diagram and reference ---------------------------------------

    private fun showReference() {
        ui {
            write(Console.Style.HEADER, "radare2 reference")
            writeOutput(R2Reference.TEXT)
        }
        advice("pick a function from the FIND menu, or type 27 for the block diagram.")
    }

    /** Analyses the code, then turns radare2's JSON block list into a readable flow. */
    private fun runBlocks(next: String) {
        runR2("aaa;s entry0;agfj", next) { raw -> renderBlocks(raw) }
    }

    private fun renderBlocks(raw: String) {
        val start = Regex("\\[\\s*\\{").find(raw)?.range?.first
        if (start == null) {
            say(Console.Style.WARN, "No block data at the entry point.")
            advice("run 11 (auto-analyze) first, then 27 again.")
            return
        }
        try {
            val fn = (JSONTokener(raw.substring(start)).nextValue() as JSONArray).getJSONObject(0)
            val blocks = fn.getJSONArray("blocks")
            val text = StringBuilder()
            text.append("function ${fn.optString("name", "entry0")}: ${blocks.length()} blocks\n")
            text.append("Each block is a run of instructions with no jumps inside it.\n\n")
            var loops = 0
            var branches = 0
            var returns = 0
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                val addr = b.getLong("addr")
                val jump = b.optLong("jump", -1L)
                val fail = b.optLong("fail", -1L)
                text.append("BLOCK ${"0x%08x".format(addr)}  (${b.optLong("size")} bytes)\n")
                if (jump >= 0) {
                    val kind = if (jump <= addr) "loop back" else "forward"
                    if (jump <= addr) loops++ else branches++
                    text.append("   if true  -> ${"0x%08x".format(jump)}  $kind\n")
                }
                if (fail >= 0) {
                    val kind = if (fail <= addr) "loop back" else "forward"
                    if (fail <= addr) loops++ else branches++
                    text.append("   if false -> ${"0x%08x".format(fail)}  $kind\n")
                }
                if (jump < 0 && fail < 0) {
                    text.append("   ends the function (return)\n")
                    returns++
                }
                text.append("\n")
            }
            text.append("SUMMARY: $loops loop edge(s), $branches forward branch(es), $returns return block(s).\n")
            text.append("A 'loop back' edge means the code jumps to an earlier block and repeats.\n")
            ui {
                write(Console.Style.HEADER, "Block diagram of the code flow")
                writeOutput(text.toString()) { tapAddress(it) }
            }
            advice("tap an address to read the code of that block. 'loop back' means a loop.")
        } catch (e: Exception) {
            say(Console.Style.ERROR, "could not read the block data: ${e.message}")
            advice("run 11 (auto-analyze), then 27 again. If it fails again, use 14 (flow chart).")
        }
    }

    // ---- encryption, patching and the guide -------------------------------

    /** Folder for every output file (encrypted, decrypted, patched). Shown to the user after each save. */
    private fun outputDir(): File = getExternalFilesDir(null) ?: filesDir

    private fun showGuide() {
        ui {
            write(Console.Style.HEADER, "Patching guide")
            writeOutput(Guide.PATCHING)
        }
        advice("tap FUNCTIONS and pick 25 to patch a copy, or 23 to encrypt the file first.")
    }

    private fun askPassword(title: String, onOk: (CharArray) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "password"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Remember this password. Without it the file cannot be opened again.")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().toCharArray()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun encryptLoaded(target: String, name: String) {
        askPassword("Encrypt $name") { pw ->
            if (pw.isEmpty()) {
                say(Console.Style.WARN, "No password typed. Nothing was encrypted.")
                advice("tap 23 again and type a password.")
                return@askPassword
            }
            worker.execute {
                val out = File(outputDir(), "msar2_" + name.removePrefix("msar2_") + ".r2enc")
                try {
                    say(Console.Style.INFO, "[1/3] reading ${File(target).length()} bytes")
                    say(Console.Style.INFO, "[2/3] deriving key (100000 rounds) and encrypting")
                    FileCrypto.encrypt(File(target), out, pw)
                    say(Console.Style.OK, "[3/3] saved ${out.name} (${out.length()} bytes)")
                    say(Console.Style.INFO, "location: ${out.absolutePath}")
                    say(Console.Style.INFO, "find it in Files > Android > data > com.msa.r2mobile > files")
                    advice("keep the password safe. Function 24 decrypts this file.")
                } catch (e: Exception) {
                    out.delete()
                    say(Console.Style.ERROR, "encrypt failed: ${e.message}")
                    advice(Advice.FAILED)
                }
            }
        }
    }

    private fun decryptLoaded(target: String, name: String) {
        askPassword("Decrypt $name") { pw ->
            if (pw.isEmpty()) {
                say(Console.Style.WARN, "No password typed. Nothing was decrypted.")
                advice("tap 24 again and type the password.")
                return@askPassword
            }
            worker.execute {
                val out = File(outputDir(), "msar2_" + name.removePrefix("msar2_").removeSuffix(".r2enc"))
                try {
                    say(Console.Style.INFO, "[1/3] reading ${File(target).length()} bytes")
                    say(Console.Style.INFO, "[2/3] deriving key and decrypting")
                    FileCrypto.decrypt(File(target), out, pw)
                    targetPath = out.absolutePath
                    loadedName = out.name
                    val category = FileCategory.detect(out)
                    loaded = category
                    filter = category
                    say(Console.Style.OK, "[3/3] decrypted to ${out.name} (${out.length()} bytes), now the target")
                    say(Console.Style.INFO, "location: ${out.absolutePath}")
                    advice("the decrypted file is loaded. Run 1 for its info.")
                } catch (e: Exception) {
                    out.delete()
                    say(Console.Style.ERROR, "decrypt failed: wrong password, or not an R2 encrypted file (${e.message})")
                    advice("check the password, or make sure the loaded file came from function 23.")
                }
            }
        }
    }

    private fun askPatch(target: String, name: String) {
        val offsetBox = EditText(this).apply {
            hint = "offset in hex, e.g. 0x1a40"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val bytesBox = EditText(this).apply {
            hint = "new bytes in hex, e.g. 1f 20 03 d5"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(offsetBox)
            addView(bytesBox)
        }
        AlertDialog.Builder(this)
            .setTitle("Patch $name")
            .setMessage("Writes the new bytes into a COPY. Your original is not changed. See 26 for the steps.")
            .setView(box)
            .setPositiveButton("Patch") { _, _ ->
                patchLoaded(target, name, offsetBox.text.toString(), bytesBox.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun patchLoaded(target: String, name: String, offsetText: String, bytesText: String) {
        worker.execute {
            try {
                val offset = BytePatch.parseOffset(offsetText)
                val newBytes = BytePatch.parseHex(bytesText)
                var out = File(outputDir(), "msar2_" + name.removePrefix("msar2_"))
                // Patching a copy that is already loaded must not overwrite the file it reads from.
                if (out.absolutePath == File(target).absolutePath) {
                    out = File(outputDir(), "msar2_edit_" + name.removePrefix("msar2_"))
                }
                say(Console.Style.INFO, "[1/3] copying ${File(target).length()} bytes")
                say(Console.Style.INFO, "[2/3] writing ${newBytes.size} bytes at 0x${"%x".format(offset)}")
                val before = BytePatch.patch(File(target), out, offset, newBytes)
                targetPath = out.absolutePath
                loadedName = out.name
                val category = FileCategory.detect(out)
                loaded = category
                filter = category
                say(Console.Style.OK, "[3/3] patched copy saved: ${out.name}")
                say(Console.Style.INFO, "before: ${BytePatch.hex(before)}")
                say(Console.Style.INFO, "after:  ${BytePatch.hex(newBytes)}")
                say(Console.Style.INFO, "location: ${out.absolutePath}")
                if (category == FileCategory.APK) {
                    say(Console.Style.WARN, "APK: this copy is unsigned. Android will not install it until it is re-signed.")
                }
                advice("check the result with  s 0x${"%x".format(offset)};px 16  Then patch again or export.")
            } catch (e: Exception) {
                say(Console.Style.ERROR, "patch failed: ${e.message}")
                advice("check the offset and bytes, e.g. offset 0x1a40 and bytes 1f 20 03 d5.")
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
        /** Lines shown on screen at once; the full output stays in a file. */
        private const val DISPLAY_LINES = 3_000
        private const val MAX_PICK = 200_000
        private const val NEWLINE: Byte = 10
        private const val TIMEOUT_SECONDS = 900L
        private const val MAX_MANIFEST = 4_000_000L
        private val ADDRESS_INPUT = Regex("0x[0-9a-fA-F]+")
    }
}
