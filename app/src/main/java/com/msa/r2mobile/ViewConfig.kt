package com.msa.r2mobile

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * View template: text sizes for the console, buttons and file list, plus hidden files and the pane layout.
 * Saved on this device, shared by the console and the file manager.
 */
object ViewConfig {
    private const val PREFS = "r2_view_config"

    var fileText = 15f
    var consoleText = 12f
    var buttonText = 13f
    var showHidden = true
    var sideBySide = true

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fileText = p.getFloat("file", 15f)
        consoleText = p.getFloat("console", 12f)
        buttonText = p.getFloat("button", 13f)
        showHidden = p.getBoolean("hidden", true)
        sideBySide = p.getBoolean("side", true)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("file", fileText)
            .putFloat("console", consoleText)
            .putFloat("button", buttonText)
            .putBoolean("hidden", showHidden)
            .putBoolean("side", sideBySide)
            .apply()
    }

    /** Sets the text size of every button under [root]. */
    fun applyButtons(root: View) {
        if (root is Button) root.textSize = buttonText
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyButtons(root.getChildAt(i))
        }
    }

    /** Opens the settings dialog. Save keeps the changes; Cancel restores the previous ones. */
    fun dialog(ctx: Context, onChanged: () -> Unit) {
        val before = listOf(fileText, consoleText, buttonText)
        val beforeFlags = listOf(showHidden, sideBySide)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        box.addView(slider(ctx, "File list text", fileText, 9, 32) { fileText = it })
        box.addView(slider(ctx, "Console text", consoleText, 8, 24) { consoleText = it })
        box.addView(slider(ctx, "Button text", buttonText, 10, 22) { buttonText = it })
        box.addView(CheckBox(ctx).apply {
            text = "Show hidden files (names starting with a dot)"
            isChecked = showHidden
            setOnCheckedChangeListener { _, c -> showHidden = c }
        })
        box.addView(CheckBox(ctx).apply {
            text = "Two panes side by side (off = stacked)"
            isChecked = sideBySide
            setOnCheckedChangeListener { _, c -> sideBySide = c }
        })
        AlertDialog.Builder(ctx)
            .setTitle("View settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                save(ctx)
                onChanged()
            }
            .setNegativeButton("Cancel") { _, _ ->
                fileText = before[0]
                consoleText = before[1]
                buttonText = before[2]
                showHidden = beforeFlags[0]
                sideBySide = beforeFlags[1]
                onChanged()
            }
            .show()
    }

    private fun slider(ctx: Context, label: String, value: Float, min: Int, max: Int, set: (Float) -> Unit): LinearLayout {
        val title = TextView(ctx).apply { text = "$label: ${value.toInt()} sp" }
        val bar = SeekBar(ctx).apply {
            this.max = max - min
            progress = value.toInt() - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (min + p).toFloat()
                    title.text = "$label: ${v.toInt()} sp"
                    set(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(bar)
        }
    }
}
