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

    private fun wepIssue() = SecurityIssue(
        title = "WEP Encryption (Broken)",
        detail = "WEP was deprecated in 2004. Weak RC4 key scheduling and 24-bit IV reuse allow unauthenticated eavesdroppers to passively capture packets and recover the full encryption key in minutes using statistical tools.",
        recommendation = "Do not connect or transmit private data. Reconfigure the router to WPA2-AES (CCMP) or WPA3-SAE.",
        severity = SecurityIssueSeverity.CRITICAL,
    )

    private fun openIssue() = SecurityIssue(
        title = "Open Network (Unencrypted)",
        detail = "All 802.11 frames are transmitted without encryption. Anyone in radio range can passively observe unencrypted traffic or impersonate the SSID.",
        recommendation = "Use a trusted VPN if connecting, or enable WPA2/WPA3 or OWE on the access point.",
        severity = SecurityIssueSeverity.HIGH,
    )

    private fun tkipIssue() = SecurityIssue(
        title = "TKIP Cipher (Deprecated)",
        detail = "TKIP uses RC4 and the deprecated Michael MIC algorithm. Deprecated by the Wi-Fi Alliance.",
        recommendation = "Disable TKIP in router settings; configure WPA2-AES (CCMP) or WPA3 only.",
        severity = SecurityIssueSeverity.MEDIUM,
    )

    fun parse(capabilities: String?): ParsedCapabilities {
        val raw = capabilities?.trim().orEmpty()
        val upper = raw.uppercase()

        val mode = when {
            upper.contains("ESS") -> NetworkMode.INFRASTRUCTURE
            upper.contains("IBSS") -> NetworkMode.ADHOC
            upper.contains("MESH") -> NetworkMode.MESH
            raw.isEmpty() -> NetworkMode.UNKNOWN
            else -> NetworkMode.INFRASTRUCTURE
        }

        val hasWps = upper.contains("WPS")
        val wpsDetails = when {
            upper.contains("WPS-PBC") -> "Push Button (PBC)"
            upper.contains("WPS-PIN") -> "PIN"
            hasWps -> "Enabled"
            else -> null
        }

        val isFastTransition = upper.contains("FT/") || upper.contains("FT-")
        val isPasspoint = upper.contains("HS20") || upper.contains("PASSPOINT")

        val ciphers = buildList {
            if (upper.contains("CCMP")) add("CCMP (AES)")
            if (upper.contains("GCMP")) add("GCMP")
            if (upper.contains("TKIP")) add("TKIP")
            if (upper.contains("WEP")) add("WEP")
        }

        val hasSae = upper.contains("SAE")
        val hasPsk = upper.contains("PSK")
        val isWpa3 = upper.contains("WPA3") || hasSae || upper.contains("SUITE_B")
        val isWpa2 = upper.contains("WPA2") || (upper.contains("RSN") && !hasSae)
        val isWpa1 = upper.contains("WPA") && !upper.contains("WPA2") && !isWpa3
        val isEap = upper.contains("EAP") || upper.contains("8021X") || upper.contains("SUITE_B")
        val isOwe = upper.contains("OWE")
        val isWep = upper.contains("WEP")

        val suite = when {
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
            else -> SecuritySuite.OPEN
        }

        val akmList = buildList {
            if (hasSae) add("SAE")
            if (hasPsk) add("PSK")
            if (isEap) add("EAP (802.1X)")
            if (isOwe) add("OWE")
            if (isWep) add("WEP Key")
        }
        val keyManagement = when {
            akmList.isNotEmpty() -> akmList.joinToString(", ")
            suite == SecuritySuite.OPEN -> "None"
            else -> "Standard"
        }

        val issues = buildList {
            when {
                suite == SecuritySuite.WEP || isWep -> add(wepIssue())
                suite == SecuritySuite.OPEN -> add(openIssue())
            }
            if (hasWps) {
                val isPin = wpsDetails == "PIN"
                add(
                    SecurityIssue(
                        title = "WPS Enabled" + (wpsDetails?.let { " ($it)" } ?: ""),
                        detail = "Wi-Fi Protected Setup PIN registration has protocol flaws susceptible to brute-force recovery.",
                        recommendation = "Disable WPS in the access point administration settings.",
                        severity = if (isPin) SecurityIssueSeverity.HIGH else SecurityIssueSeverity.MEDIUM,
                    )
                )
            }
            if ((upper.contains("TKIP") || suite == SecuritySuite.WPA || suite == SecuritySuite.WPA_ENTERPRISE) && suite != SecuritySuite.WEP) {
                add(tkipIssue())
            }
            if (mode == NetworkMode.ADHOC) {
                add(
                    SecurityIssue(
                        title = "Ad-Hoc Network (IBSS)",
                        detail = "Independent Basic Service Set connects devices directly peer-to-peer without centralized AP access control.",
                        recommendation = "Verify peer device identity before communicating over ad-hoc Wi-Fi.",
                        severity = SecurityIssueSeverity.LOW,
                    )
                )
            }
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
        adv.firstOrNull { it.startsWith("Raw Caps: ") }?.substringAfter("Raw Caps: ")?.let { return parse(it) }
        val secLine = adv.firstOrNull { it.startsWith("Security: ") }?.substringAfter("Security: ")
        return if (secLine != null && secLine.contains("[")) parse(secLine) else null
    }

    fun fromSuite(suite: SecuritySuite): ParsedCapabilities {
        val issues = when (suite) {
            SecuritySuite.WEP -> listOf(wepIssue())
            SecuritySuite.OPEN -> listOf(openIssue())
            SecuritySuite.WPA, SecuritySuite.WPA_ENTERPRISE -> listOf(tkipIssue())
            else -> emptyList()
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
