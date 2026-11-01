package com.dejascan.app.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Map grid used to pre-count devices for a zoomed-out map. At zoom z a cell is 1/8 of a Web-Mercator tile,
 * so a cell at zoom z splits into 4 cells at z+1 (cx/2, cy/2 is the parent).
 */
object CellGrid {
    const val SUB = 8
    const val MIN_Z = 3
    /** Finest zoom with pre-counted cells; closer in, single devices are queried directly. */
    const val BASE_Z = 14

    fun nx(lon: Double): Double = (lon + 180.0) / 360.0

    fun ny(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }

    fun cell(norm: Double, z: Int): Int {
        val n = (1 shl z) * SUB
        return floor(norm * n).toInt().coerceIn(0, n - 1)
    }
}
