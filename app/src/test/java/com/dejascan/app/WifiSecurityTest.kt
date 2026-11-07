package com.dejascan.app

import com.dejascan.app.scan.NetworkMode
import com.dejascan.app.scan.SecurityIssueSeverity
import com.dejascan.app.scan.SecurityLevel
import com.dejascan.app.scan.SecuritySuite
import com.dejascan.app.scan.Sighting
import com.dejascan.app.scan.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSecurityTest {

    @Test
    fun essAloneMeansOpenInfrastructureNetworkNotSecurity() {
        val parsed = WifiSecurity.parse("[ESS]")
        // ESS specifies multi-AP infrastructure distribution, NOT security
        assertEquals(SecuritySuite.OPEN, parsed.suite)
        assertEquals(SecurityLevel.OPEN, parsed.suite.level)
        assertEquals(NetworkMode.INFRASTRUCTURE, parsed.mode)
        assertEquals("None", parsed.keyManagement)
        assertTrue(parsed.ciphers.isEmpty())
        assertFalse(parsed.hasWps)
    }

    @Test
    fun ibssMeansAdhocPeerToPeer() {
        val parsed = WifiSecurity.parse("[IBSS]")
        assertEquals(SecuritySuite.OPEN, parsed.suite)
        assertEquals(NetworkMode.ADHOC, parsed.mode)
    }

    @Test
    fun parseWpa2Personal() {
        val parsed = WifiSecurity.parse("[WPA2-PSK-CCMP][ESS]")
        assertEquals(SecuritySuite.WPA2, parsed.suite)
        assertEquals(NetworkMode.INFRASTRUCTURE, parsed.mode)
        assertEquals("PSK", parsed.keyManagement)
        assertTrue(parsed.ciphers.contains("CCMP (AES)"))
        assertFalse(parsed.hasWps)
    }

    @Test
    fun parseWpa3Personal() {
        val parsed = WifiSecurity.parse("[WPA3-SAE-CCMP][RSN-SAE-CCMP][ESS]")
        assertEquals(SecuritySuite.WPA3, parsed.suite)
        assertEquals(SecurityLevel.HIGH, parsed.suite.level)
        assertEquals("SAE", parsed.keyManagement)
        assertTrue(parsed.ciphers.contains("CCMP (AES)"))
    }

    @Test
    fun parseWpa2Wpa3Transition() {
        val parsed = WifiSecurity.parse("[WPA2-PSK-CCMP][WPA3-SAE-CCMP][ESS]")
        assertEquals(SecuritySuite.WPA2_WPA3, parsed.suite)
        assertTrue(parsed.keyManagement.contains("SAE"))
        assertTrue(parsed.keyManagement.contains("PSK"))
    }

    @Test
    fun parseEnterpriseEap() {
        val parsed = WifiSecurity.parse("[WPA2-EAP-CCMP][ESS]")
        assertEquals(SecuritySuite.WPA2_ENTERPRISE, parsed.suite)
        assertEquals(SecurityLevel.ENTERPRISE, parsed.suite.level)
        assertTrue(parsed.keyManagement.contains("EAP"))
    }

    @Test
    fun parseOweEnhancedOpen() {
        val parsed = WifiSecurity.parse("[RSN-OWE-CCMP][ESS]")
        assertEquals(SecuritySuite.OWE, parsed.suite)
        assertEquals(SecurityLevel.ENHANCED_OPEN, parsed.suite.level)
    }

    @Test
    fun parseWepInsecure() {
        val parsed = WifiSecurity.parse("[WEP][ESS]")
        assertEquals(SecuritySuite.WEP, parsed.suite)
        assertEquals(SecurityLevel.INSECURE, parsed.suite.level)
        assertTrue(parsed.suite.isInsecure)
        assertTrue(parsed.ciphers.contains("WEP"))
    }

    @Test
    fun parseWpsAndFastTransition() {
        val parsed = WifiSecurity.parse("[WPA2-PSK-CCMP][ESS][WPS-PBC][FT/PSK]")
        assertTrue(parsed.hasWps)
        assertEquals("Push Button (PBC)", parsed.wpsDetails)
        assertTrue(parsed.isFastTransition)
    }

    @Test
    fun fromAdvExtractsCapsAndSecurity() {
        val adv = listOf(
            "SSID: CorporateOffice",
            "Channel: 36 (5180 MHz)",
            "Security: WPA2-Personal (PSK)",
            "Raw Caps: [WPA2-PSK-CCMP][ESS]",
        )
        val parsed = WifiSecurity.fromAdv(adv)
        assertNotNull(parsed)
        assertEquals(SecuritySuite.WPA2, parsed!!.suite)
        assertEquals(NetworkMode.INFRASTRUCTURE, parsed.mode)
    }

    @Test
    fun parseWepGeneratesCriticalSecurityIssue() {
        val parsed = WifiSecurity.parse("[WEP][ESS]")
        assertEquals(SecuritySuite.WEP, parsed.suite)
        assertTrue(parsed.securityIssues.isNotEmpty())
        val wepIssue = parsed.securityIssues.first()
        assertEquals(SecurityIssueSeverity.CRITICAL, wepIssue.severity)
        assertTrue(wepIssue.title.contains("WEP"))
        assertTrue(wepIssue.detail.contains("RC4"))
        assertTrue(wepIssue.recommendation.contains("WPA2") || wepIssue.recommendation.contains("WPA3"))
    }

    @Test
    fun parseOpenNetworkGeneratesHighSecurityIssue() {
        val parsed = WifiSecurity.parse("[ESS]")
        assertEquals(SecuritySuite.OPEN, parsed.suite)
        assertTrue(parsed.securityIssues.isNotEmpty())
        val openIssue = parsed.securityIssues.first()
        assertEquals(SecurityIssueSeverity.HIGH, openIssue.severity)
        assertTrue(openIssue.title.contains("Open"))
        assertTrue(openIssue.recommendation.contains("VPN") || openIssue.recommendation.contains("WPA2"))
    }

    @Test
    fun parseWpsIssues() {
        val pinParsed = WifiSecurity.parse("[WPA2-PSK-CCMP][WPS-PIN][ESS]")
        val pinIssue = pinParsed.securityIssues.firstOrNull { it.title.contains("WPS") }
        assertNotNull(pinIssue)
        assertEquals(SecurityIssueSeverity.HIGH, pinIssue!!.severity)

        val pbcParsed = WifiSecurity.parse("[WPA2-PSK-CCMP][WPS-PBC][ESS]")
        val pbcIssue = pbcParsed.securityIssues.firstOrNull { it.title.contains("WPS") }
        assertNotNull(pbcIssue)
        assertEquals(SecurityIssueSeverity.MEDIUM, pbcIssue!!.severity)
    }

    @Test
    fun parseTkipGeneratesMediumIssue() {
        val parsed = WifiSecurity.parse("[WPA-PSK-TKIP][ESS]")
        val tkipIssue = parsed.securityIssues.firstOrNull { it.title.contains("TKIP") }
        assertNotNull(tkipIssue)
        assertEquals(SecurityIssueSeverity.MEDIUM, tkipIssue!!.severity)
    }

    @Test
    fun fromSuiteProducesDefaultSecurityIssues() {
        val wep = WifiSecurity.fromSuite(SecuritySuite.WEP)
        assertTrue(wep.securityIssues.any { it.severity == SecurityIssueSeverity.CRITICAL })

        val open = WifiSecurity.fromSuite(SecuritySuite.OPEN)
        assertTrue(open.securityIssues.any { it.severity == SecurityIssueSeverity.HIGH })
    }

    @Test
    fun sightingSecurityProperties() {
        val wepSighting = Sighting(
            mac = "00:11:22:33:44:55",
            name = "VulnerableNet",
            type = "W",
            randomised = false,
            rssi = -65,
            time = 1000L,
            firstSeen = 1000L,
            best = -65,
            count = 1,
            history = emptyList(),
            freq = 2412,
            adv = listOf("Security: WEP (Insecure)", "Raw Caps: [WEP][ESS]"),
            wigleLastTime = 0L,
            lat = 0.0,
            lon = 0.0,
            securitySuite = SecuritySuite.WEP,
        )
        assertEquals(SecuritySuite.WEP, wepSighting.parsedCapabilities?.suite)
        assertFalse(wepSighting.isOpen)
        assertTrue(wepSighting.parsedCapabilities!!.securityIssues.any { it.severity == SecurityIssueSeverity.CRITICAL })

        val secureSighting = Sighting(
            mac = "00:11:22:33:44:66",
            name = "SecureNet",
            type = "W",
            randomised = false,
            rssi = -60,
            time = 1000L,
            firstSeen = 1000L,
            best = -60,
            count = 1,
            history = emptyList(),
            freq = 5180,
            adv = listOf("Security: WPA3-Personal (SAE)", "Raw Caps: [WPA3-SAE-CCMP][ESS]"),
            wigleLastTime = 0L,
            lat = 0.0,
            lon = 0.0,
            securitySuite = SecuritySuite.WPA3,
        )
        assertFalse(secureSighting.isOpen)
        assertTrue(secureSighting.parsedCapabilities!!.securityIssues.isEmpty())
    }
}
