package com.dejascan.app.ui

import android.graphics.Color as AColor
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dejascan.app.geo.Estimate
import com.dejascan.app.geo.Geo
import com.dejascan.app.scan.PhoneFix
import kotlinx.coroutines.delay
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Paint
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
import kotlin.math.max

/** Device (diamond = WiGLE last known, cross in a circle = estimate) and the phone (ring) on the small map. */
private class MiniLayer(private val dp: Float) : Layer() {
    @Volatile var target: LatLong? = null
    @Volatile var estimate: Estimate? = null
    @Volatile var phone: PhoneFix? = null

    private val gf = AndroidGraphicFactory.INSTANCE
    private fun paint(color: Int, fill: Boolean, width: Float = 2f): Paint = gf.createPaint().apply {
        setColor(color)
        setStyle(if (fill) Style.FILL else Style.STROKE)
        setStrokeWidth(width * dp)
    }

    override fun draw(box: BoundingBox, zoom: Byte, canvas: Canvas, top: Point, rotation: Rotation) {
        val mapSize = MercatorProjection.getMapSize(zoom, displayModel.tileSize)
        fun px(lon: Double) = (MercatorProjection.longitudeToPixelX(lon, mapSize) - top.x).toInt()
        fun py(lat: Double) = (MercatorProjection.latitudeToPixelY(lat, mapSize) - top.y).toInt()
        val red = 0xFFD50000.toInt()
        phone?.let { p ->
            canvas.drawCircle(px(p.lon), py(p.lat), (8 * dp).toInt(), paint(AColor.WHITE, true))
            canvas.drawCircle(px(p.lon), py(p.lat), (8 * dp).toInt(), paint(0xFF1E88E5.toInt(), false, 3f))
        }
        val e = estimate
        val t = target ?: return
        val x = px(t.longitude); val y = py(t.latitude)
        if (e != null) {
            val mpp = MercatorProjection.calculateGroundResolution(e.lat, mapSize)
            val rad = max((8 * dp).toInt(), (e.radiusM / mpp).toInt())
            canvas.drawCircle(x, y, rad, paint((red and 0x00FFFFFF) or 0x33000000, true))
            canvas.drawCircle(x, y, rad, paint(red, false, 2f))
            val c = paint(red, false, 3f)
            val r = (9 * dp).toInt()
            canvas.drawLine(x - r, y, x + r, y, c); canvas.drawLine(x, y - r, x, y + r, c)
        } else {
            val r = (11 * dp).toInt()
            val c = paint(red, false, 3f)
            canvas.drawLine(x, y - r, x + r, y, c); canvas.drawLine(x + r, y, x, y + r, c)
            canvas.drawLine(x, y + r, x - r, y, c); canvas.drawLine(x - r, y, x, y - r, c)
        }
    }
}

/** A small second map centred on the device, using the same offline map files. */
@Composable
fun MiniMap(
    files: List<MapFileInfo>,
    target: LatLong?,
    estimate: Estimate?,
    phone: PhoneFix?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val mapView = remember { MapView(ctx) }
    val dp = ctx.resources.displayMetrics.density
    val layer = remember { MiniLayer(dp) }
    val tiles = remember { OfflineTiles(ctx, mapView, "mini") }

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
        onDispose {
            tiles.release()
            mapView.destroyAll()
            AndroidGraphicFactory.clearResourceMemoryCache()
        }
    }

    LaunchedEffect(files) { tiles.load(files) }

    LaunchedEffect(target, estimate, phone) {
        layer.target = target
        layer.estimate = estimate
        layer.phone = phone
        layer.requestRedraw()
    }

    // centre once per device (and again when the phone's first fix arrives), not on every GPS tick
    LaunchedEffect(target, phone != null) {
        val t = target ?: return@LaunchedEffect
        delay(200) // wait for the view to be measured
        val dim = mapView.dimension
        val near = phone != null && Geo.haversineM(phone.lat, phone.lon, t.latitude, t.longitude) < 3000
        val pos = if (near && dim.width > 0 && dim.height > 0) {
            val bb = BoundingBox(listOf(t, LatLong(phone.lat, phone.lon)))
            val z = (LatLongUtils.zoomForBounds(dim, bb, mapView.model.displayModel.tileSize) - 1).coerceIn(3, 17)
            MapPosition(bb.centerPoint, z.toByte())
        } else MapPosition(t, 16)
        mapView.model.mapViewPosition.mapPosition = pos
    }

    Box(modifier.clipToBounds().background(Phosphor.bg)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}
