package com.dejascan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dejascan.app.data.Vendors
import com.dejascan.app.scan.Classification
import com.dejascan.app.scan.DetectionCategory
import com.dejascan.app.scan.MacUtil
import com.dejascan.app.scan.SecuritySuite
import com.dejascan.app.scan.Sighting

data class VendorGroup(
    val name: String,
    val isRandomized: Boolean,
    val isUnknown: Boolean,
    val devices: List<Sighting>,
    val wifiCount: Int,
    val btCount: Int,
    val totalCount: Int,
    val bestRssi: Int,
    val monogram: String,
    val hue: Float,
)

fun vendorColor(hue: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val c = 0.65f
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = 0.25f
    val (r, g, b) = when ((h / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

fun groupSightingsByVendor(
    sightings: Collection<Sighting>,
    typeFilter: String = "ALL", // "ALL", "W", "BT"
    query: String = "",
): List<VendorGroup> {
    val filtered = when (typeFilter) {
        "W" -> sightings.filter { it.type == "W" }
        "BT" -> sightings.filter { it.type == "B" || it.type == "E" }
        else -> sightings
    }

    val groups = filtered.groupBy { s ->
        when {
            MacUtil.isRandomised(s.mac) -> "Randomized (Private MAC)"
            else -> Vendors.forMac(s.mac) ?: "Unknown OUI"
        }
    }

    val rawList = groups.map { (name, devs) ->
        val isRand = name.startsWith("Randomized")
        val isUnk = name.startsWith("Unknown")
        val wCount = devs.count { it.type == "W" }
        val bCount = devs.size - wCount
        val best = devs.maxOfOrNull { it.rssi } ?: -100
        val mono = when {
            isRand -> "RM"
            isUnk -> "??"
            else -> Vendors.monogram(name)
        }
        val h = when {
            isRand -> 265f
            isUnk -> 35f
            else -> Vendors.hue(name)
        }
        VendorGroup(
            name = name,
            isRandomized = isRand,
            isUnknown = isUnk,
            devices = devs.sortedByDescending { it.rssi },
            wifiCount = wCount,
            btCount = bCount,
            totalCount = devs.size,
            bestRssi = best,
            monogram = mono,
            hue = h,
        )
    }

    val searched = if (query.isBlank()) rawList else {
        val q = query.trim()
        rawList.filter { g ->
            g.name.contains(q, ignoreCase = true) ||
                g.devices.any { d ->
                    d.name.contains(q, ignoreCase = true) ||
                        d.mac.contains(q, ignoreCase = true)
                }
        }
    }

    return searched.sortedWith(
        compareByDescending<VendorGroup> { it.totalCount }
            .thenBy { it.isRandomized || it.isUnknown }
            .thenBy { it.name.lowercase() }
    )
}

@Composable
fun VendorsPanel(
    sightings: Collection<Sighting>,
    classifications: Map<String, Classification>,
    selectedMac: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready by Vendors.ready.collectAsState()
    var typeFilter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var toggledVendors by remember { mutableStateOf(setOf<String>()) }

    if (!ready) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MText("> LOADING VENDOR OUI DATABASE...", color = Phosphor.cyan, size = 12.sp, align = TextAlign.Center)
        }
        return
    }

    if (sightings.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MText(
                "> NO DEVICES HEARD YET\n> PRESS SCAN TO ANALYZE VENDORS",
                color = Phosphor.dim, size = 12.sp, align = TextAlign.Center, modifier = Modifier.padding(20.dp),
            )
        }
        return
    }

    val totalCount = sightings.size
    val wifiTotal = remember(sightings) { sightings.count { it.type == "W" } }
    val btTotal = remember(sightings, wifiTotal) { totalCount - wifiTotal }

    val allGroups = remember(sightings, ready) { groupSightingsByVendor(sightings, "ALL") }
    val uniqueVendors = remember(allGroups) { allGroups.count { !it.isRandomized && !it.isUnknown } }
    val randomizedTotal = remember(allGroups) { allGroups.firstOrNull { it.isRandomized }?.totalCount ?: 0 }
    val unknownTotal = remember(allGroups) { allGroups.firstOrNull { it.isUnknown }?.totalCount ?: 0 }

    val groups = remember(sightings, typeFilter, query, ready) {
        groupSightingsByVendor(sightings, typeFilter, query)
    }

    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MText("VENDORS: $uniqueVendors", color = Phosphor.green, size = 10.sp, weight = FontWeight.Bold)
            MText("FIXED: ${totalCount - randomizedTotal - unknownTotal}", color = Phosphor.cyan, size = 10.sp)
            MText("RAND: $randomizedTotal", color = Phosphor.purple, size = 10.sp)
            if (unknownTotal > 0) {
                MText("UNK: $unknownTotal", color = Phosphor.amber, size = 10.sp)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("ALL" to "ALL ($totalCount)", "W" to "WI-FI ($wifiTotal)", "BT" to "BT ($btTotal)").forEach { (key, label) ->
                val selected = typeFilter == key
                Box(
                    modifier = Modifier
                        .clickable { typeFilter = key }
                        .background(
                            if (selected) Phosphor.dim else Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .border(
                            1.dp,
                            if (selected) Phosphor.green else Phosphor.grid,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    MText(
                        label,
                        color = if (selected) Phosphor.green else Phosphor.sub,
                        size = 9.sp,
                        weight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .width(126.dp)
                    .background(Phosphor.bg, RoundedCornerShape(6.dp))
                    .border(1.dp, Phosphor.edge, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MText(">", color = Phosphor.green, size = 10.sp, weight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Phosphor.text),
                    cursorBrush = SolidColor(Phosphor.green),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            MText("search...", color = Phosphor.dim, size = 9.sp)
                        }
                        innerTextField()
                    },
                )
                if (query.isNotEmpty()) {
                    MText(
                        "x",
                        color = Phosphor.dim,
                        size = 9.sp,
                        modifier = Modifier.clickable { query = "" }.padding(start = 2.dp),
                    )
                }
            }
        }

        val maxGroupCount = remember(groups) { groups.maxOfOrNull { it.totalCount } ?: 1 }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(groups, key = { it.name }) { vendor ->
                val color = remember(vendor.hue, vendor.isRandomized) {
                    if (vendor.isRandomized) Phosphor.purple else vendorColor(vendor.hue)
                }
                val pct = (vendor.totalCount * 100f / totalCount.coerceAtLeast(1)).toInt()
                val hasSelected = selectedMac != null && vendor.devices.any { it.mac == selectedMac }
                val isExpanded = (vendor.name in toggledVendors) xor hasSelected

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Phosphor.bg, RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            if (hasSelected) Phosphor.green else Phosphor.edge,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            toggledVendors = if (vendor.name in toggledVendors) toggledVendors - vendor.name else toggledVendors + vendor.name
                        }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .border(1.dp, color, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            MText(vendor.monogram, color = color, size = 10.5.sp, weight = FontWeight.Bold)
                        }

                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                MText(
                                    vendor.name,
                                    color = Phosphor.text,
                                    size = 10.5.sp,
                                    weight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                MText("${vendor.totalCount} ($pct%)", color = color, size = 10.5.sp, weight = FontWeight.Bold)
                                MText(if (isExpanded) " [-]" else " [+]", color = Phosphor.dim, size = 10.5.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                MText("${vendor.wifiCount} Wi-Fi, ${vendor.btCount} BT", color = Phosphor.sub, size = 9.sp)
                                MText("Best: ${vendor.bestRssi} dBm", color = Phosphor.forRssi(vendor.bestRssi), size = 9.sp)
                            }
                        }
                    }

                    val barFrac = vendor.totalCount / maxGroupCount.toFloat()
                    Row(
                        Modifier
                            .fillMaxWidth(barFrac.coerceIn(0.04f, 1f))
                            .height(3.dp)
                            .background(Phosphor.grid, RoundedCornerShape(2.dp)),
                    ) {
                        if (vendor.wifiCount > 0) {
                            Box(Modifier.fillMaxHeight().weight(vendor.wifiCount.toFloat()).background(Phosphor.green, RoundedCornerShape(2.dp)))
                        }
                        if (vendor.btCount > 0) {
                            Box(Modifier.fillMaxHeight().weight(vendor.btCount.toFloat()).background(Phosphor.cyan, RoundedCornerShape(2.dp)))
                        }
                    }

                    if (isExpanded) {
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Phosphor.grid))
                        Spacer(Modifier.height(2.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            vendor.devices.forEach { d ->
                                val isSelected = d.mac == selectedMac
                                val dName = if (d.name.isNotBlank() && d.name != d.mac) d.name else "(Hidden / Unnamed)"
                                val cat = classifications[d.mac]?.category ?: DetectionCategory.NORMAL
                                val catColor = Phosphor.forCategory(cat)
                                val chText = d.channelSummary

                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) Phosphor.dim.copy(alpha = 0.5f) else Phosphor.grid.copy(alpha = 0.3f),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) Phosphor.text else Color.Transparent,
                                            RoundedCornerShape(4.dp),
                                        )
                                        .clickable { onSelect(if (isSelected) null else d.mac) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(Modifier.size(7.dp).background(catColor, CircleShape))
                                    Column(Modifier.weight(1f)) {
                                        MText(
                                            dName,
                                            color = if (dName.startsWith("(")) Phosphor.dim else Phosphor.text,
                                            size = 9.5.sp,
                                            maxLines = 1,
                                        )
                                        val sub = buildString {
                                            append(d.mac)
                                            if (chText.isNotEmpty()) append(" • ").append(chText)
                                            if (d.type == "W" && d.securitySuite != SecuritySuite.UNKNOWN) {
                                                append(" • ").append(d.securitySuite.shortLabel)
                                            }
                                        }
                                        MText(sub, color = Phosphor.sub, size = 8.5.sp, maxLines = 1)
                                    }
                                    if (cat != DetectionCategory.NORMAL) {
                                        MText(cat.shortTag, color = catColor, size = 8.sp, weight = FontWeight.Bold)
                                    }
                                    MText(
                                        "${d.rssi} dBm",
                                        color = catColor,
                                        size = 9.sp,
                                        weight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
