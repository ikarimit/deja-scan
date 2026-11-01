package com.dejascan.app

import com.dejascan.app.geo.CellGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellGridTest {
    @Test fun parentCellIsHalfOfChild() {
        val lat = 51.1657; val lon = 10.4515
        for (z in CellGrid.MIN_Z until CellGrid.BASE_Z) {
            val child = CellGrid.cell(CellGrid.nx(lon), z + 1)
            val parent = CellGrid.cell(CellGrid.nx(lon), z)
            assertEquals(child / 2, parent)
            assertEquals(CellGrid.cell(CellGrid.ny(lat), z + 1) / 2, CellGrid.cell(CellGrid.ny(lat), z))
        }
    }

    @Test fun northIsSmallerYAndEastIsLargerX() {
        assertTrue(CellGrid.ny(60.0) < CellGrid.ny(40.0))
        assertTrue(CellGrid.nx(20.0) > CellGrid.nx(10.0))
    }

    @Test fun edgesStayInRange() {
        val n = (1 shl 5) * CellGrid.SUB
        assertEquals(0, CellGrid.cell(CellGrid.nx(-180.0), 5))
        assertEquals(n - 1, CellGrid.cell(CellGrid.nx(180.0), 5))
        assertTrue(CellGrid.cell(CellGrid.ny(90.0), 5) >= 0)
    }
}
