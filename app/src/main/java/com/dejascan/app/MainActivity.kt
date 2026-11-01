package com.dejascan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dejascan.app.scan.ScanService
import com.dejascan.app.ui.AppViewModel
import com.dejascan.app.ui.DejaApp
import com.dejascan.app.ui.DejaTheme
import com.dejascan.app.ui.isDark

class MainActivity : ComponentActivity() {
    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Android stops showing the permission dialog after it was refused, so send the user to the settings page. */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            val dark = vm.themeMode.isDark()

            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            DejaTheme(dark) {
                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    val denied = result.filterValues { !it }.keys.map { it.substringAfterLast('.') }
                    if (denied.isEmpty()) toast("All permissions are granted")
                    else {
                        toast("Not granted: ${denied.joinToString(", ")}. Opening the app settings: allow them there.")
                        openAppSettings()
                    }
                }
                val bgLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) toast("Background location is allowed")
                    else {
                        toast("Opening the app settings: choose Permissions > Location > Allow all the time")
                        openAppSettings()
                    }
                }
                val mapLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris -> vm.importMapFiles(uris) }
                val pickLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> if (uri != null) vm.linkFile(uri) }

                DejaApp(
                    vm = vm,
                    onToggleScan = { start ->
                        if (start) ScanService.start(this@MainActivity) else ScanService.stop(this@MainActivity)
                    },
                    onGrantPermissions = {
                        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 31) {
                            p += Manifest.permission.BLUETOOTH_SCAN
                            p += Manifest.permission.BLUETOOTH_CONNECT
                        }
                        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
                        val missing = p.filter { !granted(it) }
                        if (missing.isEmpty()) toast("All permissions are already granted")
                        else permLauncher.launch(missing.toTypedArray())
                    },
                    // Android 11+ requires background location to be asked separately, after fine location.
                    onGrantBackground = {
                        if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) toast("Background location is already allowed")
                        else bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    },
                    onPickFile = { pickLauncher.launch(arrayOf("*/*")) },
                    onPickMap = { mapLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}
