package com.dejascan.app.ui

import android.content.Context
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File

private const val BIG_FILE_BYTES = 100_000_000L

/** Zoom from which big map files are drawn (setting in the MAPS dialog). */
object MapDetail {
    @Volatile var zoom: Byte = 10
}

/** Puts all stored offline map files, combined, under the other layers of one MapView. */
class OfflineTiles(private val ctx: Context, private val mapView: MapView, private val cacheId: String) {
    private var cache: TileCache? = null
    private var layer: TileRendererLayer? = null
    private var store: MapDataStore? = null

    /** Centre of the loaded maps, or null when none is loaded. */
    var centre: LatLong? = null
        private set

    /** Returns an error message, or null when fine (also when there are simply no files). */
    fun load(files: List<MapFileInfo>): String? {
        release()
        val usable = files.map { File(it.path) }.filter { it.canRead() }
        if (usable.isEmpty()) return null
        return try {
            val stores = usable.flatMap { openMap(it) }
            val s: MapDataStore = if (stores.size == 1) stores[0] else {
                MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).also { m ->
                    stores.forEach { m.addMapDataStore(it, false, false) }
                }
            }
            val c = AndroidUtil.createTileCache(
                ctx, "$cacheId-${files.hashCode()}", mapView.model.displayModel.tileSize, 1f,
                mapView.model.frameBufferModel.overdrawFactor,
            )
            val l = TileRendererLayer(c, s, mapView.model.mapViewPosition, AndroidGraphicFactory.INSTANCE)
            l.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
            mapView.layerManager.layers.add(0, l)
            cache = c; layer = l; store = s
            centre = s.boundingBox().centerPoint
            null
        } catch (e: Exception) {
            "Cannot use map files: ${e.message}"
        }
    }

    /**
     * A big file (over 100 MB) is opened twice with disjoint zoom ranges: the thin zoomed-out data (up to zoom 7)
     * and the detailed data from the chosen detail zoom. The zoom levels in between (8 and up to the detail zoom)
     * are skipped because a tile there pulls a large slice of a country into memory (several files at once ran
     * the app out of memory). A small overview map (under 100 MB) is used at every zoom.
     */
    private fun openMap(f: File): List<MapFile> {
        if (f.length() <= BIG_FILE_BYTES) return listOf(MapFile(f))
        val detail = MapDetail.zoom.toInt()
        val out = ArrayList<MapFile>()
        val lowMax = minOf(7, detail - 1)
        if (lowMax >= 0) out.add(MapFile(f).also { it.restrictToZoomRange(0, lowMax.toByte()) })
        out.add(MapFile(f).also { it.restrictToZoomRange(detail.toByte(), 21) })
        return out
    }

    fun release() {
        layer?.let { mapView.layerManager.layers.remove(it); it.onDestroy() }
        cache?.destroy()
        store?.let { runCatching { it.close() } }
        layer = null; cache = null; store = null; centre = null
    }
}
