package com.dejascan.app

import com.dejascan.app.data.Device
import com.dejascan.app.scan.SightingBus
import com.dejascan.app.scan.promiscuous.CtrlSubtype
import com.dejascan.app.scan.promiscuous.Dot11FrameParser
import com.dejascan.app.scan.promiscuous.InMemoryRestoreStateStore
import com.dejascan.app.scan.promiscuous.MgmtSubtype
import com.dejascan.app.scan.promiscuous.MonitorState
import com.dejascan.app.scan.promiscuous.ParsedDot11Frame
import com.dejascan.app.scan.promiscuous.PromiscuousMonitor
import com.dejascan.app.scan.promiscuous.PromiscuousTracker
import com.dejascan.app.scan.promiscuous.RestoreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PromiscuousModeTest {

    private fun macBytes(mac: String): ByteArray =
        mac.split(':').map { it.toInt(16).toByte() }.toByteArray()

    private fun writeU16Le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeI32Le(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    /** Builds a Radiotap v0 header with Flags (bit 1), Channel (bit 3), and dBm Signal (bit 5). */
    private fun buildRadiotap(freqMhz: Int = 5180, rssiDbm: Int = -54, flags: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0) // version 0
        out.write(0) // pad
        writeU16Le(out, 16) // header length = 16 bytes
        val present = (1 shl 1) or (1 shl 3) or (1 shl 5)
        writeI32Le(out, present)
        out.write(flags) // offset 8: flags
        out.write(0) // offset 9: alignment pad for 2-byte channel
        writeU16Le(out, freqMhz) // offset 10..11: channel freq
        writeU16Le(out, 0x0140) // offset 12..13: channel flags
        out.write(rssiDbm) // offset 14: signed dBm antenna signal
        out.write(0) // offset 15: pad to 16
        return out.toByteArray()
    }

    private fun buildControlFrame(subtype: Int, ra: String, ta: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((subtype shl 4) or (1 shl 2)) // type = 1 (Control)
        out.write(0)
        writeU16Le(out, 0) // duration
        out.write(macBytes(ra))
        if (ta != null) out.write(macBytes(ta))
        return out.toByteArray()
    }

    private fun buildMgmtHeader(subtype: Int, da: String, sa: String, bssid: String): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        out.write(subtype shl 4) // type = 0 (Management)
        out.write(0)
        writeU16Le(out, 0) // duration
        out.write(macBytes(da))
        out.write(macBytes(sa))
        out.write(macBytes(bssid))
        writeU16Le(out, 0) // seq ctrl
        return out
    }

    private fun buildBeaconOrProbeResp(
        subtype: MgmtSubtype,
        da: String,
        bssid: String,
        ssidBytes: ByteArray?,
        dsChannel: Int = 0,
        privacy: Boolean = true,
    ): ByteArray {
        val out = buildMgmtHeader(subtype.code, da = da, sa = bssid, bssid = bssid)
        repeat(8) { out.write(0) } // 8-byte timestamp
        writeU16Le(out, 100) // beacon interval
        writeU16Le(out, if (privacy) 0x0011 else 0x0001) // capability info
        if (ssidBytes != null) {
            out.write(0) // Tag 0: SSID
            out.write(ssidBytes.size)
            out.write(ssidBytes)
        }
        if (dsChannel > 0) {
            out.write(3) // Tag 3: DS Parameter Set
            out.write(1)
            out.write(dsChannel)
        }
        return out.toByteArray()
    }

    @Test
    fun testRadiotapParsingAndFcsHandling() {
        val rts = buildControlFrame(subtype = 11, ra = "00:11:22:33:44:55", ta = "aa:bb:cc:dd:ee:02")
        val rtWithFcs = buildRadiotap(freqMhz = 5180, rssiDbm = -52, flags = 0x10) // RADIOTAP_F_FCS
        val pkt = rtWithFcs + rts + byteArrayOf(0x12, 0x34, 0x56, 0x78) // 4-byte FCS trailer

        val parsed = Dot11FrameParser.parsePacket(pkt) as? ParsedDot11Frame.Control
        assertNotNull(parsed)
        assertEquals(CtrlSubtype.RTS, parsed!!.subtype)
        assertEquals(-52, parsed.rssi)
        assertEquals(5180, parsed.freqMhz)
        assertEquals("00:11:22:33:44:55", parsed.receiverMac)
        assertEquals("aa:bb:cc:dd:ee:02", parsed.transmitterMac)

        // Corrupt FCS frames must be dropped
        val rtBadFcs = buildRadiotap(freqMhz = 5180, rssiDbm = -52, flags = 0x40) // RADIOTAP_F_BADFCS
        assertNull(Dot11FrameParser.parsePacket(rtBadFcs + rts))
    }

    @Test
    fun testControlFrameExtractionAndPsPollAssociation() {
        val tracker = PromiscuousTracker()
        val apMac = "00:1a:2b:3c:4d:5e"
        val clientMac = "52:aa:bb:cc:dd:01"

        val psPoll = Dot11FrameParser.parseDot11(buildControlFrame(10, ra = apMac, ta = clientMac), 0, 16, rssi = -63, freqMhz = 2437)
        val rts = Dot11FrameParser.parseDot11(buildControlFrame(11, ra = apMac, ta = clientMac), 0, 16, rssi = -61, freqMhz = 2437)
        val cts = Dot11FrameParser.parseDot11(buildControlFrame(12, ra = clientMac), 0, 10, rssi = -49, freqMhz = 2437)
        val ack = Dot11FrameParser.parseDot11(buildControlFrame(13, ra = apMac), 0, 10, rssi = -60, freqMhz = 2437)
        val ba = Dot11FrameParser.parseDot11(buildControlFrame(9, ra = clientMac, ta = apMac), 0, 16, rssi = -48, freqMhz = 2437)

        listOfNotNull(psPoll, rts, cts, ack, ba).forEach { tracker.ingest(it, now = 1_000L) }

        val snap = tracker.snapshot.value
        assertEquals(1L, snap.stats.psPoll)
        assertEquals(1L, snap.stats.rts)
        assertEquals(1L, snap.stats.cts)
        assertEquals(1L, snap.stats.ack)
        assertEquals(1L, snap.stats.blockAck)
        assertEquals(5L, snap.stats.totalControl)

        val st = snap.stations[clientMac]
        assertNotNull(st)
        assertEquals(apMac, st!!.associatedBssid)
        assertEquals(-61, st.rssi)
        assertTrue(st.randomised)
        assertTrue(st.controlFrames >= 2)
    }

    @Test
    fun testHiddenSsidUnmaskingViaProbeResponseAndAssocRequest() {
        val tracker = PromiscuousTracker()
        val bssid1 = "00:22:33:44:55:66"
        val client1 = "6a:11:22:33:44:55"

        // 1. Hidden beacon with NUL-padded SSID IE + DS channel 6 (2437 MHz)
        val nulBeaconBytes = buildBeaconOrProbeResp(
            subtype = MgmtSubtype.BEACON,
            da = "ff:ff:ff:ff:ff:ff",
            bssid = bssid1,
            ssidBytes = byteArrayOf(0, 0, 0, 0, 0),
            dsChannel = 6,
        )
        val beaconFrame = Dot11FrameParser.parseDot11(nulBeaconBytes, 0, nulBeaconBytes.size, rssi = -58)
            as ParsedDot11Frame.Management
        assertTrue(beaconFrame.isHiddenSsid)
        assertNull(beaconFrame.ssid)
        assertEquals(2437, beaconFrame.freqMhz)

        tracker.ingest(beaconFrame, now = 1_000L)
        assertTrue(bssid1 in tracker.snapshot.value.hiddenBssids)
        assertTrue(tracker.snapshot.value.unmasked.isEmpty())

        // 2. Directed Probe Response reveals "OpsBackbone"
        val probeRespBytes = buildBeaconOrProbeResp(
            subtype = MgmtSubtype.PROBE_RESP,
            da = client1,
            bssid = bssid1,
            ssidBytes = "OpsBackbone".toByteArray(Charsets.UTF_8),
            dsChannel = 6,
        )
        val probeRespFrame = Dot11FrameParser.parseDot11(probeRespBytes, 0, probeRespBytes.size, rssi = -56)!!
        val event1 = tracker.ingest(probeRespFrame, now = 1_500L)
        assertNotNull(event1)
        assertTrue(event1!!.unmaskedNow)
        assertEquals("Probe Response", event1.unmaskMethod)
        assertEquals("OpsBackbone", tracker.snapshot.value.unmasked[bssid1]?.ssid)

        // 3. Hidden AP noted from WifiManager ScanResult, then unmasked via Association Request
        val bssid2 = "00:aa:bb:cc:dd:ee"
        val client2 = "00:16:ea:12:34:56"
        tracker.noteKnownAp("00:AA:BB:CC:DD:EE", isHidden = true)

        val assocOut = buildMgmtHeader(MgmtSubtype.ASSOC_REQ.code, da = bssid2, sa = client2, bssid = bssid2)
        writeU16Le(assocOut, 0x0431) // capability info
        writeU16Le(assocOut, 10) // listen interval
        val ssid2 = "SensorMesh".toByteArray(Charsets.UTF_8)
        assocOut.write(0)
        assocOut.write(ssid2.size)
        assocOut.write(ssid2)
        val assocBytes = assocOut.toByteArray()

        val assocFrame = Dot11FrameParser.parseDot11(assocBytes, 0, assocBytes.size, rssi = -64, freqMhz = 5180)!!
        val event2 = tracker.ingest(assocFrame, now = 2_000L)
        assertNotNull(event2)
        assertTrue(event2!!.unmaskedNow)
        assertEquals("Assoc Request", event2.unmaskMethod)
        assertEquals("SensorMesh", tracker.snapshot.value.unmasked[bssid2]?.ssid)
        assertEquals(bssid2, tracker.snapshot.value.stations[client2]?.associatedBssid)
        assertFalse(tracker.snapshot.value.stations[client2]!!.randomised)
    }

    @Test
    fun testSightingBusPreservesUnmaskedSsidAgainstEmptyBeacons() {
        SightingBus.clear()
        val dev = Device("00:22:33:44:55:99", "", "W", 0L, 0.0, 0.0, randomised = false, watched = true)
        SightingBus.report(
            dev = dev,
            liveName = "UnmaskedCorp",
            rssi = -60,
            freq = 5180,
            adv = listOf("Unmasked SSID: UnmaskedCorp (via Probe Response)"),
            now = 1_000L,
        )
        // Subsequent standard Android ScanResult with blank hidden SSID must NOT clobber the unmasked name
        SightingBus.report(
            dev = dev,
            liveName = "",
            rssi = -58,
            freq = 5180,
            adv = listOf("Security: WPA2-Personal (PSK)"),
            now = 2_000L,
        )
        val s = SightingBus.sightings.value[dev.mac]!!
        assertEquals("UnmaskedCorp", s.name)
        assertTrue(s.adv.any { it.startsWith("Unmasked SSID:") })
    }

    @Test
    fun testClientDataFramesProbeRequestsAndDeauthTracking() {
        val tracker = PromiscuousTracker()
        val ap = "00:11:22:33:44:00"
        val client = "02:aa:bb:11:22:33"

        // Probe Request for "LabWiFi"
        val probeOut = buildMgmtHeader(MgmtSubtype.PROBE_REQ.code, da = "ff:ff:ff:ff:ff:ff", sa = client, bssid = "ff:ff:ff:ff:ff:ff")
        val targetSsid = "LabWiFi".toByteArray(Charsets.UTF_8)
        probeOut.write(0)
        probeOut.write(targetSsid.size)
        probeOut.write(targetSsid)
        val probeBytes = probeOut.toByteArray()
        tracker.ingest(Dot11FrameParser.parseDot11(probeBytes, 0, probeBytes.size, rssi = -70, freqMhz = 2412)!!, now = 1_000L)

        // Uplink Data frame (ToDS=1, FromDS=0): addr1=BSSID, addr2=Client, addr3=DA
        val dataOut = ByteArrayOutputStream()
        dataOut.write(2 shl 2) // type = 2 (Data)
        dataOut.write(0x01) // ToDS = 1
        writeU16Le(dataOut, 0)
        dataOut.write(macBytes(ap)) // addr1 = BSSID
        dataOut.write(macBytes(client)) // addr2 = Client SA
        dataOut.write(macBytes("00:99:88:77:66:55")) // addr3 = DA
        writeU16Le(dataOut, 0)
        val dataBytes = dataOut.toByteArray()
        tracker.ingest(Dot11FrameParser.parseDot11(dataBytes, 0, dataBytes.size, rssi = -59, freqMhz = 5180)!!, now = 1_500L)

        // Broadcast Data frame (FromDS=1 to ff:ff:ff:ff:ff:ff) must be ignored as a client station
        val bcastOut = ByteArrayOutputStream()
        bcastOut.write(2 shl 2)
        bcastOut.write(0x02) // FromDS = 1
        writeU16Le(bcastOut, 0)
        bcastOut.write(macBytes("ff:ff:ff:ff:ff:ff"))
        bcastOut.write(macBytes(ap))
        bcastOut.write(macBytes(ap))
        writeU16Le(bcastOut, 0)
        assertNull(Dot11FrameParser.parseDot11(bcastOut.toByteArray(), 0, bcastOut.size()))

        // Deauth frame from AP with IEEE reason code 7
        val deauthOut = buildMgmtHeader(MgmtSubtype.DEAUTH.code, da = client, sa = ap, bssid = ap)
        writeU16Le(deauthOut, 7)
        val deauthBytes = deauthOut.toByteArray()
        tracker.ingest(Dot11FrameParser.parseDot11(deauthBytes, 0, deauthBytes.size, rssi = -55, freqMhz = 5180)!!, now = 2_000L)

        val snap = tracker.snapshot.value
        assertEquals(1L, snap.stats.probeReq)
        assertEquals(1L, snap.stats.dataHeaders)
        assertEquals(1L, snap.stats.deauth)

        val st = snap.stations[client]!!
        assertEquals(ap, st.associatedBssid)
        assertEquals(-59, st.rssi)
        assertEquals(listOf("LabWiFi"), st.probedSsids)

        val apSt = snap.apStats[ap]!!
        assertEquals(1L, apSt.dataFrames)
        assertEquals(1L, apSt.deauthFrames)
        assertEquals(7, apSt.lastReasonCode)
    }

    @Test
    fun testPcapStreamReader() {
        val pcap = ByteArrayOutputStream()
        // Global header (24 bytes, little-endian microsecond pcap, DLT_IEEE802_11_RADIO = 127)
        writeI32Le(pcap, 0xa1b2c3d4.toInt())
        writeU16Le(pcap, 2)
        writeU16Le(pcap, 4)
        writeI32Le(pcap, 0)
        writeI32Le(pcap, 0)
        writeI32Le(pcap, 256)
        writeI32Le(pcap, Dot11FrameParser.DLT_IEEE802_11_RADIO)

        fun appendPkt(payload: ByteArray) {
            writeI32Le(pcap, 1_700_000_000)
            writeI32Le(pcap, 0)
            writeI32Le(pcap, payload.size)
            writeI32Le(pcap, payload.size)
            pcap.write(payload)
        }

        val rt = buildRadiotap(freqMhz = 2462, rssiDbm = -47)
        appendPkt(rt + buildBeaconOrProbeResp(MgmtSubtype.BEACON, "ff:ff:ff:ff:ff:ff", "00:11:22:33:44:55", ByteArray(0)))
        appendPkt(rt + buildBeaconOrProbeResp(MgmtSubtype.PROBE_RESP, "aa:bb:cc:dd:ee:02", "00:11:22:33:44:55", "VaultNet".toByteArray()))
        appendPkt(rt + buildControlFrame(13, ra = "00:11:22:33:44:55"))

        val decoded = ArrayList<ParsedDot11Frame>()
        Dot11FrameParser.readPcapStream(ByteArrayInputStream(pcap.toByteArray())) { decoded.add(it) }
        assertEquals(3, decoded.size)
        assertTrue((decoded[0] as ParsedDot11Frame.Management).isHiddenSsid)
        assertEquals("VaultNet", (decoded[1] as ParsedDot11Frame.Management).ssid)
        assertEquals(CtrlSubtype.ACK, (decoded[2] as ParsedDot11Frame.Control).subtype)
    }

    @Test
    fun testPromiscuousMonitorProbeAndInterfacePreparation() {
        assertEquals("wlan0", PromiscuousMonitor.sanitizeIface("wlan0; reboot"))
        assertEquals("wlan1", PromiscuousMonitor.sanitizeIface("wlan1"))

        val tracker = PromiscuousTracker()
        var allowVif = true
        val monitor = PromiscuousMonitor(
            tracker = tracker,
            isDeviceRooted = { true },
            rootShell = { cmd ->
                when {
                    cmd == "id" -> PromiscuousMonitor.ShellResult(0, "uid=0(root) gid=0(root)")
                    cmd.contains("IFACE:") -> PromiscuousMonitor.ShellResult(
                        0,
                        """
                        IFACE:wlan0
                        BIN:tcpdump:/system/xbin/tcpdump
                        BIN:iw:/vendor/bin/iw
                        BIN:ip:/system/bin/ip
                        CONMODE:/sys/module/wlan/parameters/con_mode
                        """.trimIndent(),
                    )
                    cmd.startsWith("cat /sys/class/net/") -> PromiscuousMonitor.ShellResult(0, "1")
                    cmd.contains("interface add dejamon0 type monitor flags none") ->
                        PromiscuousMonitor.ShellResult(if (allowVif) 0 else 1, "")
                    cmd.contains("con_mode") -> PromiscuousMonitor.ShellResult(0, "")
                    cmd.contains("promisc on") -> PromiscuousMonitor.ShellResult(0, "")
                    else -> PromiscuousMonitor.ShellResult(0, "")
                }
            },
        )

        val caps = monitor.probeCapabilities()
        assertTrue(caps.rootGranted)
        assertTrue(caps.canCaptureDot11)
        assertEquals(listOf("wlan0"), caps.wirelessInterfaces)
        assertEquals("/system/xbin/tcpdump", caps.tcpdumpPath)

        // 1. Concurrent passive VIF (`flags none`) preferred when `iw` succeeds
        val vifSetup = monitor.prepareInterface(caps, preferred = "auto", allowDriverSwitch = false)
        assertNotNull(vifSetup)
        assertEquals("dejamon0", vifSetup!!.captureIface)
        assertEquals(MonitorState.ACTIVE_MONITOR, vifSetup.mode)
        assertTrue(vifSetup.teardownCmd!!.contains("dev dejamon0 del"))

        // 2. When VIF fails and driver switch is NOT opted in, falls back non-destructively to promiscuous link mode
        allowVif = false
        val promiscSetup = monitor.prepareInterface(caps, preferred = "auto", allowDriverSwitch = false)
        assertNotNull(promiscSetup)
        assertEquals("wlan0", promiscSetup!!.captureIface)
        assertEquals(MonitorState.ACTIVE_PROMISC, promiscSetup.mode)

        // 3. When VIF fails and Qualcomm con_mode fallback IS opted in, enables hardware monitor mode with restore cmd
        val qcomSetup = monitor.prepareInterface(caps, preferred = "auto", allowDriverSwitch = true)
        assertNotNull(qcomSetup)
        assertEquals("wlan0", qcomSetup!!.captureIface)
        assertEquals(MonitorState.ACTIVE_MONITOR, qcomSetup.mode)
        assertTrue(qcomSetup.teardownCmd!!.contains("echo 0 > /sys/module/wlan/parameters/con_mode"))
    }

    @Test
    fun testRejectsWritableBinaryFoldersAndRestoresAfterSimulatedCrash() {
        // Writable folders (/data/local/tmp, Termux) must never be probed or accepted as root binaries
        assertFalse(PromiscuousMonitor.isTrustedBinaryPath("/data/local/tmp/tcpdump", "tcpdump"))
        assertFalse(PromiscuousMonitor.isTrustedBinaryPath("/data/data/com.termux/files/usr/bin/iw", "iw"))
        assertFalse(PromiscuousMonitor.isTrustedBinaryPath("/system/bin/../data/local/tmp/ip", "ip"))
        assertTrue(PromiscuousMonitor.isTrustedBinaryPath("/system/xbin/tcpdump", "tcpdump"))
        assertTrue(PromiscuousMonitor.isTrustedBinaryPath("/vendor/bin/iw", "iw"))

        val tracker = PromiscuousTracker()
        val store = InMemoryRestoreStateStore()
        val executedCommands = ArrayList<String>()

        val untrustedMonitor = PromiscuousMonitor(
            tracker = tracker,
            isDeviceRooted = { true },
            restoreStore = store,
            rootShell = { cmd ->
                executedCommands += cmd
                when {
                    cmd == "id" -> PromiscuousMonitor.ShellResult(0, "uid=0(root)")
                    cmd.contains("IFACE:") -> {
                        // Ensure the probe script itself does not scan /data/local/tmp or termux
                        assertFalse(cmd.contains("/data/local/tmp"))
                        assertFalse(cmd.contains("com.termux"))
                        PromiscuousMonitor.ShellResult(
                            0,
                            """
                            IFACE:wlan0
                            BIN:tcpdump:/data/local/tmp/tcpdump
                            BIN:iw:/data/data/com.termux/files/usr/bin/iw
                            BIN:ip:/system/bin/ip
                            """.trimIndent(),
                        )
                    }
                    else -> PromiscuousMonitor.ShellResult(0, "")
                }
            },
        )
        val untrustedCaps = untrustedMonitor.probeCapabilities()
        assertNull(untrustedCaps.tcpdumpPath)
        assertNull(untrustedCaps.iwPath)
        assertFalse(untrustedCaps.canCaptureDot11)

        // Simulate enabling Qualcomm con_mode=4 and then crashing before cleanupCapture() runs
        val crashStore = InMemoryRestoreStateStore()
        val crashMonitor = PromiscuousMonitor(
            tracker = tracker,
            isDeviceRooted = { true },
            restoreStore = crashStore,
            rootShell = { cmd ->
                executedCommands += cmd
                PromiscuousMonitor.ShellResult(0, "0")
            },
        )
        val validCaps = untrustedCaps.copy(
            tcpdumpPath = "/system/xbin/tcpdump",
            ipPath = "/system/bin/ip",
            qcomConModePath = "/sys/module/wlan/parameters/con_mode",
        )
        val setup = crashMonitor.prepareInterface(validCaps, preferred = "auto", allowDriverSwitch = true)
        assertNotNull(setup)
        // Pending restore must already be persisted in the store before capture even begins
        val pending = crashStore.load()
        assertNotNull(pending)
        assertEquals(RestoreKind.QCOM_CONMODE, pending!!.kind)

        // Next app start creates a fresh PromiscuousMonitor sharing the same persisted store
        val recoveryCommands = ArrayList<String>()
        val nextStartMonitor = PromiscuousMonitor(
            tracker = tracker,
            isDeviceRooted = { true },
            restoreStore = crashStore,
            rootShell = { cmd ->
                recoveryCommands += cmd
                when {
                    cmd == "id" -> PromiscuousMonitor.ShellResult(0, "uid=0(root)")
                    cmd.contains("BIN:") -> PromiscuousMonitor.ShellResult(0, "BIN:ip:/system/bin/ip\nBIN:iw:/vendor/bin/iw")
                    else -> PromiscuousMonitor.ShellResult(0, "")
                }
            },
        )
        assertTrue(nextStartMonitor.restorePendingIfNeeded())
        assertNull(crashStore.load())
        assertTrue(recoveryCommands.any { it.contains("echo 0 > /sys/module/wlan/parameters/con_mode") })
    }
}
