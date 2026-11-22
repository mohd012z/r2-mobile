package com.msa.r2mobile

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * View template: font, colour template, console colours (hex codes), text sizes, hidden files and the pane split.
 * Saved on this device and shared by the console and the file manager.
 */
object ViewConfig {
    private const val PREFS = "r2_view_config"

    const val DEFAULT_FILE = 14f
    const val DEFAULT_CONSOLE = 12f
    const val DEFAULT_BUTTON = 12f
    const val DEFAULT_CONSOLE_BG = "#000000"
    const val DEFAULT_CONSOLE_FG = "#C9D1D9"

    val FONT_NAMES = listOf("Monospace", "Sans-serif", "Serif")
    val THEME_NAMES = listOf("Dark", "Light", "Green terminal", "Blue")

    var fontIndex = 0
    var themeIndex = 0
    var fileText = DEFAULT_FILE
    var consoleText = DEFAULT_CONSOLE
    var buttonText = DEFAULT_BUTTON
    /** Console background and text colour as hex codes, for example #0B0F14. */
    var consoleBgHex = DEFAULT_CONSOLE_BG
    var consoleFgHex = DEFAULT_CONSOLE_FG
    var showHidden = true
    /** true = panes side by side with a vertical divider; false = stacked with a horizontal divider. */
    var sideBySide = true

    /** Colours of one template: panel background, text, header bar, and the highlight for marked rows. */
    data class Palette(val bg: Int, val text: Int, val header: Int, val mark: Int)

    fun palette(): Palette = when (themeIndex) {
        1 -> Palette(0xFFF5F7FA.toInt(), 0xFF101418.toInt(), 0xFFDDE3EA.toInt(), 0x552979FF)
        2 -> Palette(0xFF050A05.toInt(), 0xFF39FF14.toInt(), 0xFF0E1F0E.toInt(), 0x5539FF14)
        3 -> Palette(0xFF0B1A2E.toInt(), 0xFFBBD7FF.toInt(), 0xFF16304F.toInt(), 0x5500B8D4)
        else -> Palette(0xFF0B0F14.toInt(), 0xFFD4D4D4.toInt(), 0xFF1E2A36.toInt(), 0x552979FF)
    }

    fun typeface(): Typeface = when (fontIndex) {
        1 -> Typeface.SANS_SERIF
        2 -> Typeface.SERIF
        else -> Typeface.MONOSPACE
    }

    fun consoleBg(): Int = parseHex(consoleBgHex, DEFAULT_CONSOLE_BG)
    fun consoleFg(): Int = parseHex(consoleFgHex, DEFAULT_CONSOLE_FG)

    fun isHex(text: String): Boolean = Regex("#[0-9A-Fa-f]{6}").matches(text)

    private fun parseHex(hex: String, fallback: String): Int = try {
        Color.parseColor(if (isHex(hex)) hex else fallback)
    } catch (e: IllegalArgumentException) {
        Color.parseColor(fallback)
    }

    fun resetDefaults() {
        fontIndex = 0
        themeIndex = 0
        fileText = DEFAULT_FILE
        consoleText = DEFAULT_CONSOLE
        buttonText = DEFAULT_BUTTON
        consoleBgHex = DEFAULT_CONSOLE_BG
        consoleFgHex = DEFAULT_CONSOLE_FG
        showHidden = true
        sideBySide = true
    }

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fontIndex = p.getInt("font", 0)
        themeIndex = p.getInt("theme", 0)
        fileText = p.getFloat("file", DEFAULT_FILE)
        consoleText = p.getFloat("console", DEFAULT_CONSOLE)
        buttonText = p.getFloat("button", DEFAULT_BUTTON)
        consoleBgHex = p.getString("consoleBg", DEFAULT_CONSOLE_BG) ?: DEFAULT_CONSOLE_BG
        consoleFgHex = p.getString("consoleFg", DEFAULT_CONSOLE_FG) ?: DEFAULT_CONSOLE_FG
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
            .putString("consoleBg", consoleBgHex)
            .putString("consoleFg", consoleFgHex)
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

    /**
     * Opens the settings dialog. Save keeps the changes, Cancel restores the previous ones,
     * Defaults resets every value to the built-in default and saves it.
     */
    fun dialog(ctx: Context, onChanged: () -> Unit) {
        val beforeFont = fontIndex
        val beforeTheme = themeIndex
        val beforeBg = consoleBgHex
        val beforeFg = consoleFgHex
        val beforeSizes = listOf(fileText, consoleText, buttonText)
        val beforeFlags = listOf(showHidden, sideBySide)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
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

        box.addView(heading(ctx, "Appearance (console and file manager)"))
        box.addView(fontButton)
        box.addView(themeButton)

        box.addView(heading(ctx, "Console colours (hex code, e.g. #0B0F14)"))
        box.addView(TextView(ctx).apply { text = "Background" })
        box.addView(hexBox(ctx, consoleBgHex) { consoleBgHex = it })
        box.addView(TextView(ctx).apply { text = "Text" })
        box.addView(hexBox(ctx, consoleFgHex) { consoleFgHex = it })

        box.addView(heading(ctx, "Console"))
        box.addView(slider(ctx, "Console text", consoleText, 8, 24) { consoleText = it })

        box.addView(heading(ctx, "Buttons"))
        box.addView(slider(ctx, "Button text", buttonText, 9, 20) { buttonText = it })

        box.addView(heading(ctx, "File manager"))
        box.addView(slider(ctx, "File list text", fileText, 9, 32) { fileText = it })
        box.addView(CheckBox(ctx).apply {
            text = "Show hidden files (names starting with a dot)"
            isChecked = showHidden
            setOnCheckedChangeListener { _, c -> showHidden = c }
        })
        box.addView(CheckBox(ctx).apply {
            text = "Vertical split: panes side by side (off = horizontal split, stacked)"
            isChecked = sideBySide
            setOnCheckedChangeListener { _, c -> sideBySide = c }
        })

        AlertDialog.Builder(ctx)
            .setTitle("View settings")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                save(ctx)
                onChanged()
            }
            .setNeutralButton("Defaults") { _, _ ->
                resetDefaults()
                save(ctx)
                onChanged()
            }
            .setNegativeButton("Cancel") { _, _ ->
                fontIndex = beforeFont
                themeIndex = beforeTheme
                consoleBgHex = beforeBg
                consoleFgHex = beforeFg
                fileText = beforeSizes[0]
                consoleText = beforeSizes[1]
                buttonText = beforeSizes[2]
                showHidden = beforeFlags[0]
                sideBySide = beforeFlags[1]
                onChanged()
            }
            .show()
    }

    /** A box for a colour code. Only a valid #RRGGBB code is kept; anything else shows an error. */
    private fun hexBox(ctx: Context, initial: String, set: (String) -> Unit): EditText = EditText(ctx).apply {
        setText(initial)
        hint = "#RRGGBB"
        setSingleLine(true)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.trim().orEmpty()
                if (isHex(v)) {
                    set(v)
                    setError(null)
                } else {
                    setError("use #RRGGBB, for example #0B0F14")
                }
            }
        })
    }

    private fun heading(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 28, 0, 6)
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
