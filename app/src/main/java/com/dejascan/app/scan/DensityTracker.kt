package com.dejascan.app.scan

/**
 * Tracks unique APs and networks observed per minute, supporting a rolling time window
 * for density calculations and history graphs.
 */
class DensityTracker(private val maxMinutes: Int = 120) {

    private val minuteBuckets = LinkedHashMap<Long, MinuteRecord>()

    data class MinuteRecord(
        val aps: MutableSet<String> = HashSet(),
        val newAps: MutableSet<String> = HashSet(),
        val networks: MutableSet<String> = HashSet(),
        val newNetworks: MutableSet<String> = HashSet(),
    )

    data class MinutePoint(
        val minute: Long,      // epoch minute (timestamp / 60_000L)
        val apCount: Int,      // unique APs heard in this minute
        val newApCount: Int,   // unique new APs (not in local DB) heard in this minute
        val networkCount: Int, // unique networks (Wi-Fi + BT) heard in this minute
        val newNetworkCount: Int,
    )

    @Synchronized
    fun record(mac: String, type: String, inDatabase: Boolean, now: Long) {
        val m = now / 60_000L
        val rec = minuteBuckets.getOrPut(m) { MinuteRecord() }
        rec.networks.add(mac)
        if (!inDatabase) rec.newNetworks.add(mac)
        if (type == "W") {
            rec.aps.add(mac)
            if (!inDatabase) rec.newAps.add(mac)
        }
        val cutoff = m - maxMinutes
        val it = minuteBuckets.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key < cutoff) it.remove() else break
        }
    }

    @Synchronized
    fun markKnown(knownMacs: Set<String>) {
        for (rec in minuteBuckets.values) {
            rec.newAps.removeAll(knownMacs)
            rec.newNetworks.removeAll(knownMacs)
        }
    }

    @Synchronized
    fun rollingPoints(now: Long, windowMinutes: Int = 30): List<MinutePoint> {
        val curMinute = now / 60_000L
        val startMinute = curMinute - windowMinutes + 1
        return (startMinute..curMinute).map { m ->
            val rec = minuteBuckets[m]
            MinutePoint(
                minute = m,
                apCount = rec?.aps?.size ?: 0,
                newApCount = rec?.newAps?.size ?: 0,
                networkCount = rec?.networks?.size ?: 0,
                newNetworkCount = rec?.newNetworks?.size ?: 0,
            )
        }
    }

    @Synchronized
    fun peakApDensity(windowMinutes: Int = 30, now: Long = System.currentTimeMillis()): Int {
        val points = rollingPoints(now, windowMinutes)
        return points.maxOfOrNull { it.apCount } ?: 0
    }

    @Synchronized
    fun clear() {
        minuteBuckets.clear()
    }
}
