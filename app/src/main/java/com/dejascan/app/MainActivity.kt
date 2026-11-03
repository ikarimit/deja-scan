package com.dejascan.app

import android.Manifest
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dejascan.app.scan.ScanService
import com.dejascan.app.ui.AppViewModel
import com.dejascan.app.ui.DejaApp
import com.dejascan.app.ui.DejaTheme
import com.dejascan.app.ui.isDark

class MainActivity : ComponentActivity() {
    private var onSelectMac: ((String) -> Unit)? = null

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Android stops showing the permission dialog after it was refused, so send the user to the settings page. */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val mac = intent.getStringExtra(EXTRA_SELECT_MAC)
        if (!mac.isNullOrEmpty()) {
            onSelectMac?.invoke(mac)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val initialMac = intent?.getStringExtra(EXTRA_SELECT_MAC)
        setContent {
            val vm: AppViewModel = viewModel()
            val dark = vm.themeMode.isDark()

            DisposableEffect(vm) {
                onSelectMac = { vm.select(it) }
                if (!initialMac.isNullOrEmpty()) {
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
                var askedBgOnce by remember { mutableStateOf(false) }
                var bgAllowed by remember {
                    mutableStateOf(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }

                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
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
                    if (isGranted) toast("Background location allowed: scanning continues with screen off")
                    else toast("Background location denied: scanning active while app is in foreground")
                }

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    val denied = result.filterValues { !it }.keys
                    if (denied.isEmpty()) {
                        if (pendingStartScan) {
                            pendingStartScan = false
                            ScanService.start(this@MainActivity)
                            // Prompt sequentially for background location if not yet allowed
                            if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && !askedBgOnce) {
                                askedBgOnce = true
                                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                    } else {
                        pendingStartScan = false
                        val deniedNames = denied.map { it.substringAfterLast('.') }
                        val permanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
                        if (permanentlyDenied) {
                            toast("Required permissions denied (${deniedNames.joinToString(", ")}). Opening App Settings...")
                            openAppSettings()
                        } else {
                            toast("Scanning requires permissions: ${deniedNames.joinToString(", ")}")
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

                DejaApp(
                    vm = vm,
                    onToggleScan = { start ->
                        if (!start) {
                            ScanService.stop(this@MainActivity)
                        } else {
                            // Request permissions at the exact time of needing them to scan
                            val missing = requiredScanPermissions().filter { !granted(it) }
                            if (missing.isEmpty()) {
                                ScanService.start(this@MainActivity)
                                if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && !askedBgOnce) {
                                    askedBgOnce = true
                                    bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
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
                            toast("Background location is already allowed")
                        } else {
                            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    },
                    onPickFile = { pickLauncher.launch(arrayOf("*/*")) },
                    onPickMap = { mapLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SELECT_MAC = "com.dejascan.app.SELECT_MAC"
    }
}
