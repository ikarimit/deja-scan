package com.dejascan.app.data

import android.content.Context
import com.dejascan.app.scan.MacUtil
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.Reader
import kotlin.concurrent.thread

/** Manufacturer lookup from the IEEE OUI registry (MA-L/M/S) and Bluetooth SIG company identifiers. */
object Vendors {
    private var prefixes: Map<String, String> = emptyMap()
    private var companies: Map<Int, String> = emptyMap()
    val ready = MutableStateFlow(false)

    fun init(context: Context) {
        if (ready.value) return
        val app = context.applicationContext
        thread(name = "vendors-load") {
            try {
                prefixes = app.assets.open("vendors.tsv").bufferedReader().use { parsePrefixes(it) }
                companies = app.assets.open("companies.tsv").bufferedReader().use { parseCompanies(it) }
            } catch (_: Exception) {
                // leave empty: names just won't resolve
            }
            ready.value = true
        }
    }

    /** Name for a normalised MAC, or null when unknown or the address is randomised (no real prefix). */
    fun forMac(mac: String): String? = lookup(prefixes, mac)

    fun forCompanyId(id: Int): String? = companies[id]

    fun lookup(table: Map<String, String>, mac: String): String? {
        val m = MacUtil.normalize(mac) ?: return null
        if (MacUtil.isRandomised(m)) return null
        val hex = m.replace(":", "").uppercase()
        return table[hex.substring(0, 9)] ?: table[hex.substring(0, 7)] ?: table[hex.substring(0, 6)]
    }

    /** Lines of `PREFIX<TAB>NAME`. */
    fun parsePrefixes(r: Reader): Map<String, String> {
        val out = HashMap<String, String>(70_000)
        BufferedReader(r).forEachLine { line ->
            val i = line.indexOf('\t')
            if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    /** Lines of `DECIMAL_ID<TAB>NAME`. */
    fun parseCompanies(r: Reader): Map<Int, String> {
        val out = HashMap<Int, String>(5_000)
        BufferedReader(r).forEachLine { line ->
            val i = line.indexOf('\t')
            val id = if (i > 0) line.substring(0, i).toIntOrNull() else null
            if (id != null) out[id] = line.substring(i + 1)
        }
        return out
    }

    private val SUFFIXES = setOf(
        "inc", "ltd", "llc", "co", "corp", "corporation", "gmbh", "ag", "limited", "company", "plc", "sa", "bv", "oy", "ab", "the",
    )

    /** One or two letters for the badge, skipping corporate suffixes. */
    fun monogram(name: String?): String {
        if (name.isNullOrBlank()) return "?"
        val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        val real = words.filter { it.lowercase() !in SUFFIXES }.ifEmpty { words }
        if (real.isEmpty()) return "?"
        return if (real.size >= 2) "${real[0][0]}${real[1][0]}".uppercase() else real[0].take(2).uppercase()
    }

    /** Stable hue 0..359 from the name. */
    fun hue(name: String?): Float = if (name.isNullOrBlank()) 0f else ((name.lowercase().hashCode() and 0x7FFFFFFF) % 360).toFloat()
}
