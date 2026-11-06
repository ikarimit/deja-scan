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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dejascan.app.MainActivity
import com.dejascan.app.data.DejaDb
import com.dejascan.app.data.Device
import com.dejascan.app.data.Prefs
import com.dejascan.app.data.Vendors
import com.dejascan.app.scan.promiscuous.PromiscuousBus
import com.dejascan.app.scan.promiscuous.PromiscuousMonitor
import java.util.concurrent.Executors

/**
 * Foreground service that listens for Wi-Fi, BLE, Bluetooth Classic, and (when rooted & enabled)
 * passive 802.11 monitor/promiscuous frames. Notifications are kept strictly minimal: one silent
 * collapsed ongoing indicator (`IMPORTANCE_MIN`) and, only while backgrounded, a single rate-limited
 * silent summary when watched targets appear or move closer.
 */
@SuppressLint("MissingPermission")
class ScanService : Service() {

    private lateinit var db: DejaDb
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val approach = ApproachTracker()
    private val io = Executors.newSingleThreadExecutor()
    private val lastSave = HashMap<String, Long>()
    private var lastHitNotifyMs = 0L
    private var locMgr: LocationManager? = null

    private val promiscMonitor = PromiscuousMonitor { ev ->
        val bssid = ev.apBssid ?: return@PromiscuousMonitor
        // Throttle passive beacon reports unless an SSID was newly unmasked or unseen
        val existing = SightingBus.sightings.value[bssid]
        val now = System.currentTimeMillis()
        if (!ev.unmaskedNow && existing != null && now - existing.time < 2_500L) return@PromiscuousMonitor
        handler.post {
            if (!SightingBus.running.value) return@post
            val adv = buildList {
                if (!ev.apSsid.isNullOrEmpty()) add("SSID: ${ev.apSsid}")
                if (ev.unmaskedNow && !ev.apSsid.isNullOrEmpty()) {
                    add("Unmasked SSID: ${ev.apSsid} (via ${ev.unmaskMethod})")
                }
            }
            val suite = when {
                existing != null && existing.securitySuite != SecuritySuite.UNKNOWN -> existing.securitySuite
                !ev.privacyBit && ev.freqMhz > 0 -> SecuritySuite.OPEN
                else -> SecuritySuite.UNKNOWN
            }
            onHeard(
                rawMac = bssid,
                kinds = WIFI_KINDS,
                rssi = ev.rssi,
                liveName = ev.apSsid.orEmpty(),
                freq = ev.freqMhz,
                adv = adv,
                securitySuite = suite,
            )
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val now = System.currentTimeMillis()
            val cur = SightingBus.phone.value
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
            onHeard(result.device.address, BT_KINDS, result.rssi, result.scanRecord?.deviceName.orEmpty(), 0, bleAdv(result))
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SYNC_PROMISC -> {
                if (SightingBus.running.value) syncPromiscuousMode()
                return START_NOT_STICKY
            }
        }
        createChannels()
        startForeground(ONGOING_ID, ongoingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!SightingBus.running.value) beginScanning() else syncPromiscuousMode()
        return START_NOT_STICKY
    }

    private fun syncPromiscuousMode() {
        if (prefs.promiscuousEnabled && RootDetector.isRooted) {
            promiscMonitor.start(prefs.promiscuousIface, prefs.promiscuousDriverSwitch)
        } else {
            promiscMonitor.stop()
        }
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
        } else {
            SightingBus.message.value = "Location permission missing: Wi-Fi scanning off"
        }

        if (btAdapter?.isEnabled == true && hasBtScan()) {
            try {
                btAdapter?.bluetoothLeScanner?.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                    bleCallback,
                )
                bleActive = true
            } catch (_: SecurityException) {
                SightingBus.message.value = "Bluetooth permission refused"
            }
            startClassicDiscovery()
        } else {
            SightingBus.message.value = SightingBus.message.value.ifEmpty { "Bluetooth off or permission missing" }
        }

        syncPromiscuousMode()
    }

    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        locMgr = lm
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 3000L, 0f, locListener, Looper.getMainLooper())
            }
        }
    }

    private fun startClassicDiscovery() {
        runCatching {
            if (btAdapter?.isDiscovering == false) btAdapter?.startDiscovery()
        }
    }

    private fun readWifiResults() {
        val results = try { wifi?.scanResults } catch (_: SecurityException) { null } ?: return
        val unmaskedMap = PromiscuousBus.snapshot.value.unmasked
        for (r in results) {
            @Suppress("DEPRECATION") val rawSsid = r.SSID.orEmpty()
            val normBssid = MacUtil.normalize(r.BSSID)
            if (normBssid != null) {
                PromiscuousBus.tracker.noteKnownAp(normBssid, rawSsid.isEmpty())
            }
            val unmasked = normBssid?.let(unmaskedMap::get)
            val ssid = rawSsid.ifEmpty { unmasked?.ssid.orEmpty() }

            val sec = WifiSecurity.parse(r.capabilities)
            val channelWidth = r.channelWidth
            val centerFreq0 = r.centerFreq0
            val rawStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) r.wifiStandard else 0
            val wifiStandard = WifiStandardHelper.resolveStandard(rawStandard, r.frequency, channelWidth)
            val stdLabel = WifiStandardHelper.getStandardName(wifiStandard, r.frequency)
            val widthLabel = ChannelWidthHelper.getWidthLabel(channelWidth)
            val band = WifiBand.forFrequency(r.frequency)
            val span = ChannelWidthHelper.getFrequencySpan(r.frequency, channelWidth, centerFreq0)

            val adv = buildList {
                add("SSID: ${ssid.ifEmpty { "(hidden)" }}")
                if (unmasked != null && rawSsid.isEmpty()) {
                    add("Unmasked SSID: ${unmasked.ssid} (via ${unmasked.source})")
                }
                add("Channel: ${channelFor(r.frequency)} • $widthLabel (${r.frequency} MHz, ${band.label})")
                if (stdLabel.isNotEmpty() && stdLabel != "Unknown standard") add("Standard: $stdLabel")
                val cfSuffix = if (centerFreq0 > 0 && centerFreq0 != r.frequency) ", Center: $centerFreq0 MHz" else ""
                add("Bandwidth: $widthLabel (${span.first}–${span.last} MHz$cfSuffix)")
                add("Security: ${sec.suite.label}")
                if (sec.keyManagement.isNotEmpty() && sec.keyManagement != "None") add("Auth: ${sec.keyManagement}")
                if (sec.ciphers.isNotEmpty()) add("Ciphers: ${sec.ciphers.joinToString(", ")}")
                add("Mode: ${sec.mode.label} (${sec.mode.description})")
                if (sec.hasWps) add("WPS: Supported" + (sec.wpsDetails?.let { " ($it)" }.orEmpty()))
                if (sec.isFastTransition) add("802.11r FT: Fast BSS Transition supported")
                if (sec.isPasspoint) add("Passpoint: Hotspot 2.0 supported")
                if (sec.raw.isNotEmpty()) add("Raw Caps: ${sec.raw}")
            }
            onHeard(
                rawMac = r.BSSID,
                kinds = WIFI_KINDS,
                rssi = r.level,
                liveName = ssid,
                freq = r.frequency,
                adv = adv,
                securitySuite = sec.suite,
                channelWidth = channelWidth,
                centerFreq0 = centerFreq0,
                wifiStandard = wifiStandard,
                standardLabel = stdLabel,
                rawCaps = sec.raw,
            )
        }
    }

    private fun onHeard(
        rawMac: String?,
        kinds: Set<String>,
        rssi: Int,
        liveName: String,
        freq: Int,
        adv: List<String>,
        securitySuite: SecuritySuite = SecuritySuite.UNKNOWN,
        channelWidth: Int = -1,
        centerFreq0: Int = 0,
        wifiStandard: Int = 0,
        standardLabel: String = "",
        rawCaps: String = "",
    ) {
        val mac = MacUtil.normalize(rawMac) ?: return
        val matched = db.match(mac, kinds, prefs.filter())
        val inDbDev = matched ?: db.devices(listOf(mac)).firstOrNull()
        val dev = inDbDev ?: Device(
            mac = mac, name = "", type = kinds.first(), lastTime = 0L, lat = 0.0, lon = 0.0,
            randomised = MacUtil.isRandomised(mac), watched = false, freq = freq, caps = rawCaps,
        )
        val now = System.currentTimeMillis()
        SightingBus.report(
            dev = dev,
            liveName = liveName,
            rssi = rssi,
            freq = freq,
            adv = adv,
            now = now,
            watched = matched != null,
            inDatabase = inDbDev != null,
            securitySuite = securitySuite,
            channelWidth = channelWidth,
            centerFreq0 = centerFreq0,
            wifiStandard = wifiStandard,
            standardLabel = standardLabel,
        )
        if (rssi <= -100) return
        val fix = SightingBus.phone.value
        val every = if (matched != null) 5_000L else 30_000L
        if (fix != null && now - fix.time < 15_000 && fix.acc <= 50f && now - (lastSave[mac] ?: 0L) >= every) {
            lastSave[mac] = now
            io.execute { db.addSight(mac, now, fix.lat, fix.lon, fix.acc, rssi) }
        }
        if (matched != null && approach.onReading(mac, rssi, now)) {
            if (!SightingBus.inForeground.value && prefs.notifyHits && now - lastHitNotifyMs >= HIT_COOLDOWN_MS) {
                lastHitNotifyMs = now
                val name = dev.name.ifEmpty { liveName }.ifEmpty { mac }
                val activeWatched = SightingBus.sightings.value.values.count { it.watched && now - it.time <= STALE_MS }
                getSystemService(NotificationManager::class.java)
                    ?.notify(HIT_ID, hitNotification(name, mac, rssi, activeWatched))
            }
        }
    }

    private fun shortUuid(u: String): String =
        if (u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + u.substring(4, 8) else u

    private fun bleAdv(r: ScanResult): List<String> = buildList {
        val rec = r.scanRecord
        rec?.deviceName?.let { add("Name: $it") }
        rec?.serviceUuids?.takeIf { it.isNotEmpty() }?.let { l ->
            add("Services: " + l.joinToString { shortUuid(it.uuid.toString()) })
        }
        rec?.manufacturerSpecificData?.takeIf { it.size() > 0 }?.let { m ->
            add("Manufacturer: " + (0 until m.size()).joinToString {
                val id = m.keyAt(it)
                (Vendors.forCompanyId(id) ?: "unknown") + " (0x%04X)".format(id)
            })
        }
        if (rec != null && rec.txPowerLevel != Int.MIN_VALUE) add("TX power: ${rec.txPowerLevel} dBm")
        add("Connectable: ${r.isConnectable}")
    }

    private fun classicAdv(d: BluetoothDevice): List<String> = try {
        d.bluetoothClass?.let { listOf("Class: 0x%06X".format(it.deviceClass)) }.orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun safeName(d: BluetoothDevice): String = try { d.name.orEmpty() } catch (_: SecurityException) { "" }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasBtScan() =
        if (Build.VERSION.SDK_INT >= 31) has(Manifest.permission.BLUETOOTH_SCAN)
        else has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel("security")
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Active scan", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Minimal status while radio scanning is active"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_HITS, "Watched device summary", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Quiet background summary when watched devices appear nearby"
                setShowBadge(false)
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
        return PendingIntent.getActivity(
            this, selectMac?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ongoingNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Deja Scan active")
            .setContentText("Passive wireless scan running")
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun hitNotification(name: String, mac: String, rssi: Int, totalWatched: Int): Notification {
        val suffix = if (totalWatched > 1) " (+${totalWatched - 1} more in range)" else ""
        return Notification.Builder(this, CH_HITS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Watched device nearby")
            .setContentText("$name • $rssi dBm$suffix")
            .setContentIntent(openApp(mac))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        promiscMonitor.release()
        if (receiversRegistered) runCatching { unregisterReceiver(receiver) }
        if (bleActive) runCatching { btAdapter?.bluetoothLeScanner?.stopScan(bleCallback) }
        runCatching { if (btAdapter?.isDiscovering == true) btAdapter?.cancelDiscovery() }
        runCatching { locMgr?.removeUpdates(locListener) }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(HIT_ID) }
        SightingBus.phone.value = null
        io.execute { db.close() }
        io.shutdown()
        SightingBus.running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.dejascan.app.STOP"
        const val ACTION_SYNC_PROMISC = "com.dejascan.app.SYNC_PROMISC"
        private const val CH_ONGOING = "ongoing_min"
        private const val CH_HITS = "hits_quiet"
        private const val ONGOING_ID = 1
        private const val HIT_ID = 2
        private const val HIT_COOLDOWN_MS = 30_000L
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

        fun syncPromiscuous(context: Context) {
            if (SightingBus.running.value) {
                context.startService(Intent(context, ScanService::class.java).setAction(ACTION_SYNC_PROMISC))
            }
        }
    }
}
