package com.msa.r2mobile
import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

class AndroidPerformanceProbe(private val context: Context) {
    fun sample(visible: Boolean): PerformancePolicy.Sample {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bm = context.getSystemService(BatteryManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return PerformancePolicy.Sample(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
            bm.isCharging, pm.isPowerSaveMode, mem.availMem / 1048576, mem.lowMemory,
            if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else null, visible)
    }
}