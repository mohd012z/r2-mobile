package com.msa.r2mobile

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Loads one file, detects its type, and runs numbered functions on it.
 * Radare2 functions run the bundled libr2.so; the APK/ZIP functions run natively.
 */
class MainActivity : ComponentActivity() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var cmdInput: EditText

    @Volatile private var targetPath: String? = null
    @Volatile private var loaded: FileCategory? = null
    /** Type filter for the Functions menu. null shows every function. */
    @Volatile private var filter: FileCategory? = null

    private val r2Binary: File by lazy { File(applicationInfo.nativeLibraryDir, "libr2.so") }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        worker.execute {
            try {
                val dest = File(cacheDir, "target.bin")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("cannot open selected file")
                val category = FileCategory.detect(dest)
                targetPath = dest.absolutePath
                loaded = category
                filter = category
                append("loaded ${dest.name} (${dest.length()} bytes)")
                append("detected type: ${category.label}. Filter set to this type.")
                append("Next: tap Functions and choose a number.")
            } catch (e: Exception) {
                append("load error: ${e.message}")
            }
        }
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

        append("R2 Mobile ready.")
        append("Steps: 1) Open binary  2) Functions, pick a number  3) Read the tutorial, tap Run.")
        append("Shortcut: type a function number in the box, e.g. 11, then tap Run.")
        if (!r2Binary.exists()) append("WARNING: libr2.so is missing from the app's native folder.")
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- menus -------------------------------------------------------------

    private fun visibleFeatures(): List<Feature> {
        val f = filter ?: return Catalog.all
        return Catalog.all.filter { f in it.appliesTo }
    }

    private fun showFunctionMenu() {
        val features = visibleFeatures()
        if (features.isEmpty()) {
            append("No functions for this file type.")
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
                append("filter: ${options[which]?.label ?: "All file types"}")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** The tutorial for one function, with a Run button. */
    private fun showTutorial(feature: Feature) {
        val current = loaded
        val status = when {
            current == null -> "No file loaded yet. Tap Open binary first."
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

    // ---- running -----------------------------------------------------------

    private fun runTyped() {
        val text = cmdInput.text.toString().trim()
        if (text.isEmpty()) return
        val number = text.toIntOrNull()
        if (number != null) {
            val feature = Catalog.byNumber(number)
            if (feature == null) append("No function #$number. Choose 1 to ${Catalog.all.size}.")
            else showTutorial(feature)
            return
        }
        runR2(text)
    }

    private fun executeFeature(feature: Feature) {
        val current = loaded
        val target = targetPath
        if (current == null || target == null) {
            append("Open a file first (Open binary).")
            return
        }
        if (current !in feature.appliesTo) {
            append("#${feature.number} does not apply to ${current.label}.")
            return
        }
        append("== #${feature.number} ${feature.name}")
        when (feature.action) {
            Action.R2 -> runR2(feature.command)
            Action.NATIVE_INFO -> background { nativeInfo(target, current) }
            Action.NATIVE_ZIP_LIST -> background { listArchive(target) }
            Action.NATIVE_EXTRACT_DEX -> background { extractDex(target) }
        }
    }

    private fun background(block: () -> Unit) {
        worker.execute {
            try {
                block()
            } catch (e: Exception) {
                append("error: ${e.message}")
            }
        }
    }

    private fun runR2(cmd: String) {
        val target = targetPath
        if (target == null) {
            append("no file loaded: tap Open binary first")
            return
        }
        if (!r2Binary.exists()) {
            append("libr2.so missing from nativeLibraryDir: build and package it first")
            return
        }
        worker.execute {
            append("r2> $cmd")
            try {
                val process = ProcessBuilder(
                    r2Binary.absolutePath, "-N", "-e", "scr.color=0", "-qc", cmd, target
                )
                    .redirectErrorStream(true)
                    .directory(filesDir)
                    .apply { environment()["HOME"] = filesDir.absolutePath }
                    .start()
                val text = process.inputStream.bufferedReader().use { it.readText() }
                val code = process.waitFor()
                append(if (text.isBlank()) "(no output, exit $code)" else "$text\n[exit $code]")
            } catch (e: Exception) {
                append("exec error: ${e.message}")
            }
        }
    }

    // ---- native functions --------------------------------------------------

    private fun nativeInfo(path: String, category: FileCategory) {
        val f = File(path)
        append(
            "name: ${f.name}\n" +
                "size: ${f.length()} bytes\n" +
                "type: ${category.label}\n" +
                "sha256: ${sha256(f)}\n" +
                "first 16 bytes: ${headHex(f, 16)}"
        )
    }

    private fun listArchive(path: String) {
        ZipFile(File(path)).use { zf ->
            val entries = Collections.list(zf.entries())
            append("${entries.size} entries:")
            entries.take(300).forEach { e -> append("%10d  %s".format(e.size, e.name)) }
            if (entries.size > 300) append("... ${entries.size - 300} more not shown")
        }
    }

    private fun extractDex(path: String) {
        val outDir = File(filesDir, "extracted").apply { mkdirs() }
        ZipFile(File(path)).use { zf ->
            val dexEntries = Collections.list(zf.entries()).filter { DEX_NAME.matches(it.name) }
            if (dexEntries.isEmpty()) {
                append("No classes*.dex found in this file.")
                return@use
            }
            dexEntries.forEach { e ->
                val out = File(outDir, e.name)
                zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                append("extracted ${e.name} (${out.length()} bytes)")
            }
            val primary = File(outDir, "classes.dex")
            if (primary.exists()) {
                targetPath = primary.absolutePath
                loaded = FileCategory.DEX
                filter = FileCategory.DEX
                append("Target is now classes.dex. Functions 2 to 19 work on it.")
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

    // ---- output ------------------------------------------------------------

    private fun append(line: String) {
        runOnUiThread {
            outputText.append(line + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    companion object {
        private val DEX_NAME = Regex("classes\\d*\\.dex")
    }
}
