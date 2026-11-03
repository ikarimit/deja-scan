package com.dejascan.app.scan

enum class SecurityLevel {
    OPEN,            // Unencrypted / no authentication
    ENHANCED_OPEN,   // Opportunistic Wireless Encryption (OWE)
    INSECURE,        // WEP (cryptographically broken)
    LEGACY,          // WPA / TKIP
    MODERN,          // WPA2-PSK (AES-CCMP)
    HIGH,            // WPA3-SAE
    ENTERPRISE,      // 802.1X / EAP authentication
}

enum class SecuritySuite(
    val label: String,
    val shortLabel: String,
    val level: SecurityLevel,
) {
    OPEN("Open (Unencrypted)", "OPEN", SecurityLevel.OPEN),
    OWE("OWE (Enhanced Open)", "OWE", SecurityLevel.ENHANCED_OPEN),
    WEP("WEP (Insecure)", "WEP", SecurityLevel.INSECURE),
    WPA("WPA-Personal", "WPA", SecurityLevel.LEGACY),
    WPA_ENTERPRISE("WPA-Enterprise", "WPA-ENT", SecurityLevel.LEGACY),
    WPA2("WPA2-Personal (PSK)", "WPA2", SecurityLevel.MODERN),
    WPA2_ENTERPRISE("WPA2-Enterprise (EAP)", "WPA2-ENT", SecurityLevel.ENTERPRISE),
    WPA2_WPA3("WPA2/WPA3 (Transition)", "WPA2/3", SecurityLevel.MODERN),
    WPA3("WPA3-Personal (SAE)", "WPA3", SecurityLevel.HIGH),
    WPA3_ENTERPRISE("WPA3-Enterprise (Suite-B)", "WPA3-ENT", SecurityLevel.HIGH),
    ENTERPRISE("Enterprise (802.1X)", "802.1X", SecurityLevel.ENTERPRISE),
    UNKNOWN("Unknown", "UNK", SecurityLevel.OPEN);

    val isEncrypted: Boolean get() = this != OPEN && this != UNKNOWN
    val isInsecure: Boolean get() = this == OPEN || this == WEP
}

enum class SecurityIssueSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
}

data class SecurityIssue(
    val title: String,
    val summary: String,
    val detail: String,
    val recommendation: String,
    val severity: SecurityIssueSeverity,
)

enum class NetworkMode(val label: String, val description: String) {
    INFRASTRUCTURE("Infrastructure (ESS)", "Multi-AP / Distribution network"),
    ADHOC("Ad-Hoc (IBSS)", "Peer-to-peer / Independent BSS"),
    MESH("Mesh", "802.11s Mesh network"),
    UNKNOWN("Unknown", "Unspecified network mode"),
}

data class ParsedCapabilities(
    val raw: String,
    val suite: SecuritySuite,
    val keyManagement: String,
    val ciphers: List<String>,
    val mode: NetworkMode,
    val hasWps: Boolean,
    val wpsDetails: String?,
    val isFastTransition: Boolean,
    val isPasspoint: Boolean,
    val securityIssues: List<SecurityIssue> = emptyList(),
)

object WifiSecurity {

    fun parse(capabilities: String?): ParsedCapabilities {
        val raw = capabilities?.trim().orEmpty()
        val upper = raw.uppercase()

        // 1. Network Mode: ESS refers to Extended Service Set (Infrastructure multi-AP network)
        val mode = when {
            upper.contains("[ESS]") || upper.contains("ESS") -> NetworkMode.INFRASTRUCTURE
            upper.contains("[IBSS]") || upper.contains("IBSS") -> NetworkMode.ADHOC
            upper.contains("[MESH]") || upper.contains("MESH") -> NetworkMode.MESH
            raw.isEmpty() -> NetworkMode.UNKNOWN
            else -> NetworkMode.INFRASTRUCTURE
        }

        // 2. WPS detection
        val hasWps = upper.contains("WPS")
        val wpsDetails = when {
            upper.contains("WPS-PBC") -> "Push Button (PBC)"
            upper.contains("WPS-PIN") -> "PIN"
            hasWps -> "Enabled"
            else -> null
        }

        // 3. Fast BSS Transition (802.11r) and Passpoint (Hotspot 2.0)
        val isFastTransition = upper.contains("FT/") || upper.contains("FT-")
        val isPasspoint = upper.contains("HS20") || upper.contains("PASSPOINT")

        // 4. Ciphers
        val ciphers = ArrayList<String>()
        if (upper.contains("CCMP")) ciphers.add("CCMP (AES)")
        if (upper.contains("GCMP")) ciphers.add("GCMP")
        if (upper.contains("TKIP")) ciphers.add("TKIP")
        if (upper.contains("WEP")) ciphers.add("WEP")

        // 5. Security Suite & Key Management
        val hasSae = upper.contains("SAE")
        val hasPsk = upper.contains("PSK")
        val isWpa3 = upper.contains("WPA3") || hasSae || upper.contains("SUITE_B")
        val isWpa2 = upper.contains("WPA2") || (upper.contains("RSN") && !hasSae)
        val isWpa1 = upper.contains("WPA-") || (upper.contains("WPA") && !upper.contains("WPA2") && !isWpa3)
        val isEap = upper.contains("EAP") || upper.contains("8021X") || upper.contains("IEEE8021X") || upper.contains("SUITE_B")
        val isOwe = upper.contains("OWE")
        val isWep = upper.contains("WEP")

        val suite: SecuritySuite = when {
            isOwe -> SecuritySuite.OWE
            isEap && isWpa3 -> SecuritySuite.WPA3_ENTERPRISE
            isEap && isWpa2 -> SecuritySuite.WPA2_ENTERPRISE
            isEap && isWpa1 -> SecuritySuite.WPA_ENTERPRISE
            isEap -> SecuritySuite.ENTERPRISE
            hasSae && hasPsk -> SecuritySuite.WPA2_WPA3
            hasSae || (isWpa3 && !hasPsk) -> SecuritySuite.WPA3
            isWpa2 || hasPsk -> SecuritySuite.WPA2
            isWpa1 -> SecuritySuite.WPA
            isWep -> SecuritySuite.WEP
            upper.contains("WPA") -> SecuritySuite.WPA
            else -> SecuritySuite.OPEN
        }

        val akmList = ArrayList<String>()
        if (hasSae) akmList.add("SAE")
        if (hasPsk) akmList.add("PSK")
        if (isEap) akmList.add("EAP (802.1X)")
        if (isOwe) akmList.add("OWE")
        if (isWep) akmList.add("WEP Key")
        val keyManagement = if (akmList.isNotEmpty()) akmList.joinToString(", ") else if (suite == SecuritySuite.OPEN) "None" else "Standard"

        // 6. Security issues / vulnerability detection
        val issues = ArrayList<SecurityIssue>()
        if (suite == SecuritySuite.WEP || ciphers.any { it.contains("WEP") }) {
            issues.add(
                SecurityIssue(
                    title = "WEP Encryption (Broken)",
                    summary = "Cryptographically compromised (RC4 IV collisions)",
                    detail = "WEP was deprecated in 2004. Weak RC4 key scheduling and 24-bit IV reuse allow unauthenticated eavesdroppers to passively capture packets and recover the full encryption key in minutes using statistical tools (e.g. Aircrack-ng / PTW).",
                    recommendation = "Do not connect or transmit private data. Reconfigure the router to WPA2-AES (CCMP) or WPA3-SAE.",
                    severity = SecurityIssueSeverity.CRITICAL,
                )
            )
        } else if (suite == SecuritySuite.OPEN) {
            issues.add(
                SecurityIssue(
                    title = "Open Network (Unencrypted)",
                    summary = "Cleartext transmission / no encryption",
                    detail = "All 802.11 frames are transmitted without encryption. Anyone in radio range can intercept packets, harvest session credentials, or mount Evil Twin rogue AP attacks.",
                    recommendation = "Use a trusted VPN if connecting, or enable WPA2/WPA3 or OWE on the access point.",
                    severity = SecurityIssueSeverity.HIGH,
                )
            )
        }

        if (hasWps) {
            val isPin = wpsDetails?.contains("PIN") == true
            issues.add(
                SecurityIssue(
                    title = "WPS Enabled" + if (wpsDetails != null) " ($wpsDetails)" else "",
                    summary = "PIN brute-force vulnerability",
                    detail = "Wi-Fi Protected Setup allows external device registration via PIN. Protocol design flaws allow offline and online PIN brute-forcing (Pixie Dust / Reaver attacks) in minutes without knowing the WPA passphrase.",
                    recommendation = "Disable WPS in the access point administration settings.",
                    severity = if (isPin) SecurityIssueSeverity.HIGH else SecurityIssueSeverity.MEDIUM,
                )
            )
        }

        if ((ciphers.any { it.contains("TKIP") } || suite == SecuritySuite.WPA || suite == SecuritySuite.WPA_ENTERPRISE) && suite != SecuritySuite.WEP) {
            issues.add(
                SecurityIssue(
                    title = "TKIP Cipher (Deprecated)",
                    summary = "Legacy cipher vulnerable to packet injection",
                    detail = "TKIP uses RC4 and the vulnerable Michael MIC algorithm (Beck-Tews / chopchop attacks). Deprecated by the Wi-Fi Alliance.",
                    recommendation = "Disable TKIP in router settings; configure WPA2-AES (CCMP) or WPA3 only.",
                    severity = SecurityIssueSeverity.MEDIUM,
                )
            )
        }

        if (mode == NetworkMode.ADHOC) {
            issues.add(
                SecurityIssue(
                    title = "Ad-Hoc Network (IBSS)",
                    summary = "Peer-to-peer network without centralized control",
                    detail = "Independent Basic Service Set connects devices directly peer-to-peer, which may bypass network firewalls or access controls.",
                    recommendation = "Verify peer device identity before communicating over ad-hoc Wi-Fi.",
                    severity = SecurityIssueSeverity.LOW,
                )
            )
        }

        return ParsedCapabilities(
            raw = raw,
            suite = suite,
            keyManagement = keyManagement,
            ciphers = ciphers,
            mode = mode,
            hasWps = hasWps,
            wpsDetails = wpsDetails,
            isFastTransition = isFastTransition,
            isPasspoint = isPasspoint,
            securityIssues = issues,
        )
    }

    /** Extracts and parses capabilities from advertised info lines. */
    fun fromAdv(adv: List<String>): ParsedCapabilities? {
        val rawCaps = adv.firstOrNull { it.startsWith("Raw Caps: ") }?.substringAfter("Raw Caps: ")
        if (rawCaps != null) return parse(rawCaps)
        val secLine = adv.firstOrNull { it.startsWith("Security: ") }?.substringAfter("Security: ")
        if (secLine != null && secLine.contains("[")) return parse(secLine)
        return null
    }

    fun fromSuite(suite: SecuritySuite): ParsedCapabilities {
        val issues = ArrayList<SecurityIssue>()
        when (suite) {
            SecuritySuite.WEP -> issues.add(
                SecurityIssue(
                    title = "WEP Encryption (Broken)",
                    summary = "Cryptographically compromised (RC4 IV collisions)",
                    detail = "WEP was deprecated in 2004. Weak RC4 key scheduling and 24-bit IV reuse allow unauthenticated eavesdroppers to passively capture packets and recover the full encryption key in minutes using statistical tools (e.g. Aircrack-ng / PTW).",
                    recommendation = "Do not connect or transmit private data. Reconfigure the router to WPA2-AES (CCMP) or WPA3-SAE.",
                    severity = SecurityIssueSeverity.CRITICAL,
                )
            )
            SecuritySuite.OPEN -> issues.add(
                SecurityIssue(
                    title = "Open Network (Unencrypted)",
                    summary = "Cleartext transmission / no encryption",
                    detail = "All 802.11 frames are transmitted without encryption. Anyone in radio range can intercept packets, harvest session credentials, or mount Evil Twin rogue AP attacks.",
                    recommendation = "Use a trusted VPN if connecting, or enable WPA2/WPA3 or OWE on the access point.",
                    severity = SecurityIssueSeverity.HIGH,
                )
            )
            SecuritySuite.WPA, SecuritySuite.WPA_ENTERPRISE -> issues.add(
                SecurityIssue(
                    title = "TKIP Cipher / WPA Legacy",
                    summary = "Deprecated TKIP cipher",
                    detail = "WPA1 relies on TKIP with Michael MIC, which has known packet forgery attacks (Beck-Tews / chopchop). Deprecated by the Wi-Fi Alliance.",
                    recommendation = "Upgrade network security to WPA2-AES (CCMP) or WPA3.",
                    severity = SecurityIssueSeverity.MEDIUM,
                )
            )
            else -> Unit
        }

        return ParsedCapabilities(
            raw = "",
            suite = suite,
            keyManagement = when (suite) {
                SecuritySuite.WPA3 -> "SAE"
                SecuritySuite.WPA2_WPA3 -> "SAE, PSK"
                SecuritySuite.WPA2, SecuritySuite.WPA -> "PSK"
                SecuritySuite.WPA3_ENTERPRISE, SecuritySuite.WPA2_ENTERPRISE, SecuritySuite.WPA_ENTERPRISE, SecuritySuite.ENTERPRISE -> "EAP (802.1X)"
                SecuritySuite.OWE -> "OWE"
                SecuritySuite.WEP -> "WEP Key"
                SecuritySuite.OPEN, SecuritySuite.UNKNOWN -> "None"
            },
            ciphers = if (suite == SecuritySuite.WEP) listOf("WEP") else emptyList(),
            mode = NetworkMode.INFRASTRUCTURE,
            hasWps = false,
            wpsDetails = null,
            isFastTransition = false,
            isPasspoint = false,
            securityIssues = issues,
        )
    }
}
