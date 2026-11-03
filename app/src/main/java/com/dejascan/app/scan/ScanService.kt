package com.dejascan.app.scan

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.dejascan.app.data.Device
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dejascan.app.MainActivity
import com.dejascan.app.data.DejaDb
import com.dejascan.app.data.Prefs
import com.dejascan.app.data.Vendors
import java.util.concurrent.Executors

/**
 * Foreground service that listens for Wi-Fi, BLE and Bluetooth Classic and reports addresses that
 * match the watch settings. Wi-Fi scans are requested every [WIFI_INTERVAL_MS] to stay inside the
 * platform limit of 4 foreground scans per 2 minutes (Android 9+); results from other apps' scans
 * are also used.
 */
@SuppressLint("MissingPermission")
class ScanService : Service() {

    private lateinit var db: DejaDb
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val approach = ApproachTracker()
    private val io = Executors.newSingleThreadExecutor()
    private val lastSave = HashMap<String, Long>()
    private var locMgr: LocationManager? = null

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val now = System.currentTimeMillis()
            val cur = SightingBus.phone.value
            // keep a fresh, more accurate GPS fix over a coarser network fix
            if (loc.provider == LocationManager.NETWORK_PROVIDER && cur != null &&
                now - cur.time < 15_000 && cur.acc <= loc.accuracy
            ) return
            SightingBus.phone.value = PhoneFix(loc.latitude, loc.longitude, loc.accuracy, now)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }
    private var wifi: WifiManager? = null
    private var btAdapter: BluetoothAdapter? = null
    private var receiversRegistered = false
    private var bleActive = false

    private val wifiTick = object : Runnable {
        @Suppress("DEPRECATION")
        override fun run() {
            val started = try { wifi?.startScan() ?: false } catch (_: SecurityException) { false }
            if (!started) SightingBus.message.value = "Wi-Fi scan throttled or refused; using latest results"
            readWifiResults()
            handler.postDelayed(this, WIFI_INTERVAL_MS)
        }
    }

    private val classicTick = Runnable { startClassicDiscovery() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> readWifiResults()
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (dev != null) onHeard(dev.address, BT_KINDS, rssi, safeName(dev), 0, classicAdv(dev))
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                    handler.postDelayed(classicTick, CLASSIC_PAUSE_MS)
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onHeard(result.device.address, BT_KINDS, result.rssi, result.scanRecord?.deviceName ?: "", 0, bleAdv(result))
        }

        override fun onScanFailed(errorCode: Int) {
            SightingBus.message.value = "BLE scan failed (code $errorCode)"
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = DejaDb(this)
        prefs = Prefs(this)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        SightingBus.wifiCapabilities.value = WifiCapabilities.detect(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        startForeground(
            ONGOING_ID, ongoingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        if (!SightingBus.running.value) beginScanning()
        return START_NOT_STICKY
    }

    private fun beginScanning() {
        SightingBus.running.value = true
        SightingBus.message.value = ""
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)
        receiversRegistered = true

        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            handler.post(wifiTick)
            startLocation()
        }
        else SightingBus.message.value = "Location permission missing: Wi-Fi scanning off"

        if (btAdapter?.isEnabled == true && hasBtScan()) {
            try {
                btAdapter?.bluetoothLeScanner?.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                    bleCallback,
                )
                bleActive = true
            } catch (e: SecurityException) {
                SightingBus.message.value = "Bluetooth permission refused"
            }
            startClassicDiscovery()
        } else {
            SightingBus.message.value = SightingBus.message.value.ifEmpty { "Bluetooth off or permission missing" }
        }
    }

    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        locMgr = lm
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 3000L, 0f, locListener, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
    }

    private fun startClassicDiscovery() {
        try {
            if (btAdapter?.isDiscovering == false) btAdapter?.startDiscovery()
        } catch (_: SecurityException) {
        }
    }

    private fun readWifiResults() {
        val results = try { wifi?.scanResults } catch (_: SecurityException) { null } ?: return
        for (r in results) {
            @Suppress("DEPRECATION") val ssid = r.SSID ?: ""
            val sec = WifiSecurity.parse(r.capabilities)
            val channelWidth = r.channelWidth
            val centerFreq0 = r.centerFreq0
            val centerFreq1 = r.centerFreq1
            val rawStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) r.wifiStandard else 0
            val wifiStandard = WifiStandardHelper.resolveStandard(rawStandard, r.frequency, channelWidth, r.capabilities)
            val stdLabel = WifiStandardHelper.getStandardName(wifiStandard, r.frequency)
            val widthLabel = ChannelWidthHelper.getWidthLabel(channelWidth)
            val band = WifiBand.forFrequency(r.frequency)
            val span = ChannelWidthHelper.getFrequencySpan(r.frequency, channelWidth, centerFreq0)

            val adv = buildList {
                add("SSID: ${ssid.ifEmpty { "(hidden)" }}")
                add("Channel: ${channelFor(r.frequency)} (${r.frequency} MHz) [${band.label}]")
                if (stdLabel.isNotEmpty() && stdLabel != "Unknown standard") {
                    add("Standard: $stdLabel")
                }
                add("Bandwidth: $widthLabel (${span.first}–${span.last} MHz" +
                    (if (centerFreq0 > 0 && centerFreq0 != r.frequency) ", Center: $centerFreq0 MHz" else "") + ")")
                add("Security: ${sec.suite.label}")
                if (sec.keyManagement.isNotEmpty() && sec.keyManagement != "None") {
                    add("Auth: ${sec.keyManagement}")
                }
                if (sec.ciphers.isNotEmpty()) {
                    add("Ciphers: ${sec.ciphers.joinToString(", ")}")
                }
                add("Mode: ${sec.mode.label} (${sec.mode.description})")
                if (sec.hasWps) {
                    add("WPS: Supported" + if (sec.wpsDetails != null) " (${sec.wpsDetails})" else "")
                }
                if (sec.isFastTransition) add("802.11r FT: Fast BSS Transition supported")
                if (sec.isPasspoint) add("Passpoint: Hotspot 2.0 supported")
                if (sec.raw.isNotEmpty()) add("Raw Caps: ${sec.raw}")
            }
            onHeard(
                r.BSSID, WIFI_KINDS, r.level, ssid, r.frequency, adv, sec.suite,
                channelWidth = channelWidth,
                centerFreq0 = centerFreq0,
                centerFreq1 = centerFreq1,
                wifiStandard = wifiStandard,
                standardLabel = stdLabel,
            )
        }
        updateSecurityNotifications()
    }

    private fun onHeard(
        rawMac: String?,
        kinds: Set<String>,
        rssi: Int,
        liveName: String,
        freq: Int,
        adv: List<String>,
        securitySuite: SecuritySuite = SecuritySuite.UNKNOWN,
        channelWidth: Int = 0,
        centerFreq0: Int = 0,
        centerFreq1: Int = 0,
        wifiStandard: Int = 0,
        standardLabel: String = "",
    ) {
        val mac = MacUtil.normalize(rawMac) ?: return
        val matched = db.match(mac, kinds, prefs.filter())
        val inDbDev = matched ?: db.devices(listOf(mac)).firstOrNull()
        val inDb = inDbDev != null
        // other devices are still reported (for the map) but never alert and are kept out of the radar
        val dev = inDbDev ?: Device(
            mac = mac, name = "", type = kinds.first(), lastTime = 0L, lat = 0.0, lon = 0.0,
            randomised = MacUtil.isRandomised(mac), watched = false,
        )
        val now = System.currentTimeMillis()
        SightingBus.report(
            dev, liveName, rssi, freq, adv, now, matched != null, inDatabase = inDb, securitySuite = securitySuite,
            channelWidth = channelWidth, centerFreq0 = centerFreq0, centerFreq1 = centerFreq1,
            wifiStandard = wifiStandard, standardLabel = standardLabel,
        )
        val fix = SightingBus.phone.value
        val every = if (matched != null) 5_000L else 30_000L
        if (fix != null && now - fix.time < 15_000 && fix.acc <= 50f && now - (lastSave[mac] ?: 0L) >= every) {
            lastSave[mac] = now
            io.execute { db.addSight(mac, now, fix.lat, fix.lon, fix.acc, rssi) }
        }
        if (matched != null && approach.onReading(mac, rssi, now)) {
            val name = dev.name.ifEmpty { liveName }.ifEmpty { mac }
            getSystemService(NotificationManager::class.java)
                .notify(mac.hashCode(), hitNotification(name, mac, rssi))
        }
    }

    private fun shortUuid(u: String): String =
        if (u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + u.substring(4, 8) else u

    private fun bleAdv(r: ScanResult): List<String> {
        val out = ArrayList<String>()
        val rec = r.scanRecord
        rec?.deviceName?.let { out += "Name: $it" }
        rec?.serviceUuids?.takeIf { it.isNotEmpty() }?.let { l ->
            out += "Services: " + l.joinToString { shortUuid(it.uuid.toString()) }
        }
        rec?.manufacturerSpecificData?.let { m ->
            if (m.size() > 0) out += "Manufacturer: " + (0 until m.size()).joinToString {
                val id = m.keyAt(it)
                (Vendors.forCompanyId(id) ?: "unknown") + " (0x%04X)".format(id)
            }
        }
        if (rec != null && rec.txPowerLevel != Int.MIN_VALUE) out += "TX power: ${rec.txPowerLevel} dBm"
        out += "Connectable: ${r.isConnectable}"
        return out
    }

    private fun classicAdv(d: BluetoothDevice): List<String> = try {
        d.bluetoothClass?.let { listOf("Class: 0x%06X".format(it.deviceClass)) } ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun safeName(d: BluetoothDevice): String = try { d.name ?: "" } catch (_: SecurityException) { "" }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasBtScan() =
        if (Build.VERSION.SDK_INT >= 31) has(Manifest.permission.BLUETOOTH_SCAN)
        else has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Scanning", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_HITS, "Device nearby", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SECURITY, "Security advisories", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Silent alerts when an AP in range has WEP or security issues"
                enableVibration(false)
                enableLights(false)
            }
        )
    }

    private fun openApp(selectMac: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (selectMac != null) putExtra(MainActivity.EXTRA_SELECT_MAC, selectMac)
        }
        val requestCode = if (selectMac != null) selectMac.hashCode() else 0
        return PendingIntent.getActivity(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ongoingNotification(insecureAps: List<Sighting> = emptyList()): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val wepAps = insecureAps.filter { it.isWep }
        val openAps = insecureAps.filter { it.isOpen }
        val otherInsecure = insecureAps.filter { !it.isWep && !it.isOpen }

        val builder = Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(if (wepAps.isNotEmpty()) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Deja Scan is watching")
            .setContentIntent(openApp(insecureAps.firstOrNull()?.mac))
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)

        if (insecureAps.isNotEmpty()) {
            val alertSummary = buildList {
                if (wepAps.isNotEmpty()) add("${wepAps.size} WEP")
                if (openAps.isNotEmpty()) add("${openAps.size} Open")
                if (otherInsecure.isNotEmpty()) add("${otherInsecure.size} Insecure")
            }.joinToString(", ")
            builder.setContentText("⚠️ Insecure Wi-Fi: $alertSummary • Watching")

            builder.style = Notification.BigTextStyle()
                .setBigContentTitle("Deja Scan is watching")
                .setSummaryText(if (wepAps.isNotEmpty()) "⚠️ WEP Detected" else "Security Advisory")
                .bigText(
                    "Listening for saved devices\n" +
                    "⚠️ Insecure Wi-Fi in range:\n" +
                    insecureAps.take(4).joinToString("\n") { s ->
                        "• ${s.name} [${s.securitySuite.shortLabel}]: ${s.primarySecurityIssue?.summary ?: "Insecure"} (${s.rssi} dBm)"
                    } + if (insecureAps.size > 4) "\n+ ${insecureAps.size - 4} more" else ""
                )
            return builder.build()
        } else {
            builder.setContentText("Listening for saved devices")
            return builder.build()
        }
    }

    private fun updateSecurityNotifications() {
        val now = System.currentTimeMillis()
        val all = SightingBus.sightings.value.values
        val activeInsecure = all.filter {
            it.type == "W" && (now - it.time <= STALE_MS) && it.hasSecurityIssue
        }.sortedWith(compareBy({ it.primarySecurityIssue?.severity?.ordinal ?: 99 }, { -it.rssi }))

        val nm = getSystemService(NotificationManager::class.java) ?: return

        // 1. Passively update the ongoing foreground service notification
        nm.notify(ONGOING_ID, ongoingNotification(activeInsecure))

        // 2. Manage the passive silent security advisory notification
        if (activeInsecure.isNotEmpty()) {
            val wepAps = activeInsecure.filter { it.isWep }
            val top = activeInsecure.first()
            val issue = top.primarySecurityIssue

            val title = if (wepAps.isNotEmpty()) {
                "⚠️ WEP Wi-Fi Detected (${wepAps.size} in range)"
            } else {
                "⚠️ Insecure Wi-Fi Detected: ${top.name}"
            }

            val text = if (wepAps.isNotEmpty()) {
                val first = wepAps.first()
                "AP '${first.name}' (${first.mac}, ${first.rssi} dBm) uses broken WEP encryption."
            } else {
                "AP '${top.name}' has security risk: ${issue?.summary ?: "Insecure"}"
            }

            val builder = Notification.Builder(this, CH_SECURITY)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(top.mac))
                .setAutoCancel(true)

            builder.style = Notification.BigTextStyle()
                .setBigContentTitle(title)
                .setSummaryText(if (wepAps.isNotEmpty()) "Critical Security Risk" else "Security Advisory")
                .bigText(
                    text + "\n" +
                    activeInsecure.take(5).joinToString("\n") { s ->
                        "• ${s.name} [${s.securitySuite.shortLabel}]: ${s.primarySecurityIssue?.summary ?: "Insecure"} (${s.rssi} dBm)"
                    } + if (activeInsecure.size > 5) "\n+ ${activeInsecure.size - 5} more" else ""
                )

            nm.notify(SECURITY_ID, builder.build())
        } else {
            nm.cancel(SECURITY_ID)
        }
    }

    private fun hitNotification(name: String, mac: String, rssi: Int): Notification =
        Notification.Builder(this, CH_HITS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Saved device nearby")
            .setContentText("$name ($mac) - ${MacUtil.proximity(rssi)}, $rssi dBm")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiversRegistered) runCatching { unregisterReceiver(receiver) }
        if (bleActive) runCatching { btAdapter?.bluetoothLeScanner?.stopScan(bleCallback) }
        runCatching { if (btAdapter?.isDiscovering == true) btAdapter?.cancelDiscovery() }
        runCatching { locMgr?.removeUpdates(locListener) }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(SECURITY_ID) }
        SightingBus.phone.value = null
        io.execute { db.close() }
        io.shutdown()
        SightingBus.running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.dejascan.app.STOP"
        private const val CH_ONGOING = "ongoing"
        private const val CH_HITS = "hits"
        private const val CH_SECURITY = "security"
        private const val ONGOING_ID = 1
        private const val SECURITY_ID = 2
        private const val STALE_MS = 5 * 60_000L
        private const val WIFI_INTERVAL_MS = 35_000L
        private const val CLASSIC_PAUSE_MS = 30_000L
        private val WIFI_KINDS = setOf("W")
        private val BT_KINDS = setOf("B", "E")

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScanService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScanService::class.java))
        }
    }
}
