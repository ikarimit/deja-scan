# Deja Scan

An Android app that imports the SQLite backup database of the WiGLE Android app and checks, live, whether any
saved devices (Wi-Fi access points, Bluetooth Classic, Bluetooth LE) are nearby — while classifying RF rarity,
outdated security, randomized MACs, and potential wireless audit/attack tools.

Not affiliated with WiGLE, IEEE, the Bluetooth SIG, or OpenStreetMap.

## Features

- **WiGLE SQLite Import & Two-Way Merge:** Import WiGLE `.sqlite` backups with observation histories (retaining frequency and security capabilities) and merge newly discovered live networks and GPS sightings directly back into the linked WiGLE file from the **Devices** tab.
- **6-Tier Color Classification Scheme:** Unified color coding across Radar blips, Signal rows, Vendor lists, Device cards, Map dots, and the Info inspector:
  - **Threat / Potential Attacker (`#FF2A55` Crimson):** Pwnagotchi (`pwnd` / `de:ad:be:ef:de:ad`), Hak5 WiFi Pineapple (`00:13:37` / PineAP), Flipper Zero, ESP32 Marauder, 802.11 Deauther, and rogue AP signatures.
  - **Outdated WEP (`#FF6D00` Blaze Orange):** Broken RC4 WEP encryption (live beacons and stored WiGLE records).
  - **Rare Detection (`#FFD600` Gold):** Wi-Fi 7 (802.11be / 320 MHz), 6 GHz band (Wi-Fi 6E / 7), 2.4 GHz Ch 14, Ad-Hoc (`IBSS`), 802.11s Mesh, 60 GHz WiGig, WPA3-Enterprise Suite-B, rare Bluetooth classes (Medical, Imaging, Toy), and unique uncommon hardware vendor OUIs.
  - **Uncommon (`#00E5FF` Cyan):** OWE (Enhanced Open), 160 MHz wide channels, 802.1X Enterprise, Passpoint (Hotspot 2.0), legacy WPA1/TKIP, unencrypted Open Wi-Fi, and newly discovered fixed-MAC devices unseen in the linked WiGLE database.
  - **Randomized MAC (`#B388FF` Purple):** Locally-administered / private ephemeral Wi-Fi BSSIDs and BLE addresses.
  - **Normal (`#39FF88` Phosphor Green):** Standard WPA2/WPA3 networks and fixed-MAC Bluetooth devices.
- **Channel & Channel Width Everywhere:** Even without a dedicated spectrum analyzer screen, Wi-Fi channel numbers and channel widths (e.g., `Ch 36 • 80MHz`, `Ch 1 (6G) • 320MHz`, plus MHz frequency span) are surfaced directly in **Live** (`Radar`, `Signals`, `Vendors`), **Devices** cards, **Map** selection HUD tags, and the **Info** inspector.
- **Condensed, Compartmentalized UI:**
  - **Live:** Slim header with one-tap `SCAN`/`STOP`, `BG` permission badge, and interactive `ROOT` / `ROOT • MON` / `USER` indicator; compact telemetry & clickable category counter strip; and four focused compartments:
    1. *Radar:* 360° proximity sweep (`ALL` vs. `WATCHED` scope toggle) with pulsing rings on threats and WEP.
    2. *Signals:* Ranked live feed with quick filter pills (`ALL`, `WATCHED`, `THREAT/WEP`, `RARE`, `RAND`), channel + width tags, and signal meters.
    3. *Activity:* Combined **Signal History** timelines, rolling **AP Density** per-minute chart, and **Passive 802.11 Monitor** (`SIGNAL` / `DENSITY` / `MONITOR`).
    4. *Vendors:* Live manufacturer grouping (IEEE OUI & Bluetooth SIG company IDs) with monogram badges and search.
  - **Devices:** Collapsible *Watch & Notification Rules*, *WiGLE Database Sync*, and *Root / Promiscuous Mode* compartments, instant SSID/MAC/Date search, category filter chips, and tap-to-inspect details.
  - **Map:** Offline Mapsforge vector rendering with a collapsible `LAYERS` menu (`LIVE`, `WIGLE`, `UNWATCHED`, `WI-FI`, `BT`, `BLE`, `SOLO`, `LINKS`, `TRAIL`), color-coded dots, on-map HUD tags with channel + width, and movement-based position estimation.
- **Root / Promiscuous Mode (Optional, Passive Only):** On rooted Android devices with `tcpdump` and monitor/promiscuous driver support (concurrent `iw` virtual `dejamon0` VIF with `flags none`, external OTG wireless adapters, opt-in Qualcomm `con_mode`, or link-layer `promisc on` fallback), Deja Scan can passively inspect unencrypted 802.11 headers (`scan/promiscuous/`) to provide:
  - **802.11 Control & Management Frame Extraction:** Live counters for `RTS`, `CTS`, `ACK`, `BlockAck`, `PS-Poll`, `Beacon`, `Probe Req`, `Data` headers, and passive `Deauth`/`Disassoc` frame & reason-code visibility.
  - **Passive Hidden SSID Unmasking:** Automatically correlates hidden-SSID beacons (`len=0` or NUL-padded SSIDs) with legitimate client `Probe Response`, `Association Request`, and `Reassociation Request` frames to reveal the SSID without transmitting a single packet.
  - **Client Station Tracking:** Identifies active Wi-Fi client stations (`ToDS`/`FromDS` data headers, `PS-Poll`, association, and probe requests), their associated AP BSSID, RSSI, probed SSIDs, and hardware OUI vs. randomized MAC status.
- **Minimal, Quiet Notifications:** Low-footprint `IMPORTANCE_MIN` ongoing service indicator (no status-bar badge, no sound/vibration), zero foreground popups, no security notification spam, and an optional rate-limited silent summary when watched devices are detected in the background.
- **Hot/Cold Proximity Tracker:** On-demand `TRACK` tab inside the device inspector with configurable audible beeps and haptic feedback.
- **Privacy First:** 100% offline, no cloud connectivity, zero internet permission (`android.permission.INTERNET` is not declared).

## Legal & Ethical Use Notice

**Deja Scan does not condone, facilitate, or support illegal activities, unauthorized network intrusion, or wireless interference.**

- All standard and root/promiscuous capabilities in this application are **strictly passive, receive-only RF diagnostics** (`flags none` on monitor interfaces, header-only 802.11 inspection).
- Deja Scan **never** performs packet injection, deauthentication attacks, active SSID brute-forcing, EAPOL/handshake cracking, or payload decryption.
- Use this software only for personal situational awareness, wireless troubleshooting, and authorized security auditing in compliance with local telecommunications and privacy laws.

## Build

Requirements: JDK 17 or newer, Android SDK platform 37 and build-tools 37.0.0 (see `app/build.gradle.kts`).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties     # not committed
python3 scripts/fetch_vendor_data.py                     # downloads the manufacturer name tables (see below)
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The app also runs without the name tables;
manufacturer names are then simply omitted.

## Data you supply

- A WiGLE database backup (`.sqlite` from the WiGLE Android app).
- Offline map files in Mapsforge `.map` format, e.g. from <https://download.mapsforge.org/>. Add them with the `MAPS`
  button on the **Map** tab. Map files over 100 MB are drawn from a configurable detail zoom level (default 10) to keep
  memory usage low.

## Manufacturer name tables

`scripts/fetch_vendor_data.py` builds `vendors.tsv` and `companies.tsv` from the public IEEE OUI registries and
the Bluetooth SIG company identifiers. They are not committed to this repository because their redistribution terms
were not confirmed; check the publishers' terms before redistributing them.

## Documentation

`docs/DECISIONS.md` (what was decided and why), `docs/SOURCES.md` (every outside source, with how far it was
verified), and `docs/CHANGELOG.md`.

## Licence

MIT, see `LICENSE`. The Mapsforge libraries used are LGPL-3.0 with a linking exception, see `docs/SOURCES.md`.
