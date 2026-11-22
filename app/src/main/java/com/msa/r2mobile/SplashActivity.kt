package com.msa.r2mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Welcome screen shown for about 1.5 seconds, then opens the console. */
class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val splash = TextView(this).apply {
            text = "R2 Mobile\nradare2 reverse engineering\n\nWelcome. Open a file to begin."
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D4D4D4"))
            setBackgroundColor(Color.parseColor("#0B0F14"))
            setPadding(48, 48, 48, 48)
        }
        setContentView(splash)
        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MS = 1500L
    }
}
