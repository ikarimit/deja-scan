package com.dejascan.app.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6_371_000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(sqrt(a))
    }

    /** Metres east/north of the origin (flat-earth approximation, fine for a few km). */
    fun toLocal(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> =
        Pair(
            Math.toRadians(lon - lon0) * cos(Math.toRadians(lat0)) * R,
            Math.toRadians(lat - lat0) * R,
        )

    fun fromLocal(lat0: Double, lon0: Double, x: Double, y: Double): Pair<Double, Double> =
        Pair(
            lat0 + Math.toDegrees(y / R),
            lon0 + Math.toDegrees(x / (R * cos(Math.toRadians(lat0)))),
        )
}

data class Sample(val lat: Double, val lon: Double, val rssi: Int)

enum class Method { CENTROID, PATH_LOSS_FIT }

/** radiusM is a rough heuristic, not a statistical confidence interval. */
data class Estimate(val lat: Double, val lon: Double, val radiusM: Double, val method: Method, val samples: Int)

/**
 * Estimates where a static transmitter is from signal readings taken at known phone positions.
 * Weighted centroid works with few samples; the path-loss fit needs enough samples spread around the device.
 */
object PositionEstimator {
    /** Assumed path-loss exponent; real environments vary (not from a verified source). */
    const val PATH_LOSS_EXPONENT = 2.7
    private const val MAX_SAMPLES = 300
    private const val MIN_SAMPLES = 3
    private const val FIT_MIN_SAMPLES = 8
    private const val FIT_MIN_SPREAD_M = 20.0
    private const val FIT_MIN_RSSI_RANGE = 8
    private const val FIT_MAX_SIGMA_DB = 12.0

    fun estimate(all: List<Sample>): Estimate? {
        if (all.size < MIN_SAMPLES) return null
        val step = (all.size + MAX_SAMPLES - 1) / MAX_SAMPLES
        val s = if (step > 1) all.filterIndexed { i, _ -> i % step == 0 } else all
        val lat0 = s.map { it.lat }.average()
        val lon0 = s.map { it.lon }.average()
        val pts = s.map { Geo.toLocal(lat0, lon0, it.lat, it.lon) }
        val rssi = s.map { it.rssi }

        // weighted centroid, weights = linear power
        val maxR = rssi.max()
        val w = rssi.map { 10.0.pow((it - maxR) / 10.0) }
        val wSum = w.sum()
        val cx = pts.indices.sumOf { pts[it].first * w[it] } / wSum
        val cy = pts.indices.sumOf { pts[it].second * w[it] } / wSum
        val rms = sqrt(pts.indices.sumOf { w[it] * (hypot(pts[it].first - cx, pts[it].second - cy)).pow(2) } / wSum)

        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val spread = hypot(maxX - minX, maxY - minY)
        val rssiRange = rssi.max() - rssi.min()

        if (s.size >= FIT_MIN_SAMPLES && spread >= FIT_MIN_SPREAD_M && rssiRange >= FIT_MIN_RSSI_RANGE) {
            val fit = fit(pts, rssi, cx, cy, maxOf(spread, 30.0))
            if (fit != null) {
                val (fx, fy, sigma) = fit
                val dists = pts.map { hypot(it.first - fx, it.second - fy) }.sorted()
                val median = dists[dists.size / 2]
                val factor = 10.0.pow(sigma / (10 * PATH_LOSS_EXPONENT))
                val (la, lo) = Geo.fromLocal(lat0, lon0, fx, fy)
                return Estimate(la, lo, maxOf(10.0, (factor - 1) * median), Method.PATH_LOSS_FIT, s.size)
            }
        }
        val (la, lo) = Geo.fromLocal(lat0, lon0, cx, cy)
        return Estimate(la, lo, maxOf(10.0, rms), Method.CENTROID, s.size)
    }

    /** Returns (x, y, residual sigma in dB), or null when the fit is too noisy to trust. */
    private fun fit(pts: List<Pair<Double, Double>>, rssi: List<Int>, cx: Double, cy: Double, extent: Double): Triple<Double, Double, Double>? {
        var bx = cx
        var by = cy
        var half = extent
        var bestSse = Double.MAX_VALUE
        repeat(12) {
            var nx = bx
            var ny = by
            for (i in -5..5) for (j in -5..5) {
                val x = bx + half * i / 5.0
                val y = by + half * j / 5.0
                val sse = sse(pts, rssi, x, y)
                if (sse < bestSse) { bestSse = sse; nx = x; ny = y }
            }
            bx = nx; by = ny
            half *= 0.5
        }
        val sigma = sqrt(bestSse / pts.size)
        return if (sigma <= FIT_MAX_SIGMA_DB) Triple(bx, by, sigma) else null
    }

    /** Sum of squared residuals of rssi = P0 - 10 n log10(d) with P0 solved in closed form. */
    private fun sse(pts: List<Pair<Double, Double>>, rssi: List<Int>, x: Double, y: Double): Double {
        val r = DoubleArray(pts.size) {
            val d = maxOf(1.0, hypot(pts[it].first - x, pts[it].second - y))
            rssi[it] + 10 * PATH_LOSS_EXPONENT * log10(d)
        }
        val mean = r.average()
        return r.sumOf { (it - mean).pow(2) }
    }
}
