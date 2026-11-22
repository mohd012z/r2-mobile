package com.msa.r2mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Dual-pane file manager. Two folder lists sit side by side (or stacked in portrait).
 * Copy, move, delete, create folders, and send the selected file to the R2 console.
 * It only sees the app's own folders, so no storage permission is needed.
 */
class FileManagerActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    /** One folder pane: its path header, its list, and the file highlighted in it. */
    private inner class Pane(start: File) {
        var dir: File = start
        var selected: File? = null
        private var rows: List<File?> = emptyList()   // null row = ".." (go up)
        private val header = TextView(this@FileManagerActivity).apply {
            textSize = 12f
            setPadding(12, 10, 12, 10)
            setBackgroundColor(0xFF1E2A36.toInt())
            setTextColor(0xFFD4D4D4.toInt())
        }
        private val adapter = ArrayAdapter<String>(
            this@FileManagerActivity, android.R.layout.simple_list_item_1, mutableListOf(),
        )
        private val list = ListView(this@FileManagerActivity).apply {
            adapter = this@Pane.adapter
            setOnItemClickListener { _, _, pos, _ ->
                active = this@Pane
                val row = rows.getOrNull(pos)
                when {
                    row == null && pos == 0 && dir.parentFile != null -> go(dir.parentFile!!)
                    row == null -> Unit
                    row.isDirectory -> go(row)
                    else -> {
                        selected = row
                        refresh()
                    }
                }
            }
        }
        val root = LinearLayout(this@FileManagerActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        fun go(d: File) {
            dir = d
            selected = null
            refresh()
        }

        fun refresh() {
            val kids = dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
            rows = (if (dir.parentFile != null) listOf<File?>(null) else emptyList()) + kids
            adapter.clear()
            adapter.addAll(rows.map { label(it) })
            adapter.notifyDataSetChanged()
            val sel = selected?.let { "   selected: ${it.name}" } ?: ""
            val readable = if (dir.canRead()) "" else "   (cannot read)"
            header.text = "${dir.absolutePath}$readable$sel"
        }

        private fun label(f: File?): String = when {
            f == null -> "[..] parent folder"
            f.isDirectory -> "[DIR] ${f.name}"
            else -> "${f.name}   ${humanSize(f.length())}"
        }
    }

    private lateinit var left: Pane
    private lateinit var right: Pane
    /** The pane the user tapped last. Operations act on it. */
    private var active: Pane? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        left = Pane(getExternalFilesDir(null) ?: filesDir)
        right = Pane(filesDir)
        active = left

        val panes = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val paneParams = if (landscape) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        panes.addView(left.root, paneParams)
        panes.addView(right.root, paneParams)

        val row1 = buttonRow(
            "Copy to other pane" to { copySelected(move = false) },
            "Move to other pane" to { copySelected(move = true) },
            "Delete" to { deleteSelected() },
        )
        val row2 = buttonRow(
            "New folder" to { newFolder() },
            "Open in R2" to { openSelectedInR2() },
            "Refresh" to { refreshBoth() },
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6, 6, 6, 6)
            addView(panes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(row1)
            addView(row2)
        }
        setContentView(root)
        left.refresh()
        right.refresh()
        Toast.makeText(this, "Tap a folder to open it. Tap a file to select it, then use the buttons.", Toast.LENGTH_LONG).show()
    }

    private fun buttonRow(vararg items: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEach { (label, action) ->
                addView(Button(this@FileManagerActivity).apply {
                    text = label
                    textSize = 12f
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun refreshBoth() {
        left.refresh()
        right.refresh()
    }

    private fun other(p: Pane): Pane = if (p === left) right else left

    /** The file highlighted in the active pane, or null with a message telling the user what to tap. */
    private fun requireSelected(): File? {
        val p = active ?: return null
        val f = p.selected
        if (f == null) toast("tap a file in a pane first, then press the button again.")
        return f
    }

    private fun copySelected(move: Boolean) {
        val from = active ?: return
        val src = requireSelected() ?: return
        val to = other(from)
        val dest = uniqueTarget(File(to.dir, src.name))
        try {
            src.copyRecursively(dest, overwrite = false)
            if (move) src.deleteRecursively()
            toast((if (move) "moved" else "copied") + " ${src.name} to ${to.dir.name}")
            from.selected = null
        } catch (e: Exception) {
            toast("failed: ${e.message ?: e.javaClass.simpleName}")
        }
        refreshBoth()
    }

    private fun deleteSelected() {
        val f = requireSelected() ?: return
        val p = active ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete ${f.name}?")
            .setMessage(if (f.isDirectory) "The folder and everything in it will be deleted." else "The file will be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = f.deleteRecursively()
                p.selected = null
                toast(if (ok) "deleted ${f.name}" else "could not delete ${f.name}")
                refreshBoth()
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
                    p.refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSelectedInR2() {
        val f = requireSelected() ?: return
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
            val candidate = File(f.parentFile, "${f.nameWithoutExtension} ($n)" + (if (f.extension.isEmpty()) "" else ".${f.extension}"))
            if (!candidate.exists()) return candidate
            n++
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
