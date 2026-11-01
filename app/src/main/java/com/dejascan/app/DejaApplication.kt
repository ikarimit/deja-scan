package com.dejascan.app

import android.app.Application
import com.dejascan.app.data.Prefs
import com.dejascan.app.data.Vendors
import com.dejascan.app.ui.MapDetail
import org.mapsforge.core.util.Parameters
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

class DejaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidGraphicFactory.createInstance(this)
        // fewer simultaneous tile renderers: each one holds a block of map data, and 8 at once ran the heap dry
        Parameters.NUMBER_OF_THREADS = 2
        MapDetail.zoom = Prefs(this).mapDetailZoom.toByte()
        Vendors.init(this)
    }
}
