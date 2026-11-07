package com.dejascan.app.ui

import com.dejascan.app.data.Device
import com.dejascan.app.geo.Estimate
import com.dejascan.app.scan.DetectionCategory

/**
 * One dot on the map. Live dots are devices heard recently (at the estimated position, or approximately
 * at the phone when there is no estimate); WiGLE dots sit at the device's last known place.
 */
data class MapDot(
    val device: Device,
    val live: Boolean,
    val approx: Boolean,
    val rssi: Int,
    val lat: Double,
    val lon: Double,
    val estimate: Estimate?,
    val watched: Boolean = true,
    /** More than 1 for a bubble that stands for several WiGLE devices. */
    val count: Int = 1,
    /** Visual classification (Possible audit tool, WEP, Rare, Uncommon, Randomized, Normal). */
    val category: DetectionCategory = DetectionCategory.NORMAL,
    /** Compact Wi-Fi channel and bandwidth label (e.g. "Ch 36 • 80MHz"). */
    val channelInfo: String = "",
) {
    val mac: String get() = device.mac
}

/** A Mapsforge map file stored in the app; all stored files are combined into one map. */
data class MapFileInfo(val name: String, val bytes: Long, val modified: Long, val path: String)

/** A dotted line through the places one device was seen (oldest to newest). */
class LinkPath(val mac: String, val color: Int, val lat: DoubleArray, val lon: DoubleArray, val numbered: Boolean = false)

/** One place on the day map; type picks the colour. */
data class DayPoint(val lat: Double, val lon: Double, val type: String)

/** One place a device was seen (places closer than 25 m are merged); time 0 means unknown. */
data class Place(val lat: Double, val lon: Double, val time: Long, val own: Boolean)
