package com.dejascan.app.scan

import com.dejascan.app.data.Device
import com.dejascan.app.data.Vendors

/**
 * Unified visual classification for Wi-Fi and Bluetooth detections:
 * - [AUDIT_TOOL]: Possible wireless audit / research hardware matched by heuristic (unverified; see docs/SOURCES.md C-014)
 * - [WEP]: Outdated WEP encryption (broken RC4 cipher)
 * - [RARE]: Rare RF / protocol / hardware detections (6 GHz, Wi-Fi 7 / 320 MHz, Mesh/IBSS, Ch 14, rare BT classes, rare OUI)
 * - [UNCOMMON]: Uncommon configurations or newly discovered fixed-MAC devices (OWE, 802.1X, 160 MHz, Passpoint, WPA1, Open, new OUI)
 * - [RANDOMIZED]: Locally-administered / ephemeral randomized MAC addresses
 * - [NORMAL]: Standard WPA2/WPA3 networks and normal fixed-MAC Bluetooth devices
 */
enum class DetectionCategory(
    val label: String,
    val shortTag: String,
    val uiColorHex: Long,
    val mapColorArgb: Int,
    val priority: Int,
) {
    AUDIT_TOOL("Possible audit tool (heuristic)", "AUDIT", 0xFFFF2A55, 0xFFFF1744.toInt(), 0),
    WEP("Outdated WEP", "WEP", 0xFFFF6D00, 0xFFFF6D00.toInt(), 1),
    RARE("Rare Detection", "RARE", 0xFFFFD600, 0xFFF9A825.toInt(), 2),
    UNCOMMON("Uncommon", "UNCOMMON", 0xFF00E5FF, 0xFF00ACC1.toInt(), 3),
    RANDOMIZED("Randomized MAC", "RAND", 0xFFB388FF, 0xFF8E24AA.toInt(), 4),
    NORMAL("Normal", "NORMAL", 0xFF39FF88, 0xFF00A152.toInt(), 5),
}

data class Classification(
    val category: DetectionCategory,
    val reason: String,
)

object DetectionClassifier {

    /**
     * Heuristic MAC prefixes (unverified; see docs/SOURCES.md C-014).
     * Note: 00:13:37 is also a legitimate IEEE OUI block, so ordinary hardware can match.
     */
    private val HEURISTIC_MAC_PREFIXES = listOf(
        "00:13:37" to "Possible audit tool (heuristic: 00:13:37 prefix; also an assigned OUI)",
    )

    private val HEURISTIC_NAME_PATTERNS = listOf(
        Regex("(?i)\\bpwnagotchi\\b|pwnagotchi") to "Possible audit tool (heuristic: Pwnagotchi name pattern)",
        Regex("(?i)^pwnd\\b|\\bpwnd[_-]") to "Possible audit tool (heuristic: pwnd beacon name pattern)",
        Regex("(?i)wifi[_ -]?pineapple|\\bpineap\\b|\\bhak5\\b") to "Possible audit tool (heuristic: Pineapple / Hak5 name pattern)",
        Regex("(?i)\\bmarauder\\b|esp32[_ -]?marauder") to "Possible audit tool (heuristic: ESP32 Marauder name pattern)",
        Regex("(?i)\\bdeauther\\b|pwned[_ -]?ap") to "Possible audit tool (heuristic: 802.11 Deauther name pattern)",
        Regex("(?i)\\bwifiphisher\\b|\\bairgeddon\\b|\\bevil[_ -]?twin\\b") to "Possible audit tool (heuristic: wireless audit framework name)",
        Regex("(?i)\\bbjorn[_ -]?cyber|\\bm5pwn\\b") to "Possible audit tool (heuristic: portable audit unit name)",
    )

    private val FLIPPER_BT_NAME_PATTERN = Regex("(?i)^flipper\\s+[A-Za-z0-9_-]{3,16}$")

    private val FLIPPER_SERVICE_UUIDS = listOf("0x3081", "0x3082", "0x3083", "00003081", "00003082", "00003083")

    private val COMMON_VENDORS = listOf(
        "apple", "samsung", "google", "intel", "tp-link", "netgear", "asus", "cisco", "ubiquiti",
        "amazon", "microsoft", "xiaomi", "huawei", "murata", "espressif", "realtek", "qualcomm",
        "broadcom", "mediatek", "sonos", "roku", "lg ", "sony", "hp ", "dell", "lenovo", "aruba",
        "commscope", "arris", "technicolor", "sagemcom", "nokia", "eero", "linksys", "d-link", "belkin",
    )

    fun classify(
        s: Sighting,
        hasDatabase: Boolean = false,
        vendor: String? = Vendors.forMac(s.mac),
        vendorPeerCount: Int = 0,
        totalFixedPeers: Int = 0,
    ): Classification = classifyRaw(
        mac = s.mac,
        name = s.name,
        type = s.type,
        randomised = s.randomised,
        freq = s.freq,
        channelWidth = s.channelWidth,
        wifiStandard = s.wifiStandard,
        securitySuite = s.securitySuite,
        caps = s.adv.firstOrNull { it.startsWith("Raw Caps: ") }?.removePrefix("Raw Caps: ").orEmpty(),
        adv = s.adv,
        inDatabase = s.inDatabase,
        hasDatabase = hasDatabase,
        vendor = vendor,
        vendorPeerCount = vendorPeerCount,
        totalFixedPeers = totalFixedPeers,
    )

    fun classifyDevice(
        d: Device,
        liveSighting: Sighting? = null,
        hasDatabase: Boolean = true,
        vendor: String? = Vendors.forMac(d.mac),
    ): Classification {
        if (liveSighting != null) {
            return classify(liveSighting, hasDatabase = hasDatabase, vendor = vendor)
        }
        val parsedSuite = if (d.type == "W" && d.caps.isNotEmpty()) WifiSecurity.parse(d.caps).suite else SecuritySuite.UNKNOWN
        return classifyRaw(
            mac = d.mac,
            name = d.name,
            type = d.type,
            randomised = d.randomised,
            freq = d.freq,
            channelWidth = -1,
            wifiStandard = 0,
            securitySuite = parsedSuite,
            caps = d.caps,
            adv = emptyList(),
            inDatabase = true,
            hasDatabase = hasDatabase,
            vendor = vendor,
            vendorPeerCount = 0,
            totalFixedPeers = 0,
        )
    }

    fun classifyRaw(
        mac: String,
        name: String,
        type: String,
        randomised: Boolean,
        freq: Int = 0,
        channelWidth: Int = -1,
        wifiStandard: Int = 0,
        securitySuite: SecuritySuite = SecuritySuite.UNKNOWN,
        caps: String = "",
        adv: List<String> = emptyList(),
        inDatabase: Boolean = true,
        hasDatabase: Boolean = false,
        vendor: String? = null,
        vendorPeerCount: Int = 0,
        totalFixedPeers: Int = 0,
    ): Classification {
        val normMac = mac.trim().lowercase()
        val cleanName = if (name.equals(mac, ignoreCase = true)) "" else name.trim()
        val upperCaps = caps.uppercase()

        // 1. Possible audit tools matched by heuristic (unverified; see docs/SOURCES.md C-014)
        if (normMac == "de:ad:be:ef:de:ad") {
            return Classification(
                DetectionCategory.AUDIT_TOOL,
                "Possible audit tool (heuristic: de:ad:be:ef:de:ad Pwnagotchi grid MAC)",
            )
        }
        for ((prefix, reason) in HEURISTIC_MAC_PREFIXES) {
            if (normMac.startsWith(prefix)) {
                return Classification(DetectionCategory.AUDIT_TOOL, reason)
            }
        }
        if (cleanName.isNotEmpty()) {
            for ((regex, reason) in HEURISTIC_NAME_PATTERNS) {
                if (regex.containsMatchIn(cleanName)) {
                    return Classification(DetectionCategory.AUDIT_TOOL, reason)
                }
            }
            if (type != "W" && FLIPPER_BT_NAME_PATTERN.matches(cleanName)) {
                return Classification(
                    DetectionCategory.AUDIT_TOOL,
                    "Possible audit tool (heuristic: 'Flipper' BT/BLE name; may match ordinary devices)",
                )
            }
        }
        if (type != "W" && adv.isNotEmpty()) {
            val joinedAdv = adv.joinToString(" ").lowercase()
            if (FLIPPER_SERVICE_UUIDS.any { joinedAdv.contains(it) }) {
                return Classification(
                    DetectionCategory.AUDIT_TOOL,
                    "Possible audit tool (heuristic: Flipper Zero BLE service UUID)",
                )
            }
            if (joinedAdv.contains("marauder") || joinedAdv.contains("pwnagotchi")) {
                return Classification(
                    DetectionCategory.AUDIT_TOOL,
                    "Possible audit tool (heuristic: audit tool keyword in advertisement)",
                )
            }
        }

        // 2. Outdated WEP encryption
        val isWep = type == "W" && (
            securitySuite == SecuritySuite.WEP ||
                upperCaps.contains("WEP") ||
                adv.any { it.startsWith("Security:") && it.contains("WEP", ignoreCase = true) }
            )
        if (isWep) {
            return Classification(DetectionCategory.WEP, "Outdated WEP (broken RC4 encryption)")
        }

        // 3. Rare Wi-Fi & Bluetooth detections
        if (type == "W") {
            if (wifiStandard == WifiStandardHelper.STANDARD_11BE || channelWidth == ChannelWidthHelper.WIDTH_320MHZ) {
                return Classification(DetectionCategory.RARE, "Rare Wi-Fi 7 (802.11be / 320 MHz) AP")
            }
            if (freq in 5925..7125) {
                return Classification(DetectionCategory.RARE, "Rare 6 GHz band (Wi-Fi 6E / 7) AP")
            }
            if (freq == 2484) {
                return Classification(DetectionCategory.RARE, "Rare 2.4 GHz Channel 14 (2484 MHz)")
            }
            if (upperCaps.contains("[IBSS]") || adv.any { it.contains("Ad-Hoc (IBSS)") }) {
                return Classification(DetectionCategory.RARE, "Rare Ad-Hoc (802.11 IBSS) peer network")
            }
            if (upperCaps.contains("[MESH]") || adv.any { it.contains("802.11s Mesh") }) {
                return Classification(DetectionCategory.RARE, "Rare 802.11s Mesh network")
            }
            if (wifiStandard == WifiStandardHelper.STANDARD_11AD || freq >= 58_000) {
                return Classification(DetectionCategory.RARE, "Rare 60 GHz WiGig (802.11ad) AP")
            }
            if (securitySuite == SecuritySuite.WPA3_ENTERPRISE || upperCaps.contains("SUITE_B")) {
                return Classification(DetectionCategory.RARE, "Rare WPA3-Enterprise (Suite-B) network")
            }
        } else {
            // Rare Bluetooth Classic device classes (Health/Medical 0x0900, Imaging 0x0600, Toy 0x0800)
            val classLine = adv.firstOrNull { it.startsWith("Class: 0x") }
            if (classLine != null) {
                val cod = classLine.removePrefix("Class: 0x").trim().toIntOrNull(16) ?: 0
                val major = (cod shr 8) and 0x1F
                when (major) {
                    0x09 -> return Classification(DetectionCategory.RARE, "Rare Bluetooth Health/Medical class")
                    0x06 -> return Classification(DetectionCategory.RARE, "Rare Bluetooth Imaging hardware class")
                    0x08 -> return Classification(DetectionCategory.RARE, "Rare Bluetooth Toy/Controller class")
                }
            }
        }

        val isRand = randomised || MacUtil.isRandomised(normMac)
        if (!isRand && !vendor.isNullOrBlank() && vendorPeerCount == 1 && totalFixedPeers >= 10) {
            val vLower = vendor.lowercase()
            if (COMMON_VENDORS.none { vLower.contains(it) }) {
                return Classification(DetectionCategory.RARE, "Rare hardware vendor ($vendor)")
            }
        }

        // 4. Randomized MAC addresses (kept distinct from Uncommon so private MACs always share one clear color)
        if (isRand) {
            val detail = when {
                type == "W" && securitySuite == SecuritySuite.OPEN -> "Randomized MAC (Open hotspot/AP)"
                type == "W" -> "Randomized BSSID (mobile hotspot / virtual AP)"
                else -> "Randomized Bluetooth address"
            }
            return Classification(DetectionCategory.RANDOMIZED, detail)
        }

        // 5. Uncommon Wi-Fi & Bluetooth detections (non-randomized)
        if (type == "W") {
            if (securitySuite == SecuritySuite.OWE || upperCaps.contains("OWE")) {
                return Classification(DetectionCategory.UNCOMMON, "Uncommon OWE (Enhanced Open) encryption")
            }
            if (channelWidth == ChannelWidthHelper.WIDTH_160MHZ || channelWidth == ChannelWidthHelper.WIDTH_80PLUS80MHZ) {
                return Classification(DetectionCategory.UNCOMMON, "Uncommon 160 MHz wide channel")
            }
            if (securitySuite in setOf(SecuritySuite.WPA2_ENTERPRISE, SecuritySuite.WPA_ENTERPRISE, SecuritySuite.ENTERPRISE) || upperCaps.contains("EAP")) {
                return Classification(DetectionCategory.UNCOMMON, "Enterprise 802.1X authentication")
            }
            if (upperCaps.contains("HS20") || upperCaps.contains("PASSPOINT") || adv.any { it.startsWith("Passpoint:") }) {
                return Classification(DetectionCategory.UNCOMMON, "Uncommon Passpoint (Hotspot 2.0) AP")
            }
            if (securitySuite == SecuritySuite.WPA) {
                return Classification(DetectionCategory.UNCOMMON, "Legacy WPA1 / TKIP network")
            }
            if (securitySuite == SecuritySuite.OPEN) {
                return Classification(DetectionCategory.UNCOMMON, "Unencrypted Open Wi-Fi network")
            }
        }
        if (hasDatabase && !inDatabase) {
            val kind = if (type == "W") "Wi-Fi AP" else "Bluetooth device"
            return Classification(DetectionCategory.UNCOMMON, "New fixed-MAC $kind (unseen in WiGLE DB)")
        }

        // 6. Normal network / Bluetooth device
        val normalDesc = when {
            type == "W" && securitySuite != SecuritySuite.UNKNOWN -> "Standard ${securitySuite.shortLabel} network"
            type == "W" -> "Standard Wi-Fi network"
            type == "B" -> "Standard Bluetooth Classic device"
            else -> "Standard BLE device"
        }
        return Classification(DetectionCategory.NORMAL, normalDesc)
    }
}
