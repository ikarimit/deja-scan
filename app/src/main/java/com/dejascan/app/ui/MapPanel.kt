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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dejascan.app.data.ObsRow
import com.dejascan.app.data.SightPoint
import com.dejascan.app.scan.ChannelWidthHelper
import com.dejascan.app.scan.DetectionCategory
import com.dejascan.app.scan.DetectionClassifier
import com.dejascan.app.scan.PhoneFix
import com.dejascan.app.scan.SightingBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.FontFamily
import org.mapsforge.core.graphics.FontStyle
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

/**
 * Draws one dot per device color-coded by [DetectionCategory]:
 * - Possible audit tool (Crimson), Outdated WEP (Orange), Rare (Gold), Uncommon (Cyan), Randomized MAC (Purple), Normal (Green).
 * - Live estimated positions are larger filled dots; live approximate positions are hollow dots fanned out around the phone;
 *   WiGLE stored positions are compact filled dots (or numbered cluster bubbles when zoomed out).
 * - Selecting a dot highlights it with a target ring and an on-map HUD tag displaying its name, category, and channel + width.
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
    private class Cell {
        var sx = 0.0; var sy = 0.0; var sLat = 0.0; var sLon = 0.0; var n = 0
        var first: MapDot? = null
        var topCategory: DetectionCategory = DetectionCategory.NORMAL
    }

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
        setTextSize(11f * dp)
        setTypeface(FontFamily.MONOSPACE, FontStyle.BOLD)
    }
    private val subLabel = gf.createPaint().apply {
        setStyle(Style.FILL)
        setColor(0xFFB8FFD6.toInt())
        setTextSize(10f * dp)
        setTypeface(FontFamily.MONOSPACE, FontStyle.NORMAL)
    }

    private fun dot(c: Canvas, x: Int, y: Int, r: Float, color: Int, hollow: Boolean, alertHalo: Boolean = false) {
        val rad = (r * dp).toInt()
        if (alertHalo) {
            stroke.setStrokeWidth(2f * dp)
            stroke.setColor(color)
            c.drawCircle(x, y, rad + (4 * dp).toInt(), stroke)
        }
        fill.setColor(if (hollow) (color and 0x00FFFFFF) or 0x55000000 else color)
        c.drawCircle(x, y, rad, fill)
        stroke.setStrokeWidth((if (hollow) 2.5f else 1.5f) * dp)
        stroke.setColor(if (hollow) color else AColor.BLACK)
        c.drawCircle(x, y, rad, stroke)
        if (hollow) {
            stroke.setStrokeWidth(1f * dp)
            stroke.setColor(AColor.BLACK)
            c.drawCircle(x, y, rad + (2 * dp).toInt(), stroke)
        }
    }

    private fun bubble(c: Canvas, x: Int, y: Int, n: Int, color: Int, hollow: Boolean) {
        val r = 11f + min(9f, log2(n.toFloat()) * 1.5f)
        dot(c, x, y, r, color, hollow)
        label.setColor(if (hollow) AColor.BLACK else AColor.WHITE)
        val t = when {
            n < 1000 -> n.toString()
            n < 100_000 -> "%.1fk".format(java.util.Locale.ROOT, n / 1000f)
            else -> "${n / 1000}k"
        }
        c.drawText(t, x - (t.length * 3.4f * dp).toInt(), y + (4 * dp).toInt(), label)
    }

    private fun drawHudTag(c: Canvas, x: Int, y: Int, d: MapDot) {
        val namePart = d.device.name.ifEmpty { d.mac }.take(18)
        val catTag = if (d.category != DetectionCategory.NORMAL) " [${d.category.shortTag}]" else ""
        val line1 = "$namePart$catTag"
        val line2 = buildString {
            if (d.channelInfo.isNotEmpty()) append(d.channelInfo)
            if (d.live) {
                if (isNotEmpty()) append(" • ")
                append("${d.rssi} dBm")
            }
        }
        val chars = max(line1.length, line2.length)
        val boxW = (chars * 6.8f * dp + 14f * dp).toInt()
        val boxH = ((if (line2.isNotEmpty()) 30f else 18f) * dp).toInt()
        val bx = max((6 * dp).toInt(), x - boxW / 2)
        val by = max((8 * dp).toInt(), y - (18 * dp).toInt() - boxH)

        val bg = gf.createPath().apply {
            moveTo(bx.toFloat(), by.toFloat())
            lineTo((bx + boxW).toFloat(), by.toFloat())
            lineTo((bx + boxW).toFloat(), (by + boxH).toFloat())
            lineTo(bx.toFloat(), (by + boxH).toFloat())
            lineTo(bx.toFloat(), by.toFloat())
        }
        fill.setColor(0xE6040A06.toInt())
        c.drawPath(bg, fill)
        stroke.setStrokeWidth(1.5f * dp)
        stroke.setColor(d.category.mapColorArgb)
        c.drawPath(bg, stroke)

        label.setColor(AColor.WHITE)
        c.drawText(line1, bx + (7 * dp).toInt(), by + (13 * dp).toInt(), label)
        if (line2.isNotEmpty()) {
            subLabel.setColor(0xFF00E5FF.toInt())
            c.drawText(line2, bx + (7 * dp).toInt(), by + (25 * dp).toInt(), subLabel)
        }
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
        for (l in links) {
            for (i in l.lat.indices) {
                val x = wx(l.lon[i]); val y = wy(l.lat[i])
                if (l.numbered) {
                    val color = when (i) { 0 -> 0xFF00A152.toInt(); l.lat.size - 1 -> 0xFFD50000.toInt(); else -> l.color }
                    dot(canvas, sx(x), sy(y), 11f, color, false)
                    label.setColor(AColor.WHITE)
                    val t = (i + 1).toString()
                    canvas.drawText(t, sx(x) - (t.length * 3.4f * dp).toInt(), sy(y) + (4 * dp).toInt(), label)
                } else {
                    dot(canvas, sx(x), sy(y), 6f, l.color, false)
                }
                out.add(Hit(x, y, l.mac, l.lat[i], l.lon[i]))
            }
        }

        for (o in trailObs) if (box.contains(o.lat, o.lon)) dot(canvas, sx(wx(o.lon)), sy(wy(o.lat)), 3.5f, 0xFF455A64.toInt(), true)
        for (p in trailOwn) if (box.contains(p.lat, p.lon)) dot(canvas, sx(wx(p.lon)), sy(wy(p.lat)), 3.5f, 0xFF37474F.toInt(), false)

        // WiGLE devices: clusters and color-coded single dots
        for (d in wigle) {
            if (d.count == 1 && d.mac == sel) continue
            val x = wx(d.lon); val y = wy(d.lat)
            if (d.count > 1) {
                bubble(canvas, sx(x), sy(y), d.count, 0xFF37474F.toInt(), false)
                out.add(Hit(x, y, null, d.lat, d.lon))
            } else {
                val isAlert = d.category == DetectionCategory.AUDIT_TOOL || d.category == DetectionCategory.WEP
                dot(canvas, sx(x), sy(y), 5f, d.category.mapColorArgb, false, alertHalo = isAlert)
                out.add(Hit(x, y, d.mac, d.lat, d.lon))
            }
        }

        phone?.let { p ->
            val x = sx(wx(p.lon)); val y = sy(wy(p.lat))
            fill.setColor(AColor.WHITE); canvas.drawCircle(x, y, (9 * dp).toInt(), fill)
            stroke.setStrokeWidth(3.5f * dp); stroke.setColor(0xFF1E88E5.toInt()); canvas.drawCircle(x, y, (9 * dp).toInt(), stroke)
        }

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
                if (d.category.priority < c.topCategory.priority) c.topCategory = d.category
            }
            for (c in cells.values) {
                val x = c.sx / c.n; val y = c.sy / c.n
                if (c.n == 1) {
                    val d = c.first!!
                    val isAlert = d.category == DetectionCategory.AUDIT_TOOL || d.category == DetectionCategory.WEP
                    dot(canvas, sx(x), sy(y), 7.5f, d.category.mapColorArgb, false, alertHalo = isAlert)
                    out.add(Hit(x, y, d.mac, d.lat, d.lon))
                } else {
                    val bCol = if (c.topCategory.priority <= 1) c.topCategory.mapColorArgb else 0xFF00796B.toInt()
                    bubble(canvas, sx(x), sy(y), c.n, bCol, false)
                    out.add(Hit(x, y, null, c.sLat / c.n, c.sLon / c.n))
                }
            }
        }
        for (d in exact) {
            if (zoom >= CLUSTER_BELOW_ZOOM || d.mac == sel) {
                val x = wx(d.lon); val y = wy(d.lat)
                val isAlert = d.category == DetectionCategory.AUDIT_TOOL || d.category == DetectionCategory.WEP
                dot(canvas, sx(x), sy(y), 7.5f, d.category.mapColorArgb, false, alertHalo = isAlert)
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
                    val d = g[0]
                    val isAlert = d.category == DetectionCategory.AUDIT_TOOL || d.category == DetectionCategory.WEP
                    dot(canvas, sx(x), sy(y), 6.5f, d.category.mapColorArgb, true, alertHalo = isAlert)
                    out.add(Hit(x, y, d.mac, d.lat, d.lon))
                } else {
                    val topCat = g.minByOrNull { it.category.priority }?.category ?: DetectionCategory.NORMAL
                    val bCol = if (topCat.priority <= 1) topCat.mapColorArgb else 0xFF00796B.toInt()
                    bubble(canvas, sx(x), sy(y), g.size, bCol, true)
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
                val isAlert = d.category == DetectionCategory.AUDIT_TOOL || d.category == DetectionCategory.WEP
                dot(canvas, sx(x), sy(y), 6.5f, d.category.mapColorArgb, true, alertHalo = isAlert)
                out.add(Hit(x, y, d.mac, d.lat, d.lon))
                if (d.mac == sel) { selX = x; selY = y; selDot = d }
            }
        }
        if (sel != null && selDot == null) {
            wigle.firstOrNull { it.mac == sel }?.let { d ->
                selX = wx(d.lon); selY = wy(d.lat); selDot = d
                dot(canvas, sx(selX), sy(selY), 6.5f, d.category.mapColorArgb, false)
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
            stroke.setStrokeWidth(2f * dp); stroke.setColor(d.category.mapColorArgb)
            canvas.drawCircle(sx(selX), sy(selY), (13 * dp).toInt(), stroke)
            drawHudTag(canvas, sx(selX), sy(selY), d)
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

@Composable
private fun MapButton(label: String, on: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Phosphor.edge),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
        modifier = Modifier.height(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (on) Phosphor.green else Phosphor.bg.copy(alpha = 0.92f),
            contentColor = if (on) Phosphor.bg else Phosphor.green,
        ),
    ) { MText(label, color = if (on) Phosphor.bg else Phosphor.green, size = 10.5.sp, weight = FontWeight.Bold) }
}

@Composable
private fun MiniToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (on) Phosphor.dim else Phosphor.bg, RoundedCornerShape(5.dp))
            .border(1.dp, if (on) Phosphor.green else Phosphor.grid, RoundedCornerShape(5.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        MText(label, color = if (on) Phosphor.green else Phosphor.sub, size = 9.5.sp, weight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun MapChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.background(Phosphor.bg.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
            .border(1.dp, Phosphor.edge, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
    ) { content() }
}

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
    var showLayers by rememberSaveable { mutableStateOf(false) }
    var showLive by rememberSaveable { mutableStateOf(true) }
    var showWigle by rememberSaveable { mutableStateOf(true) }
    var showOthers by rememberSaveable { mutableStateOf(true) }
    var showTrail by rememberSaveable { mutableStateOf(false) }
    var showLegend by rememberSaveable { mutableStateOf(false) }
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
        mapView.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        mapView.layerManager.layers.add(points)
        val observer = Observer { viewTick.value = viewTick.value + 1 }
        mapView.model.mapViewPosition.addObserver(observer)
        mapView.model.mapViewPosition.mapPosition =
            MapPosition(LatLong(vm.mapCenterLat, vm.mapCenterLon), (if (vm.mapZoom > 0) vm.mapZoom else 2).toByte())
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

    LaunchedEffect(vm.mapFiles, vm.mapDetailZoom) {
        tiles.load(vm.mapFiles)?.let { vm.reportMapError(it) }
        if (vm.mapZoom == 0 && SightingBus.phone.value == null) {
            tiles.centre?.let { mapView.model.mapViewPosition.mapPosition = MapPosition(it, 12) }
        }
    }

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

    LaunchedEffect(showLive) {
        while (showLive) { vm.refreshLiveDots(); delay(5_000) }
    }

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
                if (z >= LINKS_FROM_ZOOM) { vm.clearWigle(); vm.loadLinks(s, n, w, e, types) }
                else { vm.clearLinks(); vm.loadWigle(s, n, w, e, z, types, linkedOnly = true) }
            } else {
                vm.clearLinks()
                if (showWigle) vm.loadWigle(s, n, w, e, z, types) else vm.clearWigle()
            }
        }
    }

    LaunchedEffect(mac, showTrail) { vm.loadTrail(if (showTrail) mac else null) }

    val phoneKey = phone?.let { (it.lat * 1e5).toLong() to (it.lon * 1e5).toLong() }
    LaunchedEffect(
        vm.liveDots, vm.wigleDots, showLive, showOthers, showWigle, mac, phoneKey, vm.trailObs, vm.trailOwn,
        showTrail, vm.linkPaths, showLinks, vm.linkMode, soloMode, vm.selectedPlaces, vm.selectedDevice,
    ) {
        if (soloMode && mac != null) {
            points.live = vm.liveDots.filter { it.mac == mac }
            val d = vm.selectedDevice?.takeIf { it.lat != 0.0 || it.lon != 0.0 }
            points.wigle = if (points.live.isEmpty() && d != null) {
                val liveS = SightingBus.sightings.value[d.mac]
                val cls = DetectionClassifier.classifyDevice(d, liveS, hasDatabase = vm.total > 0)
                val ch = liveS?.channelSummary?.ifEmpty { null }
                    ?: if (d.type == "W" && d.freq > 0) ChannelWidthHelper.formatChannelWidth(d.freq, -1, compact = true) else ""
                listOf(MapDot(d, false, false, 0, d.lat, d.lon, null, category = cls.category, channelInfo = ch))
            } else emptyList()
            val pl = vm.selectedPlaces
            points.links = if (pl.size >= 2) {
                listOf(LinkPath(mac, 0xFF6A1B9A.toInt(), DoubleArray(pl.size) { pl[it].lat }, DoubleArray(pl.size) { pl[it].lon }, numbered = true))
            } else emptyList()
        } else {
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
            title = { MText("OFFLINE MAPS", color = Phosphor.green, size = 14.sp, weight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MText(
                        "> stored .map files are rendered together offline. Files over 100 MB draw from the detail zoom below.",
                        color = Phosphor.sub, size = 11.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MText("Detail zoom", Modifier.weight(1f), color = Phosphor.text, size = 12.sp)
                        MapButton("-") { vm.changeMapDetail(vm.mapDetailZoom - 1) }
                        MText("${vm.mapDetailZoom}", Modifier.padding(horizontal = 10.dp), color = Phosphor.green, size = 14.sp, weight = FontWeight.Bold)
                        MapButton("+") { vm.changeMapDetail(vm.mapDetailZoom + 1) }
                    }
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
            confirmButton = { MapButton("ADD .MAP", onClick = onPickMap) },
            dismissButton = { MapButton("CLOSE") { showFiles = false } },
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val panelHeight = maxHeight * 0.50f
        val emptyHeight = maxHeight * 0.34f
        val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        Box(Modifier.fillMaxSize().background(Phosphor.bg)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            val panel: @Composable (Modifier) -> Unit = { m ->
                if (mac != null) {
                    DeviceDetails(vm, mac, showMapTab = true, m)
                } else if (showLegend) {
                    LegendPanel(vm, showOthers, { showLegend = false }, m)
                }
            }

            // Condensed top-left compartment controls
            Column(
                Modifier.align(Alignment.TopStart).then(safe).then(if (landscape) Modifier.fillMaxHeight() else Modifier)
                    .padding(start = 6.dp, top = 6.dp, end = 72.dp, bottom = if (landscape) 6.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MapButton(if (showLayers) "LAYERS ▲" else "LAYERS ▼", showLayers) { showLayers = !showLayers }
                    if (mac != null) {
                        MapButton("SOLO", soloMode) { soloMode = !soloMode }
                    }
                    MapButton("LEGEND", showLegend) { showLegend = !showLegend }
                }

                if (showLayers) {
                    MapChip {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                MText("SRC ", color = Phosphor.sub, size = 9.5.sp, weight = FontWeight.Bold)
                                MiniToggle("LIVE", showLive) { showLive = !showLive }
                                MiniToggle("WIGLE", showWigle) { showWigle = !showWigle }
                                MiniToggle("UNWATCHED", showOthers) { showOthers = !showOthers }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                MText("TYPE", color = Phosphor.sub, size = 9.5.sp, weight = FontWeight.Bold)
                                MiniToggle("WI-FI", showWifi) { showWifi = !showWifi }
                                MiniToggle("BT", showBt) { showBt = !showBt }
                                MiniToggle("BLE", showBle) { showBle = !showBle }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                MText("PATH", color = Phosphor.sub, size = 9.5.sp, weight = FontWeight.Bold)
                                MiniToggle("SOLO", soloMode) { soloMode = !soloMode }
                                MiniToggle("LINKS", showLinks) { showLinks = !showLinks }
                                MiniToggle("TRAIL", showTrail) { showTrail = !showTrail }
                            }
                        }
                    }
                }

                if (showLinks) {
                    MapChip {
                        MText(
                            when {
                                vm.linkError.isNotEmpty() -> "> links error: ${vm.linkError}"
                                vm.linkMode && vm.linkLoading -> "> loading multi-place links..."
                                vm.linkMode -> "${vm.linkPaths.size} multi-place devices in view"
                                else -> "${vm.wigleDots.sumOf { it.count }} multi-place devices (zoom 12+ for lines)"
                            },
                            color = Phosphor.amber, size = 10.5.sp,
                        )
                    }
                }
                if (vm.mapStatus.isNotEmpty()) {
                    MapChip { MText(vm.mapStatus, color = Phosphor.amber, size = 10.5.sp) }
                }
                if (landscape && (mac != null || showLegend)) {
                    panel(Modifier.width(380.dp).weight(1f))
                }
            }

            Column(
                Modifier.align(Alignment.TopEnd).then(safe).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalAlignment = Alignment.End,
            ) {
                MapButton("FIT") { fit() }
                MapButton("ME") {
                    phone?.let { mapView.model.mapViewPosition.mapPosition = MapPosition(LatLong(it.lat, it.lon), 16) }
                }
                MapButton("MAPS") { showFiles = true }
            }

            if (!landscape && (mac != null || showLegend)) {
                panel(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height(if (mac != null) panelHeight else emptyHeight).padding(8.dp),
                )
            }
        }
    }
}

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

private suspend fun fitAll(vm: AppViewModel, mapView: MapView, phone: PhoneFix?, live: List<MapDot>) {
    val ll = ArrayList<LatLong>()
    vm.deviceBounds()?.let { b -> ll.add(LatLong(b[0], b[2])); ll.add(LatLong(b[1], b[3])) }
    live.forEach { ll.add(LatLong(it.lat, it.lon)) }
    phone?.let { ll.add(LatLong(it.lat, it.lon)) }
    fitPoints(mapView, ll)
}

@Composable
private fun LegendPanel(vm: AppViewModel, others: Boolean, onHide: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val scroll = rememberScrollState()
    Column(
        modifier.fillMaxWidth().background(Phosphor.bg.copy(alpha = 0.95f), shape).border(1.dp, Phosphor.edge, shape).padding(10.dp)
            .scrollbar(scroll).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MText(
                "${vm.liveDots.count { others || it.watched }} live • ${vm.wigleDots.sumOf { it.count }} WiGLE in view",
                Modifier.weight(1f), color = Phosphor.green, size = 11.5.sp, weight = FontWeight.Bold,
            )
            Box(
                Modifier.size(30.dp).border(1.5.dp, Phosphor.red, RoundedCornerShape(6.dp)).clickable { onHide() },
                contentAlignment = Alignment.Center,
            ) { MText("X", color = Phosphor.red, size = 15.sp, weight = FontWeight.Bold) }
        }
        MText("-- DETECTION COLORS --", color = Phosphor.green, size = 10.sp, weight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DetectionCategory.entries.forEach { cat ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(Color(cat.uiColorHex), CircleShape))
                    MText("${cat.shortTag}: ${cat.label}", color = Phosphor.text, size = 9.5.sp)
                }
            }
        }
        MText("-- DOT SHAPES & CHANNELS --", color = Phosphor.green, size = 10.sp, weight = FontWeight.Bold)
        MText("• Large filled = live estimated  • Hollow = live near phone  • Small filled = WiGLE", color = Phosphor.sub, size = 9.5.sp)
        MText("• Tap any dot to inspect its Wi-Fi channel + width (e.g. Ch 36 • 80MHz) & details.", color = Phosphor.sub, size = 9.5.sp)
        if (vm.mapFiles.isEmpty()) {
            MText("> no offline map loaded: tap MAPS to add a .map file", color = Phosphor.amber, size = 10.sp)
        }
    }
}
