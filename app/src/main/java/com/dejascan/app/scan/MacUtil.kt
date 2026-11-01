package com.dejascan.app.scan

object MacUtil {
    private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    /** Lower-case, colon-separated form used as the key in both databases. Returns null if not a MAC. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.replace('-', ':') ?: return null
        return if (MAC.matches(s)) s else null
    }

    /** Locally-administered bit (0x02 of the first octet) set: address is likely randomised. */
    fun isRandomised(mac: String): Boolean {
        val first = mac.substring(0, 2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }

    /** Rough, uncalibrated signal bucket. Not a distance measurement. */
    fun proximity(rssi: Int): String = when {
        rssi >= -55 -> "Very close"
        rssi >= -70 -> "Near"
        rssi >= -85 -> "Far"
        else -> "Faint"
    }
}
