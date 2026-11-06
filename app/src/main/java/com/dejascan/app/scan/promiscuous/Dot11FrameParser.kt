package com.dejascan.app.scan.promiscuous

import java.io.EOFException
import java.io.InputStream

enum class MgmtSubtype(val code: Int, val label: String) {
    ASSOC_REQ(0, "Assoc Req"),
    REASSOC_REQ(2, "Reassoc Req"),
    PROBE_REQ(4, "Probe Req"),
    PROBE_RESP(5, "Probe Resp"),
    BEACON(8, "Beacon"),
    DISASSOC(10, "Disassoc"),
    AUTH(11, "Auth"),
    DEAUTH(12, "Deauth"),
    ACTION(13, "Action"),
    OTHER(-1, "Mgmt");

    companion object {
        fun fromCode(code: Int): MgmtSubtype = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

enum class CtrlSubtype(val code: Int, val label: String) {
    BLOCK_ACK_REQ(8, "BlockAckReq"),
    BLOCK_ACK(9, "BlockAck"),
    PS_POLL(10, "PS-Poll"),
    RTS(11, "RTS"),
    CTS(12, "CTS"),
    ACK(13, "ACK"),
    CF_END(14, "CF-End"),
    OTHER(-1, "Ctrl");

    companion object {
        fun fromCode(code: Int): CtrlSubtype = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

sealed interface ParsedDot11Frame {
    val rssi: Int
    val freqMhz: Int

    data class Management(
        val subtype: MgmtSubtype,
        val bssid: String?,
        val sourceMac: String?,
        val destMac: String?,
        val ssid: String?,
        val isHiddenSsid: Boolean,
        val privacyBit: Boolean,
        val reasonCode: Int,
        override val rssi: Int,
        override val freqMhz: Int,
    ) : ParsedDot11Frame

    data class Control(
        val subtype: CtrlSubtype,
        val receiverMac: String,
        val transmitterMac: String?,
        override val rssi: Int,
        override val freqMhz: Int,
    ) : ParsedDot11Frame

    data class Data(
        val bssid: String,
        val clientMac: String,
        val toDs: Boolean,
        val fromDs: Boolean,
        override val rssi: Int,
        override val freqMhz: Int,
    ) : ParsedDot11Frame
}

/**
 * Passive, zero-dependency parser for libpcap streams and IEEE 802.11 frames (with or without Radiotap/Prism headers).
 * Inspects strictly unencrypted 802.11 MAC headers and management Information Elements; payloads are never decoded.
 */
object Dot11FrameParser {
    const val DLT_IEEE802_11 = 105
    const val DLT_PRISM_HEADER = 119
    const val DLT_IEEE802_11_RADIO = 127

    private const val PCAP_MAGIC_LE_USEC = 0xa1b2c3d4.toInt()
    private const val PCAP_MAGIC_LE_NSEC = 0xa1b23c4d.toInt()
    private const val PCAP_MAGIC_BE_USEC = 0xd4c3b2a1.toInt()
    private const val PCAP_MAGIC_BE_NSEC = 0x4d3cb2a1.toInt()
    private const val PRISM_HEADER_LEN = 144

    private const val RADIOTAP_F_FCS = 0x10
    private const val RADIOTAP_F_BADFCS = 0x40
    private val HEX = "0123456789abcdef".toCharArray()

    data class RadiotapMeta(
        val headerLen: Int,
        val rssi: Int,
        val freqMhz: Int,
        val hasFcs: Boolean,
        val badFcs: Boolean,
    )

    /** Reads a libpcap byte stream (e.g. `tcpdump -U -w -`) and invokes [onFrame] for each decoded 802.11 frame. */
    fun readPcapStream(
        input: InputStream,
        shouldContinue: () -> Boolean = { true },
        onFrame: (ParsedDot11Frame) -> Unit,
    ) {
        val globalHdr = ByteArray(24)
        if (!readFully(input, globalHdr, 24)) return
        val magic = readI32Le(globalHdr, 0)
        val littleEndian = when (magic) {
            PCAP_MAGIC_LE_USEC, PCAP_MAGIC_LE_NSEC -> true
            PCAP_MAGIC_BE_USEC, PCAP_MAGIC_BE_NSEC -> false
            else -> return
        }
        val linkType = readI32(globalHdr, 20, littleEndian)
        if (linkType != DLT_IEEE802_11_RADIO && linkType != DLT_IEEE802_11 && linkType != DLT_PRISM_HEADER) return

        val pktHdr = ByteArray(16)
        var pktBuf = ByteArray(2048)
        while (shouldContinue()) {
            if (!readFully(input, pktHdr, 16)) break
            val inclLen = readI32(pktHdr, 8, littleEndian)
            if (inclLen <= 0 || inclLen > 65_535) break
            if (inclLen > pktBuf.size) pktBuf = ByteArray(inclLen)
            if (!readFully(input, pktBuf, inclLen)) break
            parsePacket(pktBuf, 0, inclLen, linkType)?.let(onFrame)
        }
    }

    fun parsePacket(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset, linkType: Int = DLT_IEEE802_11_RADIO): ParsedDot11Frame? {
        if (length <= 0 || offset < 0 || offset + length > buf.size) return null
        return when (linkType) {
            DLT_IEEE802_11_RADIO -> {
                val rt = parseRadiotap(buf, offset, length) ?: return null
                if (rt.badFcs) return null
                val fcsTrim = if (rt.hasFcs) 4 else 0
                val dot11Len = length - rt.headerLen - fcsTrim
                if (dot11Len < 10) return null
                parseDot11(buf, offset + rt.headerLen, dot11Len, rt.rssi, rt.freqMhz)
            }
            DLT_PRISM_HEADER -> {
                if (length <= PRISM_HEADER_LEN + 10) return null
                parseDot11(buf, offset + PRISM_HEADER_LEN, length - PRISM_HEADER_LEN, -100, 0)
            }
            DLT_IEEE802_11 -> parseDot11(buf, offset, length, -100, 0)
            else -> null
        }
    }

    fun parseRadiotap(buf: ByteArray, offset: Int, length: Int): RadiotapMeta? {
        if (length < 8) return null
        val version = buf[offset].toInt() and 0xFF
        if (version != 0) return null
        val itLen = readU16Le(buf, offset + 2)
        if (itLen < 8 || itLen > length) return null

        val word0 = readI32Le(buf, offset + 4)
        var rel = 8
        var curWord = word0
        while ((curWord and (1 shl 31)) != 0) {
            if (rel + 4 > itLen) return null
            curWord = readI32Le(buf, offset + rel)
            rel += 4
        }

        var flags = 0
        var freqMhz = 0
        var rssi = -100

        // Walk standard Radiotap namespace fields in word0 up to DBM_ANTSIGNAL (bit 5)
        if ((word0 and (1 shl 0)) != 0) { // TSFT: 8 bytes, align 8
            rel = align(rel, 8) + 8
        }
        if ((word0 and (1 shl 1)) != 0) { // Flags: 1 byte
            if (rel + 1 <= itLen) flags = buf[offset + rel].toInt() and 0xFF
            rel += 1
        }
        if ((word0 and (1 shl 2)) != 0) { // Rate: 1 byte
            rel += 1
        }
        if ((word0 and (1 shl 3)) != 0) { // Channel: 2-byte freq + 2-byte flags, align 2
            rel = align(rel, 2)
            if (rel + 4 <= itLen) freqMhz = readU16Le(buf, offset + rel)
            rel += 4
        }
        if ((word0 and (1 shl 4)) != 0) { // FHSS: 2 bytes, align 2
            rel = align(rel, 2) + 2
        }
        if ((word0 and (1 shl 5)) != 0) { // dBm Antenna Signal: 1 signed byte
            if (rel + 1 <= itLen) {
                val dbm = buf[offset + rel].toInt() // sign-extended
                if (dbm in -120..-1) rssi = dbm
            }
        }

        return RadiotapMeta(
            headerLen = itLen,
            rssi = rssi,
            freqMhz = freqMhz,
            hasFcs = (flags and RADIOTAP_F_FCS) != 0,
            badFcs = (flags and RADIOTAP_F_BADFCS) != 0,
        )
    }

    fun parseDot11(buf: ByteArray, offset: Int, length: Int, rssi: Int = -100, freqMhz: Int = 0): ParsedDot11Frame? {
        if (length < 10 || offset + length > buf.size) return null
        val fc0 = buf[offset].toInt() and 0xFF
        val fc1 = buf[offset + 1].toInt() and 0xFF
        if ((fc0 and 0x03) != 0) return null // IEEE 802.11 protocol version must be 0

        val type = (fc0 ushr 2) and 0x03
        val subtype = (fc0 ushr 4) and 0x0F

        return when (type) {
            1 -> parseControl(buf, offset, length, subtype, rssi, freqMhz)
            0 -> parseManagement(buf, offset, length, subtype, fc1, rssi, freqMhz)
            2 -> parseData(buf, offset, length, fc1, rssi, freqMhz)
            else -> null
        }
    }

    private fun parseControl(buf: ByteArray, offset: Int, length: Int, subtypeCode: Int, rssi: Int, freqMhz: Int): ParsedDot11Frame.Control {
        val ra = formatMac(buf, offset + 4)
        val hasTa = subtypeCode in intArrayOf(8, 9, 10, 11, 14, 15) && length >= 16
        val ta = if (hasTa) formatMac(buf, offset + 10).takeIf { isUnicast(buf, offset + 10) } else null
        return ParsedDot11Frame.Control(
            subtype = CtrlSubtype.fromCode(subtypeCode),
            receiverMac = ra,
            transmitterMac = ta,
            rssi = rssi,
            freqMhz = freqMhz,
        )
    }

    private fun parseManagement(
        buf: ByteArray,
        offset: Int,
        length: Int,
        subtypeCode: Int,
        fc1: Int,
        rssi: Int,
        freqMhz: Int,
    ): ParsedDot11Frame.Management? {
        if (length < 24) return null
        val da = formatMac(buf, offset + 4)
        val sa = formatMac(buf, offset + 10).takeIf { isUnicast(buf, offset + 10) }
        val bssid = formatMac(buf, offset + 16).takeIf { isUnicast(buf, offset + 16) }
        val subtype = MgmtSubtype.fromCode(subtypeCode)

        val hdrLen = if ((fc1 and 0x80) != 0) 28 else 24
        val fEnd = offset + length
        var pos = offset + hdrLen
        if (pos > fEnd) return null

        var reasonCode = 0
        var privacyBit = false
        val parseIes: Boolean

        when (subtype) {
            MgmtSubtype.BEACON, MgmtSubtype.PROBE_RESP -> {
                if (pos + 12 > fEnd) return null
                val capInfo = readU16Le(buf, pos + 10)
                privacyBit = (capInfo and 0x0010) != 0
                pos += 12
                parseIes = true
            }
            MgmtSubtype.ASSOC_REQ -> {
                if (pos + 4 > fEnd) return null
                pos += 4
                parseIes = true
            }
            MgmtSubtype.REASSOC_REQ -> {
                if (pos + 10 > fEnd) return null
                pos += 10
                parseIes = true
            }
            MgmtSubtype.PROBE_REQ -> parseIes = true
            MgmtSubtype.DEAUTH, MgmtSubtype.DISASSOC -> {
                if (pos + 2 <= fEnd) reasonCode = readU16Le(buf, pos)
                parseIes = false
            }
            else -> parseIes = false
        }

        var ssid: String? = null
        var isHidden = false
        var resolvedFreq = freqMhz
        var sawSsidTag = false

        if (parseIes) {
            while (pos + 2 <= fEnd) {
                val tag = buf[pos].toInt() and 0xFF
                val len = buf[pos + 1].toInt() and 0xFF
                pos += 2
                if (pos + len > fEnd) break
                when (tag) {
                    0 -> if (!sawSsidTag && len <= 32) {
                        sawSsidTag = true
                        val allNull = len == 0 || (0 until len).all { buf[pos + it].toInt() == 0 }
                        if (allNull) {
                            if (subtype == MgmtSubtype.BEACON) isHidden = true
                        } else {
                            ssid = decodeCleanSsid(buf, pos, len)
                            if (ssid.isNullOrEmpty() && subtype == MgmtSubtype.BEACON) isHidden = true
                        }
                    }
                    3 -> if (len == 1 && resolvedFreq == 0) {
                        val ch = buf[pos].toInt() and 0xFF
                        resolvedFreq = when {
                            ch == 14 -> 2484
                            ch in 1..13 -> 2407 + ch * 5
                            ch in 36..177 -> 5000 + ch * 5
                            else -> 0
                        }
                    }
                }
                pos += len
            }
            if (subtype == MgmtSubtype.BEACON && !sawSsidTag) isHidden = true
        }

        return ParsedDot11Frame.Management(
            subtype = subtype,
            bssid = bssid,
            sourceMac = sa,
            destMac = da,
            ssid = ssid,
            isHiddenSsid = isHidden,
            privacyBit = privacyBit,
            reasonCode = reasonCode,
            rssi = rssi,
            freqMhz = resolvedFreq,
        )
    }

    private fun parseData(buf: ByteArray, offset: Int, length: Int, fc1: Int, rssi: Int, freqMhz: Int): ParsedDot11Frame.Data? {
        if (length < 24) return null
        val toDs = (fc1 and 0x01) != 0
        val fromDs = (fc1 and 0x02) != 0

        val (bssidOff, clientOff) = when {
            toDs && !fromDs -> (offset + 4) to (offset + 10)   // Client (TA=addr2) -> AP (RA/BSSID=addr1)
            !toDs && fromDs -> (offset + 10) to (offset + 4)   // AP (TA/BSSID=addr2) -> Client (RA=addr1)
            !toDs && !fromDs -> (offset + 16) to (offset + 10) // IBSS: BSSID=addr3, Peer=addr2
            else -> (offset + 4) to (offset + 10)              // WDS: RA=addr1, TA=addr2
        }

        if (!isUnicast(buf, bssidOff) || !isUnicast(buf, clientOff)) return null
        val bssid = formatMac(buf, bssidOff)
        val client = formatMac(buf, clientOff)
        if (bssid == client) return null

        return ParsedDot11Frame.Data(
            bssid = bssid,
            clientMac = client,
            toDs = toDs,
            fromDs = fromDs,
            rssi = rssi,
            freqMhz = freqMhz,
        )
    }

    /** True when the I/G multicast bit is 0 and the 6-byte MAC is non-zero. */
    fun isUnicast(buf: ByteArray, off: Int): Boolean {
        if ((buf[off].toInt() and 0x01) != 0) return false
        for (i in 0 until 6) if (buf[off + i].toInt() != 0) return true
        return false
    }

    fun formatMac(buf: ByteArray, off: Int): String {
        val chars = CharArray(17)
        var ci = 0
        for (i in 0 until 6) {
            if (i > 0) chars[ci++] = ':'
            val b = buf[off + i].toInt() and 0xFF
            chars[ci++] = HEX[b ushr 4]
            chars[ci++] = HEX[b and 0x0F]
        }
        return String(chars)
    }

    private fun decodeCleanSsid(buf: ByteArray, off: Int, len: Int): String? {
        val raw = String(buf, off, len, Charsets.UTF_8)
        val cleaned = buildString(raw.length) {
            for (ch in raw) if (ch.code >= 0x20 && ch != '\uFFFD') append(ch)
        }.trim()
        return cleaned.ifEmpty { null }
    }

    private fun align(offset: Int, alignment: Int): Int = (offset + alignment - 1) and (alignment - 1).inv()

    private fun readU16Le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun readI32Le(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun readI32Be(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun readI32(buf: ByteArray, off: Int, littleEndian: Boolean): Int =
        if (littleEndian) readI32Le(buf, off) else readI32Be(buf, off)

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var total = 0
        while (total < len) {
            val n = try {
                input.read(buf, total, len - total)
            } catch (_: EOFException) {
                -1
            }
            if (n <= 0) return false
            total += n
        }
        return true
    }
}
