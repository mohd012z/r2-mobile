package com.msa.r2mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Dual-pane file manager. Two folder lists sit side by side (or stacked, see View settings).
 * Select files, copy/cut/paste, rename, delete, share, zip/unzip, encrypt/decrypt, search,
 * and send a file to the R2 console. Pinch on the panes to change the text size.
 */
class FileManagerActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_SEARCH = 5_000
    }

    /** One folder pane: its header, its list, and the files the user has marked. */
    private inner class Pane(start: File) {
        var dir: File = start
        val marked = LinkedHashSet<File>()
        /** Search results shown instead of the folder, or null when browsing. */
        var search: List<File>? = null
        private var rows: List<File?> = emptyList()   // null row = ".." (go up)

        private val header = TextView(this@FileManagerActivity).apply {
            textSize = 12f
            setPadding(10, 8, 10, 8)
            setBackgroundColor(0xFF1E2A36.toInt())
            setTextColor(0xFFD4D4D4.toInt())
        }

        private val adapter = object : ArrayAdapter<String>(
            this@FileManagerActivity, android.R.layout.simple_list_item_1, ArrayList<String>(),
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = ViewConfig.fileText
                v.typeface = ViewConfig.typeface()
                v.setTextColor(ViewConfig.palette().text)
                return v
            }
        }

        private val list = ListView(this@FileManagerActivity).apply {
            adapter = this@Pane.adapter
            setOnItemClickListener { _, _, pos, _ ->
                active = this@Pane
                if (pos >= rows.size) return@setOnItemClickListener
                val row = rows[pos]
                when {
                    row == null -> goUp()
                    row.isDirectory && search == null -> go(row)
                    else -> toggle(row)
                }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                active = this@Pane
                if (pos < rows.size) rows[pos]?.let { toggle(it) }
                true
            }
        }

        val root = LinearLayout(this@FileManagerActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        fun go(d: File) {
            dir = d
            search = null
            marked.clear()
            refresh()
        }

        fun goUp() {
            dir.parentFile?.let { go(it) }
        }

        fun toggle(f: File) {
            if (!marked.add(f)) marked.remove(f)
            refresh()
        }

        /** Files and folders currently listed, without the ".." row. */
        fun visible(): List<File> = rows.filterNotNull()

        fun refresh() {
            val results = search
            rows = if (results != null) {
                results
            } else {
                val kids = dir.listFiles()
                    ?.filter { ViewConfig.showHidden || !it.name.startsWith(".") }
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
                (if (dir.parentFile != null) listOf<File?>(null) else emptyList()) + kids
            }
            adapter.clear()
            adapter.addAll(rows.map { label(it, results != null) })
            adapter.notifyDataSetChanged()
            val mode = if (results != null) "SEARCH: ${results.size} found under ${dir.name}" else dir.absolutePath
            val readable = if (dir.canRead() || results != null) "" else "  (cannot read: Android blocks this folder)"
            header.text = "$mode$readable   marked: ${marked.size}"
            val p = ViewConfig.palette()
            header.setBackgroundColor(p.header)
            header.setTextColor(p.text)
            list.setBackgroundColor(p.bg)
        }

        private fun label(f: File?, searching: Boolean): String {
            if (f == null) return "[..] parent folder"
            val mark = if (f in marked) "✔ " else ""
            val name = if (searching) f.absolutePath.removePrefix(dir.absolutePath + File.separator) else f.name
            return if (f.isDirectory) "$mark[DIR] $name" else "$mark$name   ${humanSize(f.length())}"
        }
    }

    private lateinit var left: Pane
    private lateinit var right: Pane
    /** The pane the user tapped last. Operations act on it. */
    private var active: Pane? = null
    private val clipboard = ArrayList<File>()
    private var clipMove = false
    private lateinit var root: LinearLayout
    private lateinit var detector: ScaleGestureDetector
    /** Zip, encrypt, decrypt and search run here so large files do not freeze the screen. */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewConfig.load(this)
        left = Pane(getExternalFilesDir(null) ?: filesDir)
        right = Pane(filesDir)
        active = left
        detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                ViewConfig.fileText = (ViewConfig.fileText * d.scaleFactor).coerceIn(9f, 32f)
                refreshBoth()
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                ViewConfig.save(this@FileManagerActivity)
            }
        })
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
        }
        setContentView(root)
        buildLayout()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /** Pinch zoom is read here, before the lists get the touch, so it works anywhere on the screen. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun buildLayout() {
        root.removeAllViews()
        root.setBackgroundColor(ViewConfig.palette().bg)
        val sideBySide = ViewConfig.sideBySide
        val panes = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val paneParams = if (sideBySide) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        panes.addView(left.root, paneParams)
        panes.addView(right.root, paneParams)
        root.addView(panes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttonRow(
            "Actions" to { showActions() },
            "Search" to { searchDialog() },
            "Open in R2" to { openInR2() },
            "Settings" to { ViewConfig.dialog(this) { buildLayout() } },
        ))
        refreshBoth()
    }

    private fun buttonRow(vararg items: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEach { (label, action) ->
                addView(Button(this@FileManagerActivity).apply {
                    text = label
                    textSize = ViewConfig.buttonText
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun refreshBoth() {
        left.refresh()
        right.refresh()
    }

    private fun other(p: Pane): Pane = if (p === left) right else left

    private fun toast(text: String) = runOnUiThread {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** The marked files in the active pane. Shows a message and returns null when nothing is marked. */
    private fun targets(): List<File>? {
        val p = active ?: return null
        if (p.marked.isEmpty()) {
            toast("mark files first: tap a file so it shows a tick, or use Actions > Select all.")
            return null
        }
        return p.marked.toList()
    }

    private fun showActions() {
        val items: List<Pair<String, () -> Unit>> = listOf(
            "Select all in this pane" to { active?.let { it.marked.addAll(it.visible()); refreshBoth() } },
            "Clear marks" to { active?.marked?.clear(); refreshBoth() },
            "Copy marked" to { prepare(move = false) },
            "Cut marked (moves on paste)" to { prepare(move = true) },
            "Paste here" to { paste() },
            "Share marked" to { share() },
            "Rename (one item)" to { rename() },
            "New folder" to { newFolder() },
            "Compress marked (zip, tar.gz, gz)" to { zipMarked() },
            "Extract archive (zip, tar, tar.gz, gz)" to { unzipOne() },
            "View file (text or hex)" to { viewFile() },
            "Encrypt marked files (password)" to { encryptMarked() },
            "Decrypt .r2enc files (password)" to { decryptMarked() },
            "Delete marked" to { deleteMarked() },
            "Refresh" to { refreshBoth() },
        )
        AlertDialog.Builder(this)
            .setTitle("Actions")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun prepare(move: Boolean) {
        val files = targets() ?: return
        clipboard.clear()
        clipboard += files
        clipMove = move
        toast("${files.size} item(s) ready. Open the other or this pane and choose Paste here.")
    }

    private fun paste() {
        val dest = active?.dir ?: return
        if (clipboard.isEmpty()) {
            toast("nothing copied or cut yet.")
            return
        }
        worker.execute {
            var done = 0
            for (src in clipboard.toList()) {
                if (!src.exists()) continue
                if (src.isDirectory && dest.canonicalPath.startsWith(src.canonicalPath)) {
                    toast("cannot paste ${src.name} into itself.")
                    continue
                }
                val target = uniqueTarget(File(dest, src.name))
                try {
                    if (clipMove) {
                        if (!src.renameTo(target)) {
                            src.copyRecursively(target, overwrite = false)
                            src.deleteRecursively()
                        }
                    } else {
                        src.copyRecursively(target, overwrite = false)
                    }
                    done++
                } catch (e: Exception) {
                    toast("failed ${src.name}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            if (clipMove) clipboard.clear()
            runOnUiThread {
                active?.marked?.clear()
                toast("pasted $done item(s)")
                refreshBoth()
            }
        }
    }

    private fun deleteMarked() {
        val files = targets() ?: return
        val p = active ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete ${files.size} item(s)?")
            .setMessage("Folders are deleted with everything inside them. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val failed = files.count { !it.deleteRecursively() }
                p.marked.clear()
                toast(if (failed == 0) "deleted ${files.size} item(s)" else "$failed item(s) could not be deleted")
                refreshBoth()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rename() {
        val files = targets() ?: return
        if (files.size != 1) {
            toast("mark exactly one item to rename.")
            return
        }
        val f = files[0]
        val box = EditText(this).apply {
            setText(f.name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Rename") { _, _ ->
                val name = box.text.toString().trim()
                val target = File(f.parentFile, name)
                when {
                    name.isEmpty() || name.contains('/') -> toast("type a name without slashes.")
                    target.exists() -> toast("$name already exists here.")
                    f.renameTo(target) -> {
                        active?.marked?.clear()
                        refreshBoth()
                    }
                    else -> toast("could not rename ${f.name}.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newFolder() {
        val p = active ?: return
        val box = EditText(this).apply {
            hint = "folder name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("New folder in ${p.dir.name}")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val name = box.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) {
                    toast("type a folder name without slashes.")
                } else {
                    val made = File(p.dir, name).mkdirs()
                    toast(if (made) "created $name" else "could not create $name")
                    refreshBoth()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun share() {
        val files = targets() ?: return
        val uris = ArrayList<Uri>()
        for (f in files.filter { it.isFile }) {
            try {
                uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            } catch (e: IllegalArgumentException) {
                toast("${f.name} is outside the shared folders. Copy it into the app folder first.")
            }
        }
        if (uris.isEmpty()) {
            toast("nothing to share: mark files, not folders.")
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share"))
    }

    private fun zipMarked() {
        val files = targets() ?: return
        val dir = active?.dir ?: return
        val formats = Archive.Format.values()
        AlertDialog.Builder(this)
            .setTitle("Compress ${files.size} item(s) as")
            .setItems(formats.map { it.label }.toTypedArray()) { _, i ->
                val format = formats[i]
                worker.execute {
                    val base = if (files.size == 1) files[0].name else "archive"
                    val out = uniqueTarget(File(dir, base + format.suffix))
                    try {
                        Archive.create(files, out, format)
                        toast("made ${out.name}")
                    } catch (e: Exception) {
                        out.delete()
                        toast("compress failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                    runOnUiThread {
                        active?.marked?.clear()
                        refreshBoth()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unzipOne() {
        val files = targets() ?: return
        val f = files.singleOrNull() ?: run {
            toast("mark exactly one archive file.")
            return
        }
        if (!Archive.isArchive(f.name)) {
            toast("${f.name} is not a supported archive: zip, tar, tar.gz or gz.")
            return
        }
        worker.execute {
            val dest = uniqueTarget(File(f.parentFile, Archive.stem(f.name)))
            try {
                Archive.extract(f, dest)
                toast("extracted to ${dest.name}")
            } catch (e: Exception) {
                toast("extract failed: ${e.message ?: e.javaClass.simpleName}")
            }
            runOnUiThread {
                active?.marked?.clear()
                refreshBoth()
            }
        }
    }

    /** Shows the first 64 KB of one file: as text when it is text, otherwise as a hex dump. */
    private fun viewFile() {
        val files = targets() ?: return
        val f = files.singleOrNull() ?: run {
            toast("mark exactly one file to view.")
            return
        }
        if (f.isDirectory) {
            toast("choose a file, not a folder.")
            return
        }
        worker.execute {
            val bytes = try {
                readPreview(f)
            } catch (e: Exception) {
                toast("cannot read ${f.name}: ${e.message ?: e.javaClass.simpleName}")
                return@execute
            }
            val asText = looksLikeText(bytes)
            val shown = when {
                bytes.isEmpty() -> "(empty file)"
                asText -> String(bytes, Charsets.UTF_8)
                else -> hexDump(bytes)
            }
            val note = "${f.name}: ${humanSize(f.length())}. Showing the first ${humanSize(bytes.size.toLong())} " +
                (if (asText) "as text." else "as hex (binary file).")
            runOnUiThread {
                val view = TextView(this).apply {
                    typeface = ViewConfig.typeface()
                    textSize = ViewConfig.consoleText
                    setTextIsSelectable(true)
                    setPadding(24, 16, 24, 16)
                }
                view.text = shown
                AlertDialog.Builder(this)
                    .setTitle("View ${f.name}")
                    .setMessage(note)
                    .setView(ScrollView(this).apply { addView(view) })
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    private fun readPreview(f: File): ByteArray {
        val buf = ByteArray(65_536)
        val n = f.inputStream().use { it.read(buf) }
        return if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.any { it == 0.toByte() }) return false
        val texty = bytes.count {
            val c = it.toInt() and 0xFF
            c in 9..13 || c in 32..126 || c >= 128
        }
        return texty >= bytes.size * 0.95
    }

    private fun hexDump(bytes: ByteArray): String = bytes.toList().chunked(16).mapIndexed { i, row ->
        val hex = row.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }.padEnd(47)
        val ascii = row.map { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")
        "%08x  %s  %s".format(i * 16, hex, ascii)
    }.joinToString("\n")

    private fun askPassword(title: String, confirm: Boolean, then: (CharArray) -> Unit) {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val first = EditText(this).apply { hint = "password"; inputType = type }
        val second = EditText(this).apply { hint = "type it again"; inputType = type }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(first)
            if (confirm) addView(second)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("The password is not stored. Lost passwords cannot be recovered.")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val pw = first.text.toString()
                when {
                    pw.isEmpty() -> toast("the password cannot be empty.")
                    confirm && pw != second.text.toString() -> toast("the two passwords do not match.")
                    else -> then(pw.toCharArray())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun encryptMarked() {
        val files = targets()?.filter { it.isFile } ?: return
        if (files.isEmpty()) {
            toast("mark files (not folders) to encrypt.")
            return
        }
        askPassword("Encrypt ${files.size} file(s)", confirm = true) { pw ->
            worker.execute {
                var done = 0
                for (f in files) {
                    val out = uniqueTarget(File(f.parentFile, f.name + ".r2enc"))
                    try {
                        FileCrypto.encrypt(f, out, pw)
                        done++
                    } catch (e: Exception) {
                        out.delete()
                        toast("failed ${f.name}")
                    }
                }
                pw.fill('\u0000')
                runOnUiThread {
                    active?.marked?.clear()
                    toast("encrypted $done file(s). The originals are kept.")
                    refreshBoth()
                }
            }
        }
    }

    private fun decryptMarked() {
        val files = targets()?.filter { it.name.endsWith(".r2enc") } ?: return
        if (files.isEmpty()) {
            toast("mark .r2enc files to decrypt.")
            return
        }
        askPassword("Decrypt ${files.size} file(s)", confirm = false) { pw ->
            worker.execute {
                var done = 0
                for (f in files) {
                    val out = uniqueTarget(File(f.parentFile, f.name.removeSuffix(".r2enc")))
                    try {
                        FileCrypto.decrypt(f, out, pw)
                        done++
                    } catch (e: Exception) {
                        out.delete()
                        toast("could not decrypt ${f.name}: wrong password or damaged file")
                    }
                }
                pw.fill('\u0000')
                runOnUiThread {
                    active?.marked?.clear()
                    toast("decrypted $done file(s)")
                    refreshBoth()
                }
            }
        }
    }

    private fun searchDialog() {
        val p = active ?: return
        val box = EditText(this).apply {
            hint = "part of a name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Search in ${p.dir.name}")
            .setMessage("Looks at file and folder names, including subfolders. Press Refresh to leave the results.")
            .setView(box)
            .setPositiveButton("Search") { _, _ ->
                val q = box.text.toString().trim()
                if (q.isEmpty()) {
                    toast("type part of a name.")
                    return@setPositiveButton
                }
                worker.execute {
                    val found = p.dir.walkTopDown()
                        .filter { ViewConfig.showHidden || !it.name.startsWith(".") }
                        .filter { it.name.contains(q, ignoreCase = true) }
                        .take(MAX_SEARCH)
                        .toList()
                    runOnUiThread {
                        p.search = found
                        p.marked.clear()
                        p.refresh()
                        toast("${found.size} found")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openInR2() {
        val files = targets() ?: return
        val f = files.singleOrNull() ?: run {
            toast("mark exactly one file to open.")
            return
        }
        if (f.isDirectory) {
            toast("choose a file, not a folder.")
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, f.absolutePath))
        finish()
    }

    private fun uniqueTarget(f: File): File {
        if (!f.exists()) return f
        var n = 2
        while (true) {
            val ext = if (f.extension.isEmpty()) "" else ".${f.extension}"
            val candidate = File(f.parentFile, "${f.nameWithoutExtension} ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
