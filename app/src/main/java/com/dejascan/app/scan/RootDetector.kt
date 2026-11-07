package com.dejascan.app.scan

import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

object RootDetector {
    @Volatile
    private var cachedRooted: Boolean? = null

    /** Last known root state without performing any disk I/O or spawning a process (safe on main thread). */
    fun peekCached(): Boolean = cachedRooted ?: false

    /** Re-evaluates root status (performs file/process checks; call off the main thread) and caches the result. */
    fun refresh(): Boolean = checkRoot().also { cachedRooted = it }

    /** Cached root check; evaluates on first call if not yet refreshed. Call from a background thread. */
    val isRooted: Boolean
        get() = cachedRooted ?: refresh()

    val KNOWN_ROOT_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/system/xbin/daemonsu",
        "/system/etc/init.d/99SuperSUDaemon",
        "/system/bin/.ext/.su",
        "/data/adb/magisk",
        "/data/adb/ksu",
        "/data/adb/ap",
    )

    fun checkRoot(
        buildTags: String? = Build.TAGS,
        pathEnv: String? = try { System.getenv("PATH") } catch (_: Throwable) { null },
        fileChecker: (String) -> Boolean = { path ->
            try {
                File(path).exists()
            } catch (_: Throwable) {
                false
            }
        },
        commandChecker: (String) -> Boolean = { cmd ->
            try {
                val process = Runtime.getRuntime().exec(arrayOf("which", cmd))
                val line = process.inputStream.bufferedReader().use { it.readLine() }
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                !line.isNullOrBlank()
            } catch (_: Throwable) {
                false
            }
        },
    ): Boolean {
        return hasTestKeys(buildTags) ||
                hasSuBinary(fileChecker) ||
                hasSuInPath(pathEnv, fileChecker) ||
                canExecuteSu(commandChecker)
    }

    fun hasTestKeys(tags: String?): Boolean =
        (tags != null) && tags.contains("test-keys")

    fun hasSuBinary(fileChecker: (String) -> Boolean): Boolean =
        KNOWN_ROOT_PATHS.any { fileChecker(it) }

    fun hasSuInPath(pathEnv: String?, fileChecker: (String) -> Boolean): Boolean {
        if (pathEnv.isNullOrBlank()) return false
        return pathEnv.splitToSequence(':')
            .filter { it.isNotBlank() }
            .any { dir -> fileChecker("$dir/su") }
    }

    fun canExecuteSu(commandChecker: (String) -> Boolean): Boolean =
        commandChecker("su")
}
