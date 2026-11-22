package com.msa.r2mobile

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * View template: font, colour template, text sizes, hidden files and the pane layout.
 * Saved on this device and shared by the console and the file manager.
 */
object ViewConfig {
    private const val PREFS = "r2_view_config"

    val FONT_NAMES = listOf("Monospace", "Sans-serif", "Serif")
    val THEME_NAMES = listOf("Dark", "Light", "Green terminal", "Blue")

    var fontIndex = 0
    var themeIndex = 0
    var fileText = 15f
    var consoleText = 12f
    var buttonText = 13f
    var showHidden = true
    var sideBySide = true

    /** Colours of one template: panel background, text, and header bar. */
    data class Palette(val bg: Int, val text: Int, val header: Int)

    fun palette(): Palette = when (themeIndex) {
        1 -> Palette(0xFFF5F7FA.toInt(), 0xFF101418.toInt(), 0xFFDDE3EA.toInt())
        2 -> Palette(0xFF050A05.toInt(), 0xFF39FF14.toInt(), 0xFF0E1F0E.toInt())
        3 -> Palette(0xFF0B1A2E.toInt(), 0xFFBBD7FF.toInt(), 0xFF16304F.toInt())
        else -> Palette(0xFF0B0F14.toInt(), 0xFFD4D4D4.toInt(), 0xFF1E2A36.toInt())
    }

    fun typeface(): Typeface = when (fontIndex) {
        1 -> Typeface.SANS_SERIF
        2 -> Typeface.SERIF
        else -> Typeface.MONOSPACE
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fontIndex = p.getInt("font", 0)
        themeIndex = p.getInt("theme", 0)
        fileText = p.getFloat("file", 15f)
        consoleText = p.getFloat("console", 12f)
        buttonText = p.getFloat("button", 13f)
        showHidden = p.getBoolean("hidden", true)
        sideBySide = p.getBoolean("side", true)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("font", fontIndex)
            .putInt("theme", themeIndex)
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
        val before = listOf(fontIndex, themeIndex)
        val beforeSizes = listOf(fileText, consoleText, buttonText)
        val beforeFlags = listOf(showHidden, sideBySide)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val fontButton = Button(ctx)
        val themeButton = Button(ctx)
        fun refreshChoiceButtons() {
            fontButton.text = "Font: ${FONT_NAMES[fontIndex]}"
            themeButton.text = "Colour template: ${THEME_NAMES[themeIndex]}"
        }
        refreshChoiceButtons()
        fontButton.setOnClickListener {
            choose(ctx, "Font", FONT_NAMES, fontIndex) { fontIndex = it; refreshChoiceButtons() }
        }
        themeButton.setOnClickListener {
            choose(ctx, "Colour template", THEME_NAMES, themeIndex) { themeIndex = it; refreshChoiceButtons() }
        }
        box.addView(fontButton)
        box.addView(themeButton)
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
                fontIndex = before[0]
                themeIndex = before[1]
                fileText = beforeSizes[0]
                consoleText = beforeSizes[1]
                buttonText = beforeSizes[2]
                showHidden = beforeFlags[0]
                sideBySide = beforeFlags[1]
                onChanged()
            }
            .show()
    }

    private fun choose(ctx: Context, title: String, names: List<String>, current: Int, set: (Int) -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
                set(which)
                d.dismiss()
            }
            .setNegativeButton("Close", null)
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
