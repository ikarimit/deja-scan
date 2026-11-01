package com.dejascan.app.scan

/**
 * Alert rule: alert on the first sighting (or after the device has been gone for [forgetMs]), then only
 * when the smoothed signal rises [deltaDb] above the last alert level. If the signal falls [deltaDb]
 * below the baseline the baseline follows it down, so coming back later alerts again.
 */
class ApproachTracker(
    private val deltaDb: Int = 10,
    private val forgetMs: Long = 10 * 60_000L,
    private val smoothing: Double = 0.4,
) {
    private class State(var smooth: Double, var baseline: Double, var lastSeen: Long)

    private val states = HashMap<String, State>()

    /** Returns true when this reading should raise an alert. */
    fun onReading(mac: String, rssi: Int, now: Long): Boolean {
        val s = states[mac]
        if (s == null || now - s.lastSeen > forgetMs) {
            states[mac] = State(rssi.toDouble(), rssi.toDouble(), now)
            return true
        }
        s.lastSeen = now
        s.smooth = s.smooth * (1 - smoothing) + rssi * smoothing
        if (s.smooth >= s.baseline + deltaDb) {
            s.baseline = s.smooth
            return true
        }
        if (s.smooth <= s.baseline - deltaDb) s.baseline = s.smooth
        return false
    }
}
