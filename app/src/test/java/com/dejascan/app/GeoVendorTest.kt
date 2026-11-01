package com.dejascan.app

import com.dejascan.app.data.Vendors
import com.dejascan.app.geo.Geo
import com.dejascan.app.geo.Method
import com.dejascan.app.geo.PositionEstimator
import com.dejascan.app.geo.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

class GeoVendorTest {
    private val lat0 = 52.0
    private val lon0 = 13.0

    private fun sampleAt(x: Double, y: Double, devX: Double, devY: Double, noise: Double = 0.0): Sample {
        val d = maxOf(1.0, Math.hypot(x - devX, y - devY))
        val rssi = (-40 - 10 * PositionEstimator.PATH_LOSS_EXPONENT * log10(d) + noise).toInt()
        val (la, lo) = Geo.fromLocal(lat0, lon0, x, y)
        return Sample(la, lo, rssi)
    }

    @Test fun haversineKnownDistance() {
        // one degree of latitude is about 111.2 km
        assertEquals(111_195.0, Geo.haversineM(0.0, 0.0, 1.0, 0.0), 300.0)
        assertEquals(0.0, Geo.haversineM(10.0, 10.0, 10.0, 10.0), 0.001)
    }

    @Test fun localConversionRoundTrips() {
        val (x, y) = Geo.toLocal(lat0, lon0, lat0 + 0.001, lon0 + 0.002)
        val (la, lo) = Geo.fromLocal(lat0, lon0, x, y)
        assertEquals(lat0 + 0.001, la, 1e-9)
        assertEquals(lon0 + 0.002, lo, 1e-9)
    }

    @Test fun fitFindsDeviceFromSurroundingWalk() {
        val devX = 7.0
        val devY = -4.0
        val samples = ArrayList<Sample>()
        for (r in listOf(15.0, 35.0, 60.0)) for (k in 0 until 12) {
            val a = 2 * PI * k / 12
            samples.add(sampleAt(r * cos(a), r * sin(a), devX, devY))
        }
        val e = PositionEstimator.estimate(samples)
        assertNotNull(e)
        assertEquals(Method.PATH_LOSS_FIT, e!!.method)
        val (ex, ey) = Geo.toLocal(lat0, lon0, e.lat, e.lon)
        assertTrue("error ${Math.hypot(ex - devX, ey - devY)}", Math.hypot(ex - devX, ey - devY) < 3.0)
    }

    @Test fun fewSamplesFallBackToCentroidNearStrongestReading() {
        val samples = listOf(
            sampleAt(0.0, 0.0, 2.0, 2.0), sampleAt(50.0, 0.0, 2.0, 2.0), sampleAt(0.0, 50.0, 2.0, 2.0),
        )
        val e = PositionEstimator.estimate(samples)!!
        assertEquals(Method.CENTROID, e.method)
        val (ex, ey) = Geo.toLocal(lat0, lon0, e.lat, e.lon)
        assertTrue(Math.hypot(ex, ey) < 15.0)
    }

    @Test fun tooFewSamplesGivesNoEstimate() {
        assertNull(PositionEstimator.estimate(listOf(sampleAt(0.0, 0.0, 1.0, 1.0), sampleAt(5.0, 5.0, 1.0, 1.0))))
        assertNull(PositionEstimator.estimate(emptyList()))
    }

    @Test fun noisyFitIsRejectedInFavourOfCentroid() {
        val samples = ArrayList<Sample>()
        val rnd = java.util.Random(7)
        for (r in listOf(15.0, 35.0, 60.0)) for (k in 0 until 12) {
            val a = 2 * PI * k / 12
            samples.add(sampleAt(r * cos(a), r * sin(a), 0.0, 0.0, noise = (rnd.nextDouble() - 0.5) * 90))
        }
        assertEquals(Method.CENTROID, PositionEstimator.estimate(samples)!!.method)
    }

    private val table = Vendors.parsePrefixes(
        StringReader("286FB9\tNokia Shanghai Bell Co., Ltd.\nC85CE27\tSYNERGY SYSTEMS\n8C1F64AFA\tDATA ELECTRONIC DEVICES, INC\n8C1F64\tIEEE-L\n")
    )

    @Test fun vendorLookupUsesLongestPrefix() {
        assertEquals("Nokia Shanghai Bell Co., Ltd.", Vendors.lookup(table, "28:6f:b9:00:00:01"))
        assertEquals("SYNERGY SYSTEMS", Vendors.lookup(table, "c8:5c:e2:70:00:01"))
        assertEquals("DATA ELECTRONIC DEVICES, INC", Vendors.lookup(table, "8c:1f:64:af:a0:01"))
        assertEquals("IEEE-L", Vendors.lookup(table, "8c:1f:64:00:00:01"))
    }

    @Test fun vendorLookupSkipsRandomisedAndUnknown() {
        assertNull(Vendors.lookup(table, "2a:6f:b9:00:00:01")) // locally administered bit set
        assertNull(Vendors.lookup(table, "00:00:00:00:00:01"))
        assertNull(Vendors.lookup(table, "not a mac"))
    }

    @Test fun companyIdsParse() {
        val m = Vendors.parseCompanies(StringReader("76\tApple, Inc.\n6\tMicrosoft\nbad line\n"))
        assertEquals("Apple, Inc.", m[76])
        assertEquals(2, m.size)
    }

    @Test fun monogramSkipsCorporateSuffixes() {
        assertEquals("A", Vendors.monogram("Apple, Inc.").take(1))
        assertEquals("AP", Vendors.monogram("Apple, Inc."))
        assertEquals("TI", Vendors.monogram("Texas Instruments"))
        assertEquals("?", Vendors.monogram(null))
        assertEquals("?", Vendors.monogram(""))
    }
}
