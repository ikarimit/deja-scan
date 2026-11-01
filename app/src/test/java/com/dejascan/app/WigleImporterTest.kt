package com.dejascan.app

import com.dejascan.app.data.WigleImporter
import org.junit.Assert.assertEquals
import org.junit.Test

class WigleImporterTest {
    @Test fun prefersBestLocation() {
        assertEquals(11.0 to 21.0, WigleImporter.pickLocation(11.0, 21.0, 10.0, 20.0))
    }

    @Test fun fallsBackToLastWhenBestIsZeroOrInfinite() {
        assertEquals(10.0 to 20.0, WigleImporter.pickLocation(0.0, 0.0, 10.0, 20.0))
        assertEquals(10.0 to 20.0, WigleImporter.pickLocation(Double.POSITIVE_INFINITY, 5.0, 10.0, 20.0))
        assertEquals(10.0 to 20.0, WigleImporter.pickLocation(95.0, 5.0, 10.0, 20.0))
    }

    @Test fun returnsZeroWhenNothingUsable() {
        assertEquals(0.0 to 0.0, WigleImporter.pickLocation(0.0, 0.0, Double.NaN, 1.0))
    }
}
