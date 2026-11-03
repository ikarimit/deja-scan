package com.dejascan.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.Sighting
import java.io.File

object WigleMerger {

    data class SightRecord(
        val mac: String,
        val time: Long,
        val lat: Double,
        val lon: Double,
        val acc: Float,
        val rssi: Int,
    )

    data class NetworkRecord(
        val bssid: String,
        val ssid: String,
        val frequency: Int,
        val capabilities: String,
        val lastTime: Long,
        val lastLat: Double,
        val lastLon: Double,
        val type: String,
        val bestLevel: Int,
        val bestLat: Double,
        val bestLon: Double,
    )

    data class Result(
        val newNetworks: Int,
        val updatedNetworks: Int,
        val newLocations: Int,
    )

    fun prepareNetworkRecord(s: Sighting, sightsForMac: List<SightRecord>): NetworkRecord {
        val mac = MacUtil.normalize(s.mac) ?: s.mac
        val latestSight = sightsForMac.maxByOrNull { it.time }
        val bestSight = sightsForMac.maxByOrNull { it.rssi }

        val lastLat = latestSight?.lat ?: s.lat
        val lastLon = latestSight?.lon ?: s.lon
        val bestLat = bestSight?.lat ?: s.lat
        val bestLon = bestSight?.lon ?: s.lon
        val bestLevel = maxOf(s.best, bestSight?.rssi ?: Int.MIN_VALUE)

        val rawCaps = s.adv.firstOrNull { it.startsWith("Raw Caps: ") }?.substringAfter("Raw Caps: ")
            ?: if (s.type == "W") "[ESS]" else ""
        val ssid = if (!s.name.equals(s.mac, ignoreCase = true)) s.name else ""

        return NetworkRecord(
            bssid = mac,
            ssid = ssid,
            frequency = s.freq,
            capabilities = rawCaps,
            lastTime = s.time,
            lastLat = lastLat,
            lastLon = lastLon,
            type = s.type,
            bestLevel = bestLevel,
            bestLat = bestLat,
            bestLon = bestLon,
        )
    }

    fun prepareCombinedSights(sightings: Collection<Sighting>, sights: List<SightRecord>): List<SightRecord> {
        val sightsByMac = sights.groupBy { MacUtil.normalize(it.mac).orEmpty() }
        val combined = ArrayList(sights)
        for (s in sightings) {
            if (WigleImporter.validLocation(s.lat, s.lon)) {
                val mac = MacUtil.normalize(s.mac) ?: continue
                if (sightsByMac[mac]?.none { it.time == s.time } != false) {
                    combined.add(SightRecord(mac, s.time, s.lat, s.lon, 0f, s.rssi))
                }
            }
        }
        return combined
    }

    fun merge(
        file: File,
        sightings: Collection<Sighting>,
        sights: List<SightRecord>,
        progress: (String) -> Unit = {},
    ): Result {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.use {
            var newNets = 0
            var updNets = 0
            var newLocs = 0

            db.beginTransaction()
            try {
                progress("Merging networks into linked database...")
                val sightsByMac = sights.groupBy { MacUtil.normalize(it.mac).orEmpty() }

                val netCols = getTableColumns(db, "network")
                if (netCols.contains("bssid")) {
                    val checkStmt = db.compileStatement(
                        "SELECT COUNT(*) FROM network WHERE bssid = ?",
                    )

                    val insertSql = buildString {
                        append("INSERT INTO network (bssid, ssid, frequency, capabilities, lasttime, lastlat, lastlon, type, bestlevel, bestlat, bestlon")
                        if (netCols.contains("rcois")) append(", rcois")
                        if (netCols.contains("mfgrid")) append(", mfgrid")
                        append(") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?")
                        if (netCols.contains("rcois")) append(", ''")
                        if (netCols.contains("mfgrid")) append(", 0")
                        append(")")
                    }
                    val insertStmt = db.compileStatement(insertSql)

                    val updateSql = "UPDATE network SET " +
                        "ssid = CASE WHEN (ssid = '' OR ssid IS NULL) AND ? != '' THEN ? ELSE ssid END, " +
                        "capabilities = CASE WHEN (capabilities = '' OR capabilities = '[ESS]' OR capabilities IS NULL) AND ? != '' AND ? != '[ESS]' THEN ? ELSE capabilities END, " +
                        "lasttime = max(lasttime, ?), " +
                        "lastlat = CASE WHEN ? != 0.0 THEN ? ELSE lastlat END, " +
                        "lastlon = CASE WHEN ? != 0.0 THEN ? ELSE lastlon END, " +
                        "bestlat = CASE WHEN (? >= bestlevel OR bestlat = 0.0) AND ? != 0.0 THEN ? ELSE bestlat END, " +
                        "bestlon = CASE WHEN (? >= bestlevel OR bestlon = 0.0) AND ? != 0.0 THEN ? ELSE bestlon END, " +
                        "bestlevel = max(bestlevel, ?) " +
                        "WHERE bssid = ?"
                    val updateStmt = db.compileStatement(updateSql)

                    for (s in sightings) {
                        val mac = MacUtil.normalize(s.mac) ?: continue
                        val rec = prepareNetworkRecord(s, sightsByMac[mac] ?: emptyList())

                        checkStmt.bindString(1, mac)
                        val exists = checkStmt.simpleQueryForLong() > 0

                        if (!exists) {
                            insertStmt.clearBindings()
                            insertStmt.bindString(1, rec.bssid)
                            insertStmt.bindString(2, rec.ssid)
                            insertStmt.bindLong(3, rec.frequency.toLong())
                            insertStmt.bindString(4, rec.capabilities)
                            insertStmt.bindLong(5, rec.lastTime)
                            insertStmt.bindDouble(6, rec.lastLat)
                            insertStmt.bindDouble(7, rec.lastLon)
                            insertStmt.bindString(8, rec.type)
                            insertStmt.bindLong(9, rec.bestLevel.toLong())
                            insertStmt.bindDouble(10, rec.bestLat)
                            insertStmt.bindDouble(11, rec.bestLon)
                            insertStmt.executeInsert()
                            newNets++
                        } else {
                            updateStmt.clearBindings()
                            updateStmt.bindString(1, rec.ssid)
                            updateStmt.bindString(2, rec.ssid)
                            updateStmt.bindString(3, rec.capabilities)
                            updateStmt.bindString(4, rec.capabilities)
                            updateStmt.bindString(5, rec.capabilities)
                            updateStmt.bindLong(6, rec.lastTime)
                            updateStmt.bindDouble(7, rec.lastLat)
                            updateStmt.bindDouble(8, rec.lastLat)
                            updateStmt.bindDouble(9, rec.lastLon)
                            updateStmt.bindDouble(10, rec.lastLon)
                            updateStmt.bindLong(11, rec.bestLevel.toLong())
                            updateStmt.bindDouble(12, rec.bestLat)
                            updateStmt.bindDouble(13, rec.bestLat)
                            updateStmt.bindLong(14, rec.bestLevel.toLong())
                            updateStmt.bindDouble(15, rec.bestLon)
                            updateStmt.bindDouble(16, rec.bestLon)
                            updateStmt.bindLong(17, rec.bestLevel.toLong())
                            updateStmt.bindString(18, mac)
                            updateStmt.executeUpdateDelete()
                            updNets++
                        }
                    }
                }

                val combinedSights = prepareCombinedSights(sightings, sights)

                // Insert locations into location table
                val locCols = getTableColumns(db, "location")
                if (locCols.contains("bssid") && locCols.contains("lat") && locCols.contains("lon")) {
                    val hasAccuracy = locCols.contains("accuracy")
                    val hasAltitude = locCols.contains("altitude")

                    db.execSQL("CREATE INDEX IF NOT EXISTS deja_merge_loc ON location (bssid, time)")

                    val checkLocStmt = db.compileStatement(
                        "SELECT COUNT(*) FROM location WHERE bssid = ? AND time = ?"
                    )

                    val insertLocSql = buildString {
                        append("INSERT INTO location (bssid, time, lat, lon, level")
                        if (hasAccuracy) append(", accuracy")
                        if (hasAltitude) append(", altitude")
                        append(") VALUES (?, ?, ?, ?, ?")
                        if (hasAccuracy) append(", ?")
                        if (hasAltitude) append(", 0.0")
                        append(")")
                    }
                    val insertLocStmt = db.compileStatement(insertLocSql)

                    for (sight in combinedSights) {
                        val mac = MacUtil.normalize(sight.mac) ?: continue
                        if (!WigleImporter.validLocation(sight.lat, sight.lon)) continue

                        checkLocStmt.bindString(1, mac)
                        checkLocStmt.bindLong(2, sight.time)
                        if (checkLocStmt.simpleQueryForLong() == 0L) {
                            insertLocStmt.clearBindings()
                            insertLocStmt.bindString(1, mac)
                            insertLocStmt.bindLong(2, sight.time)
                            insertLocStmt.bindDouble(3, sight.lat)
                            insertLocStmt.bindDouble(4, sight.lon)
                            insertLocStmt.bindLong(5, sight.rssi.toLong())
                            if (hasAccuracy) {
                                insertLocStmt.bindDouble(6, sight.acc.toDouble())
                            }
                            insertLocStmt.executeInsert()
                            newLocs++
                        }
                    }

                    db.execSQL("DROP INDEX IF EXISTS deja_merge_loc")
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            return Result(newNets, updNets, newLocs)
        }
    }

    private fun getTableColumns(db: SQLiteDatabase, tableName: String): Set<String> {
        val cols = HashSet<String>()
        try {
            db.rawQuery("PRAGMA table_info($tableName)", null).use { c ->
                while (c.moveToNext()) {
                    cols.add(c.getString(1).lowercase())
                }
            }
        } catch (_: SQLiteException) {}
        return cols
    }
}
