package com.dejascan.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.ScanService
import com.dejascan.app.scan.SightingBus
import com.dejascan.app.ui.AppViewModel
import com.dejascan.app.ui.DejaApp
import com.dejascan.app.ui.DejaTheme
import com.dejascan.app.ui.isDark

class MainActivity : ComponentActivity() {
    private var onSelectMac: ((String) -> Unit)? = null

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onStart() {
        super.onStart()
        SightingBus.inForeground.value = true
        // Clear any background hit notification once the user opens the app
        getSystemService(NotificationManager::class.java)?.cancel(2)
    }

    override fun onStop() {
        SightingBus.inForeground.value = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val mac = MacUtil.normalize(intent.getStringExtra(EXTRA_SELECT_MAC))
        if (mac != null) {
            onSelectMac?.invoke(mac)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val initialMac = MacUtil.normalize(intent?.getStringExtra(EXTRA_SELECT_MAC))
        setContent {
            val vm: AppViewModel = viewModel()
            val dark = vm.themeMode.isDark()

            DisposableEffect(vm) {
                onSelectMac = { vm.select(it) }
                if (initialMac != null) {
                    vm.select(initialMac)
                }
                onDispose { onSelectMac = null }
            }

            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            DejaTheme(dark) {
                var pendingStartScan by remember { mutableStateOf(false) }
                var bgAllowed by remember {
                    mutableStateOf(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            bgAllowed = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val bgLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { isGranted ->
                    bgAllowed = isGranted
                    if (!isGranted && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                        openAppSettings()
                    }
                }

                fun isPostNotifications(perm: String): Boolean =
                    Build.VERSION.SDK_INT >= 33 && perm == Manifest.permission.POST_NOTIFICATIONS

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    val denied = result.filterValues { !it }.keys
                    // Allow scanning even if optional POST_NOTIFICATIONS was declined
                    val criticalDenied = denied.filterNot(::isPostNotifications)
                    if (criticalDenied.isEmpty()) {
                        if (pendingStartScan) {
                            pendingStartScan = false
                            ScanService.start(this@MainActivity)
                        }
                    } else {
                        pendingStartScan = false
                        val deniedNames = criticalDenied.map { it.substringAfterLast('.') }
                        val permanentlyDenied = criticalDenied.any { !shouldShowRequestPermissionRationale(it) }
                        if (permanentlyDenied) {
                            toast("Grant ${deniedNames.joinToString(", ")} in Settings")
                            openAppSettings()
                        } else {
                            toast("Required: ${deniedNames.joinToString(", ")}")
                        }
                    }
                }

                fun requiredScanPermissions(): List<String> {
                    val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 31) {
                        p += Manifest.permission.BLUETOOTH_SCAN
                        p += Manifest.permission.BLUETOOTH_CONNECT
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        p += Manifest.permission.POST_NOTIFICATIONS
                    }
                    return p
                }

                val mapLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris -> vm.importMapFiles(uris) }
                val pickLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> if (uri != null) vm.linkFile(uri) }
                val saveMergedLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri -> if (uri != null) vm.exportMergedToUri(uri) }
                val saveBackupLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri -> if (uri != null) vm.exportBackupToUri(uri) }

                DejaApp(
                    vm = vm,
                    onToggleScan = { start ->
                        if (!start) {
                            ScanService.stop(this@MainActivity)
                        } else {
                            val missing = requiredScanPermissions().filter { !granted(it) }
                            val criticalMissing = missing.filterNot(::isPostNotifications)
                            if (missing.isEmpty() || (criticalMissing.isEmpty() && SightingBus.running.value)) {
                                ScanService.start(this@MainActivity)
                            } else {
                                pendingStartScan = true
                                permLauncher.launch(missing.toTypedArray())
                            }
                        }
                    },
                    hasBackgroundPermission = bgAllowed,
                    onRequestBackground = {
                        if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                            bgAllowed = true
                            toast("Background scanning enabled")
                        } else {
                            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    },
                    onPickFile = { pickLauncher.launch(arrayOf("*/*")) },
                    onPickMap = { mapLauncher.launch(arrayOf("*/*")) },
                    onExportMerged = { saveMergedLauncher.launch(vm.suggestedMergedFileName()) },
                    onExportBackup = { saveBackupLauncher.launch("wigle-pre-merge-backup.sqlite") },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SELECT_MAC = "com.dejascan.app.SELECT_MAC"
    }
}
