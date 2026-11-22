package com.msa.r2mobile

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the bundled radare2 binary (packaged as jniLibs/arm64-v8a/libr2.so so Android
 * extracts it into nativeLibraryDir with execute permission) one command at a time.
 * Each Run executes: libr2.so -N -qc "<command>" <loaded file>
 */
class MainActivity : ComponentActivity() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var outputText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var cmdInput: EditText
    private var targetPath: String? = null

    private val r2Binary: File by lazy { File(applicationInfo.nativeLibraryDir, "libr2.so") }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        worker.execute {
            try {
                val dest = File(cacheDir, "target.bin")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("cannot open selected file")
                targetPath = dest.absolutePath
                append("loaded ${dest.length()} bytes -> ${dest.absolutePath}")
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

        findViewById<Button>(R.id.openButton).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.runButton).setOnClickListener { runCommand() }

        append("radare2 binary: ${r2Binary.absolutePath} (exists=${r2Binary.exists()})")
        append("Open a binary, then enter an r2 command.")
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun runCommand() {
        val cmd = cmdInput.text.toString().trim()
        val target = targetPath
        if (cmd.isEmpty()) return
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

    private fun append(line: String) {
        runOnUiThread {
            outputText.append(line + "\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
