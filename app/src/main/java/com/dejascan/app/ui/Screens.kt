package com.dejascan.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.SightingBus
import kotlinx.coroutines.delay

@Composable
fun DejaApp(
    vm: AppViewModel,
    onToggleScan: (Boolean) -> Unit,
    onGrantPermissions: () -> Unit,
    onGrantBackground: () -> Unit,
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
                0 -> LiveScreen(vm, onToggleScan, onGrantPermissions, onGrantBackground)
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
private fun Header(title: String, subtitle: String, vm: AppViewModel, dotColor: androidx.compose.ui.graphics.Color) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveScreen(
    vm: AppViewModel,
    onToggleScan: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onGrantBg: () -> Unit,
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

    val header: @Composable () -> Unit = {
        Header("DEJA SCAN", if (running) "SCANNING" else "IDLE", vm, if (running) deja.strong else deja.weak)
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
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                views.forEachIndexed { i, _ ->
                    Box(
                        Modifier.size(7.dp).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                    )
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
                    LiveView.SPECTRUM -> SpectrumPanel(tracks, Modifier.fillMaxSize())
                }
            }
        }
    }
    // start/stop stays pinned; everything else lives in the scrolling info panel
    val pinned: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onToggleScan(!running) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (running) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) else ButtonDefaults.buttonColors(),
                ) { MText(if (running) "STOP" else "START WATCHING", color = LocalContentColor.current, weight = FontWeight.Bold, letterSpacing = 2.sp, size = 13.sp) }
                OutlinedButton(
                    onClick = onGrant, Modifier.height(48.dp), shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { MText("PERMS", color = MaterialTheme.colorScheme.primary, size = 10.sp) }
                OutlinedButton(
                    onClick = onGrantBg, Modifier.height(48.dp), shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { MText("BG", color = MaterialTheme.colorScheme.primary, size = 10.sp) }
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
            InfoPlaceholder(
                listOf(
                    if (running) "> tap a blip or bar for details" else "> press START WATCHING",
                    "Signal labels are rough guides from signal strength, not distances.",
                ),
                m,
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            val side = minOf(maxHeight - 150.dp, maxWidth * 0.5f).coerceAtLeast(160.dp)
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(side), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    header()
                    viewBar()
                    screen(Modifier.size(side))
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pinned()
                    details(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            val side = minOf(maxWidth - 32.dp, maxHeight * 0.34f).coerceAtLeast(150.dp)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                header()
                viewBar()
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { screen(Modifier.size(side)) }
                pinned()
                details(Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen(vm: AppViewModel, onPickFile: () -> Unit) {
    val deja = LocalDejaColors.current
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
