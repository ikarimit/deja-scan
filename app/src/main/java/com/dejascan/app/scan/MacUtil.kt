package com.dejascan.app.scan

object MacUtil {
    private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    /** Lower-case, colon-separated form used as the key in both databases. Returns null if not a MAC. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim()?.lowercase()?.replace('-', ':') ?: return null
        return if (MAC.matches(s)) s else null
    }

    /**
     * Strips control characters, Unicode bidi/formatting overrides, replacement characters, and line breaks
     * from untrusted over-the-air strings (SSIDs, Bluetooth names, capabilities) before display or storage.
     */
    fun cleanAirText(raw: String?, maxLen: Int = 64): String {
        if (raw.isNullOrEmpty()) return ""
        val trimmed = raw.trim()
        if (trimmed.equals("<unknown ssid>", ignoreCase = true)) return ""
        val sb = StringBuilder(minOf(raw.length, maxLen + 8))
        var prevSpace = true // trims leading spaces and collapses runs
        for (ch in raw) {
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
                continue
            }
            if (ch.isISOControl() || ch == '\uFFFD') continue
            when (ch.category) {
                CharCategory.CONTROL,
                CharCategory.FORMAT,
                CharCategory.LINE_SEPARATOR,
                CharCategory.PARAGRAPH_SEPARATOR,
                CharCategory.PRIVATE_USE,
                CharCategory.SURROGATE,
                CharCategory.UNASSIGNED -> continue
                else -> Unit
            }
            if (ch.isWhitespace()) {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                sb.append(ch)
                prevSpace = false
            }
            if (sb.length >= maxLen + 1) break
        }
        val cleaned = sb.toString().trim()
        return if (cleaned.length > maxLen) cleaned.substring(0, maxLen).trimEnd() else cleaned
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
