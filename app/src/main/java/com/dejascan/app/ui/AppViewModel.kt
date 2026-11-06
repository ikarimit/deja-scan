package com.dejascan.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dejascan.app.data.DejaDb
import com.dejascan.app.data.Device
import com.dejascan.app.data.ObsRow
import com.dejascan.app.data.Prefs
import com.dejascan.app.data.SightPoint
import com.dejascan.app.data.Vendors
import com.dejascan.app.data.WatchMode
import com.dejascan.app.data.WigleImporter
import com.dejascan.app.data.WigleMerger
import com.dejascan.app.geo.CellGrid
import com.dejascan.app.geo.Estimate
import com.dejascan.app.geo.Geo
import com.dejascan.app.geo.PositionEstimator
import com.dejascan.app.geo.Sample
import com.dejascan.app.scan.ChannelWidthHelper
import com.dejascan.app.scan.DetectionClassifier
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.RootDetector
import com.dejascan.app.scan.ScanService
import com.dejascan.app.scan.SightingBus
import com.dejascan.app.scan.promiscuous.PromiscuousMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = DejaDb(app)
    private val prefs = Prefs(app)

    var isRooted by mutableStateOf(RootDetector.isRooted); private set
    var promiscuousEnabled by mutableStateOf(prefs.promiscuousEnabled); private set
    var promiscuousIface by mutableStateOf(prefs.promiscuousIface); private set
    var promiscuousDriverSwitch by mutableStateOf(prefs.promiscuousDriverSwitch); private set
    var mode by mutableStateOf(prefs.mode); private set
    var types by mutableStateOf(prefs.types); private set
    var skipRandomised by mutableStateOf(prefs.skipRandomised); private set
    var notifyHits by mutableStateOf(prefs.notifyHits); private set
    var query by mutableStateOf(""); private set
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    var total by mutableStateOf(0); private set
    var status by mutableStateOf(prefs.lastImport); private set
    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(prefs.themeMode) }.getOrDefault(ThemeMode.SYSTEM)); private set
    var busy by mutableStateOf(false); private set
    var linkedName by mutableStateOf(prefs.linkedName); private set

    var selectedMac by mutableStateOf<String?>(null); private set
    /** Where the selected device was seen (WiGLE + my scans), oldest first, and its stored record. */
    var selectedPlaces by mutableStateOf<List<Place>>(emptyList()); private set
    var selectedDevice by mutableStateOf<Device?>(null); private set
    var haptic by mutableStateOf(prefs.haptic); private set
    var beep by mutableStateOf(prefs.beep); private set

    var liveDots by mutableStateOf<List<MapDot>>(emptyList()); private set
    var wigleDots by mutableStateOf<List<MapDot>>(emptyList()); private set
    var trailObs by mutableStateOf<List<ObsRow>>(emptyList()); private set
    var trailOwn by mutableStateOf<List<SightPoint>>(emptyList()); private set
    /** Bumped after an import so the map reloads its WiGLE dots. */
    var dataVersion by mutableStateOf(0); private set
    private var wigleJob: Job? = null
    private var linkJob: Job? = null
    private var dayJob: Job? = null
    var linkPaths by mutableStateOf<List<LinkPath>>(emptyList()); private set
    /** True while the link view is active (zoomed in enough): only devices seen in several places are shown. */
    var linkMode by mutableStateOf(false); private set
    var linkLoading by mutableStateOf(false); private set
    var linkError by mutableStateOf(""); private set
    var dayEpoch by mutableStateOf<Long?>(null); private set
    var dayDevices by mutableStateOf<List<Device>>(emptyList()); private set
    var dayPoints by mutableStateOf<List<DayPoint>>(emptyList()); private set
    var dayTotal by mutableStateOf(0); private set
    var dayBusy by mutableStateOf(false); private set
    @Volatile private var indexing = false
    private val estCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Estimate?>>()
    var mapDetailZoom by mutableStateOf(prefs.mapDetailZoom); private set
    var mapFiles by mutableStateOf<List<MapFileInfo>>(emptyList()); private set
    var mapStatus by mutableStateOf(""); private set
    var mapCenterLat by mutableStateOf(0.0)
    var mapCenterLon by mutableStateOf(0.0)
    var mapZoom by mutableStateOf(0)

    init {
        mapFiles = scanMaps()
        viewModelScope.launch(Dispatchers.IO) {
            if (db.count() > 0 && (!db.cellsReady() || (db.obsCount() > 0 && !db.linkedReady()))) indexCells()
            if (isRooted) PromiscuousMonitor().probeCapabilities()
        }
        refresh()
        refreshLinked(force = false)
    }

    fun changePromiscuousEnabled(enabled: Boolean) {
        val next = enabled && isRooted
        promiscuousEnabled = next
        prefs.promiscuousEnabled = next
        if (next) probePromiscuousCapabilities()
        ScanService.syncPromiscuous(getApplication())
    }

    fun changePromiscuousIface(iface: String) {
        promiscuousIface = iface
        prefs.promiscuousIface = iface
        ScanService.syncPromiscuous(getApplication())
    }

    fun changePromiscuousDriverSwitch(allow: Boolean) {
        promiscuousDriverSwitch = allow
        prefs.promiscuousDriverSwitch = allow
        ScanService.syncPromiscuous(getApplication())
    }

    fun probePromiscuousCapabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            isRooted = RootDetector.checkRoot()
            PromiscuousMonitor().probeCapabilities()
        }
    }

    fun cycleTheme() {
        themeMode = ThemeMode.entries[(themeMode.ordinal + 1) % ThemeMode.entries.size]
        prefs.themeMode = themeMode.name
    }

    fun select(mac: String?) {
        selectedMac = mac
        if (mac == null) { selectedPlaces = emptyList(); selectedDevice = null; return }
        viewModelScope.launch(Dispatchers.IO) {
            val dev = try { db.devices(listOf(mac)).firstOrNull() } catch (_: Exception) { null }
            val places = try { collapsePlaces(db.placesOf(listOf(mac))[mac] ?: emptyList()) } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) {
                if (selectedMac == mac) { selectedDevice = dev; selectedPlaces = places }
            }
        }
    }

    /** Oldest first; a place within 25 m of the previous one is merged into it. */
    private fun collapsePlaces(raw: List<DoubleArray>): List<Place> {
        val kept = ArrayList<Place>()
        for (p in raw) {
            val last = kept.lastOrNull()
            if (last == null || Geo.haversineM(last.lat, last.lon, p[0], p[1]) >= LINK_MIN_STEP_M) {
                kept.add(Place(p[0], p[1], p[2].toLong(), p[3] == 1.0))
            }
        }
        if (kept.size <= MAX_PLACES) return kept
        return List(MAX_PLACES) { i -> kept[(i.toLong() * (kept.size - 1) / (MAX_PLACES - 1)).toInt()] }
    }

    fun reportMapError(msg: String) { mapStatus = msg }

    fun changeMapDetail(z: Int) {
        val v = z.coerceIn(6, 14)
        prefs.mapDetailZoom = v
        MapDetail.zoom = v.toByte()
        mapDetailZoom = v
    }

    private fun estimateRecent(mac: String): Estimate? {
        val since = System.currentTimeMillis() - LIVE_ESTIMATE_WINDOW_MS
        return PositionEstimator.estimate(db.sightsFor(mac, since, 1000).map { Sample(it.lat, it.lon, it.rssi) })
    }

    private fun estimateCached(mac: String, now: Long): Estimate? {
        val c = estCache[mac]
        if (c != null && now - c.first < 15_000) return c.second
        val e = estimateRecent(mac)
        estCache[mac] = now to e
        return e
    }

    /** One dot per device heard recently: at its estimated position, else at the phone (approximate). */
    fun refreshLiveDots() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val phone = SightingBus.phone.value
            val dots = ArrayList<MapDot>()
            val counts = try { db.sightCounts(now - LIVE_ESTIMATE_WINDOW_MS) } catch (_: Exception) { emptyMap() }
            val hasDb = total > 0
            val heard = SightingBus.sightings.value.values
                .filter { now - it.time <= LIVE_MS }.sortedByDescending { it.rssi }.take(LIVE_DOT_LIMIT)
            val fixedVendors = HashMap<String, Int>()
            var fixedTotal = 0
            for (s in heard) {
                if (!s.randomised) {
                    val v = Vendors.forMac(s.mac)
                    if (!v.isNullOrBlank()) {
                        fixedVendors[v] = (fixedVendors[v] ?: 0) + 1
                        fixedTotal++
                    }
                }
            }
            for (s in heard) {
                val est = if ((counts[s.mac] ?: 0) >= 3) try { estimateCached(s.mac, now) } catch (_: Exception) { null } else null
                val vendor = Vendors.forMac(s.mac)
                val cls = DetectionClassifier.classify(
                    s = s,
                    hasDatabase = hasDb,
                    vendor = vendor,
                    vendorPeerCount = if (vendor != null) (fixedVendors[vendor] ?: 0) else 0,
                    totalFixedPeers = fixedTotal,
                )
                val rawCaps = s.adv.firstOrNull { it.startsWith("Raw Caps: ") }?.removePrefix("Raw Caps: ").orEmpty()
                val dev = Device(s.mac, s.name, s.type, s.wigleLastTime, s.lat, s.lon, s.randomised, false, s.freq, rawCaps)
                val dot = when {
                    est != null -> MapDot(
                        device = dev, live = true, approx = false, rssi = s.rssi,
                        lat = est.lat, lon = est.lon, estimate = est, watched = s.watched,
                        category = cls.category, channelInfo = s.channelSummary,
                    )
                    phone != null -> MapDot(
                        device = dev, live = true, approx = true, rssi = s.rssi,
                        lat = phone.lat, lon = phone.lon, estimate = null, watched = s.watched,
                        category = cls.category, channelInfo = s.channelSummary,
                    )
                    else -> null
                }
                if (dot != null) dots.add(dot)
            }
            withContext(Dispatchers.Main) { liveDots = dots }
        }
    }

    /** WiGLE devices (last known place) inside the visible map area: pre-counted cells when zoomed out. */
    fun loadWigle(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, zoom: Int, types: Set<String>, linkedOnly: Boolean = false) {
        wigleJob?.cancel()
        wigleJob = viewModelScope.launch(Dispatchers.IO) {
            val liveMap = SightingBus.sightings.value
            val hasDb = total > 0
            val list = try {
                if (zoom > CellGrid.BASE_Z && !linkedOnly) {
                    db.inBounds(minLat, maxLat, minLon, maxLon, types, WIGLE_DOT_LIMIT)
                        .map { d ->
                            val liveS = liveMap[d.mac]
                            val cls = DetectionClassifier.classifyDevice(d, liveS, hasDatabase = hasDb)
                            val chInfo = liveS?.channelSummary?.ifEmpty { null }
                                ?: if (d.type == "W" && d.freq > 0) ChannelWidthHelper.formatChannelWidth(d.freq, -1, compact = true) else ""
                            MapDot(
                                device = d, live = false, approx = false, rssi = 0,
                                lat = d.lat, lon = d.lon, estimate = null,
                                category = cls.category, channelInfo = chInfo,
                            )
                        }
                } else {
                    val z = zoom.coerceIn(CellGrid.MIN_Z, CellGrid.BASE_Z)
                    db.cellsInView(
                        z,
                        CellGrid.cell(CellGrid.nx(minLon), z), CellGrid.cell(CellGrid.nx(maxLon), z),
                        CellGrid.cell(CellGrid.ny(maxLat), z), CellGrid.cell(CellGrid.ny(minLat), z),
                        types, linkedOnly,
                    ).map { c ->
                        val rand = MacUtil.isRandomised(c.mac)
                        val dev = Device(c.mac, "", c.type, 0L, c.lat, c.lon, rand, false)
                        val cls = DetectionClassifier.classifyDevice(dev, liveMap[c.mac], hasDatabase = hasDb)
                        MapDot(
                            device = dev, live = false, approx = false, rssi = 0,
                            lat = c.lat, lon = c.lon, estimate = null, count = c.count,
                            category = cls.category,
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) { wigleDots = list }
        }
    }

    fun clearWigle() { wigleJob?.cancel(); if (wigleDots.isNotEmpty()) wigleDots = emptyList() }

    /** Dotted lines for every device in view that was seen in 2+ different places. */
    fun loadLinks(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, types: Set<String>) {
        linkMode = true
        linkLoading = true
        linkError = ""
        linkJob?.cancel()
        linkJob = viewModelScope.launch(Dispatchers.IO) {
            val paths = try {
                val macs = (db.linkedInView(minLat, maxLat, minLon, maxLon, types, LINK_CANDIDATES) +
                    db.linkCandidates(minLat, maxLat, minLon, maxLon, LINK_CANDIDATES)).distinct()
                val palette = Phosphor.series.map { it.toArgb() }
                val built = ArrayList<Pair<Double, LinkPath>>()
                for ((mac, pts) in db.placesOf(macs)) {
                    val kept = ArrayList<DoubleArray>()
                    for (p in pts) {
                        val last = kept.lastOrNull()
                        if (last == null || Geo.haversineM(last[0], last[1], p[0], p[1]) >= LINK_MIN_STEP_M) kept.add(p)
                    }
                    if (kept.size < 2) continue
                    var len = 0.0
                    for (i in 1 until kept.size) len += Geo.haversineM(kept[i - 1][0], kept[i - 1][1], kept[i][0], kept[i][1])
                    built.add(len to LinkPath(mac, palette[(mac.hashCode() and 0x7fffffff) % palette.size],
                        DoubleArray(kept.size) { kept[it][0] }, DoubleArray(kept.size) { kept[it][1] }))
                }
                built.sortedByDescending { it.first }.take(LINK_DEVICE_LIMIT).map { it.second }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { linkError = e.message ?: e.javaClass.simpleName }
                emptyList()
            }
            withContext(Dispatchers.Main) { linkPaths = paths; linkLoading = false }
        }
    }

    fun clearLinks() { linkJob?.cancel(); linkMode = false; linkLoading = false; if (linkPaths.isNotEmpty()) linkPaths = emptyList() }

    /** Devices seen on one day (WiGLE observations, my scans, WiGLE last-seen date) and where. */
    fun setDay(epochDay: Long?) {
        dayEpoch = epochDay
        dayJob?.cancel()
        if (epochDay == null) { dayDevices = emptyList(); dayPoints = emptyList(); dayTotal = 0; return }
        dayBusy = true
        dayJob = viewModelScope.launch(Dispatchers.IO) {
            val zone = java.time.ZoneId.systemDefault()
            val start = java.time.LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = java.time.LocalDate.ofEpochDay(epochDay + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val hits = try { db.hitsOnDay(start, end, DAY_HIT_LIMIT) } catch (_: Exception) { emptyList() }
            val macs = hits.map { it.mac }.distinct()
            val known = HashMap<String, Device>()
            for (chunk in macs.chunked(400)) db.devices(chunk).forEach { known[it.mac] = it }
            val devs = macs.map { m ->
                known[m] ?: Device(m, "", "?", 0L, 0.0, 0.0, MacUtil.isRandomised(m), false)
            }.sortedWith(compareBy({ it.name.isEmpty() }, { it.name.lowercase() }, { it.mac }))
            val pts = hits.filter { it.lat != 0.0 || it.lon != 0.0 }
                .map { DayPoint(it.lat, it.lon, known[it.mac]?.type ?: "?") }
            withContext(Dispatchers.Main) {
                dayTotal = devs.size
                dayDevices = devs.take(DAY_LIST_LIMIT)
                dayPoints = pts
                dayBusy = false
            }
        }
    }

    private fun indexCells() {
        if (indexing) return
        indexing = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (db.count() > 0) {
                    withContext(Dispatchers.Main) { mapStatus = "Preparing the map index..." }
                    db.rebuildCells()
                    db.rebuildLinked()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { mapStatus = "Map index failed: ${e.message}" }
            }
            indexing = false
            withContext(Dispatchers.Main) {
                if (mapStatus.startsWith("Preparing")) mapStatus = ""
                dataVersion++
            }
        }
    }

    fun loadTrail(mac: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val obs = if (mac == null) emptyList() else db.obsFor(mac, MAP_POINT_LIMIT)
            val own = if (mac == null) emptyList() else db.sightsFor(mac, 0L, MAP_POINT_LIMIT)
            withContext(Dispatchers.Main) { trailObs = obs; trailOwn = own }
        }
    }

    suspend fun deviceBounds(): DoubleArray? = withContext(Dispatchers.IO) { db.bounds() }

    suspend fun device(mac: String): Device? = withContext(Dispatchers.IO) { db.devices(listOf(mac)).firstOrNull() }

    private fun estimateBlocking(mac: String): Estimate? {
        val since = System.currentTimeMillis() - ESTIMATE_WINDOW_MS
        val pts = db.sightsFor(mac, since, 2000)
        return PositionEstimator.estimate(pts.map { Sample(it.lat, it.lon, it.rssi) })
    }

    suspend fun estimate(mac: String): Estimate? = withContext(Dispatchers.IO) { estimateBlocking(mac) }

    fun clearSightings() {
        viewModelScope.launch(Dispatchers.IO) {
            db.clearSights()
            withContext(Dispatchers.Main) { status = "Cleared this app's own scan history"; trailOwn = emptyList() }
        }
    }

    private fun mapDir() = File(getApplication<Application>().filesDir, "maps").apply { mkdirs() }

    private fun scanMaps(): List<MapFileInfo> =
        mapDir().listFiles { f -> f.isFile && f.name.endsWith(".map") }
            ?.sortedBy { it.name }
            ?.map { MapFileInfo(it.name, it.length(), it.lastModified(), it.path) }
            ?: emptyList()

    private fun safeMapName(raw: String): String {
        val base = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "map" }
        return if (base.endsWith(".map")) base else "$base.map"
    }

    fun importMapFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        mapStatus = "Copying ${uris.size} map file(s)..."
        viewModelScope.launch(Dispatchers.IO) {
            val dir = mapDir()
            var ok = 0
            val errors = ArrayList<String>()
            for (uri in uris) {
                val tmp = File(dir, "incoming.tmp")
                try {
                    val name = safeMapName(queryMeta(uri)?.first ?: uri.lastPathSegment.orEmpty())
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("cannot open $name")
                    input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    val head = tmp.inputStream().use { it.readNBytes(MAP_MAGIC.length).toString(Charsets.US_ASCII) }
                    if (head != MAP_MAGIC) throw IllegalStateException("$name is not a Mapsforge .map file")
                    val dest = File(dir, name)
                    dest.delete()
                    if (!tmp.renameTo(dest)) throw IllegalStateException("could not save $name")
                    ok++
                } catch (e: Exception) {
                    tmp.delete()
                    errors += e.message ?: "failed"
                }
            }
            val list = scanMaps()
            val msg = buildString {
                append("Added $ok map file(s)")
                if (errors.isNotEmpty()) append("; failed: ${errors.joinToString("; ")}")
            }
            withContext(Dispatchers.Main) { mapFiles = list; mapStatus = msg }
        }
    }

    fun removeMap(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            File(mapDir(), name).delete()
            val list = scanMaps()
            withContext(Dispatchers.Main) { mapFiles = list; mapStatus = "Removed $name" }
        }
    }

    fun changeHaptic(v: Boolean) { haptic = v; prefs.haptic = v }
    fun changeBeep(v: Boolean) { beep = v; prefs.beep = v }
    fun changeNotifyHits(v: Boolean) { notifyHits = v; prefs.notifyHits = v }

    fun changeMode(m: WatchMode) { mode = m; prefs.mode = m }
    fun changeSkipRandomised(v: Boolean) { skipRandomised = v; prefs.skipRandomised = v }
    fun toggleType(t: String) {
        types = if (t in types) types - t else types + t
        prefs.types = types
    }

    fun changeQuery(q: String) { query = q; refresh() }

    fun toggleWatched(d: Device) {
        viewModelScope.launch(Dispatchers.IO) {
            db.setWatched(d.mac, !d.watched)
            val list = if (query.isBlank()) db.watched() else db.search(query)
            withContext(Dispatchers.Main) {
                devices = list
                dayDevices = dayDevices.map { if (it.mac == d.mac) it.copy(watched = !d.watched) else it }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = if (query.isBlank()) db.watched() else db.search(query)
            val n = db.count()
            withContext(Dispatchers.Main) { devices = list; total = n }
        }
    }

    fun linkFile(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                status = "Could not keep access to that file: ${e.message}"
                return
            }
        }
        prefs.linkedUri = uri.toString()
        prefs.linkedModified = 0L
        prefs.linkedName = queryMeta(uri)?.first ?: uri.lastPathSegment.orEmpty()
        linkedName = prefs.linkedName
        refreshLinked(force = true)
    }

    fun mergeFindingsIntoLinked() {
        val raw = prefs.linkedUri
        if (raw.isEmpty()) {
            status = "No file linked yet. Use Link WiGLE file."
            return
        }
        if (busy) return
        busy = true
        status = "Merging findings into linked file..."
        viewModelScope.launch(Dispatchers.IO) {
            val uri = Uri.parse(raw)
            val resolver = getApplication<Application>().contentResolver
            val cache = File(getApplication<Application>().cacheDir, "merge.sqlite")
            var msg: String
            try {
                val input = resolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open linked file for reading")
                input.use { i -> cache.outputStream().use { o -> i.copyTo(o) } }

                val allSightings = SightingBus.sightings.value.values
                val allSights = try { db.allSights() } catch (_: Exception) { emptyList() }

                if (allSightings.isEmpty() && allSights.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        status = "No new sightings or locations to merge."
                        busy = false
                    }
                    return@launch
                }

                val res = WigleMerger.merge(cache, allSightings, allSights) { m ->
                    viewModelScope.launch(Dispatchers.Main) { status = m }
                }

                val outStream = try {
                    resolver.openOutputStream(uri, "wt")
                } catch (_: Exception) {
                    try {
                        resolver.openOutputStream(uri, "rwt")
                    } catch (_: Exception) {
                        resolver.openOutputStream(uri, "w")
                    }
                } ?: throw IllegalStateException("Cannot open linked file for writing. Ensure write permissions.")

                outStream.use { out ->
                    cache.inputStream().use { inp ->
                        inp.copyTo(out)
                    }
                }

                val meta = queryMeta(uri)
                prefs.linkedModified = meta?.second ?: System.currentTimeMillis()

                val newDevices = allSightings.map { s ->
                    val rawCaps = s.adv.firstOrNull { it.startsWith("Raw Caps: ") }?.removePrefix("Raw Caps: ").orEmpty()
                    Device(
                        mac = s.mac,
                        name = if (s.name != s.mac) s.name else "",
                        type = s.type,
                        lastTime = s.time,
                        lat = s.lat,
                        lon = s.lon,
                        randomised = s.randomised,
                        watched = s.watched,
                        freq = s.freq,
                        caps = rawCaps,
                    )
                }
                db.upsertBatch(newDevices.asSequence())

                val obsRows = allSights.map { ObsRow(it.mac, it.time, it.lat, it.lon, it.rssi) }
                db.insertObsBatch(obsRows)

                try {
                    db.rebuildCells()
                    db.rebuildLinked()
                } catch (_: Exception) {}

                val known = db.filterKnown(SightingBus.sightings.value.keys)
                withContext(Dispatchers.Main) {
                    SightingBus.markKnown(known)
                }

                msg = "Merged into ${prefs.linkedName}: ${res.newNetworks} new, ${res.updatedNetworks} updated, ${res.newLocations} locations."
            } catch (e: Exception) {
                msg = "Merge failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                cache.delete()
            }
            prefs.lastImport = msg
            val list = if (query.isBlank()) db.watched() else db.search(query)
            val n = db.count()
            withContext(Dispatchers.Main) {
                status = msg
                devices = list
                total = n
                busy = false
                dataVersion++
            }
        }
    }

    fun refreshLinked(force: Boolean) {
        val raw = prefs.linkedUri
        if (raw.isEmpty()) {
            if (force) status = "No file linked yet. Use Link WiGLE file."
            return
        }
        val uri = Uri.parse(raw)
        viewModelScope.launch(Dispatchers.IO) {
            val meta = queryMeta(uri)
            if (!force && meta != null && meta.second != 0L && meta.second == prefs.linkedModified) {
                withContext(Dispatchers.Main) { status = "Linked file unchanged since last import" }
                return@launch
            }
            withContext(Dispatchers.Main) { importLinked(uri, meta?.second ?: 0L) }
        }
    }

    private fun importLinked(uri: Uri, modified: Long) = runImport("linked file") { cache ->
        val input = try {
            getApplication<Application>().contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return@runImport WigleImporter.Result.Error("Cannot open the linked file. Link it again.")
        input.use { i -> cache.outputStream().use { o -> i.copyTo(o) } }
        val r = WigleImporter.import(cache, db) { m -> status = m }
        if (r is WigleImporter.Result.Ok) prefs.linkedModified = modified
        r
    }

    private fun queryMeta(uri: Uri): Pair<String, Long>? = try {
        getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "") to (if (c.isNull(1)) 0L else c.getLong(1)) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun runImport(label: String, work: (File) -> WigleImporter.Result) {
        if (busy) return
        busy = true
        status = "Importing from $label..."
        viewModelScope.launch(Dispatchers.IO) {
            val cache = File(getApplication<Application>().cacheDir, "import.sqlite")
            val msg = try {
                when (val r = work(cache)) {
                    is WigleImporter.Result.Ok -> "Imported ${r.imported} devices and ${r.observations} observations (${r.skipped} skipped)"
                    is WigleImporter.Result.Error -> r.message
                }
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            } finally {
                cache.delete()
            }
            prefs.lastImport = msg
            if (msg.startsWith("Imported")) {
                withContext(Dispatchers.Main) { status = "Indexing devices for the map..." }
                try { db.rebuildCells(); db.rebuildLinked() } catch (_: Exception) {}
                val known = db.filterKnown(SightingBus.sightings.value.keys)
                withContext(Dispatchers.Main) { SightingBus.markKnown(known) }
            }
            val list = if (query.isBlank()) db.watched() else db.search(query)
            val n = db.count()
            withContext(Dispatchers.Main) { status = msg; devices = list; total = n; busy = false; dataVersion++ }
        }
    }

    private companion object {
        const val MAP_POINT_LIMIT = 5000
        const val MAP_MAGIC = "mapsforge binary OSM"
        const val ESTIMATE_WINDOW_MS = 24 * 60 * 60_000L
        const val LIVE_ESTIMATE_WINDOW_MS = 10 * 60_000L
        const val LIVE_MS = 3 * 60_000L
        const val LINK_CANDIDATES = 3000
        const val LINK_DEVICE_LIMIT = 600
        const val LINK_MIN_STEP_M = 25.0
        const val MAX_PLACES = 300
        const val DAY_HIT_LIMIT = 20_000
        const val DAY_LIST_LIMIT = 300
        const val LIVE_DOT_LIMIT = 300
        const val WIGLE_DOT_LIMIT = 4000
    }

    override fun onCleared() {
        db.close()
    }
}
