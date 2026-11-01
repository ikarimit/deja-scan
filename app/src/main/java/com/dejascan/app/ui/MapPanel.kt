package com.dejascan.app.ui

import android.graphics.Color as AColor
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dejascan.app.data.ObsRow
import com.dejascan.app.data.SightPoint
import com.dejascan.app.scan.PhoneFix
import com.dejascan.app.scan.SightingBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.FontFamily
import org.mapsforge.core.graphics.FontStyle
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
import org.mapsforge.map.model.common.Observer
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val CLUSTER_BELOW_ZOOM = 15
private const val FAN_FROM_ZOOM = 15
private const val LINKS_FROM_ZOOM = 12

private fun wigleColor(type: String): Int = when (type) {
    "W" -> 0xFF1565C0.toInt()
    "B" -> 0xFFAD1457.toInt()
    else -> 0xFFEF6C00.toInt()
}

/**
 * Draws one dot per device: devices heard recently (filled at the estimated position, hollow and fanned out
 * around the phone when only approximate) and WiGLE devices at their last known place (clustered when zoomed
 * out). The chosen device is ringed, with its estimate circle; optionally its past places are drawn too.
 */
private class PointsLayer(private val dp: Float) : Layer() {
    @Volatile var live: List<MapDot> = emptyList()
    @Volatile var wigle: List<MapDot> = emptyList()
    @Volatile var selected: String? = null
    @Volatile var phone: PhoneFix? = null
    @Volatile var trailObs: List<ObsRow> = emptyList()
    @Volatile var trailOwn: List<SightPoint> = emptyList()
    @Volatile var links: List<LinkPath> = emptyList()
    @Volatile var onDot: (String) -> Unit = {}
    @Volatile var onCluster: (LatLong) -> Unit = {}

    private class Hit(val x: Double, val y: Double, val mac: String?, val lat: Double, val lon: Double)
    private class Cell { var sx = 0.0; var sy = 0.0; var sLat = 0.0; var sLon = 0.0; var n = 0; var first: MapDot? = null }

    @Volatile private var hits: List<Hit> = emptyList()
    @Volatile private var mapSizeNow = 0L

    private val gf = AndroidGraphicFactory.INSTANCE
    private val fill = gf.createPaint().apply { setStyle(Style.FILL) }
    private val stroke = gf.createPaint().apply { setStyle(Style.STROKE) }
    private val dotted = gf.createPaint().apply {
        setStyle(Style.STROKE)
        setStrokeCap(Cap.ROUND)
        setDashPathEffect(floatArrayOf(1f, 10f * dp))
        setStrokeWidth(4.5f * dp)
    }
    // white dots slightly bigger than the coloured ones, so the line stands out on any map colour
    private val dottedHalo = gf.createPaint().apply {
        setStyle(Style.STROKE)
        setStrokeCap(Cap.ROUND)
        setDashPathEffect(floatArrayOf(1f, 10f * dp))
        setStrokeWidth(8f * dp)
        setColor(AColor.WHITE)
    }
    private val label = gf.createPaint().apply {
        setStyle(Style.FILL)
        setColor(AColor.WHITE)
        setTextSize(12f * dp)
        setTypeface(FontFamily.MONOSPACE, FontStyle.BOLD)
    }

    private fun dot(c: Canvas, x: Int, y: Int, r: Float, color: Int, hollow: Boolean) {
        val rad = (r * dp).toInt()
        fill.setColor(if (hollow) (color and 0x00FFFFFF) or 0x66000000 else color)
        c.drawCircle(x, y, rad, fill)
        stroke.setStrokeWidth((if (hollow) 2.5f else 1.5f) * dp)
        stroke.setColor(if (hollow) color else AColor.BLACK)
        c.drawCircle(x, y, rad, stroke)
        if (hollow) { stroke.setStrokeWidth(1f * dp); stroke.setColor(AColor.BLACK); c.drawCircle(x, y, rad + (2 * dp).toInt(), stroke) }
    }

    /** A numbered bubble standing for several devices. */
    private fun bubble(c: Canvas, x: Int, y: Int, n: Int, color: Int, hollow: Boolean) {
        val r = 11f + min(9f, log2(n.toFloat()) * 1.5f)
        dot(c, x, y, r, color, hollow)
        label.setColor(if (hollow) AColor.BLACK else AColor.WHITE)
        val t = if (n < 1000) n.toString() else if (n < 100_000) "%.1fk".format(java.util.Locale.ROOT, n / 1000f) else "${n / 1000}k"
        c.drawText(t, x - (t.length * 3.6f * dp).toInt(), y + (4 * dp).toInt(), label)
    }

    override fun draw(box: BoundingBox, zoom: Byte, canvas: Canvas, top: Point, rotation: Rotation) {
        val mapSize = MercatorProjection.getMapSize(zoom, displayModel.tileSize)
        mapSizeNow = mapSize
        fun wx(lon: Double) = MercatorProjection.longitudeToPixelX(lon, mapSize)
        fun wy(lat: Double) = MercatorProjection.latitudeToPixelY(lat, mapSize)
        fun sx(w: Double) = (w - top.x).toInt()
        fun sy(w: Double) = (w - top.y).toInt()
        val out = ArrayList<Hit>()
        val sel = selected

        // dotted lines joining the places one device was seen
        for (l in links) {
            val path = gf.createPath()
            for (i in l.lat.indices) {
                val x = sx(wx(l.lon[i])).toFloat(); val y = sy(wy(l.lat[i])).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, dottedHalo)
            dotted.setColor(l.color)
            canvas.drawPath(path, dotted)
        }
        // the places themselves: tap one to open the device; a chosen device gets numbered places
        // (green = first seen, red = last seen)
        for (l in links) {
            for (i in l.lat.indices) {
                val x = wx(l.lon[i]); val y = wy(l.lat[i])
                if (l.numbered) {
                    val color = when (i) { 0 -> 0xFF00A152.toInt(); l.lat.size - 1 -> 0xFFD50000.toInt(); else -> l.color }
                    dot(canvas, sx(x), sy(y), 11f, color, false)
                    label.setColor(AColor.WHITE)
                    val t = (i + 1).toString()
                    canvas.drawText(t, sx(x) - (t.length * 3.6f * dp).toInt(), sy(y) + (4 * dp).toInt(), label)
                } else {
                    dot(canvas, sx(x), sy(y), 6f, l.color, false)
                }
                out.add(Hit(x, y, l.mac, l.lat[i], l.lon[i]))
            }
        }

        for (o in trailObs) if (box.contains(o.lat, o.lon)) dot(canvas, sx(wx(o.lon)), sy(wy(o.lat)), 3.5f, 0xFF455A64.toInt(), true)
        for (p in trailOwn) if (box.contains(p.lat, p.lon)) dot(canvas, sx(wx(p.lon)), sy(wy(p.lat)), 3.5f, 0xFF37474F.toInt(), false)

        // WiGLE devices: clusters (already counted by the database) and single dots
        for (d in wigle) {
            if (d.count == 1 && d.mac == sel) continue
            val x = wx(d.lon); val y = wy(d.lat)
            if (d.count > 1) {
                bubble(canvas, sx(x), sy(y), d.count, 0xFF37474F.toInt(), false)
                out.add(Hit(x, y, null, d.lat, d.lon))
            } else {
                dot(canvas, sx(x), sy(y), 5f, wigleColor(d.device.type), false)
                out.add(Hit(x, y, d.mac, d.lat, d.lon))
            }
        }

        // phone
        phone?.let { p ->
            val x = sx(wx(p.lon)); val y = sy(wy(p.lat))
            fill.setColor(AColor.WHITE); canvas.drawCircle(x, y, (9 * dp).toInt(), fill)
            stroke.setStrokeWidth(3.5f * dp); stroke.setColor(0xFF1E88E5.toInt()); canvas.drawCircle(x, y, (9 * dp).toInt(), stroke)
        }

        // live devices: estimated ones are single dots (merged when close together at low zoom); the
        // approximate ones share my position, so they are one numbered bubble until zoomed in far enough to fan out
        var selX = Double.NaN; var selY = Double.NaN; var selDot: MapDot? = null
        val exact = live.filter { !it.approx && box.contains(it.lat, it.lon) }
        if (zoom < CLUSTER_BELOW_ZOOM) {
            val cell = 46.0 * dp
            val cells = HashMap<Long, Cell>()
            for (d in exact) {
                if (d.mac == sel) continue
                val x = wx(d.lon); val y = wy(d.lat)
                val c = cells.getOrPut(floor(x / cell).toLong() * 1_000_003L + floor(y / cell).toLong()) { Cell() }
                c.sx += x; c.sy += y; c.sLat += d.lat; c.sLon += d.lon; c.n++
                if (c.first == null) c.first = d
            }
            for (c in cells.values) {
                val x = c.sx / c.n; val y = c.sy / c.n
                if (c.n == 1) {
                    val d = c.first!!
                    dot(canvas, sx(x), sy(y), 7f, Phosphor.forRssi(d.rssi).toArgb(), false)
                    out.add(Hit(x, y, d.mac, d.lat, d.lon))
                } else {
                    bubble(canvas, sx(x), sy(y), c.n, 0xFF00796B.toInt(), false)
                    out.add(Hit(x, y, null, c.sLat / c.n, c.sLon / c.n))
                }
            }
        }
        for (d in exact) {
            if (zoom >= CLUSTER_BELOW_ZOOM || d.mac == sel) {
                val x = wx(d.lon); val y = wy(d.lat)
                dot(canvas, sx(x), sy(y), 7f, Phosphor.forRssi(d.rssi).toArgb(), false)
                out.add(Hit(x, y, d.mac, d.lat, d.lon))
                if (d.mac == sel) { selX = x; selY = y; selDot = d }
            }
        }
        val groups = live.filter { it.approx && box.contains(it.lat, it.lon) }
            .groupBy { (it.lat * 1e5).toLong() to (it.lon * 1e5).toLong() }
        for (g in groups.values) {
            if (zoom < FAN_FROM_ZOOM && g.none { it.mac == sel }) {
                val x = wx(g[0].lon); val y = wy(g[0].lat)
                if (g.size == 1) {
                    dot(canvas, sx(x), sy(y), 6f, Phosphor.forRssi(g[0].rssi).toArgb(), true)
                    out.add(Hit(x, y, g[0].mac, g[0].lat, g[0].lon))
                } else {
                    bubble(canvas, sx(x), sy(y), g.size, 0xFF00796B.toInt(), true)
                    out.add(Hit(x, y, null, g[0].lat, g[0].lon))
                }
                continue
            }
            g.forEachIndexed { i, d ->
                var x = wx(d.lon); var y = wy(d.lat)
                if (g.size > 1) {
                    val r = 13.0 * dp * sqrt(i + 0.5)
                    val a = i * 2.399963
                    x += r * kotlin.math.cos(a); y += r * kotlin.math.sin(a)
                }
                dot(canvas, sx(x), sy(y), 6f, Phosphor.forRssi(d.rssi).toArgb(), true)
                out.add(Hit(x, y, d.mac, d.lat, d.lon))
                if (d.mac == sel) { selX = x; selY = y; selDot = d }
            }
        }
        // chosen device that is not live: draw it from the WiGLE list
        if (sel != null && selDot == null) {
            wigle.firstOrNull { it.mac == sel }?.let { d ->
                selX = wx(d.lon); selY = wy(d.lat); selDot = d
                dot(canvas, sx(selX), sy(selY), 6f, wigleColor(d.device.type), false)
                out.add(Hit(selX, selY, d.mac, d.lat, d.lon))
            }
        }
        selDot?.let { d ->
            d.estimate?.let { e ->
                val mpp = MercatorProjection.calculateGroundResolution(e.lat, mapSize)
                val rad = max((10 * dp).toInt(), (e.radiusM / mpp).toInt())
                fill.setColor(0x2200C853); canvas.drawCircle(sx(selX), sy(selY), rad, fill)
                stroke.setStrokeWidth(2f * dp); stroke.setColor(0xFF00C853.toInt()); canvas.drawCircle(sx(selX), sy(selY), rad, stroke)
            }
            stroke.setStrokeWidth(4f * dp); stroke.setColor(AColor.WHITE)
            canvas.drawCircle(sx(selX), sy(selY), (13 * dp).toInt(), stroke)
            stroke.setStrokeWidth(2f * dp); stroke.setColor(0xFFD50000.toInt())
            canvas.drawCircle(sx(selX), sy(selY), (13 * dp).toInt(), stroke)
        }
        hits = out
    }

    override fun onTap(tapLatLong: LatLong, layerXY: Point?, tapXY: Point?): Boolean {
        val ms = mapSizeNow
        if (ms == 0L) return false
        val tx = MercatorProjection.longitudeToPixelX(tapLatLong.longitude, ms)
        val ty = MercatorProjection.latitudeToPixelY(tapLatLong.latitude, ms)
        var best: Hit? = null
        var bd = (30.0 * dp) * (30.0 * dp)
        for (h in hits) {
            val d = (h.x - tx) * (h.x - tx) + (h.y - ty) * (h.y - ty)
            if (d <= bd) { bd = d; best = h }
        }
        val h = best ?: return false
        if (h.mac != null) onDot(h.mac) else onCluster(LatLong(h.lat, h.lon))
        return true
    }
}

/** Solid, opaque button so it stays readable on top of any map colours. */
@Composable
private fun MapButton(label: String, on: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Phosphor.edge),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (on) Phosphor.green else Phosphor.bg,
            contentColor = if (on) Phosphor.bg else Phosphor.green,
        ),
    ) { MText(label, color = if (on) Phosphor.bg else Phosphor.green, size = 11.sp, weight = FontWeight.Bold) }
}

@Composable
private fun MapChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.background(Phosphor.bg.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
            .border(1.dp, Phosphor.edge, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapTab(
    vm: AppViewModel,
    onPickMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val dp = ctx.resources.displayMetrics.density
    val phone by SightingBus.phone.collectAsState()
    var showFiles by remember { mutableStateOf(false) }
    var showLive by rememberSaveable { mutableStateOf(true) }
    var showWigle by rememberSaveable { mutableStateOf(true) }
    var showOthers by rememberSaveable { mutableStateOf(true) }
    var showTrail by rememberSaveable { mutableStateOf(false) }
    var showLegend by rememberSaveable { mutableStateOf(true) }
    var showLinks by rememberSaveable { mutableStateOf(false) }
    var showWifi by rememberSaveable { mutableStateOf(true) }
    var showBt by rememberSaveable { mutableStateOf(true) }
    var showBle by rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val mac = vm.selectedMac
    var soloMode by rememberSaveable { mutableStateOf(true) }

    val mapView = remember { MapView(ctx) }
    val points = remember { PointsLayer(dp) }
    val tiles = remember { OfflineTiles(ctx, mapView, "map") }
    val viewTick = remember { MutableStateFlow(0L) }

    DisposableEffect(Unit) {
        mapView.isClickable = true
        mapView.mapScaleBar.isVisible = true
        mapView.setBuiltInZoomControls(true)
        mapView.setZoomLevelMin(3)
        mapView.setZoomLevelMax(20)
        // keep every touch of a drag with the map, so no parent view steals the gesture halfway
        mapView.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        mapView.layerManager.layers.add(points)
        val observer = Observer { viewTick.value = viewTick.value + 1 }
        mapView.model.mapViewPosition.addObserver(observer)
        val lat = if (vm.mapCenterLat != 0.0 || vm.mapCenterLon != 0.0) vm.mapCenterLat else 0.0
        mapView.model.mapViewPosition.mapPosition =
            MapPosition(LatLong(lat, vm.mapCenterLon), (if (vm.mapZoom > 0) vm.mapZoom else 2).toByte())
        onDispose {
            mapView.model.mapViewPosition.removeObserver(observer)
            val mp = mapView.model.mapViewPosition.mapPosition
            vm.mapCenterLat = mp.latLong.latitude
            vm.mapCenterLon = mp.latLong.longitude
            vm.mapZoom = mp.zoomLevel.toInt()
            tiles.release()
            mapView.destroyAll()
            AndroidGraphicFactory.clearResourceMemoryCache()
        }
    }

    points.onDot = { m -> vm.select(m) }
    points.onCluster = { ll ->
        val z = mapView.model.mapViewPosition.zoomLevel.toInt()
        mapView.model.mapViewPosition.mapPosition = MapPosition(ll, min(20, z + 2).toByte())
    }

    // build the offline map from all stored files whenever the set changes
    LaunchedEffect(vm.mapFiles, vm.mapDetailZoom) {
        tiles.load(vm.mapFiles)?.let { vm.reportMapError(it) }
        if (vm.mapZoom == 0 && SightingBus.phone.value == null) {
            tiles.centre?.let { mapView.model.mapViewPosition.mapPosition = MapPosition(it, 12) }
        }
    }

    // first start: my position if there is a fix, otherwise everything stored
    LaunchedEffect(phone != null, vm.dataVersion) {
        if (vm.mapZoom != 0) return@LaunchedEffect
        val p = phone
        if (p != null) {
            mapView.model.mapViewPosition.mapPosition = MapPosition(LatLong(p.lat, p.lon), 15)
            vm.mapZoom = 15
        } else {
            delay(300)
            fitAll(vm, mapView, null, vm.liveDots)
            vm.mapZoom = mapView.model.mapViewPosition.zoomLevel.toInt()
        }
    }

    // live dots: refresh while the tab is open
    LaunchedEffect(showLive) {
        while (showLive) { vm.refreshLiveDots(); delay(5_000) }
    }

    // WiGLE dots: reload whenever the view settles (or after an import)
    LaunchedEffect(vm.dataVersion, showWigle, showWifi, showBt, showBle, showLinks) { viewTick.value = viewTick.value + 1 }
    LaunchedEffect(Unit) {
        viewTick.collectLatest {
            delay(120)
            val dim = mapView.dimension
            if (dim.width <= 0 || dim.height <= 0) return@collectLatest
            val mp = mapView.model.mapViewPosition.mapPosition
            val size = MercatorProjection.getMapSize(mp.zoomLevel, mapView.model.displayModel.tileSize).toDouble()
            val cx = MercatorProjection.longitudeToPixelX(mp.latLong.longitude, size.toLong())
            val cy = MercatorProjection.latitudeToPixelY(mp.latLong.latitude, size.toLong())
            val hw = dim.width * 0.75; val hh = dim.height * 0.75
            fun lon(px: Double) = MercatorProjection.pixelXToLongitude(px.coerceIn(0.0, size), size.toLong())
            fun lat(py: Double) = MercatorProjection.pixelYToLatitude(py.coerceIn(0.0, size), size.toLong())
            val types = buildSet { if (showWifi) add("W"); if (showBt) add("B"); if (showBle) add("E") }
            val z = mp.zoomLevel.toInt()
            val s = lat(cy + hh); val n = lat(cy - hh); val w = lon(cx - hw); val e = lon(cx + hw)
            if (showLinks) {
                // link view: only devices seen in several places, whatever the WIGLE button says
                if (z >= LINKS_FROM_ZOOM) { vm.clearWigle(); vm.loadLinks(s, n, w, e, types) }
                else { vm.clearLinks(); vm.loadWigle(s, n, w, e, z, types, linkedOnly = true) }
            } else {
                vm.clearLinks()
                if (showWigle) vm.loadWigle(s, n, w, e, z, types) else vm.clearWigle()
            }
        }
    }

    LaunchedEffect(mac, showTrail) { vm.loadTrail(if (showTrail) mac else null) }

    // push the data into the drawing layer (the phone only counts when it moved about a metre)
    val phoneKey = phone?.let { (it.lat * 1e5).toLong() to (it.lon * 1e5).toLong() }
    LaunchedEffect(
        vm.liveDots, vm.wigleDots, showLive, showOthers, showWigle, mac, phoneKey, vm.trailObs, vm.trailOwn,
        showTrail, vm.linkPaths, showLinks, vm.linkMode, soloMode, vm.selectedPlaces, vm.selectedDevice,
    ) {
        if (soloMode && mac != null) {
            // only the chosen device: its dot, and its places joined by a dotted line
            points.live = vm.liveDots.filter { it.mac == mac }
            val d = vm.selectedDevice?.takeIf { it.lat != 0.0 || it.lon != 0.0 }
            points.wigle = if (points.live.isEmpty() && d != null) listOf(MapDot(d, false, false, 0, d.lat, d.lon, null)) else emptyList()
            val pl = vm.selectedPlaces
            points.links = if (pl.size >= 2) {
                listOf(LinkPath(mac, 0xFF6A1B9A.toInt(), DoubleArray(pl.size) { pl[it].lat }, DoubleArray(pl.size) { pl[it].lon }, numbered = true))
            } else emptyList()
        } else {
            // in the link view only devices seen in several places are shown: no live dots, no other WiGLE dots
            points.live = if (showLive && !showLinks) vm.liveDots.filter { showOthers || it.watched } else emptyList()
            points.wigle = if (showWigle || showLinks) vm.wigleDots else emptyList()
            points.links = if (showLinks) vm.linkPaths else emptyList()
        }
        points.selected = mac
        points.phone = phone
        points.trailObs = if (showTrail) vm.trailObs else emptyList()
        points.trailOwn = if (showTrail) vm.trailOwn else emptyList()
        points.requestRedraw()
    }

    // a chosen device (tapped here or on the Live tab): zoom so all the places it was seen are visible
    LaunchedEffect(mac, vm.selectedPlaces, vm.selectedDevice) {
        if (mac == null) return@LaunchedEffect
        val pts = ArrayList<LatLong>()
        vm.selectedPlaces.forEach { pts.add(LatLong(it.lat, it.lon)) }
        vm.liveDots.firstOrNull { it.mac == mac }?.let { pts.add(LatLong(it.lat, it.lon)) }
        vm.selectedDevice?.takeIf { it.lat != 0.0 || it.lon != 0.0 }?.let { if (pts.isEmpty()) pts.add(LatLong(it.lat, it.lon)) }
        if (pts.isNotEmpty()) fitPoints(mapView, pts)
    }

    fun fit() { scope.launch { fitAll(vm, mapView, phone, vm.liveDots) } }

    if (showFiles) {
        AlertDialog(
            onDismissRequest = { showFiles = false },
            containerColor = Phosphor.bg,
            title = { MText("MAP FILES", color = Phosphor.green, size = 14.sp, weight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MText(
                        "> all files below are combined into one map, so you can add one per country or region. Files over 100 MB are only drawn from the detail zoom below (to avoid running out of memory); a small overview file shows when zoomed out.",
                        color = Phosphor.sub, size = 11.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MText("detail from zoom", Modifier.weight(1f), color = Phosphor.text, size = 12.sp)
                        MapButton("-") { vm.changeMapDetail(vm.mapDetailZoom - 1) }
                        MText("${vm.mapDetailZoom}", Modifier.padding(horizontal = 10.dp), color = Phosphor.green, size = 14.sp, weight = FontWeight.Bold)
                        MapButton("+") { vm.changeMapDetail(vm.mapDetailZoom + 1) }
                    }
                    MText("> raise it if the map is slow or the app runs out of memory; lower it to see roads when zoomed out.", color = Phosphor.sub, size = 10.sp)
                    if (vm.mapFiles.isEmpty()) MText("> none loaded yet", color = Phosphor.amber, size = 11.sp)
                    vm.mapFiles.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                MText(f.name, color = Phosphor.text, size = 12.sp, maxLines = 1)
                                MText("${f.bytes / 1_000_000} MB", color = Phosphor.sub, size = 10.sp)
                            }
                            MText(
                                "[REMOVE]", Modifier.clickable { vm.removeMap(f.name) }.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                                color = Phosphor.red, size = 11.sp,
                            )
                        }
                    }
                    if (vm.mapStatus.isNotEmpty()) MText(vm.mapStatus, color = Phosphor.amber, size = 11.sp)
                }
            },
            confirmButton = { MapButton("ADD FILES", onClick = onPickMap) },
            dismissButton = { MapButton("CLOSE") { showFiles = false } },
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val panelHeight = maxHeight * 0.52f
        val emptyHeight = maxHeight * 0.3f
        val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        Box(Modifier.fillMaxSize().background(Phosphor.bg)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            val panel: @Composable (Modifier) -> Unit = { m ->
                if (mac != null) {
                    DeviceDetails(vm, mac, showMapTab = true, m)
                } else if (showLegend) {
                    EmptyPanel(vm, showLive, showOthers, showWigle, { showLegend = false }, m)
                }
            }
            // landscape: the box opens below the buttons, in the same column; portrait: it sits at the bottom
            Column(
                Modifier.align(Alignment.TopStart).then(safe).then(if (landscape) Modifier.fillMaxHeight() else Modifier)
                    .padding(start = 6.dp, top = 6.dp, end = 76.dp, bottom = if (landscape) 6.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapButton("LIVE", showLive) { showLive = !showLive }
                    MapButton("WIGLE", showWigle) { showWigle = !showWigle }
                    MapButton("OTHERS", showOthers) { showOthers = !showOthers }
                    MapButton("WI-FI", showWifi) { showWifi = !showWifi }
                    MapButton("BT", showBt) { showBt = !showBt }
                    MapButton("BLE", showBle) { showBle = !showBle }
                    MapButton("TRAIL", showTrail) { showTrail = !showTrail }
                    MapButton("LINKS", showLinks) { showLinks = !showLinks }
                    MapButton("SOLO", soloMode) { soloMode = !soloMode }
                    MapButton("LEGEND", showLegend) { showLegend = !showLegend }
                }
                if (showLinks) {
                    MapChip {
                        MText(
                            if (vm.linkError.isNotEmpty()) "> links failed: ${vm.linkError}"
                            else if (vm.linkMode && vm.linkLoading) "> looking for devices seen in several places..."
                            else if (vm.linkMode) "${vm.linkPaths.size} devices seen in several places in this view (others hidden)"
                            else "${vm.wigleDots.sumOf { it.count }} devices seen in several places in view. Zoom in (12+) for the dotted lines",
                            color = Phosphor.amber, size = 11.sp,
                        )
                    }
                }
                if (vm.mapStatus.isNotEmpty()) {
                    MapChip { MText(vm.mapStatus, color = Phosphor.amber, size = 11.sp) }
                }
                if (landscape && (mac != null || showLegend)) {
                    panel(Modifier.width(384.dp).weight(1f))
                }
            }
            Column(
                Modifier.align(Alignment.TopEnd).then(safe).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                MapButton("FIT") { fit() }
                MapButton("ME") {
                    phone?.let { mapView.model.mapViewPosition.mapPosition = MapPosition(LatLong(it.lat, it.lon), 16) }
                }
                MapButton("MAPS") { showFiles = true }
            }

            if (!landscape) {
                panel(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height(if (mac != null) panelHeight else emptyHeight).padding(8.dp),
                )
            }
        }
    }
}


/** Zooms and centres the map so all the points are visible. */
private fun fitPoints(mapView: MapView, ll: List<LatLong>) {
    if (ll.isEmpty()) return
    val bb = BoundingBox(ll)
    val dim = mapView.dimension
    val same = bb.maxLatitude - bb.minLatitude < 1e-5 && bb.maxLongitude - bb.minLongitude < 1e-5
    val z = if (!same && dim.width > 0 && dim.height > 0) {
        (LatLongUtils.zoomForBounds(dim, bb, mapView.model.displayModel.tileSize) - 1).coerceIn(3, 17)
    } else 16
    mapView.model.mapViewPosition.mapPosition = MapPosition(bb.centerPoint, z.toByte())
}

/** Fits the view to everything stored (plus live dots and the phone). */
private suspend fun fitAll(vm: AppViewModel, mapView: MapView, phone: PhoneFix?, live: List<MapDot>) {
    val ll = ArrayList<LatLong>()
    vm.deviceBounds()?.let { b -> ll.add(LatLong(b[0], b[2])); ll.add(LatLong(b[1], b[3])) }
    live.forEach { ll.add(LatLong(it.lat, it.lon)) }
    phone?.let { ll.add(LatLong(it.lat, it.lon)) }
    fitPoints(mapView, ll)
}

/** The box that is always there: legend and counts while no device is chosen. */
@Composable
private fun EmptyPanel(vm: AppViewModel, live: Boolean, others: Boolean, wigle: Boolean, onHide: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val scroll = rememberScrollState()
    Column(
        modifier.fillMaxWidth().background(Phosphor.bg, shape).border(1.dp, Phosphor.edge, shape).padding(12.dp)
            .scrollbar(scroll).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MText("> tap a dot for details", Modifier.weight(1f), color = Phosphor.green, size = 13.sp, weight = FontWeight.Bold)
            Box(
                Modifier.size(40.dp).border(2.dp, Phosphor.red, RoundedCornerShape(8.dp)).clickable { onHide() },
                contentAlignment = Alignment.Center,
            ) { MText("X", color = Phosphor.red, size = 22.sp, weight = FontWeight.Bold) }
        }
        MText(
            "${vm.liveDots.count { others || it.watched }} heard now   ${vm.wigleDots.sumOf { it.count }} WiGLE in view" +
                if (vm.wigleDots.size >= 4000) " (capped: zoom in)" else "",
            color = Phosphor.text, size = 11.sp,
        )
        MText("-- LEGEND --", color = Phosphor.green, size = 11.sp, weight = FontWeight.Bold)
        MText("filled green  heard now, position estimated", color = Phosphor.text, size = 11.sp)
        MText("hollow green  heard now, only near my position (approximate)", color = Phosphor.text, size = 11.sp)
        MText("blue / pink / orange  WiGLE Wi-Fi / Bluetooth / BLE, last known place", color = Phosphor.text, size = 11.sp)
        MText("numbered bubble  several devices, tap to zoom in (they spread out as you zoom)", color = Phosphor.text, size = 11.sp)
        MText("white ring with blue edge  me", color = Phosphor.text, size = 11.sp)
        MText("dotted line  one device seen in several places (LINKS, zoom 12+)", color = Phosphor.text, size = 11.sp)
        MText("numbered places  chosen device: green 1 = first seen, red = last seen", color = Phosphor.text, size = 11.sp)
        MText("-- BUTTONS --", color = Phosphor.green, size = 11.sp, weight = FontWeight.Bold)
        MText(
            "LIVE ${if (live) "on" else "off"}: devices heard now.  OTHERS ${if (others) "on" else "off"}: devices that are not on your watch list.  " +
                "WIGLE ${if (wigle) "on" else "off"}: imported devices (WI-FI / BT / BLE pick the kinds).  TRAIL: past places of the chosen device.  LINKS: dotted lines between the places each device was seen.  SOLO: when a device is chosen, show only that device and its places.",
            color = Phosphor.sub, size = 11.sp,
        )
        if (vm.mapFiles.isEmpty()) {
            MText("> no offline map loaded: use MAPS to add one", color = Phosphor.amber, size = 11.sp)
        }
    }
}
