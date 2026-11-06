package com.dejascan.app.scan.promiscuous

import com.dejascan.app.scan.RootDetector
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the root (`su`) passive 802.11 monitor/promiscuous capture lifecycle on Android.
 *
 * Strictly passive by design:
 * - Virtual monitor interfaces are created with `flags none` (zero ACK/active transmissions).
 * - Never performs packet injection, deauthentication, or active probing.
 * - Restores any modified interface/driver state on [stop].
 */
class PromiscuousMonitor(
    private val tracker: PromiscuousTracker = PromiscuousBus.tracker,
    private val isDeviceRooted: () -> Boolean = { RootDetector.isRooted },
    private val rootShell: (String) -> ShellResult = ::execSuCommand,
    private val onIngestEvent: (PromiscuousTracker.IngestEvent) -> Unit = {},
) {
    data class ShellResult(val exitCode: Int, val stdout: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private val running = AtomicBoolean(false)
    @Volatile private var captureProcess: Process? = null
    @Volatile private var teardownCommand: String? = null
    private var worker: Thread? = null
    private val controlIo = Executors.newSingleThreadExecutor()

    /** Probes root shell, wireless interfaces, `tcpdump`/`iw`/`ip`, and Qualcomm `con_mode` support. */
    fun probeCapabilities(): PromiscuousCapabilities {
        if (!isDeviceRooted()) {
            val caps = PromiscuousCapabilities(summary = "Device is not rooted (standard Android ScanResult mode only)")
            tracker.updateStatus(MonitorState.UNSUPPORTED, "", caps.summary, caps)
            return caps
        }

        val idRes = rootShell("id")
        if (!idRes.ok || !idRes.stdout.contains("uid=0")) {
            val caps = PromiscuousCapabilities(summary = "Root access denied or unavailable via su")
            tracker.updateStatus(MonitorState.UNSUPPORTED, "", caps.summary, caps)
            return caps
        }

        val probeScript = buildString {
            append("for n in /sys/class/net/*; do ")
            append("[ -d \"\$n/wireless\" ] || [ -d \"\$n/phy80211\" ] || [ \"\$(cat \"\$n/type\" 2>/dev/null)\" = \"803\" ] && echo \"IFACE:\$(basename \"\$n\")\"; ")
            append("done; ")
            append("for d in /system/bin /system/xbin /vendor/bin /sbin /data/local/tmp /data/data/com.termux/files/usr/bin; do ")
            append("for b in tcpdump iw ip; do [ -x \"\$d/\$b\" ] && echo \"BIN:\$b:\$d/\$b\"; done; ")
            append("done; ")
            append("for c in /sys/module/wlan/parameters/con_mode /sys/module/qca_cld3_wlan/parameters/con_mode; do ")
            append("[ -e \"\$c\" ] && echo \"CONMODE:\$c\"; ")
            append("done")
        }
        val out = rootShell(probeScript).stdout.lineSequence().map { it.trim() }.toList()
        val ifaces = out.filter { it.startsWith("IFACE:") }.map { it.removePrefix("IFACE:") }.distinct()
        val bins = HashMap<String, String>()
        for (line in out) {
            if (line.startsWith("BIN:")) {
                val parts = line.split(':', limit = 3)
                if (parts.size == 3 && parts[1] !in bins) bins[parts[1]] = parts[2]
            }
        }
        val conMode = out.firstOrNull { it.startsWith("CONMODE:") }?.removePrefix("CONMODE:")

        val tcpdump = bins["tcpdump"]
        val iw = bins["iw"]
        val ip = bins["ip"]
        val summary = when {
            ifaces.isEmpty() -> "Root granted, but no wireless netdev found"
            tcpdump == null -> "Root granted (${ifaces.joinToString()}), install tcpdump for live 802.11 frame capture"
            iw != null || conMode != null -> "Ready on ${ifaces.joinToString()} (802.11 monitor + tcpdump)"
            else -> "Ready on ${ifaces.joinToString()} (promiscuous link capture)"
        }
        val caps = PromiscuousCapabilities(
            rootGranted = true,
            wirelessInterfaces = ifaces,
            tcpdumpPath = tcpdump,
            iwPath = iw,
            ipPath = ip,
            qcomConModePath = conMode,
            summary = summary,
        )
        if (!running.get()) {
            val nextState = if (caps.canCaptureDot11) MonitorState.IDLE else MonitorState.UNSUPPORTED
            tracker.updateStatus(nextState, "", summary, caps)
        }
        return caps
    }

    fun start(preferredIface: String = "auto", allowHardwareMonitorSwitch: Boolean = false) {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            try {
                tracker.updateStatus(MonitorState.PROBING, detail = "Probing root & wireless driver capabilities...")
                val caps = probeCapabilities()
                if (!caps.canCaptureDot11) {
                    running.set(false)
                    return@Thread
                }
                val setup = prepareInterface(caps, preferredIface, allowHardwareMonitorSwitch)
                if (setup == null) {
                    tracker.updateStatus(MonitorState.ERROR, "", "Could not initialize monitor or promiscuous interface", caps)
                    running.set(false)
                    return@Thread
                }
                teardownCommand = setup.teardownCmd
                tracker.updateStatus(setup.mode, setup.captureIface, "Listening passively on ${setup.captureIface}", caps)

                val tcpdump = caps.tcpdumpPath!!
                val cmd = "$tcpdump -i ${sanitizeIface(setup.captureIface)} -U -s 256 -w - 2>/dev/null"
                val proc = ProcessBuilder("su", "-c", cmd).start()
                captureProcess = proc
                proc.inputStream.use { stream ->
                    Dot11FrameParser.readPcapStream(stream, shouldContinue = { running.get() }) { frame ->
                        tracker.ingest(frame)?.let(onIngestEvent)
                    }
                }
                proc.waitFor()
            } catch (e: Exception) {
                if (running.get()) {
                    tracker.updateStatus(MonitorState.ERROR, detail = "Capture stopped: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                cleanupCapture()
            }
        }, "deja-promisc").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        controlIo.execute { cleanupCapture() }
    }

    fun release() {
        stop()
        controlIo.shutdown()
    }

    @Synchronized
    private fun cleanupCapture() {
        running.set(false)
        captureProcess?.let { proc ->
            runCatching { proc.destroy() }
            captureProcess = null
        }
        teardownCommand?.let { cmd ->
            teardownCommand = null
            runCatching { rootShell(cmd) }
        }
        if (tracker.snapshot.value.state.isActive || tracker.snapshot.value.state == MonitorState.PROBING) {
            tracker.updateStatus(MonitorState.IDLE, "", "Passive 802.11 capture stopped")
        }
    }

    internal data class InterfaceSetup(
        val captureIface: String,
        val mode: MonitorState,
        val teardownCmd: String?,
    )

    internal fun prepareInterface(
        caps: PromiscuousCapabilities,
        preferred: String,
        allowDriverSwitch: Boolean,
    ): InterfaceSetup? {
        val ifaces = caps.wirelessInterfaces
        val chosen = when {
            preferred != "auto" && preferred in ifaces -> preferred
            else -> ifaces.firstOrNull { it.contains("mon") }
                ?: ifaces.firstOrNull { it.startsWith("wlan") && it != "wlan0" } // Prefer external OTG radio over primary wlan0
                ?: ifaces.firstOrNull { it == "wlan0" }
                ?: ifaces.firstOrNull()
        } ?: return null

        val safeChosen = sanitizeIface(chosen)
        val ip = caps.ipPath ?: "ip"
        val iw = caps.iwPath

        // 1. Pre-existing radiotap monitor netdev (ARPHRD_IEEE80211_RADIOTAP = 803)
        val devType = rootShell("cat /sys/class/net/$safeChosen/type 2>/dev/null").stdout.trim()
        if (devType == "803" || devType == "801") {
            rootShell("$ip link set $safeChosen up")
            return InterfaceSetup(safeChosen, MonitorState.ACTIVE_MONITOR, teardownCmd = null)
        }

        // 2. Concurrent passive virtual monitor VIF (leaves primary wlan0 station mode intact)
        if (iw != null) {
            val monIface = "dejamon0"
            val createVif = "$iw dev $safeChosen interface add $monIface type monitor flags none && $ip link set $monIface up"
            if (rootShell(createVif).ok) {
                return InterfaceSetup(
                    captureIface = monIface,
                    mode = MonitorState.ACTIVE_MONITOR,
                    teardownCmd = "$ip link set $monIface down 2>/dev/null; $iw dev $monIface del 2>/dev/null",
                )
            }
        }

        // 3. Hardware monitor switch (secondary OTG adapter via iw, or Qualcomm con_mode=4 when explicitly opted in)
        if (iw != null && safeChosen != "wlan0") {
            val switchMon = "$ip link set $safeChosen down && $iw dev $safeChosen set monitor none && $ip link set $safeChosen up"
            if (rootShell(switchMon).ok) {
                return InterfaceSetup(
                    captureIface = safeChosen,
                    mode = MonitorState.ACTIVE_MONITOR,
                    teardownCmd = "$ip link set $safeChosen down; $iw dev $safeChosen set type managed; $ip link set $safeChosen up",
                )
            }
        }
        val conMode = caps.qcomConModePath
        if (allowDriverSwitch && conMode != null && safeChosen == "wlan0") {
            val enableQcom = "$ip link set wlan0 down && echo 4 > $conMode && $ip link set wlan0 up"
            if (rootShell(enableQcom).ok) {
                return InterfaceSetup(
                    captureIface = "wlan0",
                    mode = MonitorState.ACTIVE_MONITOR,
                    teardownCmd = "$ip link set wlan0 down; echo 0 > $conMode; svc wifi enable 2>/dev/null || $ip link set wlan0 up",
                )
            }
        }

        // 4. Non-destructive promiscuous fallback
        if (rootShell("$ip link set $safeChosen promisc on").ok) {
            return InterfaceSetup(
                captureIface = safeChosen,
                mode = MonitorState.ACTIVE_PROMISC,
                teardownCmd = "$ip link set $safeChosen promisc off 2>/dev/null",
            )
        }
        return null
    }

    companion object {
        private val SAFE_IFACE = Regex("^[a-zA-Z0-9._-]{1,16}$")

        fun sanitizeIface(raw: String): String =
            if (SAFE_IFACE.matches(raw)) raw else "wlan0"

        fun execSuCommand(command: String): ShellResult = try {
            val proc = ProcessBuilder("su", "-c", command)
                .directory(File("/"))
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            ShellResult(code, out)
        } catch (_: Throwable) {
            ShellResult(-1, "")
        }
    }
}
