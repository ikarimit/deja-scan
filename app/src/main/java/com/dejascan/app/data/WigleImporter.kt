package com.dejascan.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.dejascan.app.scan.MacUtil
import java.io.File

/**
 * Reads a WiGLE Android app SQLite database: the `network` table
 * (bssid, ssid, type, lasttime, lastlat, lastlon, bestlat, bestlon; type W/B/E = Wi-Fi/Bluetooth/BLE)
 * and the `location` table of individual observations (bssid, lat, lon, time, level).
 */
object WigleImporter {
    const val QUERY = "SELECT bssid,ssid,type,lasttime,lastlat,lastlon,bestlat,bestlon FROM network " +
        "WHERE type IN ('W','B','E')"
    const val OBS_QUERY = "SELECT bssid,time,lat,lon,level FROM location"
    private const val OBS_BATCH = 20_000

    sealed class Result {
        data class Ok(val imported: Int, val skipped: Int, val observations: Int) : Result()
        data class Error(val message: String) : Result()
    }

    fun import(file: File, target: DejaDb, progress: (String) -> Unit = {}): Result {
        val src = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            return Result.Error("Not a readable SQLite file: ${e.message}")
        }
        src.use { db ->
            var skipped = 0
            val devices = try {
                db.rawQuery(QUERY, null).use { c ->
                    val rows = sequence {
                        while (c.moveToNext()) {
                            val mac = MacUtil.normalize(c.getString(0))
                            if (mac == null) { skipped++; continue }
                            val best = pickLocation(c.getDouble(6), c.getDouble(7), c.getDouble(4), c.getDouble(5))
                            yield(
                                Device(
                                    mac = mac,
                                    name = c.getString(1) ?: "",
                                    type = c.getString(2),
                                    lastTime = c.getLong(3),
                                    lat = best.first, lon = best.second,
                                    randomised = MacUtil.isRandomised(mac),
                                    watched = false,
                                )
                            )
                        }
                    }
                    target.upsertBatch(rows)
                }
            } catch (e: SQLiteException) {
                return Result.Error("Not a WiGLE database (no usable 'network' table): ${e.message}")
            }
            progress("Imported $devices devices. Importing observations...")
            val obs = try {
                importObservations(db, target, progress)
            } catch (e: SQLiteException) {
                0 // no usable location table: devices are still imported
            }
            return Result.Ok(devices, skipped, obs)
        }
    }

    private fun importObservations(db: SQLiteDatabase, target: DejaDb, progress: (String) -> Unit): Int {
        var total = 0
        target.beginObsImport()
        try {
            db.rawQuery(OBS_QUERY, null).use { c ->
                val batch = ArrayList<ObsRow>(OBS_BATCH)
                while (c.moveToNext()) {
                    val mac = MacUtil.normalize(c.getString(0)) ?: continue
                    val lat = c.getDouble(2)
                    val lon = c.getDouble(3)
                    if (!validLocation(lat, lon)) continue
                    batch.add(ObsRow(mac, c.getLong(1), lat, lon, c.getInt(4)))
                    if (batch.size >= OBS_BATCH) {
                        target.insertObsBatch(batch)
                        total += batch.size
                        batch.clear()
                        progress("Importing observations: $total")
                    }
                }
                target.insertObsBatch(batch)
                total += batch.size
            }
        } finally {
            target.endObsImport()
        }
        return total
    }

    fun validLocation(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && !(lat == 0.0 && lon == 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0

    /** Prefer best location, fall back to last; WiGLE rows can hold 0/0 or non-finite values. */
    fun pickLocation(bestLat: Double, bestLon: Double, lastLat: Double, lastLon: Double): Pair<Double, Double> = when {
        validLocation(bestLat, bestLon) -> bestLat to bestLon
        validLocation(lastLat, lastLon) -> lastLat to lastLon
        else -> 0.0 to 0.0
    }
}
