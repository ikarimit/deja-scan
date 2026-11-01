package com.dejascan.app.ui

import android.graphics.Color as AColor
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer

/** Small coloured dots, one per place a device was seen on the chosen day. */
private class DayLayer(private val dp: Float) : Layer() {
    @Volatile var points: List<DayPoint> = emptyList()
    private val gf = AndroidGraphicFactory.INSTANCE
    private val fill = gf.createPaint().apply { setStyle(Style.FILL) }
    private val edge = gf.createPaint().apply { setStyle(Style.STROKE); setColor(AColor.BLACK); setStrokeWidth(1f * dp) }

    override fun draw(box: BoundingBox, zoom: Byte, canvas: Canvas, top: Point, rotation: Rotation) {
        val mapSize = MercatorProjection.getMapSize(zoom, displayModel.tileSize)
        val r = (4 * dp).toInt()
        for (p in points) {
            if (!box.contains(p.lat, p.lon)) continue
            val x = (MercatorProjection.longitudeToPixelX(p.lon, mapSize) - top.x).toInt()
            val y = (MercatorProjection.latitudeToPixelY(p.lat, mapSize) - top.y).toInt()
            fill.setColor(when (p.type) { "W" -> 0xFF1565C0.toInt(); "B" -> 0xFFAD1457.toInt(); "E" -> 0xFFEF6C00.toInt(); else -> 0xFF607D8B.toInt() })
            canvas.drawCircle(x, y, r, fill)
            canvas.drawCircle(x, y, r, edge)
        }
    }
}

/** Map block for the Devices tab: every place devices were seen on the chosen day. */
@Composable
fun DayMap(files: List<MapFileInfo>, points: List<DayPoint>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mapView = remember { MapView(ctx) }
    val layer = remember { DayLayer(ctx.resources.displayMetrics.density) }
    val tiles = remember { OfflineTiles(ctx, mapView, "day") }

    DisposableEffect(Unit) {
        mapView.isClickable = true
        mapView.mapScaleBar.isVisible = true
        mapView.setBuiltInZoomControls(true)
        mapView.setZoomLevelMin(3)
        mapView.setZoomLevelMax(20)
        mapView.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        mapView.layerManager.layers.add(layer)
        mapView.model.mapViewPosition.mapPosition = MapPosition(LatLong(0.0, 0.0), 2)
        onDispose {
            tiles.release()
            mapView.destroyAll()
            AndroidGraphicFactory.clearResourceMemoryCache()
        }
    }

    LaunchedEffect(files) { tiles.load(files) }

    LaunchedEffect(points) {
        layer.points = points
        layer.requestRedraw()
        if (points.isEmpty()) return@LaunchedEffect
        delay(200) // wait for the view to be measured
        val bb = BoundingBox(points.map { LatLong(it.lat, it.lon) })
        val dim = mapView.dimension
        val same = bb.maxLatitude - bb.minLatitude < 1e-5 && bb.maxLongitude - bb.minLongitude < 1e-5
        val z = if (!same && dim.width > 0 && dim.height > 0) {
            (LatLongUtils.zoomForBounds(dim, bb, mapView.model.displayModel.tileSize) - 1).coerceIn(3, 17)
        } else 16
        mapView.model.mapViewPosition.mapPosition = MapPosition(bb.centerPoint, z.toByte())
    }

    Box(modifier.clipToBounds().background(Phosphor.bg)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}
