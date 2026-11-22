package com.msa.r2mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
import java.util.concurrent.Executors

/**
 * Dual-pane file manager. Two folder panes, side by side or stacked (View settings).
 * Each pane is limited to one storage place, so "up" never leaves it. Storage places:
 * internal shared storage (/storage/emulated/0), the app's private folder, and any SD card.
 * Mark files, then copy, move, paste, rename, delete, share, compress, extract, encrypt,
 * decrypt, search, view or send to the R2 console.
 */
class FileManagerActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_SEARCH = 5_000
    }

    /** One place a pane can browse: a label for the user and the folder it starts from. */
    private data class Storage(val label: String, val dir: File)

    /** One folder pane. [top] is the storage place it may not leave. */
    private inner class Pane(var top: File, var topLabel: String) {
        var dir: File = top
        val marked = LinkedHashSet<File>()
        /** Search results shown instead of the folder, or null when browsing. */
        var search: List<File>? = null
        private var rows: List<File?> = emptyList()   // null row = ".." (go up)

        private val header = TextView(this@FileManagerActivity).apply {
            textSize = 12f
            setPadding(14, 10, 14, 10)
        }

        private val adapter = object : ArrayAdapter<String>(
            this@FileManagerActivity, android.R.layout.simple_list_item_1, ArrayList<String>(),
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val p = ViewConfig.palette()
                val f = rows.getOrNull(position)
                v.textSize = ViewConfig.fileText
                v.typeface = if (f != null && f.isDirectory) {
                    Typeface.create(ViewConfig.typeface(), Typeface.BOLD)
                } else {
                    ViewConfig.typeface()
                }
                v.setTextColor(p.text)
                v.setPadding(16, 12, 16, 12)
                v.setBackgroundColor(if (f != null && f in marked) p.mark else Color.TRANSPARENT)
                return v
            }
        }

        private val list = ListView(this@FileManagerActivity).apply {
            adapter = this@Pane.adapter
            setDivider(android.graphics.drawable.ColorDrawable(0x33FFFFFF))
            dividerHeight = 1
            setOnItemClickListener { _, _, pos, _ ->
                active = this@Pane
                if (pos >= rows.size) return@setOnItemClickListener
                val row = rows[pos]
                when {
                    row == null -> goUp()
                    row.isDirectory && search == null -> go(row)
                    else -> showFileMenu(this@Pane, row)
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

        private fun inside(d: File): Boolean {
            val p = d.canonicalPath
            val t = top.canonicalPath
            return p == t || p.startsWith(t + File.separator)
        }

        fun go(d: File) {
            dir = if (inside(d)) d else top
            search = null
            marked.clear()
            refresh()
        }

        /** Goes up one folder, but never above the storage place this pane was opened on. */
        fun goUp() {
            if (dir.canonicalPath == top.canonicalPath) {
                toast("this is the top of $topLabel. Use Actions > Storage to switch place.")
                return
            }
            dir.parentFile?.let { go(it) }
        }

        fun toggle(f: File) {
            if (!marked.add(f)) marked.remove(f)
            refresh()
        }

        /** Files and folders listed now, without the ".." row. */
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
                (if (dir.canonicalPath != top.canonicalPath) listOf<File?>(null) else emptyList()) + kids
            }
            adapter.clear()
            adapter.addAll(rows.map { label(it, results != null) })
            adapter.notifyDataSetChanged()

            val p = ViewConfig.palette()
            header.setBackgroundColor(p.header)
            header.setTextColor(p.text)
            list.setBackgroundColor(p.bg)
            header.text = if (results != null) {
                "SEARCH in $topLabel: ${results.size} found   marked ${marked.size}"
            } else {
                val rel = dir.absolutePath.removePrefix(top.absolutePath).ifEmpty { "/" }
                val blocked = if (dir.canRead()) "" else "   (Android blocks this folder)"
                "$topLabel  $rel$blocked   marked ${marked.size}"
            }
        }

        private fun label(f: File?, searching: Boolean): String {
            if (f == null) return "..  (parent folder)"
            val mark = if (f in marked) "✔ " else ""
            val name = if (searching) f.absolutePath.removePrefix(top.absolutePath + File.separator) else f.name
            return if (f.isDirectory) "$mark$name" else "$mark$name   ${humanSize(f.length())}"
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
    /** Zip, encrypt, decrypt, search and copy run here so big files do not freeze the screen. */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewConfig.load(this)
        val first = storages().first()
        left = Pane(first.dir, first.label)
        right = Pane(filesDir, "App private folder")
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

    /** Pinch zoom is read here first, so it works anywhere on the screen. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Storage places this phone shows: shared internal, app private, and any SD card. */
    private fun storages(): List<Storage> {
        val list = mutableListOf<Storage>()
        val shared = File("/storage/emulated/0")
        if (shared.isDirectory) list += Storage("Internal storage (phone shared)", shared)
        list += Storage("App private folder", filesDir)
        getExternalFilesDir(null)?.let { list += Storage("App folder on shared storage", it) }
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
            ?.forEach { list += Storage("External storage / SD card (${it.name})", it) }
        return list
    }

    private fun chooseStorage() {
        val p = active ?: return
        val list = storages()
        AlertDialog.Builder(this)
            .setTitle("Storage for this pane")
            .setMessage(
                "Internal storage: the phone's own shared storage (Download, DCIM, Documents).\n" +
                    "App private folder: only this app can see it.\n" +
                    "External storage / SD card: a memory card, if one is inserted."
            )
            .setItems(list.map { it.label }.toTypedArray()) { _, i ->
                p.top = list[i].dir
                p.topLabel = list[i].label
                p.go(list[i].dir)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun detach(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout() {
        root.removeAllViews()
        root.setBackgroundColor(ViewConfig.palette().bg)
        detach(left.root)
        detach(right.root)
        val side = ViewConfig.sideBySide
        val panes = LinearLayout(this).apply {
            orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val paneParams = if (side) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val divider = View(this).apply { setBackgroundColor(0xFF3A4654.toInt()) }
        val dividerParams = if (side) {
            LinearLayout.LayoutParams(dp(3), LinearLayout.LayoutParams.MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        }
        panes.addView(left.root, paneParams)
        panes.addView(divider, dividerParams)
        panes.addView(right.root, paneParams)
        root.addView(panes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttonRow(
            "Copy >" to { transfer(move = false) },
            "Move >" to { transfer(move = true) },
            "Delete" to { deleteMarked() },
            "Actions" to { showActions() },
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
            "Storage for this pane (internal, app, SD card)" to { chooseStorage() },
            "Select all in this pane" to { active?.let { it.marked.addAll(it.visible()); refreshBoth() } },
            "Clear marks" to { active?.marked?.clear(); refreshBoth() },
            "Copy marked (then Paste here)" to { prepare(move = false) },
            "Cut marked (then Paste here)" to { prepare(move = true) },
            "Paste here" to { active?.let { paste(it.dir) } },
            "Share marked" to { share() },
            "Rename (one item)" to { rename() },
            "New folder" to { newFolder() },
            "Compress marked (zip, tar.gz, gz)" to { zipMarked() },
            "Extract archive (zip, tar, tar.gz, gz)" to { unzipOne() },
            "View file (text or hex)" to { viewFile() },
            "Encrypt marked files (password)" to { encryptMarked() },
            "Decrypt .r2enc files (password)" to { decryptMarked() },
            "Search in this pane" to { searchDialog() },
            "Open in R2 console (one file)" to { openInR2() },
            "Settings: text, buttons, layout" to { ViewConfig.dialog(this) { buildLayout() } },
            "Refresh" to { refreshBoth() },
        )
        AlertDialog.Builder(this)
            .setTitle("Actions")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Tapping a file marks it and opens its own menu: copy, move, share, extract, compress and more. */
    private fun showFileMenu(p: Pane, f: File) {
        active = p
        p.marked.clear()
        p.marked.add(f)
        refreshBoth()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Copy to other pane" to { transfer(move = false) }
        items += "Move to other pane" to { transfer(move = true) }
        if (clipboard.isNotEmpty()) items += "Paste here" to { paste(p.dir) }
        items += "Share" to { share() }
        if (Archive.isArchive(f.name)) items += "Extract here" to { unzipOne() }
        items += "Compress (zip, tar.gz, gz)" to { zipMarked() }
        items += "Rename" to { rename() }
        items += "View (text or hex)" to { viewFile() }
        if (f.isFile) items += "Encrypt (password)" to { encryptMarked() }
        if (f.name.endsWith(".r2enc")) items += "Decrypt (password)" to { decryptMarked() }
        items += "Open in R2 console" to { openInR2() }
        items += "Delete" to { deleteMarked() }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Copy or Move the marked files from the active pane into the other pane. */
    private fun transfer(move: Boolean) {
        val from = active ?: return
        val files = targets() ?: return
        val to = other(from)
        clipboard.clear()
        clipboard += files
        clipMove = move
        paste(to.dir)
    }

    private fun prepare(move: Boolean) {
        val files = targets() ?: return
        clipboard.clear()
        clipboard += files
        clipMove = move
        toast("${files.size} item(s) ready. Open the folder you want and choose Paste here.")
    }

    private fun paste(dest: File) {
        if (clipboard.isEmpty()) {
            toast("nothing copied or moved yet.")
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
                toast("${if (clipMove) "moved" else "copied"} $done item(s)")
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
            .setTitle("Search in ${p.topLabel}")
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
