package com.dejascan.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dejascan.app.data.Device
import com.dejascan.app.data.Vendors
import com.dejascan.app.data.WatchMode
import com.dejascan.app.scan.ChannelWidthHelper
import com.dejascan.app.scan.Classification
import com.dejascan.app.scan.DetectionCategory
import com.dejascan.app.scan.DetectionClassifier
import com.dejascan.app.scan.SecuritySuite
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.SightingBus
import com.dejascan.app.scan.WifiSecurity
import com.dejascan.app.scan.promiscuous.PromiscuousBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DejaApp(
    vm: AppViewModel,
    onToggleScan: (Boolean) -> Unit,
    hasBackgroundPermission: Boolean,
    onRequestBackground: () -> Unit,
    onPickFile: () -> Unit,
    onPickMap: () -> Unit,
    onExportMerged: () -> Unit = {},
    onExportBackup: () -> Unit = {},
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
        val content = if (tab == 2) {
            Modifier.padding(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())
        } else Modifier.padding(pad)
        Box(content.fillMaxSize()) {
            when (tab) {
                0 -> LiveScreen(vm, onToggleScan, hasBackgroundPermission, onRequestBackground)
                1 -> DevicesScreen(vm, onPickFile, onExportMerged, onExportBackup)
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
    dotColor: Color,
    onRootClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            MText(
                title, color = MaterialTheme.colorScheme.primary, weight = FontWeight.Bold,
                size = 19.sp, letterSpacing = 2.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(dotColor, CircleShape))
                MText(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11.sp)
                RootIndicator(vm.isRooted, onClick = onRootClick)
            }
        }
        IconButton(onClick = { vm.cycleTheme() }, modifier = Modifier.size(32.dp)) {
            DejaIcon(IconKind.THEME, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun RootIndicator(
    isRooted: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val monSnap by PromiscuousBus.snapshot.collectAsState()
    val monActive = monSnap.state.isActive
    val accent = when {
        monActive -> Phosphor.cyan
        isRooted -> Phosphor.green
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (isRooted) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
    val dotColor = if (isRooted) accent else accent.copy(alpha = 0.6f)
    val label = when {
        monActive -> "ROOT • MON"
        isRooted -> "ROOT"
        else -> "USER"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isRooted) accent.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            )
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable {
                if (onClick != null) {
                    onClick()
                } else {
                    val msg = when {
                        monActive -> "${monSnap.state.label} active on ${monSnap.activeInterface}"
                        isRooted -> "Device is rooted (passive 802.11 monitor available)"
                        else -> "Device is not rooted (standard Android scan mode)"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(4.5.dp).background(dotColor, CircleShape))
            MText(label, color = accent, size = 8.5.sp, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DejaCard(modifier: Modifier = Modifier, borderColor: Color? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, borderColor ?: MaterialTheme.colorScheme.outlineVariant),
    ) { content() }
}

@Composable
private fun CategoryPill(cat: DetectionCategory, count: Int, onClick: () -> Unit) {
    val col = Phosphor.forCategory(cat)
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(col.copy(alpha = 0.13f))
            .border(1.dp, col.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).background(col, CircleShape))
        MText("${cat.shortTag} $count", color = col, size = 9.5.sp, weight = FontWeight.Bold)
    }
}

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
    val ready by Vendors.ready.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }

    val activeAll = remember(all, now) {
        all.values.filter { now - it.time <= STALE_MS }.sortedByDescending { it.rssi }
    }
    val watchedTracks = remember(activeAll) { activeAll.filter { it.watched } }

    var radarShowAll by rememberSaveable { mutableStateOf(true) }
    var signalFilter by rememberSaveable { mutableStateOf(SignalFilter.ALL) }
    var activitySubView by rememberSaveable { mutableStateOf(ActivitySubView.SIGNAL) }

    val classifications = remember(all, ready, vm.total) {
        val fixedVendors = HashMap<String, Int>()
        var fixedTotal = 0
        for (s in all.values) {
            if (!s.randomised) {
                val v = Vendors.forMac(s.mac)
                if (!v.isNullOrBlank()) {
                    fixedVendors[v] = (fixedVendors[v] ?: 0) + 1
                    fixedTotal++
                }
            }
        }
        val hasDb = vm.total > 0
        all.values.associate { s ->
            val v = Vendors.forMac(s.mac)
            s.mac to DetectionClassifier.classify(
                s = s,
                hasDatabase = hasDb,
                vendor = v,
                vendorPeerCount = if (v != null) (fixedVendors[v] ?: 0) else 0,
                totalFixedPeers = fixedTotal,
            )
        }
    }

    val categoryCounts = remember(activeAll, classifications) {
        countByCategory(activeAll, classifications)
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

    val topControls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Header(
                title = "DEJA SCAN",
                subtitle = if (running) "${activeAll.size} LIVE" else "IDLE",
                vm = vm,
                dotColor = if (running) deja.strong else deja.weak,
                onRootClick = if (vm.isRooted) {
                    {
                        activitySubView = ActivitySubView.MONITOR
                        scope.launch { pager.animateScrollToPage(views.indexOf(LiveView.ACTIVITY)) }
                    }
                } else null,
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = onRequestBackground,
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (hasBackgroundPermission) Phosphor.edge else Phosphor.amber.copy(alpha = 0.7f),
                            ),
                        ) {
                            Box(
                                Modifier.size(6.dp).background(
                                    if (hasBackgroundPermission) Phosphor.green else Phosphor.amber,
                                    CircleShape,
                                )
                            )
                            MText(
                                " BG",
                                color = if (hasBackgroundPermission) Phosphor.text else Phosphor.amber,
                                size = 9.5.sp,
                                weight = FontWeight.Bold,
                            )
                        }
                        Button(
                            onClick = { onToggleScan(!running) },
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = if (running) ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) else ButtonDefaults.buttonColors(),
                        ) {
                            MText(
                                if (running) "STOP" else "SCAN",
                                color = LocalContentColor.current,
                                weight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                size = 11.sp,
                            )
                        }
                    }
                },
            )
            if (message.isNotEmpty()) {
                MText(message, color = MaterialTheme.colorScheme.error, size = 10.5.sp)
            }

            // Condensed Telemetry & Detection Category Strip
            DejaCard {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        MText(
                            "SEEN $totalNetworks",
                            color = MaterialTheme.colorScheme.primary, size = 10.5.sp, weight = FontWeight.Bold,
                            modifier = Modifier.clickable { scope.launch { pager.animateScrollToPage(views.indexOf(LiveView.VENDORS)) } },
                        )
                        MText("NEW $newNetworks", color = Phosphor.cyan, size = 10.5.sp, weight = FontWeight.Bold)
                        MText("WATCHED ${watchedTracks.size}", color = Phosphor.amber, size = 10.5.sp, weight = FontWeight.Bold)
                        MText(
                            "AP/m $currentApDensity (PK $peakDensity)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, size = 10.sp,
                            modifier = Modifier.clickable {
                                activitySubView = ActivitySubView.DENSITY
                                scope.launch { pager.animateScrollToPage(views.indexOf(LiveView.ACTIVITY)) }
                            },
                        )
                    }
                    if (activeAll.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            DetectionCategory.entries.forEach { cat ->
                                val c = categoryCounts[cat] ?: 0
                                if (c > 0) {
                                    CategoryPill(cat, c) {
                                        signalFilter = when (cat) {
                                            DetectionCategory.AUDIT_TOOL, DetectionCategory.WEP -> SignalFilter.ALERTS
                                            DetectionCategory.RARE, DetectionCategory.UNCOMMON -> SignalFilter.RARE
                                            DetectionCategory.RANDOMIZED -> SignalFilter.RAND
                                            DetectionCategory.NORMAL -> SignalFilter.ALL
                                        }
                                        scope.launch { pager.animateScrollToPage(views.indexOf(LiveView.SIGNALS)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Clean 4-compartment segmented view bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Phosphor.bg, RoundedCornerShape(8.dp))
                    .border(1.dp, Phosphor.edge, RoundedCornerShape(8.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                views.forEachIndexed { i, v ->
                    val sel = pager.currentPage == i
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) Phosphor.green else Color.Transparent)
                            .clickable { scope.launch { pager.animateScrollToPage(i) } }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MText(
                            v.label,
                            color = if (sel) Phosphor.bg else Phosphor.sub,
                            size = 10.sp,
                            weight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }

    val radarItems = if (radarShowAll) activeAll else watchedTracks
    val screen: @Composable (Modifier) -> Unit = { m ->
        TerminalScreen(m) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (views[page]) {
                    LiveView.RADAR -> RadarPanel(
                        tracks = radarItems,
                        classifications = classifications,
                        running = running,
                        now = now,
                        selected = vm.selectedMac,
                        showAllNearby = radarShowAll,
                        onToggleShowAll = { radarShowAll = it },
                        onSelect = { vm.select(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    LiveView.SIGNALS -> SignalsPanel(
                        allActive = activeAll,
                        classifications = classifications,
                        selected = vm.selectedMac,
                        filter = signalFilter,
                        onFilterChange = { signalFilter = it },
                        onSelect = { vm.select(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    LiveView.ACTIVITY -> ActivityPanel(
                        vm = vm,
                        allSightings = all,
                        tracks = if (watchedTracks.isNotEmpty()) watchedTracks else activeAll.take(6),
                        densityPoints = densityPoints,
                        running = running,
                        selected = vm.selectedMac,
                        now = now,
                        subView = activitySubView,
                        onSubViewChange = { activitySubView = it },
                        onSelect = { vm.select(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    LiveView.VENDORS -> VendorsPanel(
                        sightings = activeAll,
                        classifications = classifications,
                        selectedMac = vm.selectedMac,
                        onSelect = { vm.select(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    val legendStrip: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetectionCategory.entries.forEach { cat ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(6.dp).background(Phosphor.forCategory(cat), CircleShape))
                    MText(cat.shortTag, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 8.5.sp)
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val mac = vm.selectedMac

        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(if (mac != null) 1.05f else 1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    topControls()
                    screen(Modifier.weight(1f).fillMaxWidth())
                    if (mac == null) legendStrip()
                }
                if (mac != null) {
                    DeviceDetails(vm, mac, showMapTab = false, Modifier.weight(0.95f).fillMaxHeight())
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                topControls()
                if (mac != null) {
                    screen(Modifier.weight(0.50f).fillMaxWidth())
                    DeviceDetails(vm, mac, showMapTab = false, Modifier.weight(0.50f).fillMaxWidth())
                } else {
                    screen(Modifier.weight(1f).fillMaxWidth())
                    legendStrip()
                }
            }
        }
    }
}

private fun countByCategory(
    items: List<Sighting>,
    classifications: Map<String, Classification>,
): Map<DetectionCategory, Int> {
    val map = HashMap<DetectionCategory, Int>()
    for (s in items) {
        val cat = classifications[s.mac]?.category ?: DetectionCategory.NORMAL
        map[cat] = (map[cat] ?: 0) + 1
    }
    return map
}

@Composable
private fun DevicesScreen(
    vm: AppViewModel,
    onPickFile: () -> Unit,
    onExportMerged: () -> Unit,
    onExportBackup: () -> Unit,
) {
    val deja = LocalDejaColors.current
    val sightings by SightingBus.sightings.collectAsState()
    val ready by Vendors.ready.collectAsState()
    val newFindings = remember(sightings) { sightings.values.count { !it.inDatabase } }

    var watchExpanded by rememberSaveable { mutableStateOf(false) }
    var syncExpanded by rememberSaveable { mutableStateOf(false) }
    var catFilter by rememberSaveable { mutableStateOf<DetectionCategory?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val day = vm.dayEpoch

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day?.let { it * 86_400_000L })
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.setDay(Math.floorDiv(it, 86_400_000L)) }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    val baseShown = when {
        day != null -> vm.dayDevices.filter {
            vm.query.isBlank() || it.name.contains(vm.query.trim(), true) || it.mac.contains(vm.query.trim(), true)
        }
        else -> vm.devices
    }

    val shown = remember(baseShown, catFilter, sightings, ready, vm.total) {
        if (catFilter == null) baseShown
        else baseShown.filter { d ->
            val cls = DetectionClassifier.classifyDevice(d, sightings[d.mac], hasDatabase = vm.total > 0)
            cls.category == catFilter
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Header("DEVICES", "${vm.total} stored", vm, if (vm.total > 0) deja.strong else deja.weak)
        }

        // Collapsible Compartment 1: Watch & Notification Rules
        item {
            DejaCard {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { watchExpanded = !watchExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Watch & Notification Rules", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Mode: ${vm.mode.name} • Background alerts: ${if (vm.notifyHits) "Quiet" else "Off"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        MText(if (watchExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary, size = 12.sp)
                    }
                    if (watchExpanded) {
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
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (t in listOf("W", "B", "E")) {
                                    FilterChip(selected = t in vm.types, onClick = { vm.toggleType(t) }, label = { Text(typeLabel(t)) })
                                }
                                FilterChip(
                                    selected = vm.skipRandomised,
                                    onClick = { vm.changeSkipRandomised(!vm.skipRandomised) },
                                    label = { Text("Skip randomized MACs") },
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Background watched-device alerts", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Rate-limited silent summary only while app is backgrounded",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Switch(checked = vm.notifyHits, onCheckedChange = { vm.changeNotifyHits(it) })
                        }
                    }
                }
            }
        }

        // Collapsible Compartment 2: WiGLE Database Sync
        item {
            DejaCard {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { syncExpanded = !syncExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("WiGLE Database Sync", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (vm.linkedName.isEmpty()) "No .sqlite file linked"
                                else "Linked: ${vm.linkedName}" + if (newFindings > 0) " ($newFindings new)" else "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        MText(if (syncExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary, size = 12.sp)
                    }
                    if (syncExpanded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !vm.busy, onClick = onPickFile, shape = RoundedCornerShape(10.dp)) { Text("Link WiGLE") }
                            OutlinedButton(enabled = !vm.busy, onClick = { vm.refreshLinked(force = true) }, shape = RoundedCornerShape(10.dp)) {
                                Text("Refresh")
                            }
                            OutlinedButton(onClick = { vm.clearSightings() }, shape = RoundedCornerShape(10.dp)) {
                                Text("Clear scans")
                            }
                        }
                        Button(
                            enabled = !vm.busy && vm.linkedName.isNotEmpty(),
                            onClick = onExportMerged,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (newFindings > 0) "Save merged as new file ($newFindings new)" else "Save merged as new file")
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                enabled = !vm.busy && vm.linkedName.isNotEmpty(),
                                onClick = { vm.mergeFindingsIntoLinked() },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Overwrite linked (keeps backup)")
                            }
                            if (vm.hasMergeBackup) {
                                OutlinedButton(
                                    enabled = !vm.busy,
                                    onClick = onExportBackup,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Export backup")
                                }
                            }
                        }
                        if (vm.status.isNotEmpty()) {
                            Text(vm.status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Collapsible Compartment 3: Root / Promiscuous Mode
        item {
            RootPromiscuousCard(vm)
        }

        // Search & Filter Bar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.changeQuery(it) },
                    label = { Text("Search SSID, name, or MAC") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = day != null,
                        onClick = { pickDate = true },
                        label = { Text(if (day == null) "By Date" else java.time.LocalDate.ofEpochDay(day).toString()) },
                    )
                    if (day != null) {
                        TextButton(onClick = { vm.setDay(null) }) { Text("Clear") }
                    }
                    DetectionCategory.entries.forEach { cat ->
                        val active = catFilter == cat
                        val col = Phosphor.forCategory(cat)
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) col.copy(alpha = 0.22f) else Color.Transparent)
                                .border(1.dp, if (active) col else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { catFilter = if (active) null else cat }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) {
                            MText(
                                cat.shortTag,
                                color = if (active) col else MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 10.sp,
                                weight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        if (vm.selectedMac != null) {
            item {
                DeviceDetails(vm, vm.selectedMac!!, showMapTab = true, Modifier.fillMaxWidth().height(320.dp))
            }
        }

        if (day != null) {
            item {
                DejaCard {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (vm.dayBusy) "Looking up ${java.time.LocalDate.ofEpochDay(day)}..."
                            else "${vm.dayTotal} devices seen on ${java.time.LocalDate.ofEpochDay(day)} (${vm.dayPoints.size} places)",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (vm.dayPoints.isNotEmpty()) DayMap(vm.mapFiles, vm.dayPoints, Modifier.fillMaxWidth().height(220.dp))
                    }
                }
            }
        }

        if (day == null && vm.query.isBlank() && shown.isEmpty()) {
            item {
                Text(
                    if (catFilter != null) "No watched devices match ${catFilter!!.label}. Type a search query to scan the full database."
                    else "Starred devices appear here automatically, or search by SSID / MAC / date above.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else if (day != null && vm.dayTotal > vm.dayDevices.size) {
            item {
                Text(
                    "Showing first ${vm.dayDevices.size} of ${vm.dayTotal} devices.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        items(shown, key = { it.mac }) { d ->
            DeviceCard(
                d = d,
                liveSighting = sightings[d.mac],
                hasDatabase = vm.total > 0,
                selected = vm.selectedMac == d.mac,
                onClick = { vm.select(if (vm.selectedMac == d.mac) null else d.mac) },
                onToggleWatch = { vm.toggleWatched(d) },
            )
        }
        item {
            Text(
                LEGAL_NOTICE_SHORT,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun DeviceCard(
    d: Device,
    liveSighting: Sighting?,
    hasDatabase: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleWatch: () -> Unit,
) {
    val ready by Vendors.ready.collectAsState()
    val vendor = remember(d.mac, ready) { Vendors.forMac(d.mac) }
    val cls = remember(d, liveSighting, vendor, hasDatabase) {
        DetectionClassifier.classifyDevice(d, liveSighting, hasDatabase = hasDatabase, vendor = vendor)
    }
    val catColor = Phosphor.forCategory(cls.category)
    val channelLabel = remember(d, liveSighting) {
        liveSighting?.channelSummary?.ifEmpty { null }
            ?: if (d.type == "W" && d.freq > 0) ChannelWidthHelper.formatChannelWidth(d.freq, -1, compact = true) else null
    }
    val secSuite = remember(d, liveSighting) {
        liveSighting?.securitySuite?.takeIf { it != SecuritySuite.UNKNOWN }
            ?: if (d.type == "W" && d.caps.isNotEmpty()) WifiSecurity.parse(d.caps).suite.takeIf { it != SecuritySuite.UNKNOWN } else null
    }

    DejaCard(
        modifier = Modifier.clickable { onClick() },
        borderColor = if (selected) catColor else catColor.copy(alpha = if (cls.category.priority <= 2) 0.45f else 0.18f),
    ) {
        Row(
            Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(catColor),
            )
            Spacer(Modifier.width(8.dp))
            VendorBadge(vendor, 36.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(d.name.ifEmpty { "(hidden / unnamed)" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    if (vendor != null) "${d.mac} • $vendor" else d.mac,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                FlowRow(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Chip(cls.category.shortTag, catColor)
                    Chip(typeLabel(d.type), MaterialTheme.colorScheme.primary)
                    if (!channelLabel.isNullOrEmpty()) {
                        Chip(channelLabel, Phosphor.cyan)
                    }
                    if (secSuite != null) {
                        val secCol = when {
                            secSuite == SecuritySuite.WEP -> Phosphor.orange
                            secSuite.isInsecure -> Phosphor.amber
                            else -> MaterialTheme.colorScheme.secondary
                        }
                        Chip(secSuite.shortLabel, secCol)
                    }
                }
            }
            IconButton(onClick = onToggleWatch) {
                DejaIcon(
                    if (d.watched) IconKind.STAR else IconKind.STAR_OUTLINE,
                    if (d.watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 26.dp,
                )
            }
        }
    }
}
