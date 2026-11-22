package com.msa.r2mobile

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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
    data class Palette(val bg: Int, val text: Int, val header: Int, val mark: Int, val markDim: Int)

    fun palette(): Palette = when (themeIndex) {
        1 -> Palette(0xFFF5F7FA.toInt(), 0xFF101418.toInt(), 0xFFDDE3EA.toInt(), 0xFF4493F8.toInt(), 0x554493F8.toInt())
        2 -> Palette(0xFF050A05.toInt(), 0xFF39FF14.toInt(), 0xFF0E1F0E.toInt(), 0xFF2ECC40.toInt(), 0x552ECC40.toInt())
        3 -> Palette(0xFF0B1A2E.toInt(), 0xFFBBD7FF.toInt(), 0xFF16304F.toInt(), 0xFF00B8D4.toInt(), 0x5500B8D4.toInt())
        else -> Palette(0xFF0B0F14.toInt(), 0xFFD4D4D4.toInt(), 0xFF1E2A36.toInt(), 0xFF4493F8.toInt(), 0x554493F8.toInt())
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

        box.addView(heading(ctx, "Console colours (tap a colour to pick it)"))
        box.addView(colourRow(ctx, "Background", consoleBgHex) { consoleBgHex = it })
        box.addView(colourRow(ctx, "Text", consoleFgHex) { consoleFgHex = it })

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

    /** A row with a colour swatch and its name. Tapping the swatch opens the colour picker. */
    private fun colourRow(ctx: Context, label: String, current: String, set: (String) -> Unit): LinearLayout {
        var value = current
        val swatch = View(ctx)
        val name = TextView(ctx)
        fun show() {
            swatch.setBackgroundColor(parseHex(value, DEFAULT_CONSOLE_BG))
            name.text = "$label: $value"
        }
        show()
        swatch.setOnClickListener {
            pickColour(ctx, label, value) { picked ->
                value = picked
                set(picked)
                show()
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
            addView(swatch, LinearLayout.LayoutParams(72, 72).apply { rightMargin = 24 })
            addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Colour picker: preset colours, red/green/blue sliders and a live preview. Returns #RRGGBB. */
    private fun pickColour(ctx: Context, title: String, initial: String, onPicked: (String) -> Unit) {
        val start = parseHex(initial, DEFAULT_CONSOLE_BG)
        val red = (start shr 16) and 0xFF
        val green = (start shr 8) and 0xFF
        val blue = start and 0xFF
        val preview = View(ctx)
        val code = TextView(ctx).apply { textSize = 14f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD) }
        val rgb = IntArray(3) { listOf(red, green, blue)[it] }
        fun hexNow() = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2])
        fun refresh() {
            preview.setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]))
            code.text = hexNow()
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        box.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 160))
        box.addView(code)

        // Presets: tap one to use it directly.
        val presets = listOf(
            "#000000", "#0B0F14", "#1E1E1E", "#2D2D2D", "#FFFFFF", "#C9D1D9",
            "#00FF41", "#39FF14", "#FFB000", "#00E5FF", "#FF5555", "#FFD700"
        )
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        presets.chunked(6).forEach { row ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { hex ->
                line.addView(View(ctx).apply {
                    setBackgroundColor(parseHex(hex, DEFAULT_CONSOLE_BG))
                    setOnClickListener {
                        val c = parseHex(hex, DEFAULT_CONSOLE_BG)
                        rgb[0] = (c shr 16) and 0xFF
                        rgb[1] = (c shr 8) and 0xFF
                        rgb[2] = c and 0xFF
                        refresh()
                    }
                }, LinearLayout.LayoutParams(0, 64, 1f).apply { setMargins(4, 4, 4, 4) })
            }
            grid.addView(line)
        }
        box.addView(grid)

        val names = listOf("Red", "Green", "Blue")
        names.forEachIndexed { i, n ->
            box.addView(TextView(ctx).apply { text = n; setPadding(0, 16, 0, 0) })
            box.addView(SeekBar(ctx).apply {
                max = 255
                progress = rgb[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        rgb[i] = p
                        refresh()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
        refresh()

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Use this colour") { _, _ -> onPicked(hexNow()) }
            .setNegativeButton("Cancel", null)
            .show()
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
