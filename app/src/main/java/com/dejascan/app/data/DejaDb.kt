package com.dejascan.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.dejascan.app.geo.CellGrid

enum class WatchMode { PICKED, ALL, FILTERED }

data class WatchFilter(
    val mode: WatchMode,
    val types: Set<String>,
    val skipRandomised: Boolean,
)

data class Device(
    val mac: String,
    val name: String,
    val type: String,
    val lastTime: Long,
    val lat: Double,
    val lon: Double,
    val randomised: Boolean,
    val watched: Boolean,
    val freq: Int = 0,
    val caps: String = "",
)

/** One WiGLE observation (a place a device was recorded). */
data class ObsRow(val mac: String, val time: Long, val lat: Double, val lon: Double, val level: Int)

/** One sighting recorded by this app: phone position when the device was heard. */
data class SightPoint(val lat: Double, val lon: Double, val acc: Float, val rssi: Int, val time: Long)

/** Devices of one map grid cell: how many, their mean place, and (when there is only one) which. */
data class GeoCell(val count: Int, val lat: Double, val lon: Double, val mac: String, val type: String)

data class DayHit(val mac: String, val lat: Double, val lon: Double, val time: Long)

/**
 * Our own database, filled from a WiGLE database. WiGLE type codes: W=Wi-Fi, B=Bluetooth, E=BLE.
 * Tables: device (one row per device), obs (WiGLE observations), sight (this app's own sightings).
 */
class DejaDb(context: Context) : SQLiteOpenHelper(context, "deja.db", null, 8) {

    /** Write-ahead logging lets the scan service read while an import is writing. */
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE device (" +
                "mac TEXT PRIMARY KEY NOT NULL," +
                "name TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "lasttime INTEGER NOT NULL," +
                "lat REAL NOT NULL," +
                "lon REAL NOT NULL," +
                "random INTEGER NOT NULL," +
                "watched INTEGER NOT NULL DEFAULT 0," +
                "freq INTEGER NOT NULL DEFAULT 0," +
                "caps TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX idx_device_type ON device(type)")
        db.execSQL("CREATE INDEX idx_device_pos ON device(lat, lon)")
        createV2(db)
        createV4(db)
        createV5(db)
        createV6(db)
        createV8(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2(db)
        if (oldVersion < 3) db.execSQL("CREATE INDEX IF NOT EXISTS idx_device_pos ON device(lat, lon)")
        if (oldVersion < 4) createV4(db)
        if (oldVersion < 5) createV5(db)
        if (oldVersion < 6) createV6(db)
        if (oldVersion < 7) createV7(db)
        if (oldVersion < 8) createV8(db)
    }

    private fun createV7(db: SQLiteDatabase) {
        runCatching { db.execSQL("ALTER TABLE device ADD COLUMN freq INTEGER NOT NULL DEFAULT 0") }
        runCatching { db.execSQL("ALTER TABLE device ADD COLUMN caps TEXT NOT NULL DEFAULT ''") }
    }

    /** Deduplicates observations by (mac, time) and enforces uniqueness across merges. */
    private fun createV8(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM obs WHERE rowid NOT IN (SELECT MIN(rowid) FROM obs GROUP BY mac, time)")
        db.execSQL("DROP INDEX IF EXISTS idx_obs_mac")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_obs_mac_time ON obs(mac, time)")
    }

    /** Pre-counted map cells (see CellGrid), rebuilt after every import. */
    private fun createV4(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cell (z INTEGER NOT NULL, cx INTEGER NOT NULL, cy INTEGER NOT NULL, " +
                "type TEXT NOT NULL, n INTEGER NOT NULL, slat REAL NOT NULL, slon REAL NOT NULL, mac TEXT NOT NULL, " +
                "PRIMARY KEY (z, cx, cy, type))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sight_time ON sight(time)")
    }

    /** Devices seen in several places (found after each import) and their pre-counted map cells. */
    private fun createV6(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS linked (mac TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL DEFAULT '?', " +
                "minlat REAL NOT NULL, maxlat REAL NOT NULL, minlon REAL NOT NULL, maxlon REAL NOT NULL, " +
                "clat REAL NOT NULL, clon REAL NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS lcell (z INTEGER NOT NULL, cx INTEGER NOT NULL, cy INTEGER NOT NULL, " +
                "type TEXT NOT NULL, n INTEGER NOT NULL, slat REAL NOT NULL, slon REAL NOT NULL, mac TEXT NOT NULL, " +
                "PRIMARY KEY (z, cx, cy, type))"
        )
    }

    /** Indexes for the day search and the link lines. */
    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_time ON obs(time)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_pos ON obs(lat, lon)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_device_lasttime ON device(lasttime)")
    }

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS obs (mac TEXT NOT NULL, time INTEGER NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, level INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_mac ON obs(mac)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sight (mac TEXT NOT NULL, time INTEGER NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, acc REAL NOT NULL, rssi INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sight_mac ON sight(mac, time)")
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM device", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /**
     * Insert-or-update without losing the user's watched flag. Avoids SQLite upsert (needs 3.24,
     * and Android 10 ships an older SQLite).
     */
    fun upsertBatch(rows: Sequence<Device>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            val ins = db.compileStatement(
                "INSERT OR IGNORE INTO device(mac,name,type,lasttime,lat,lon,random,watched,freq,caps) VALUES(?,?,?,?,?,?,?,0,?,?)"
            )
            val upd = db.compileStatement(
                "UPDATE device SET name=?,type=?,lasttime=?,lat=?,lon=?,random=?,freq=CASE WHEN ?!=0 THEN ? ELSE freq END,caps=CASE WHEN ?!='' THEN ? ELSE caps END WHERE mac=?"
            )
            for (d in rows) {
                ins.clearBindings()
                ins.bindString(1, d.mac); ins.bindString(2, d.name); ins.bindString(3, d.type)
                ins.bindLong(4, d.lastTime); ins.bindDouble(5, d.lat); ins.bindDouble(6, d.lon)
                ins.bindLong(7, if (d.randomised) 1 else 0)
                ins.bindLong(8, d.freq.toLong()); ins.bindString(9, d.caps)
                ins.executeInsert()
                upd.clearBindings()
                upd.bindString(1, d.name); upd.bindString(2, d.type); upd.bindLong(3, d.lastTime)
                upd.bindDouble(4, d.lat); upd.bindDouble(5, d.lon)
                upd.bindLong(6, if (d.randomised) 1 else 0)
                upd.bindLong(7, d.freq.toLong()); upd.bindLong(8, d.freq.toLong())
                upd.bindString(9, d.caps); upd.bindString(10, d.caps)
                upd.bindString(11, d.mac)
                upd.executeUpdateDelete()
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /**
     * Merges live device findings into the local `device` table without overwriting an existing
     * non-empty name or valid coordinates with empty/zero values, while preserving `watched`.
     */
    fun mergeDevicesBatch(rows: Sequence<Device>): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            val ins = db.compileStatement(
                "INSERT OR IGNORE INTO device(mac,name,type,lasttime,lat,lon,random,watched,freq,caps) VALUES(?,?,?,?,?,?,?,0,?,?)"
            )
            val upd = db.compileStatement(
                "UPDATE device SET " +
                    "name = CASE WHEN (name = '' OR name IS NULL) AND ? != '' THEN ? ELSE name END, " +
                    "lasttime = MAX(lasttime, ?), " +
                    "lat = CASE WHEN ? != 0.0 THEN ? ELSE lat END, " +
                    "lon = CASE WHEN ? != 0.0 THEN ? ELSE lon END, " +
                    "freq = CASE WHEN ? != 0 THEN ? ELSE freq END, " +
                    "caps = CASE WHEN ? != '' THEN ? ELSE caps END " +
                    "WHERE mac = ?"
            )
            for (d in rows) {
                ins.clearBindings()
                ins.bindString(1, d.mac); ins.bindString(2, d.name); ins.bindString(3, d.type)
                ins.bindLong(4, d.lastTime); ins.bindDouble(5, d.lat); ins.bindDouble(6, d.lon)
                ins.bindLong(7, if (d.randomised) 1 else 0)
                ins.bindLong(8, d.freq.toLong()); ins.bindString(9, d.caps)
                ins.executeInsert()

                upd.clearBindings()
                upd.bindString(1, d.name); upd.bindString(2, d.name)
                upd.bindLong(3, d.lastTime)
                upd.bindDouble(4, d.lat); upd.bindDouble(5, d.lat)
                upd.bindDouble(6, d.lon); upd.bindDouble(7, d.lon)
                upd.bindLong(8, d.freq.toLong()); upd.bindLong(9, d.freq.toLong())
                upd.bindString(10, d.caps); upd.bindString(11, d.caps)
                upd.bindString(12, d.mac)
                upd.executeUpdateDelete()
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun setWatched(mac: String, watched: Boolean) {
        val cv = ContentValues().apply { put("watched", if (watched) 1 else 0) }
        writableDatabase.update("device", cv, "mac=?", arrayOf(mac))
    }

    /** Browse/search list for the UI. */
    fun search(query: String, limit: Int = 200): List<Device> {
        if (query.isBlank()) return emptyList()
        val like = "%${query.trim().replace("%", "").replace("_", "")}%"
        val sql = "SELECT $COLS FROM device " +
            "WHERE (mac LIKE ? OR name LIKE ?) ORDER BY watched DESC, lasttime DESC LIMIT ?"
        return readableDatabase.rawQuery(sql, arrayOf(like, like, limit.toString())).use { toList(it) }
    }

    /** Devices whose last known place lies inside the box (for the map). */
    fun inBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, types: Set<String>, limit: Int): List<Device> {
        if (types.isEmpty()) return emptyList()
        val sql = "SELECT $COLS FROM device WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
            "AND (lat != 0 OR lon != 0) AND type IN (${types.joinToString(",") { "?" }}) LIMIT ?"
        val args = arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()) +
            types.toTypedArray() + limit.toString()
        return readableDatabase.rawQuery(sql, args).use { toList(it) }
    }

    fun linkedReady(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM linked LIMIT 1", null).use { it.moveToFirst() }

    fun obsCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM obs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private class Link(val mac: String, val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double, val cLat: Double, val cLon: Double)

    /**
     * Finds every device whose WiGLE observations lie in 2+ places at least 25 m apart, stores its extent and
     * centre, and pre-counts those devices into map cells (for the link view at any zoom).
     */
    fun rebuildLinked() {
        val found = ArrayList<Link>()
        readableDatabase.rawQuery("SELECT mac,lat,lon FROM obs ORDER BY mac, time", null).use { c ->
            var cur: String? = null
            var kept = 0
            var lastLat = 0.0; var lastLon = 0.0
            var minLat = 0.0; var maxLat = 0.0; var minLon = 0.0; var maxLon = 0.0; var sLat = 0.0; var sLon = 0.0
            fun flush() {
                val m = cur
                if (m != null && kept >= 2) found.add(Link(m, minLat, maxLat, minLon, maxLon, sLat / kept, sLon / kept))
            }
            while (c.moveToNext()) {
                val mac = c.getString(0)
                if (mac != cur) { flush(); cur = mac; kept = 0 }
                val lat = c.getDouble(1); val lon = c.getDouble(2)
                if (kept == 0 || com.dejascan.app.geo.Geo.haversineM(lastLat, lastLon, lat, lon) >= 25.0) {
                    if (kept == 0) { minLat = lat; maxLat = lat; minLon = lon; maxLon = lon; sLat = 0.0; sLon = 0.0 }
                    kept++
                    lastLat = lat; lastLon = lon
                    minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
                    minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
                    sLat += lat; sLon += lon
                }
            }
            flush()
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM linked")
            val ins = db.compileStatement("INSERT OR REPLACE INTO linked(mac,minlat,maxlat,minlon,maxlon,clat,clon) VALUES(?,?,?,?,?,?,?)")
            for (l in found) {
                ins.clearBindings()
                ins.bindString(1, l.mac)
                ins.bindDouble(2, l.minLat); ins.bindDouble(3, l.maxLat); ins.bindDouble(4, l.minLon); ins.bindDouble(5, l.maxLon)
                ins.bindDouble(6, l.cLat); ins.bindDouble(7, l.cLon)
                ins.executeInsert()
            }
            db.execSQL("UPDATE linked SET type = COALESCE((SELECT type FROM device WHERE device.mac = linked.mac), '?')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        buildCells("lcell", "SELECT clat,clon,type,mac FROM linked")
    }

    /** Linked devices whose extent touches the box, widest first. */
    fun linkedInView(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, types: Set<String>, limit: Int): List<String> {
        if (types.isEmpty()) return emptyList()
        val sql = "SELECT mac FROM linked WHERE maxlat >= ? AND minlat <= ? AND maxlon >= ? AND minlon <= ? " +
            "AND type IN (${types.joinToString(",") { "?" }}) ORDER BY (maxlat-minlat)+(maxlon-minlon) DESC LIMIT ?"
        val args = arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()) + types.toTypedArray() + limit.toString()
        return readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
    }

    fun cellsReady(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM cell LIMIT 1", null).use { it.moveToFirst() }

    private class Acc(val cx: Int, val cy: Int, val type: String, var mac: String) {
        var n = 0; var sLat = 0.0; var sLon = 0.0
    }

    /**
     * Counts all devices into map cells for every zoom from CellGrid.MIN_Z to BASE_Z, so a zoomed-out map is a
     * quick lookup instead of a scan of every device. One pass over the devices, then each coarser level is
     * derived from the finer one.
     */
    fun rebuildCells() = buildCells("cell", "SELECT lat,lon,type,mac FROM device WHERE lat != 0 OR lon != 0")

    private fun buildCells(table: String, source: String) {
        var level = HashMap<Long, Acc>()
        readableDatabase.rawQuery(source, null).use { c ->
            while (c.moveToNext()) {
                val lat = c.getDouble(0); val lon = c.getDouble(1)
                val type = c.getString(2)
                val cx = CellGrid.cell(CellGrid.nx(lon), CellGrid.BASE_Z)
                val cy = CellGrid.cell(CellGrid.ny(lat), CellGrid.BASE_Z)
                val a = level.getOrPut(key(cx, cy, type)) { Acc(cx, cy, type, c.getString(3)) }
                a.n++; a.sLat += lat; a.sLon += lon
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $table")
            val ins = db.compileStatement("INSERT INTO $table(z,cx,cy,type,n,slat,slon,mac) VALUES(?,?,?,?,?,?,?,?)")
            var z = CellGrid.BASE_Z
            while (true) {
                for (a in level.values) {
                    ins.clearBindings()
                    ins.bindLong(1, z.toLong()); ins.bindLong(2, a.cx.toLong()); ins.bindLong(3, a.cy.toLong())
                    ins.bindString(4, a.type); ins.bindLong(5, a.n.toLong())
                    ins.bindDouble(6, a.sLat); ins.bindDouble(7, a.sLon); ins.bindString(8, a.mac)
                    ins.executeInsert()
                }
                if (z <= CellGrid.MIN_Z) break
                val parent = HashMap<Long, Acc>()
                for (a in level.values) {
                    val p = parent.getOrPut(key(a.cx / 2, a.cy / 2, a.type)) { Acc(a.cx / 2, a.cy / 2, a.type, a.mac) }
                    p.n += a.n; p.sLat += a.sLat; p.sLon += a.sLon
                }
                level = parent
                z--
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun key(cx: Int, cy: Int, type: String): Long =
        (cx.toLong() * 1_048_576L + cy) * 4 + when (type) { "W" -> 0; "B" -> 1; else -> 2 }

    /** Pre-counted cells of one zoom level inside a cell range, summed over the wanted types. */
    fun cellsInView(z: Int, cxMin: Int, cxMax: Int, cyMin: Int, cyMax: Int, types: Set<String>, linkedOnly: Boolean = false): List<GeoCell> {
        if (types.isEmpty()) return emptyList()
        val sql = "SELECT SUM(n),SUM(slat)/SUM(n),SUM(slon)/SUM(n),MIN(mac),MIN(type) FROM ${if (linkedOnly) "lcell" else "cell"} " +
            "WHERE z=? AND cx BETWEEN ? AND ? AND cy BETWEEN ? AND ? AND type IN (${types.joinToString(",") { "?" }}) " +
            "GROUP BY cx, cy"
        val args = arrayOf(z.toString(), cxMin.toString(), cxMax.toString(), cyMin.toString(), cyMax.toString()) + types.toTypedArray()
        return readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<GeoCell>()
            while (c.moveToNext()) out.add(GeoCell(c.getInt(0), c.getDouble(1), c.getDouble(2), c.getString(3), c.getString(4)))
            out
        }
    }

    /** How many own sightings each device has since [since] (to skip position estimates that cannot work yet). */
    fun sightCounts(since: Long): Map<String, Int> =
        readableDatabase.rawQuery("SELECT mac, COUNT(*) FROM sight WHERE time>=? GROUP BY mac", arrayOf(since.toString())).use { c ->
            val out = HashMap<String, Int>()
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    fun bounds(): DoubleArray? = readableDatabase.rawQuery(
        "SELECT MIN(lat),MAX(lat),MIN(lon),MAX(lon),COUNT(*) FROM device WHERE lat != 0 OR lon != 0", null,
    ).use { if (it.moveToFirst() && it.getInt(4) > 0) doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3)) else null }

    fun watched(): List<Device> =
        readableDatabase.rawQuery("SELECT $COLS FROM device WHERE watched=1 ORDER BY name", null).use { toList(it) }

    fun devices(macs: Collection<String>): List<Device> {
        if (macs.isEmpty()) return emptyList()
        val sql = "SELECT $COLS FROM device WHERE mac IN (${macs.joinToString(",") { "?" }})"
        return readableDatabase.rawQuery(sql, macs.toTypedArray()).use { toList(it) }
    }

    /** Returns the subset of [macs] that exist in the local device table. */
    fun filterKnown(macs: Collection<String>): Set<String> {
        if (macs.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (chunk in macs.chunked(400)) {
            readableDatabase.rawQuery(
                "SELECT mac FROM device WHERE mac IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
        }
        return out
    }

    /**
     * Live match: is this heard address one the active watch mode cares about?
     * [kinds] are the WiGLE type codes the radio can produce (Bluetooth sightings: B and E).
     */
    fun match(mac: String, kinds: Set<String>, filter: WatchFilter): Device? {
        val where = StringBuilder("mac=?")
        val args = ArrayList<String>().apply { add(mac) }
        where.append(" AND type IN (").append(kinds.joinToString(",") { "?" }).append(")")
        args.addAll(kinds)
        when (filter.mode) {
            WatchMode.PICKED -> where.append(" AND watched=1")
            WatchMode.ALL -> Unit
            WatchMode.FILTERED -> {
                if (filter.types.isEmpty()) return null
                where.append(" AND type IN (").append(filter.types.joinToString(",") { "?" }).append(")")
                args.addAll(filter.types)
                if (filter.skipRandomised) where.append(" AND random=0")
            }
        }
        val sql = "SELECT $COLS FROM device WHERE $where LIMIT 1"
        return try {
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { toList(it).firstOrNull() }
        } catch (_: SQLiteException) {
            null // database busy or unavailable: skip this reading instead of crashing the service
        }
    }

    // ---- WiGLE observations -------------------------------------------------------------------

    fun beginObsImport() {
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_obs_mac")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_obs_mac_time")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_obs_time")
        writableDatabase.execSQL("DROP INDEX IF EXISTS idx_obs_pos")
        writableDatabase.execSQL("DELETE FROM obs")
    }

    /** Inserts observations while skipping any `(mac, time)` duplicates already in `obs`. */
    fun insertObsBatch(rows: List<ObsRow>): Int {
        if (rows.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT OR IGNORE INTO obs(mac,time,lat,lon,level) VALUES(?,?,?,?,?)")
            for (r in rows) {
                st.clearBindings()
                st.bindString(1, r.mac); st.bindLong(2, r.time)
                st.bindDouble(3, r.lat); st.bindDouble(4, r.lon); st.bindLong(5, r.level.toLong())
                if (st.executeInsert() != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun endObsImport() {
        writableDatabase.execSQL("DELETE FROM obs WHERE rowid NOT IN (SELECT MIN(rowid) FROM obs GROUP BY mac, time)")
        writableDatabase.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_obs_mac_time ON obs(mac, time)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_time ON obs(time)")
        writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS idx_obs_pos ON obs(lat, lon)")
    }

    /** One place a device was seen: a WiGLE observation, one of my own unmerged scans, or its WiGLE last-known place. */
    fun hitsOnDay(startMs: Long, endMs: Long, limit: Int): List<DayHit> {
        val sql = "SELECT mac,lat,lon,time FROM obs WHERE time>=? AND time<? " +
            "UNION ALL SELECT mac,lat,lon,time FROM sight WHERE time>=? AND time<? " +
            "AND NOT EXISTS (SELECT 1 FROM obs WHERE obs.mac = sight.mac AND obs.time = sight.time) " +
            "UNION ALL SELECT mac,lat,lon,lasttime FROM device WHERE lasttime>=? AND lasttime<? AND (lat != 0 OR lon != 0) LIMIT ?"
        val a = startMs.toString(); val b = endMs.toString()
        return readableDatabase.rawQuery(sql, arrayOf(a, b, a, b, a, b, limit.toString())).use { c ->
            val out = ArrayList<DayHit>()
            while (c.moveToNext()) out.add(DayHit(c.getString(0), c.getDouble(1), c.getDouble(2), c.getLong(3)))
            out
        }
    }

    /** Devices whose own scans inside the box are at least ~25 m apart (the stored `linked` list only covers WiGLE data). */
    fun linkCandidates(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int): List<String> {
        val sql = "SELECT mac FROM sight WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
            "GROUP BY mac HAVING COUNT(*) >= 2 AND (MAX(lat)-MIN(lat) > 0.0002 OR MAX(lon)-MIN(lon) > 0.0003) LIMIT ?"
        val args = arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString(), limit.toString())
        return readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
    }

    /** Every recorded place (lat, lon, time) of the given devices, oldest first, deduplicating scans merged into `obs`. */
    fun placesOf(macs: List<String>): Map<String, List<DoubleArray>> {
        val out = HashMap<String, ArrayList<DoubleArray>>()
        for (chunk in macs.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT mac,lat,lon,time FROM obs WHERE mac IN ($marks)", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    // [lat, lon, time, source] with source 0 = WiGLE observation, 1 = my own scan
                    out.getOrPut(c.getString(0)) { ArrayList() }.add(
                        doubleArrayOf(c.getDouble(1), c.getDouble(2), c.getLong(3).toDouble(), 0.0)
                    )
                }
            }
            readableDatabase.rawQuery(
                "SELECT mac,lat,lon,time FROM sight WHERE mac IN ($marks) " +
                    "AND NOT EXISTS (SELECT 1 FROM obs WHERE obs.mac = sight.mac AND obs.time = sight.time)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    out.getOrPut(c.getString(0)) { ArrayList() }.add(
                        doubleArrayOf(c.getDouble(1), c.getDouble(2), c.getLong(3).toDouble(), 1.0)
                    )
                }
            }
        }
        for (v in out.values) v.sortBy { it[2] }
        return out
    }

    fun obsFor(mac: String, limit: Int): List<ObsRow> =
        readableDatabase.rawQuery(
            "SELECT time,lat,lon,level FROM obs WHERE mac=? ORDER BY time DESC LIMIT ?",
            arrayOf(mac, limit.toString()),
        ).use { c ->
            val out = ArrayList<ObsRow>()
            while (c.moveToNext()) out.add(ObsRow(mac, c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3)))
            out
        }

    // ---- this app's own sightings -------------------------------------------------------------

    fun addSight(mac: String, time: Long, lat: Double, lon: Double, acc: Float, rssi: Int) {
        try {
            val cv = ContentValues().apply {
                put("mac", mac); put("time", time); put("lat", lat); put("lon", lon)
                put("acc", acc.toDouble()); put("rssi", rssi)
            }
            writableDatabase.insert("sight", null, cv)
        } catch (_: SQLiteException) {
            // busy during a long import: drop this sample
        }
    }

    fun sightsFor(mac: String, since: Long, limit: Int): List<SightPoint> =
        readableDatabase.rawQuery(
            "SELECT lat,lon,acc,rssi,time FROM sight WHERE mac=? AND time>=? ORDER BY time DESC LIMIT ?",
            arrayOf(mac, since.toString(), limit.toString()),
        ).use { c ->
            val out = ArrayList<SightPoint>()
            while (c.moveToNext()) {
                out.add(SightPoint(c.getDouble(0), c.getDouble(1), c.getFloat(2), c.getInt(3), c.getLong(4)))
            }
            out
        }

    fun clearSights() {
        writableDatabase.execSQL("DELETE FROM sight")
    }

    fun allSights(): List<WigleMerger.SightRecord> =
        readableDatabase.rawQuery("SELECT mac,time,lat,lon,acc,rssi FROM sight", null).use { c ->
            val out = ArrayList<WigleMerger.SightRecord>()
            while (c.moveToNext()) {
                out.add(
                    WigleMerger.SightRecord(
                        mac = c.getString(0),
                        time = c.getLong(1),
                        lat = c.getDouble(2),
                        lon = c.getDouble(3),
                        acc = c.getFloat(4),
                        rssi = c.getInt(5),
                    )
                )
            }
            out
        }

    private fun toList(c: android.database.Cursor): List<Device> {
        val out = ArrayList<Device>()
        while (c.moveToNext()) {
            out.add(
                Device(
                    mac = c.getString(0), name = c.getString(1), type = c.getString(2),
                    lastTime = c.getLong(3), lat = c.getDouble(4), lon = c.getDouble(5),
                    randomised = c.getInt(6) != 0, watched = c.getInt(7) != 0,
                    freq = c.getInt(8), caps = c.getString(9).orEmpty(),
                )
            )
        }
        return out
    }

    private companion object {
        const val COLS = "mac,name,type,lasttime,lat,lon,random,watched,freq,caps"
    }
}
