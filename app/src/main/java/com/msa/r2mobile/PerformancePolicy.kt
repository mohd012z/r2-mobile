package com.msa.r2mobile

/** Pure policy: no device settings are changed. Thermal values follow Android PowerManager. */
object PerformancePolicy {
    data class Sample(val battery: Int?, val charging: Boolean, val saver: Boolean,
        val availableMiB: Long, val lowMemory: Boolean, val thermal: Int?, val visible: Boolean)
    data class Plan(val mode: String, val allowHeavy: Boolean, val workers: Int,
        val refreshMs: Int, val graphNodes: Int, val animations: Boolean)
    fun decide(s: Sample): Plan = when {
        !s.visible -> Plan("background", false, 0, 5000, 30, false)
        s.lowMemory || s.availableMiB < 512 || (s.thermal ?: 0) >= 4 -> Plan("protect", false, 0, 2000, 30, false)
        s.saver || (!s.charging && (s.battery ?: 100) <= 20) || s.availableMiB < 1536 || (s.thermal ?: 0) >= 2 || s.thermal == null || s.battery == null -> Plan("eco", true, 1, 1000, 50, false)
        else -> Plan("balanced", true, 1, 250, 100, true)
    }
}