package com.dejascan.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dejascan.app.data.Vendors
import com.dejascan.app.scan.ChannelWidthHelper
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.promiscuous.ClientStation
import com.dejascan.app.scan.promiscuous.PromiscuousBus
import com.dejascan.app.scan.promiscuous.UnmaskedNetwork

private enum class MonitorTab(val label: String) {
    STATIONS("STATIONS"),
    UNMASKED("UNMASKED"),
}

/**
 * Live 802.11 passive monitor compartment (inside Activity -> MONITOR):
 * displays control frame extraction counters, tracked client stations, and passively unmasked hidden SSIDs.
 */
@Composable
fun MonitorPanel(
    vm: AppViewModel,
    sightings: Map<String, Sighting>,
    selectedMac: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap by PromiscuousBus.snapshot.collectAsState()
    val ready by Vendors.ready.collectAsState()
    var subTab by rememberSaveable { mutableStateOf(MonitorTab.STATIONS) }

    val stationList = remember(snap.stations) {
        snap.stations.values.sortedWith(compareByDescending<ClientStation> { it.associatedBssid != null }.thenByDescending { it.lastSeen })
    }
    val unmaskedList = remember(snap.unmasked) {
        snap.unmasked.values.sortedByDescending { it.lastSeenAt }
    }
    val pendingHidden = (snap.hiddenBssids - snap.unmasked.keys).size
    val st = snap.stats

    Column(modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Status & quick toggle bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(Phosphor.bg, RoundedCornerShape(6.dp))
                .border(1.dp, if (snap.state.isActive) Phosphor.cyan.copy(alpha = 0.6f) else Phosphor.grid, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val dotCol = when {
                snap.state.isActive -> Phosphor.cyan
                vm.isRooted -> Phosphor.amber
                else -> Phosphor.dim
            }
            Box(Modifier.size(7.dp).background(dotCol, CircleShape))
            Column(Modifier.weight(1f)) {
                val title = if (snap.state.isActive) "${snap.state.label} • ${snap.activeInterface}" else snap.state.label
                MText(title, color = if (snap.state.isActive) Phosphor.cyan else Phosphor.text, size = 9.5.sp, weight = FontWeight.Bold)
                MText(snap.statusDetail, color = Phosphor.sub, size = 8.5.sp, maxLines = 1)
            }
            if (vm.isRooted) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (vm.promiscuousEnabled) Phosphor.cyan.copy(alpha = 0.2f) else Phosphor.grid)
                        .border(1.dp, if (vm.promiscuousEnabled) Phosphor.cyan else Phosphor.edge, RoundedCornerShape(5.dp))
                        .clickable { vm.changePromiscuousEnabled(!vm.promiscuousEnabled) }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    MText(
                        if (vm.promiscuousEnabled) "MON ON" else "MON OFF",
                        color = if (vm.promiscuousEnabled) Phosphor.cyan else Phosphor.sub,
                        size = 8.5.sp,
                        weight = FontWeight.Bold,
                    )
                }
            }
        }

        // Control & 802.11 Frame Extraction Strip
        Column(
            Modifier
                .fillMaxWidth()
                .background(Phosphor.bg, RoundedCornerShape(6.dp))
                .border(1.dp, Phosphor.grid, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MText("CTRL ${st.totalControl}", color = Phosphor.cyan, size = 9.5.sp, weight = FontWeight.Bold)
                MText("RTS ${st.rts}", color = Phosphor.text, size = 9.sp)
                MText("CTS ${st.cts}", color = Phosphor.text, size = 9.sp)
                MText("ACK ${st.ack}", color = Phosphor.text, size = 9.sp)
                MText("BA ${st.blockAck}", color = Phosphor.text, size = 9.sp)
                MText("PSP ${st.psPoll}", color = Phosphor.text, size = 9.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val deauthTotal = st.deauth + st.disassoc
                MText(
                    "DEAUTH $deauthTotal",
                    color = if (deauthTotal > 0) Phosphor.red else Phosphor.sub,
                    size = 9.sp,
                    weight = if (deauthTotal > 0) FontWeight.Bold else FontWeight.Normal,
                )
                MText("PROBE ${st.probeReq}", color = Phosphor.sub, size = 9.sp)
                MText("BEACON ${st.beacons}", color = Phosphor.sub, size = 9.sp)
                MText("DATA ${st.dataHeaders}", color = Phosphor.sub, size = 9.sp)
                MText("TOTAL ${st.totalFrames}", color = Phosphor.green, size = 9.sp)
            }
        }

        // Sub-view switcher: STATIONS vs UNMASKED
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonitorTab.entries.forEach { tab ->
                val active = subTab == tab
                val count = if (tab == MonitorTab.STATIONS) stationList.size else unmaskedList.size
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (active) Phosphor.dim else Phosphor.bg)
                        .border(1.dp, if (active) Phosphor.green else Phosphor.grid, RoundedCornerShape(5.dp))
                        .clickable { subTab = tab }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    MText(
                        "${tab.label} ($count)",
                        color = if (active) Phosphor.green else Phosphor.sub,
                        size = 9.sp,
                        weight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (pendingHidden > 0) {
                MText("$pendingHidden hidden awaiting client", color = Phosphor.amber, size = 8.5.sp)
            }
        }

        when (subTab) {
            MonitorTab.STATIONS -> StationsList(stationList, sightings, snap.unmasked, ready, selectedMac, vm.isRooted, onSelect, Modifier.weight(1f))
            MonitorTab.UNMASKED -> UnmaskedList(unmaskedList, pendingHidden, selectedMac, vm.isRooted, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StationsList(
    stations: List<ClientStation>,
    sightings: Map<String, Sighting>,
    unmasked: Map<String, UnmaskedNetwork>,
    vendorsReady: Boolean,
    selectedMac: String?,
    isRooted: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    if (stations.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val hint = if (!isRooted) "> root required for passive 802.11 station tracking"
            else "> no client stations observed yet\n> enable MON ON + SCAN to inspect 802.11 headers"
            MText(hint, color = Phosphor.dim, size = 11.sp, align = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(stations, key = { it.mac }) { st ->
            val vendor = remember(st.mac, vendorsReady) { Vendors.forMac(st.mac) }
            val assocBssid = st.associatedBssid
            val apName = assocBssid?.let { b ->
                sightings[b]?.name?.takeUnless { it.equals(b, ignoreCase = true) }
                    ?: unmasked[b]?.ssid
                    ?: b
            }
            val isSel = assocBssid != null && assocBssid == selectedMac
            val accent = if (st.randomised) Phosphor.purple else Phosphor.cyan

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) Phosphor.grid else Phosphor.bg.copy(alpha = 0.65f))
                    .border(1.dp, if (isSel) Phosphor.text else accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .clickable(enabled = assocBssid != null) {
                        if (assocBssid != null) onSelect(if (isSel) null else assocBssid)
                    }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(accent, CircleShape))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        MText(st.mac, color = Phosphor.text, size = 10.sp, weight = FontWeight.Bold)
                        MText(
                            if (st.randomised) "RAND" else (vendor?.take(14) ?: "FIXED"),
                            color = accent,
                            size = 8.sp,
                            weight = FontWeight.Bold,
                        )
                    }
                    val assocLine = if (apName != null) "AP: $apName" else "Unassociated (probing)"
                    MText(assocLine, color = if (apName != null) Phosphor.green else Phosphor.sub, size = 8.5.sp, maxLines = 1)
                    if (st.probedSsids.isNotEmpty()) {
                        MText("Probes: ${st.probedSsids.joinToString(", ")}", color = Phosphor.amber, size = 8.sp, maxLines = 1)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (st.rssi > -100) {
                        MText("${st.rssi} dBm", color = Phosphor.forRssi(st.rssi), size = 9.5.sp, weight = FontWeight.Bold)
                    }
                    MText("${st.frameCount}f • ${st.controlFrames}c", color = Phosphor.sub, size = 8.sp)
                }
            }
        }
    }
}

@Composable
private fun UnmaskedList(
    unmasked: List<UnmaskedNetwork>,
    pendingHidden: Int,
    selectedMac: String?,
    isRooted: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    if (unmasked.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val hint = when {
                !isRooted -> "> root required for passive hidden SSID unmasking"
                pendingHidden > 0 -> "> $pendingHidden hidden AP(s) heard\n> awaiting passive Probe Response or Assoc Request"
                else -> "> no hidden SSIDs unmasked yet"
            }
            MText(hint, color = Phosphor.dim, size = 11.sp, align = TextAlign.Center, modifier = Modifier.padding(16.dp))
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(unmasked, key = { it.bssid }) { u ->
            val isSel = u.bssid == selectedMac
            val ch = ChannelWidthHelper.formatChannelWidth(u.freqMhz, -1, compact = true)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) Phosphor.grid else Phosphor.bg.copy(alpha = 0.65f))
                    .border(1.dp, if (isSel) Phosphor.text else Phosphor.gold.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .clickable { onSelect(if (isSel) null else u.bssid) }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(Phosphor.gold, CircleShape))
                Column(Modifier.weight(1f)) {
                    MText(u.ssid, color = Phosphor.gold, size = 10.5.sp, weight = FontWeight.Bold, maxLines = 1)
                    val meta = buildString {
                        append(u.bssid)
                        if (ch.isNotEmpty()) append(" • ").append(ch)
                        append(" • via ").append(u.source)
                    }
                    MText(meta, color = Phosphor.sub, size = 8.5.sp, maxLines = 1)
                }
                if (u.rssi > -100) {
                    MText("${u.rssi} dBm", color = Phosphor.gold, size = 9.5.sp, weight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Collapsible compartment on the Devices tab for configuring Root / Promiscuous Mode and viewing
 * the ethical/legal passive-use notice.
 */
@Composable
fun RootPromiscuousCard(vm: AppViewModel) {
    val snap by PromiscuousBus.snapshot.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }

    val subtitle = when {
        snap.state.isActive -> "Active (${snap.activeInterface}) • ${snap.stations.size} stations • ${snap.unmasked.size} unmasked"
        !vm.isRooted -> "Unavailable (standard user — root required)"
        vm.promiscuousEnabled -> "Armed (starts with SCAN) • ${snap.capabilities.summary}"
        else -> "Off • Root detected"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (snap.state.isActive) Phosphor.cyan.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Root / Promiscuous Mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                MText(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary, size = 12.sp)
            }

            if (expanded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Passive 802.11 monitor / promiscuous capture", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Extracts control frames, unmasks hidden SSIDs via client responses, and tracks associated stations",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = vm.promiscuousEnabled,
                        enabled = vm.isRooted,
                        onCheckedChange = { vm.changePromiscuousEnabled(it) },
                    )
                }

                if (vm.isRooted) {
                    val ifaces = listOf("auto") + snap.capabilities.wirelessInterfaces
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (iface in ifaces.distinct()) {
                            FilterChip(
                                selected = vm.promiscuousIface == iface,
                                onClick = { vm.changePromiscuousIface(iface) },
                                label = { Text(if (iface == "auto") "Iface: Auto" else iface) },
                            )
                        }
                    }

                    if (snap.capabilities.qcomConModePath != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Hardware monitor fallback (con_mode)", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Switches wlan0 into driver monitor mode if virtual VIF creation is unsupported (pauses Wi-Fi connection)",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Switch(
                                checked = vm.promiscuousDriverSwitch,
                                onCheckedChange = { vm.changePromiscuousDriverSwitch(it) },
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { vm.probePromiscuousCapabilities() },
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Probe Root & Drivers") }
                        Text(
                            snap.capabilities.summary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(
                        "Superuser (su) was not detected. Standard Android Wi-Fi, BLE, and Classic scanning remains fully available without root.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    Text(
                        LEGAL_NOTICE_SHORT,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
