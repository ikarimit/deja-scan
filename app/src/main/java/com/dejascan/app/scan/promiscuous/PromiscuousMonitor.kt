package com.dejascan.app.scan.promiscuous

import com.dejascan.app.data.Prefs
import com.dejascan.app.scan.RootDetector
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class RestoreKind {
    VIF,
    IW_MANAGED,
    QCOM_CONMODE,
    PROMISC_OFF,
}

data class PendingRestore(
    val kind: RestoreKind,
    val iface: String,
    val conModePath: String = "",
)

/** Persistence contract for crash-safe restoration of modified wireless interface/driver state. */
interface RestoreStateStore {
    fun load(): PendingRestore?
    fun save(state: PendingRestore)
    fun clear()
}

class InMemoryRestoreStateStore : RestoreStateStore {
    @Volatile private var pending: PendingRestore? = null
    override fun load(): PendingRestore? = pending
    override fun save(state: PendingRestore) { pending = state }
    override fun clear() { pending = null }
}

class PrefsRestoreStateStore(private val prefs: Prefs) : RestoreStateStore {
    override fun load(): PendingRestore? {
        if (!prefs.promiscRestoreNeeded) return null
        val kind = runCatching { RestoreKind.valueOf(prefs.promiscRestoreKind) }.getOrNull() ?: return null
        val iface = PromiscuousMonitor.sanitizeIface(prefs.promiscRestoreIface)
        val conMode = prefs.promiscRestoreConModePath.takeIf { it in PromiscuousMonitor.ALLOWED_CONMODE_PATHS }.orEmpty()
        return PendingRestore(kind, iface, conMode)
    }

    override fun save(state: PendingRestore) {
        prefs.savePromiscRestore(
            kind = state.kind.name,
            iface = PromiscuousMonitor.sanitizeIface(state.iface),
            conModePath = state.conModePath.takeIf { it in PromiscuousMonitor.ALLOWED_CONMODE_PATHS }.orEmpty(),
        )
    }

    override fun clear() {
        prefs.clearPromiscRestore()
    }
}

/**
 * Manages the root (`su`) passive 802.11 monitor/promiscuous capture lifecycle on Android.
 *
 * Strictly passive & hardened by design:
 * - Searches ONLY read-only, root-owned system folders ([TRUSTED_BIN_DIRS]) for `tcpdump`, `iw`, and `ip`.
 * - Persists a synchronous restore record via [RestoreStateStore] BEFORE altering any interface or `con_mode`,
 *   so an app crash or process kill automatically restores Wi-Fi on the next start.
 * - Virtual monitor interfaces are created with `flags none` (zero ACK/active transmissions).
 * - Never performs packet injection, deauthentication, or active probing.
 */
class PromiscuousMonitor(
    private val tracker: PromiscuousTracker = PromiscuousBus.tracker,
    private val isDeviceRooted: () -> Boolean = { RootDetector.isRooted },
    private val rootShell: (String) -> ShellResult = ::execSuCommand,
    private val restoreStore: RestoreStateStore = InMemoryRestoreStateStore(),
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

    /** Probes root shell, wireless interfaces, `tcpdump`/`iw`/`ip` in trusted system paths, and Qualcomm `con_mode`. */
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

        val trustedDirsExpr = TRUSTED_BIN_DIRS.joinToString(" ")
        val conModeExpr = ALLOWED_CONMODE_PATHS.joinToString(" ")
        val probeScript = buildString {
            append("for n in /sys/class/net/*; do ")
            append("[ -d \"\$n/wireless\" ] || [ -d \"\$n/phy80211\" ] || [ \"\$(cat \"\$n/type\" 2>/dev/null)\" = \"803\" ] && echo \"IFACE:\$(basename \"\$n\")\"; ")
            append("done; ")
            append("for d in $trustedDirsExpr; do ")
            append("for b in tcpdump iw ip; do [ -x \"\$d/\$b\" ] && echo \"BIN:\$b:\$d/\$b\"; done; ")
            append("done; ")
            append("for c in $conModeExpr; do ")
            append("[ -e \"\$c\" ] && echo \"CONMODE:\$c\"; ")
            append("done")
        }
        val out = rootShell(probeScript).stdout.lineSequence().map { it.trim() }.toList()
        val ifaces = out.filter { it.startsWith("IFACE:") }.map { it.removePrefix("IFACE:") }
            .filter { SAFE_IFACE.matches(it) }.distinct()
        val bins = HashMap<String, String>()
        for (line in out) {
            if (line.startsWith("BIN:")) {
                val parts = line.split(':', limit = 3)
                if (parts.size == 3 && parts[1] !in bins && isTrustedBinaryPath(parts[2], parts[1])) {
                    bins[parts[1]] = parts[2]
                }
            }
        }
        val conMode = out.firstOrNull { it.startsWith("CONMODE:") }
            ?.removePrefix("CONMODE:")
            ?.takeIf { it in ALLOWED_CONMODE_PATHS }

        val tcpdump = bins["tcpdump"]
        val iw = bins["iw"]
        val ip = bins["ip"]
        val summary = when {
            ifaces.isEmpty() -> "Root granted, but no wireless netdev found"
            tcpdump == null -> "Root granted (${ifaces.joinToString()}), no system tcpdump binary in trusted paths"
            ip == null -> "Root granted (${ifaces.joinToString()}), no system ip binary in trusted paths"
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
            val nextState = if (caps.canCaptureDot11 && ip != null) MonitorState.IDLE else MonitorState.UNSUPPORTED
            tracker.updateStatus(nextState, "", summary, caps)
        }
        return caps
    }

    /**
     * Checks if a previous session crashed or was killed while holding monitor/promiscuous mode,
     * and restores the interface/driver state before clearing the persisted flag.
     */
    fun restorePendingIfNeeded(): Boolean = synchronized(RESTORE_LOCK) {
        val pending = restoreStore.load() ?: return false
        if (!isDeviceRooted()) return false
        val idRes = rootShell("id")
        if (!idRes.ok || !idRes.stdout.contains("uid=0")) return false

        val trustedDirsExpr = TRUSTED_BIN_DIRS.joinToString(" ")
        val binOut = rootShell(
            "for d in $trustedDirsExpr; do for b in ip iw; do [ -x \"\$d/\$b\" ] && echo \"BIN:\$b:\$d/\$b\"; done; done"
        ).stdout.lineSequence().map { it.trim() }.toList()
        var ipPath: String? = null
        var iwPath: String? = null
        for (line in binOut) {
            if (line.startsWith("BIN:")) {
                val p = line.split(':', limit = 3)
                if (p.size == 3 && isTrustedBinaryPath(p[2], p[1])) {
                    if (p[1] == "ip" && ipPath == null) ipPath = p[2]
                    if (p[1] == "iw" && iwPath == null) iwPath = p[2]
                }
            }
        }
        val cmd = buildRestoreCommand(pending, ipPath, iwPath) ?: return false
        val res = rootShell(cmd)
        if (res.ok) {
            restoreStore.clear()
        }
        res.ok
    }

    fun start(preferredIface: String = "auto", allowHardwareMonitorSwitch: Boolean = false) {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            try {
                restorePendingIfNeeded()
                tracker.updateStatus(MonitorState.PROBING, detail = "Probing root & wireless driver capabilities...")
                val caps = probeCapabilities()
                val tcpdump = caps.tcpdumpPath?.takeIf { isTrustedBinaryPath(it, "tcpdump") }
                if (!caps.canCaptureDot11 || tcpdump == null || caps.ipPath == null) {
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

                val cmd = "$tcpdump -i ${sanitizeIface(setup.captureIface)} -U -s 256 -w - 2>/dev/null"
                val proc = ProcessBuilder("su", "-c", cmd).apply {
                    directory(File("/"))
                    environment()["PATH"] = TRUSTED_PATH_ENV
                }.start()
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
        val cmd = teardownCommand
        teardownCommand = null
        if (cmd != null) {
            val res = runCatching { rootShell(cmd) }.getOrNull()
            if (res?.ok == true) {
                restoreStore.clear()
            } else {
                runCatching { restorePendingIfNeeded() }
            }
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
    ): InterfaceSetup? = synchronized(RESTORE_LOCK) {
        val ifaces = caps.wirelessInterfaces
        val chosen = when {
            preferred != "auto" && preferred in ifaces -> preferred
            else -> ifaces.firstOrNull { it.contains("mon") }
                ?: ifaces.firstOrNull { it.startsWith("wlan") && it != "wlan0" } // Prefer external OTG radio over primary wlan0
                ?: ifaces.firstOrNull { it == "wlan0" }
                ?: ifaces.firstOrNull()
        } ?: return null

        val safeChosen = sanitizeIface(chosen)
        val ip = caps.ipPath?.takeIf { isTrustedBinaryPath(it, "ip") } ?: return null
        val iw = caps.iwPath?.takeIf { isTrustedBinaryPath(it, "iw") }

        // 1. Pre-existing radiotap monitor netdev (ARPHRD_IEEE80211_RADIOTAP = 803)
        val devType = rootShell("cat /sys/class/net/$safeChosen/type 2>/dev/null").stdout.trim()
        if (devType == "803" || devType == "801") {
            rootShell("$ip link set $safeChosen up")
            return InterfaceSetup(safeChosen, MonitorState.ACTIVE_MONITOR, teardownCmd = null)
        }

        // 2. Concurrent passive virtual monitor VIF (leaves primary wlan0 station mode intact)
        if (iw != null) {
            val monIface = "dejamon0"
            val pending = PendingRestore(RestoreKind.VIF, monIface)
            val teardown = buildRestoreCommand(pending, ip, iw)!!
            restoreStore.save(pending)
            val createVif = "$iw dev $safeChosen interface add $monIface type monitor flags none && $ip link set $monIface up"
            if (rootShell(createVif).ok) {
                return InterfaceSetup(captureIface = monIface, mode = MonitorState.ACTIVE_MONITOR, teardownCmd = teardown)
            }
            rootShell(teardown)
            restoreStore.clear()
        }

        // 3. Hardware monitor switch (secondary OTG adapter via iw, or Qualcomm con_mode=4 when explicitly opted in)
        if (iw != null && safeChosen != "wlan0") {
            val pending = PendingRestore(RestoreKind.IW_MANAGED, safeChosen)
            val teardown = buildRestoreCommand(pending, ip, iw)!!
            restoreStore.save(pending)
            val switchMon = "$ip link set $safeChosen down && $iw dev $safeChosen set monitor none && $ip link set $safeChosen up"
            if (rootShell(switchMon).ok) {
                return InterfaceSetup(captureIface = safeChosen, mode = MonitorState.ACTIVE_MONITOR, teardownCmd = teardown)
            }
            rootShell(teardown)
            restoreStore.clear()
        }

        val conMode = caps.qcomConModePath?.takeIf { it in ALLOWED_CONMODE_PATHS }
        if (allowDriverSwitch && conMode != null && safeChosen == "wlan0") {
            val pending = PendingRestore(RestoreKind.QCOM_CONMODE, "wlan0", conMode)
            val teardown = buildRestoreCommand(pending, ip, iw)!!
            restoreStore.save(pending)
            val enableQcom = "$ip link set wlan0 down && echo 4 > $conMode && $ip link set wlan0 up"
            if (rootShell(enableQcom).ok) {
                return InterfaceSetup(captureIface = "wlan0", mode = MonitorState.ACTIVE_MONITOR, teardownCmd = teardown)
            }
            rootShell(teardown)
            restoreStore.clear()
        }

        // 4. Non-destructive promiscuous fallback
        val promiscPending = PendingRestore(RestoreKind.PROMISC_OFF, safeChosen)
        val promiscTeardown = buildRestoreCommand(promiscPending, ip, iw)!!
        restoreStore.save(promiscPending)
        if (rootShell("$ip link set $safeChosen promisc on").ok) {
            return InterfaceSetup(captureIface = safeChosen, mode = MonitorState.ACTIVE_PROMISC, teardownCmd = promiscTeardown)
        }
        restoreStore.clear()
        return null
    }

    companion object {
        private val RESTORE_LOCK = Any()
        private val SAFE_IFACE = Regex("^[a-zA-Z0-9._-]{1,16}$")

        /** Only root-owned, read-only system partitions are searched or executed as root. */
        val TRUSTED_BIN_DIRS: List<String> = listOf(
            "/system/bin",
            "/system/xbin",
            "/system_ext/bin",
            "/product/bin",
            "/vendor/bin",
            "/sbin",
        )

        val ALLOWED_CONMODE_PATHS: Set<String> = setOf(
            "/sys/module/wlan/parameters/con_mode",
            "/sys/module/qca_cld3_wlan/parameters/con_mode",
        )

        private val TRUSTED_PATH_ENV: String = TRUSTED_BIN_DIRS.joinToString(":")

        fun sanitizeIface(raw: String): String =
            if (SAFE_IFACE.matches(raw)) raw else "wlan0"

        fun isTrustedBinaryPath(path: String?, expectedName: String): Boolean {
            if (path.isNullOrEmpty() || path.contains("..")) return false
            return TRUSTED_BIN_DIRS.any { dir -> path == "$dir/$expectedName" }
        }

        fun buildRestoreCommand(pending: PendingRestore, ipPath: String?, iwPath: String?): String? {
            val ip = ipPath?.takeIf { isTrustedBinaryPath(it, "ip") } ?: return null
            val iface = sanitizeIface(pending.iface)
            return when (pending.kind) {
                RestoreKind.VIF -> {
                    val iw = iwPath?.takeIf { isTrustedBinaryPath(it, "iw") } ?: return null
                    "$ip link set $iface down 2>/dev/null; $iw dev $iface del 2>/dev/null"
                }
                RestoreKind.IW_MANAGED -> {
                    val iw = iwPath?.takeIf { isTrustedBinaryPath(it, "iw") } ?: return null
                    "$ip link set $iface down; $iw dev $iface set type managed; $ip link set $iface up"
                }
                RestoreKind.QCOM_CONMODE -> {
                    val conMode = pending.conModePath.takeIf { it in ALLOWED_CONMODE_PATHS } ?: return null
                    "$ip link set wlan0 down; echo 0 > $conMode; /system/bin/svc wifi enable 2>/dev/null || $ip link set wlan0 up"
                }
                RestoreKind.PROMISC_OFF -> {
                    "$ip link set $iface promisc off 2>/dev/null"
                }
            }
        }

        /** Restores any crashed monitor/promiscuous state asynchronously at process start. */
        fun restoreOnStartupIfNeeded(prefs: Prefs) {
            if (!prefs.promiscRestoreNeeded) return
            Thread({
                runCatching {
                    PromiscuousMonitor(restoreStore = PrefsRestoreStateStore(prefs)).restorePendingIfNeeded()
                }
            }, "deja-promisc-restore").apply {
                isDaemon = true
                start()
            }
        }

        fun execSuCommand(command: String): ShellResult = try {
            val proc = ProcessBuilder("su", "-c", command)
                .directory(File("/"))
                .apply { environment()["PATH"] = TRUSTED_PATH_ENV }
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
