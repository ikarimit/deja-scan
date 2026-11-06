package com.dejascan.app.data

import android.content.Context

/** Watch-mode settings shared by the UI and the scan service. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("deja", Context.MODE_PRIVATE)

    var mode: WatchMode
        get() = runCatching { WatchMode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(WatchMode.PICKED)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var types: Set<String>
        get() = (sp.getString("types", "W,B,E") ?: "").split(',').filter { it.isNotEmpty() }.toSet()
        set(v) = sp.edit().putString("types", v.joinToString(",")).apply()

    var skipRandomised: Boolean
        get() = sp.getBoolean("skipRandom", false)
        set(v) = sp.edit().putBoolean("skipRandom", v).apply()

    var themeMode: String
        get() = sp.getString("theme", "SYSTEM") ?: "SYSTEM"
        set(v) = sp.edit().putString("theme", v).apply()

    var linkedUri: String
        get() = sp.getString("linkedUri", "") ?: ""
        set(v) = sp.edit().putString("linkedUri", v).apply()

    var linkedName: String
        get() = sp.getString("linkedName", "") ?: ""
        set(v) = sp.edit().putString("linkedName", v).apply()

    var linkedModified: Long
        get() = sp.getLong("linkedModified", 0L)
        set(v) = sp.edit().putLong("linkedModified", v).apply()

    /** Zoom from which the big (over 100 MB) map files are read; lower values need much more memory. */
    var mapDetailZoom: Int
        get() = sp.getInt("mapDetailZoom", 10)
        set(v) = sp.edit().putInt("mapDetailZoom", v).apply()

    var haptic: Boolean
        get() = sp.getBoolean("haptic", true)
        set(v) = sp.edit().putBoolean("haptic", v).apply()

    var beep: Boolean
        get() = sp.getBoolean("beep", true)
        set(v) = sp.edit().putBoolean("beep", v).apply()

    var notifyHits: Boolean
        get() = sp.getBoolean("notifyHits", true)
        set(v) = sp.edit().putBoolean("notifyHits", v).apply()

    var lastImport: String
        get() = sp.getString("lastImport", "") ?: ""
        set(v) = sp.edit().putString("lastImport", v).apply()

    var promiscuousEnabled: Boolean
        get() = sp.getBoolean("promiscEnabled", false)
        set(v) = sp.edit().putBoolean("promiscEnabled", v).apply()

    var promiscuousIface: String
        get() = sp.getString("promiscIface", "auto") ?: "auto"
        set(v) = sp.edit().putString("promiscIface", v).apply()

    var promiscuousDriverSwitch: Boolean
        get() = sp.getBoolean("promiscDriverSwitch", false)
        set(v) = sp.edit().putBoolean("promiscDriverSwitch", v).apply()

    fun filter() = WatchFilter(mode, types, skipRandomised)
}
