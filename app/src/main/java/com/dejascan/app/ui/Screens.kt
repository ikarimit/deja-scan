package com.dejascan.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dejascan.app.data.Device
import com.dejascan.app.data.Vendors
import com.dejascan.app.geo.Estimate
import com.dejascan.app.data.WatchMode
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.SecuritySuite
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.SightingBus
import kotlinx.coroutines.delay

@Composable
fun DejaApp(
    vm: AppViewModel,
    onToggleScan: (Boolean) -> Unit,
    hasBackgroundPermission: Boolean,
    onRequestBackground: () -> Unit,
    onPickFile: () -> Unit,
    onPickMap: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val navColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, colors = navColors,
                    icon = { DejaIcon(IconKind.RADAR, LocalContentColorOrPrimary(tab == 0)) },
                    label = { Text("Live") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 }, colors = navColors,
                    icon = { DejaIcon(IconKind.LIST, LocalContentColorOrPrimary(tab == 1)) },
                    label = { Text("Devices") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 }, colors = navColors,
                    icon = { DejaIcon(IconKind.MAP, LocalContentColorOrPrimary(tab == 2)) },
                    label = { Text("Map") },
                )
            }
        },
    ) { pad ->
        // the map runs under the side bars/cutout; its overlays add their own safe-area padding
        val content = if (tab == 2) {
            Modifier.padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())
        } else Modifier.padding(pad)
        Box(content.fillMaxSize()) {
            when (tab) {
                0 -> LiveScreen(vm, onToggleScan, hasBackgroundPermission, onRequestBackground)
                1 -> DevicesScreen(vm, onPickFile)
                else -> MapTab(vm, onPickMap)
            }
        }
    }
}

@Composable
private fun LocalContentColorOrPrimary(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun Header(
    title: String,
    subtitle: String,
    vm: AppViewModel,
    dotColor: androidx.compose.ui.graphics.Color,
    extra: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            MText(
                title, color = MaterialTheme.colorScheme.primary, weight = FontWeight.Bold,
                size = 22.sp, letterSpacing = 3.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                MText(
                    subtitle, Modifier.padding(start = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, size = 12.sp,
                )
                if (extra != null) {
                    Spacer(Modifier.width(8.dp))
                    extra()
                }
            }
        }
        IconButton(onClick = { vm.cycleTheme() }) {
            DejaIcon(IconKind.THEME, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            when (vm.themeMode) { ThemeMode.SYSTEM -> "AUTO"; ThemeMode.LIGHT -> "LIGHT"; ThemeMode.DARK -> "DARK" },
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun RootIndicator(isRooted: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val borderColor = if (isRooted) Phosphor.green.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
    val dotColor = if (isRooted) Phosphor.green else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val textColor = if (isRooted) Phosphor.green else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isRooted) Phosphor.green.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            )
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable {
                val msg = if (isRooted) "Device is rooted (superuser access detected)" else "Device is not rooted (standard user)"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 5.dp, vertical = 1.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.5.dp),
        ) {
            Box(Modifier.size(5.dp).background(dotColor, CircleShape))
            MText(
                text = if (isRooted) "ROOT" else "NON-ROOT",
                color = textColor,
                size = 9.sp,
                weight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DejaCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { content() }
}

@Composable
private fun TypeBadge(type: String) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) { DejaIcon(iconKindForType(type), MaterialTheme.colorScheme.primary) }
}

@Composable
private fun NetworkCountersBar(
    totalNetworks: Int,
    newNetworks: Int,
    apDensity: Int,
    peakDensity: Int,
    subNetworks: String = "VENDORS",
    subNetworksColor: androidx.compose.ui.graphics.Color? = null,
    onNetworksClick: (() -> Unit)? = null,
    onDensityClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CounterCard(
            title = "TOTAL NETWORKS",
            value = "$totalNetworks",
            color = MaterialTheme.colorScheme.primary,
            sub = subNetworks,
            subColor = subNetworksColor,
            onClick = onNetworksClick,
            modifier = Modifier.weight(1f),
        )
        CounterCard(
            title = "NEW DISCOVERED",
            value = "$newNetworks",
            color = Phosphor.amber,
            modifier = Modifier.weight(1f),
        )
        CounterCard(
            title = "AP DENSITY",
            value = "$apDensity/m",
            color = Phosphor.cyan,
            sub = "PEAK $peakDensity",
            onClick = onDensityClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CounterCard(
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    sub: String? = null,
    subColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1,
            )
            Text(
                value,
                color = color,
                fontSize = 16.sp,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            if (sub != null) {
                Text(
                    sub,
                    color = subColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 8.sp,
                    fontFamily = Mono,
                    fontWeight = if (subColor != null) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SecurityAdvisoryBanner(
    insecureAps: List<Sighting>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (insecureAps.isEmpty()) return
    val wepAps = remember(insecureAps) { insecureAps.filter { it.isWep } }
    val hasWep = wepAps.isNotEmpty()
    val top = insecureAps.first()

    var dismissed by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    if (dismissed) return

    val borderColor = if (hasWep) Phosphor.red else Phosphor.amber
    val bgColor = borderColor.copy(alpha = 0.12f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MText(if (hasWep) "⚠️" else "🛡️", size = 13.sp)
                Column(Modifier.weight(1f)) {
                    val title = if (hasWep) {
                        "SECURITY ADVISORY • ${wepAps.size} WEP NETWORK${if (wepAps.size > 1) "S" else ""}"
                    } else {
                        "SECURITY ADVISORY • ${insecureAps.size} INSECURE AP${if (insecureAps.size > 1) "S" else ""}"
                    }
                    MText(title, color = borderColor, size = 10.sp, weight = FontWeight.Bold, maxLines = 1)
                    val sub = if (hasWep) {
                        val firstWep = wepAps.first()
                        "${firstWep.name} (${firstWep.mac}) • Broken encryption"
                    } else {
                        "${top.name} • ${top.primarySecurityIssue?.summary ?: "Insecure Wi-Fi"}"
                    }
                    MText(sub, color = Phosphor.text, size = 9.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { onSelect(if (hasWep) wepAps.first().mac else top.mac) },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier.height(28.dp),
                ) {
                    MText("INSPECT", color = borderColor, size = 9.sp, weight = FontWeight.Bold)
                }
                if (insecureAps.size > 1) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clickable { expanded = !expanded },
                        contentAlignment = Alignment.Center,
                    ) {
                        MText(if (expanded) "▲" else "▼", color = Phosphor.sub, size = 10.sp)
                    }
                }
                Box(
                    Modifier
                        .size(24.dp)
                        .clickable { dismissed = true },
                    contentAlignment = Alignment.Center,
                ) {
                    MText("✕", color = Phosphor.dim, size = 11.sp, weight = FontWeight.Bold)
                }
            }
            if (expanded && insecureAps.size > 1) {
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    insecureAps.take(5).forEach { ap ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Phosphor.bg.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .clickable { onSelect(ap.mac) }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                MText(if (ap.isWep) "WEP ⚠️" else ap.securitySuite.shortLabel, color = if (ap.isWep) Phosphor.red else Phosphor.amber, size = 8.5.sp, weight = FontWeight.Bold)
                                MText(ap.name.take(16), color = Phosphor.text, size = 9.5.sp, maxLines = 1)
                            }
                            MText("${ap.rssi} dBm", color = Phosphor.forRssi(ap.rssi), size = 9.sp)
                        }
                    }
                    if (insecureAps.size > 5) {
                        MText("+ ${insecureAps.size - 5} more insecure networks in range", color = Phosphor.dim, size = 8.5.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveScreen(
    vm: AppViewModel,
    onToggleScan: (Boolean) -> Unit,
    hasBackgroundPermission: Boolean,
    onRequestBackground: () -> Unit,
) {
    val running by SightingBus.running.collectAsState()
    val all by SightingBus.sightings.collectAsState()
    val message by SightingBus.message.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    val tracks = remember(all, now) {
        all.values.filter { it.watched && now - it.time <= STALE_MS }.sortedByDescending { it.rssi }
    }
    val views = LiveView.entries
    val pager = rememberPagerState { views.size }
    val scope = rememberCoroutineScope()
    val deja = LocalDejaColors.current

    val densityPoints = remember(now, all) { SightingBus.densityTracker.rollingPoints(now, 30) }
    val currentApDensity = remember(all, now) { all.values.count { it.type == "W" && now - it.time <= 60_000L } }
    val peakDensity = remember(densityPoints, currentApDensity) {
        maxOf(currentApDensity, densityPoints.maxOfOrNull { it.apCount } ?: 0)
    }
    val totalNetworks = all.size
    val newNetworks = remember(all) { all.values.count { !it.inDatabase } }
    val wifiAps = remember(all) { all.values.count { it.type == "W" } }
    val newWifiAps = remember(all) { all.values.count { it.type == "W" && !it.inDatabase } }

    val activeInsecure = remember(all, now) {
        all.values.filter { it.type == "W" && now - it.time <= STALE_MS && it.hasSecurityIssue }
            .sortedWith(compareBy({ it.primarySecurityIssue?.severity?.ordinal ?: 99 }, { -it.rssi }))
    }
    val activeWepCount = remember(activeInsecure) { activeInsecure.count { it.isWep } }
    val activeOpenCount = remember(activeInsecure) { activeInsecure.count { it.isOpen } }

    val header: @Composable () -> Unit = {
        Header(
            title = "DEJA SCAN",
            subtitle = if (running) "SCANNING" else "IDLE",
            vm = vm,
            dotColor = if (running) deja.strong else deja.weak,
            extra = { RootIndicator(vm.isRooted) },
        )
    }
    val countersBar: @Composable () -> Unit = {
        NetworkCountersBar(
            totalNetworks = totalNetworks,
            newNetworks = newNetworks,
            apDensity = currentApDensity,
            peakDensity = peakDensity,
            subNetworks = if (activeWepCount > 0) "$activeWepCount WEP (!)" else if (activeOpenCount > 0) "$activeOpenCount OPEN (!)" else "VENDORS",
            subNetworksColor = if (activeWepCount > 0) Phosphor.red else if (activeOpenCount > 0) Phosphor.amber else MaterialTheme.colorScheme.onSurfaceVariant,
            onNetworksClick = { scope.launch { pager.animateScrollToPage(views.indexOf(LiveView.VENDORS)) } },
            onDensityClick = { scope.launch { pager.animateScrollToPage(views.indexOf(LiveView.DENSITY)) } },
        )
    }
    val securityBanner: @Composable () -> Unit = {
        SecurityAdvisoryBanner(activeInsecure, onSelect = { vm.select(it) })
    }
    val viewBar: @Composable () -> Unit = {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(8.dp)) {
                    MText("[ ${views[pager.currentPage].label} v ]", color = MaterialTheme.colorScheme.primary, weight = FontWeight.Bold)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    views.forEachIndexed { i, v ->
                        DropdownMenuItem(
                            text = { MText(v.label, color = MaterialTheme.colorScheme.onSurface) },
                            onClick = { open = false; scope.launch { pager.animateScrollToPage(i) } },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                views.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .size(16.dp)
                            .clickable { scope.launch { pager.animateScrollToPage(i) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).background(
                                if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            )
                        )
                    }
                }
            }
            MText(
                "  ${tracks.size} in range", color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11.sp,
            )
        }
    }
    val screen: @Composable (Modifier) -> Unit = { m ->
        TerminalScreen(m) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (views[page]) {
                    LiveView.RADAR -> RadarPanel(tracks, running, now, vm.selectedMac, { vm.select(it) }, Modifier.fillMaxSize())
                    LiveView.BARS -> BarsPanel(tracks, vm.selectedMac, { vm.select(it) }, Modifier.fillMaxSize())
                    LiveView.HISTORY -> HistoryPanel(tracks, vm.selectedMac, now, { vm.select(it) }, Modifier.fillMaxSize())
                    LiveView.SPECTRUM -> SpectrumPanel(
                        all = all.values.toList(),
                        watched = tracks,
                        wifiCapabilities = vm.wifiCapabilities,
                        selectedMac = vm.selectedMac,
                        onSelect = { vm.select(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    LiveView.DENSITY -> DensityPanel(densityPoints, running, Modifier.fillMaxSize())
                    LiveView.VENDORS -> VendorsPanel(all.values, vm.selectedMac, { vm.select(it) }, Modifier.fillMaxSize())
                }
            }
        }
    }
    // start/stop stays pinned; everything else lives in the scrolling info panel
    val pinned: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onToggleScan(!running) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (running) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) else ButtonDefaults.buttonColors(),
                ) {
                    MText(
                        if (running) "STOP WATCHING" else "START WATCHING",
                        color = LocalContentColor.current,
                        weight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        size = 13.sp,
                    )
                }
                OutlinedButton(
                    onClick = onRequestBackground,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (hasBackgroundPermission) Phosphor.edge else Phosphor.amber.copy(alpha = 0.7f),
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier.size(8.dp).background(
                                if (hasBackgroundPermission) Phosphor.green else Phosphor.amber,
                                CircleShape,
                            )
                        )
                        MText(
                            if (hasBackgroundPermission) "BG ON" else "BG OFF",
                            color = if (hasBackgroundPermission) Phosphor.text else Phosphor.amber,
                            size = 10.sp,
                            weight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (message.isNotEmpty()) {
                MText(message, color = MaterialTheme.colorScheme.error, size = 12.sp)
            }
        }
    }
    val details: @Composable (Modifier) -> Unit = { m ->
        val mac = vm.selectedMac
        if (mac != null) {
            DeviceDetails(vm, mac, showMapTab = false, m)
        } else {
            val btCount = totalNetworks - wifiAps
            val wifiSightings = remember(all) { all.values.filter { it.type == "W" } }
            val wpa3Count = remember(wifiSightings) {
                wifiSightings.count { it.securitySuite == SecuritySuite.WPA3 || it.securitySuite == SecuritySuite.WPA3_ENTERPRISE || it.securitySuite == SecuritySuite.WPA2_WPA3 }
            }
            val wpa2Count = remember(wifiSightings) {
                wifiSightings.count { it.securitySuite == SecuritySuite.WPA2 || it.securitySuite == SecuritySuite.WPA2_ENTERPRISE }
            }
            val wpaCount = remember(wifiSightings) {
                wifiSightings.count { it.securitySuite == SecuritySuite.WPA || it.securitySuite == SecuritySuite.WPA_ENTERPRISE }
            }
            val wepCount = remember(wifiSightings) {
                wifiSightings.count { it.securitySuite == SecuritySuite.WEP }
            }
            val openCount = remember(wifiSightings) {
                wifiSightings.count { it.securitySuite == SecuritySuite.OPEN || it.securitySuite == SecuritySuite.OWE }
            }
            val secSummary = buildString {
                val parts = mutableListOf<String>()
                if (wpa3Count > 0) parts += "WPA3: $wpa3Count"
                if (wpa2Count > 0) parts += "WPA2: $wpa2Count"
                if (wpaCount > 0) parts += "WPA: $wpaCount"
                if (wepCount > 0) parts += "WEP: $wepCount (!)"
                if (openCount > 0) parts += "OPEN: $openCount (!)"
                if (parts.isNotEmpty()) append(parts.joinToString("  |  "))
                else if (wifiAps > 0) append("gathering beacons...")
                else append("no APs heard yet")
            }
            val lines = listOf(
                if (running) "> SCANNING ACTIVE: $currentApDensity APs/min" else "> SCANNER IDLE: press START WATCHING",
                "TOTAL NETWORKS FOUND : $totalNetworks" + if (btCount > 0) " ($wifiAps Wi-Fi, $btCount BT)" else "",
                "NEW TO LOCAL DATABASE: $newNetworks" + if (wifiAps > 0) " ($newWifiAps new APs)" else "",
                "KNOWN IN LOCAL DB    : ${totalNetworks - newNetworks}",
                if (running) "AP DENSITY (NOW)     : $currentApDensity APs/min (Peak: $peakDensity)" else "AP DENSITY (PEAK)    : $peakDensity APs/min",
                "SECURITY SUITES (AP) : $secSummary",
                "DEVICE PRIVILEGES    : " + if (vm.isRooted) "ROOT (superuser)" else "NON-ROOT (standard user)",
                if (openCount > 0 || wepCount > 0) "> INSECURE APs FOUND : $openCount open (unencrypted), $wepCount WEP" else "> all heard APs use WPA2/WPA3 encryption",
                "> Note on ESS        : ESS indicates multi-AP infrastructure, not security",
                "> select DENSITY view above for rolling graph",
                "> tap a blip or bar above for device details",
            )
            InfoPlaceholder(lines, m)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val isVendors = views.getOrNull(pager.currentPage) == LiveView.VENDORS
        val hasSelection = vm.selectedMac != null

        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(if (isVendors && !hasSelection) 1.25f else 1f).fillMaxHeight().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    header()
                    countersBar()
                    securityBanner()
                    viewBar()
                    screen(Modifier.weight(1f).fillMaxWidth())
                }
                Column(
                    Modifier.weight(if (isVendors && !hasSelection) 0.75f else 1f).fillMaxHeight().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pinned()
                    details(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            val side = minOf(maxWidth - 32.dp, maxHeight * 0.36f).coerceAtLeast(160.dp)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                header()
                countersBar()
                securityBanner()
                viewBar()

                if (isVendors && !hasSelection) {
                    screen(Modifier.weight(1f).fillMaxWidth())
                    pinned()
                } else if (isVendors) {
                    screen(Modifier.weight(0.52f).fillMaxWidth())
                    pinned()
                    details(Modifier.weight(0.48f).fillMaxWidth())
                } else {
                    screen(Modifier.fillMaxWidth().height(side))
                    pinned()
                    details(Modifier.weight(1f).fillMaxWidth())
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen(vm: AppViewModel, onPickFile: () -> Unit) {
    val deja = LocalDejaColors.current
    val sightings by SightingBus.sightings.collectAsState()
    val newFindings = remember(sightings) { sightings.values.count { !it.inDatabase } }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Header("DEVICES", "${vm.total} stored", vm, if (vm.total > 0) deja.strong else deja.weak)
        }
        item {
            DejaCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WiGLE import", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (vm.linkedName.isEmpty()) "No file linked" else "Linked: ${vm.linkedName}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !vm.busy, onClick = onPickFile, shape = RoundedCornerShape(12.dp)) { Text("Link WiGLE file") }
                        OutlinedButton(
                            enabled = !vm.busy, onClick = { vm.refreshLinked(force = true) },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("Refresh") }
                    }
                    Button(
                        enabled = !vm.busy && vm.linkedName.isNotEmpty(),
                        onClick = { vm.mergeFindingsIntoLinked() },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (newFindings > 0) "Merge findings into WiGLE file ($newFindings new)" else "Merge findings into WiGLE file")
                    }
                    OutlinedButton(onClick = { vm.clearSightings() }, shape = RoundedCornerShape(12.dp)) { Text("Clear my scan history") }
                    if (vm.status.isNotEmpty()) {
                        Text(vm.status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            DejaCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Watch", style = MaterialTheme.typography.titleSmall)
                    val modes = listOf(WatchMode.PICKED to "Picked", WatchMode.ALL to "All", WatchMode.FILTERED to "Filtered")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, (m, label) ->
                            SegmentedButton(
                                selected = vm.mode == m, onClick = { vm.changeMode(m) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                            ) { Text(label) }
                        }
                    }
                    if (vm.mode == WatchMode.FILTERED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (t in listOf("W", "B", "E")) {
                                FilterChip(selected = t in vm.types, onClick = { vm.toggleType(t) }, label = { Text(typeLabel(t)) })
                            }
                        }
                        FilterChip(
                            selected = vm.skipRandomised, onClick = { vm.changeSkipRandomised(!vm.skipRandomised) },
                            label = { Text("Skip randomised addresses") },
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.changeQuery(it) },
                label = { Text("Search name or address") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            var pick by remember { mutableStateOf(false) }
            val day = vm.dayEpoch
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pick = true }, shape = RoundedCornerShape(12.dp)) {
                    Text(if (day == null) "Search by date" else java.time.LocalDate.ofEpochDay(day).toString())
                }
                if (day != null) OutlinedButton(onClick = { vm.setDay(null) }, shape = RoundedCornerShape(12.dp)) { Text("Clear date") }
            }
            if (pick) {
                val state = rememberDatePickerState(initialSelectedDateMillis = day?.let { it * 86_400_000L })
                DatePickerDialog(
                    onDismissRequest = { pick = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { vm.setDay(Math.floorDiv(it, 86_400_000L)) }
                            pick = false
                        }) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = { pick = false }) { Text("Cancel") } },
                ) { DatePicker(state = state) }
            }
        }
        val day = vm.dayEpoch
        if (day != null) {
            item {
                DejaCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (vm.dayBusy) "Looking up ${java.time.LocalDate.ofEpochDay(day)}..."
                            else "${vm.dayTotal} devices seen on ${java.time.LocalDate.ofEpochDay(day)} (${vm.dayPoints.size} places)",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (vm.dayPoints.isNotEmpty()) DayMap(vm.mapFiles, vm.dayPoints, Modifier.fillMaxWidth().height(280.dp))
                        Text(
                            "Sources: WiGLE observations and last-seen dates, plus this app's own scans. Blue Wi-Fi, pink Bluetooth, orange BLE, grey unknown to WiGLE.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        val shown = when {
            day != null -> vm.dayDevices.filter {
                vm.query.isBlank() || it.name.contains(vm.query.trim(), true) || it.mac.contains(vm.query.trim(), true)
            }
            vm.query.isNotBlank() -> vm.devices
            else -> emptyList()
        }
        if (day == null && vm.query.isBlank()) {
            item {
                Text(
                    "Type a name or address, or pick a date, to list devices.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (day != null && vm.dayTotal > vm.dayDevices.size) {
            item {
                Text(
                    "Showing the first ${vm.dayDevices.size} of ${vm.dayTotal}; the map shows all places.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(shown, key = { it.mac }) { d -> DeviceCard(d) { vm.toggleWatched(d) } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun DeviceCard(d: Device, onToggle: () -> Unit) {
    DejaCard {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val ready by Vendors.ready.collectAsState()
            val vendor = remember(d.mac, ready) { Vendors.forMac(d.mac) }
            VendorBadge(vendor, 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(d.name.ifEmpty { "(no name)" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(d.mac, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (vendor != null) Text(vendor, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(typeLabel(d.type), MaterialTheme.colorScheme.primary)
                    val sec = SightingBus.sightings.collectAsState().value[d.mac]?.securitySuite
                    if (sec != null && sec != SecuritySuite.UNKNOWN) {
                        Chip(sec.shortLabel, if (sec.isInsecure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                    }
                    if (d.randomised) Chip("RANDOMISED", MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onToggle) {
                DejaIcon(
                    if (d.watched) IconKind.STAR else IconKind.STAR_OUTLINE,
                    if (d.watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 28.dp,
                )
            }
        }
    }
}
