package com.msa.r2mobile

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.random.Random

/** Welcome screen: a green "digital rain" background, the title types itself, then the console opens. */
class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private val fullTitle = "R2 MOBILE"
    private var typed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.addView(MatrixRain(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        title = TextView(this).apply {
            text = ""
            textSize = 34f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00E676"))
            setShadowLayer(18f, 0f, 0f, Color.parseColor("#00E676"))
        }
        val subtitle = TextView(this).apply {
            text = "radare2 reverse engineering\nwelcome. loading the engine..."
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B9F6CA"))
        }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(subtitle)
        }
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        handler.post(typeTitle)
        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_MS)
    }

    private val typeTitle = object : Runnable {
        override fun run() {
            if (typed <= fullTitle.length) {
                title.text = fullTitle.substring(0, typed) + if (typed % 2 == 0) "_" else " "
                typed++
                handler.postDelayed(this, 120)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Falling green characters, like the film. Redraws every frame while visible. */
    private class MatrixRain(context: Context) : View(context) {
        private val glyphs = "01ABCDEFabcdef<>{}[]()/\\#$%&*+=-:;.|"
        private val size = 30f
        private val textPaint = Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = size
            isAntiAlias = true
        }
        private val fade = Paint().apply { color = Color.argb(60, 11, 15, 20) }
        private val rnd = Random(System.currentTimeMillis())
        private var columns = 0
        private var drops = FloatArray(0)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            columns = (w / size).toInt().coerceAtLeast(1)
            drops = FloatArray(columns) { -rnd.nextInt(30).toFloat() }
            setBackgroundColor(Color.parseColor("#0B0F14"))
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade)
            for (i in 0 until columns) {
                val y = drops[i] * size
                val x = i * size
                val ch = glyphs[rnd.nextInt(glyphs.length)].toString()
                textPaint.color = Color.parseColor("#E6FFE6")
                canvas.drawText(ch, x, y, textPaint)
                textPaint.color = Color.parseColor("#00E676")
                canvas.drawText(glyphs[rnd.nextInt(glyphs.length)].toString(), x, y - size, textPaint)
                drops[i] += 0.5f + rnd.nextFloat() * 0.5f
                if (y > height && rnd.nextFloat() > 0.975f) drops[i] = 0f
            }
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val SPLASH_MS = 2800L
    }
}
