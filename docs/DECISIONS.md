# Decisions

## [D-001] 2026-10-29 — Data source
- **Status:** accepted
- **Options offered:** WiGLE CSV export | WiGLE app SQLite DB | WiGLE web API | Other
- **User chose:** WiGLE app SQLite DB
- **Applies to:** project
- **Sources:** C-001

## [D-002] 2026-10-29 — Radio types to match
- **Status:** accepted
- **Options offered:** Wi-Fi BSSID | Bluetooth Classic | Bluetooth LE | Cell towers
- **User chose:** Wi-Fi (BSSID), Bluetooth Classic, Bluetooth LE (no cell)
- **Applies to:** project
- **Sources:** C-001

## [D-003] 2026-10-29 — Meaning of "close by"
- **Status:** accepted
- **Options offered:** Radio sighting | GPS location | Both
- **User chose:** Radio sighting (live scan hears the saved device; RSSI as rough distance)
- **Applies to:** project

## [D-004] 2026-10-29 — Tech stack
- **Status:** accepted
- **User chose:** Kotlin + Jetpack Compose
- **Applies to:** project

## [D-005] 2026-10-29 — Tier 1 source types
- **Status:** accepted
- **User chose:** Android developer docs; WiGLE official docs/source; Jetpack/Kotlin official docs; IEEE 802.11 / Bluetooth SIG specs
- **Applies to:** project

## [D-006] 2026-10-29 — Maximum source age
- **Status:** accepted
- **User chose:** 12 months
- **Applies to:** project

## [D-007] 2026-10-29 — Location of tracking files
- **Status:** accepted
- **User chose:** docs/ subfolder of the project
- **Applies to:** project

## [D-008] 2026-10-29 — minSdk
- **Status:** accepted
- **User chose:** Android 10 (API 29)
- **Applies to:** project

## [D-009] 2026-10-29 — Which devices are watched
- **Status:** accepted (confirmed: all three modes, switchable in-app)
- **Options offered:** User-picked watchlist | Whole database | Filtered subset
- **User chose:** "lets add a mix to pick from" — read as: the app offers all three modes and the user picks one in-app
- **Applies to:** project

## [D-010] 2026-10-29 — DB import method
- **Status:** accepted
- **Options offered:** File picker | Read live from WiGLE folder | Both
- **User chose:** Both
- **Notes:** Live-folder read may be blocked by scoped storage on Android 11+; app must fall back to picker.
- **Sources:** C-001

## [D-011] 2026-10-29 — Live mode
- **Status:** accepted
- **User chose:** Foreground service + notification
- **Notes:** Needs background-location grant; Wi-Fi scan throttling applies (C-002, unverified).

## [D-012] 2026-10-29 — Randomised MAC handling
- **Status:** accepted
- **User chose:** Warn and flag

## [D-013] 2026-10-29 — App name / package
- **Status:** accepted
- **User chose:** Deja Scan / com.dejascan.app

## [D-014] 2026-10-29 — Verify before build
- **Status:** accepted
- **User chose:** Verify first, then build

## [D-015] 2026-10-29 — compileSdk
- **Status:** accepted
- **Context:** Approved libraries require compileSdk 37.
- **Options offered:** Install platform 37.0 + build-tools 37.0.0 | platform 37.2 | downgrade libraries
- **User chose:** Install platform 37.0 + build-tools 37.0.0
- **Applies to:** project

## [D-016] 2026-10-29 — UI redesign
- **Status:** accepted
- **Context:** App works but looks plain.
- **User chose:** Dark radar/cyber style; green accent; animated radar on Live tab; signal strength bars/chips; cards, icons and type badges; app icon + splash; theme toggle in app (light/dark/system); system default sans font; androidx core-splashscreen library; hand-drawn vector icons (no icon library).
- **Applies to:** UI layer only (ui/, theme, resources)
- **Notes:** core-splashscreen version to be looked up and approved before use.

## [D-017] 2026-10-30 — Live link to WiGLE data
- **Status:** accepted
- **Context:** "Read WiGLE folder" cannot work on Android 11+ (WiGLE DB and backups are in its private storage, C-001).
- **Options offered:** Linked file with auto-refresh | Watched folder | WiGLE web API | Remove live link
- **User chose:** Linked file, auto-refresh
- **Sources:** C-001

## [D-018] 2026-10-30 — Alert rule
- **Status:** accepted
- **User chose:** Both rules — alert on first sighting, then only when the device gets closer (stronger signal) by a threshold
- **Notes:** Threshold +10 dB, fixed.

## [D-019] 2026-10-30 — Extra radar views
- **Status:** accepted
- **User chose:** Signal bar graph, signal history strip chart, hot/cold tracker, channel spectrum (radar stays default)

## [D-020] 2026-10-30 — Other UI requests
- **Status:** accepted
- **User chose:** Radar auto-fits screen size and rotation; retro look = CRT scanlines + glow and monospace font (no chunky pixels, no boot text); tap a blip for info (name/MAC/type/randomised, WiGLE history, live signal + trend, advertised data); bottom list removed.

## [D-021] 2026-10-30 — Probe requests
- **Status:** accepted
- **User asked:** "can we create a non root monitor mode?"
- **User chose:** Park it for now; show what Android exposes (BLE services/manufacturer data, Wi-Fi channel/security) in the tap-info panel.
- **Sources:** C-007 (Tier 2, older than age limit, unverified)

## [D-022] 2026-10-30 — Refresh, tracker feedback, view switching
- **Status:** accepted
- **User chose:** Linked file re-imported on app open + manual Refresh button; hot/cold tracker uses haptics and beeps with separate toggles; views switched by swipe and a dropdown combined.

## [D-023] 2026-10-30 — Offline map, estimation, vendor names, map data
- **Status:** accepted
- **Context:** User asked for offline map support, last-known location display, better distance, manufacturer names/logos, and a map tab.
- **User chose:** Offline vector map file (Mapsforge); Mapsforge 0.25.0 from Maven Central (older than the 12-month limit — accepted exception to D-006); user picks their own .map file with the file picker; movement-based position estimate using both weighted centroid and least-squares path-loss fit (best chosen automatically); manufacturer names with a coloured monogram badge (no logos); approved download of IEEE oui.csv, mam.csv, oui36.csv and Bluetooth SIG company_identifiers.yaml (redistribution terms not confirmed); map shows WiGLE observations and the app's own sightings; WiGLE observations imported for ALL devices (user chose this over starred-only despite the database-size warning); own sightings stored every 5 s per device in SQLite; map device checklist plus tapping a device on the radar selects it on the map.
- **Applies to:** project
- **Notes:** Interpretations to confirm: own sightings are never auto-deleted (Clear button added); no distance-moved condition; estimate uses the last 24 h of own sightings; tapped devices are ticked on the map, starred devices start unticked.
- **Sources:** C-009 to C-013

## [D-024] 2026-10-30 — Map shows one dot per device; info panel with side tabs
- **Status:** accepted
- **Context:** User saw a pile of dots at their own position (the old map drew every own sighting) and wants one dot per device, live and WiGLE, clickable, with the Live-screen info panel on the map.
- **User chose:** live dot at estimate, else at my position (approximate); TRACK tab = hot/cold tracker; MAP tab = real mini map from the offline files; layer toggles only (no checklist).
- **My choices (not asked):** approximate dots are fanned out in screen space around my position so each stays tappable (no invented direction); live = heard within 3 min; live estimate uses the last 10 min of own sightings (info panel keeps 24 h); WiGLE dots filtered by the Devices-tab type chips, clustered below zoom 17, capped at 4000 in view; trail toggle shows WiGLE observations plus own sightings for the selected device only; PERMS/BG buttons shortened to keep start/stop on one pinned row.
- **Applies to:** project

## [D-025] 2026-10-30 — Day search, link lines
- **Status:** accepted
- **User chose:** "seen on a day" = all sources (WiGLE observation, own scan, WiGLE last-seen date); day devices shown on a mini map inside the Devices tab; dotted lines for all devices in view seen in 2+ places.
- **My choices (not asked):** day list capped at 300 rows (map shows up to 20,000 places); links only from zoom 12, at most 300 devices, places closer than 25 m merged; the map gets its own type toggles (default all three) instead of reusing the watch-type chips; pressing the TRACK tab twice returns to INFO; MAP tab removed from the Live-screen info panel only (kept on the Map screen).
- **Applies to:** project

## [D-026] 2026-11-01 — Publishing to GitHub
- **Status:** accepted
- **User chose:** private repository `deja-scan` under the user's GitHub account; MIT licence; manufacturer-name tables left out and rebuilt by a script (terms unconfirmed); commits use the GitHub noreply identity instead of the personal email in the git settings; re-download of the four public files approved to test the script.
- **PII scrub:** local.properties (home path) and all build/db/map files excluded; searched the tree for the home path, email, phone serial, SD-card id, WiGLE backup file names, MAC addresses (only synthetic test values remain), IP addresses and place names; one country-specific map file mention generalised.

## [D-027] 2026-11-02 — Wi-Fi 6 / 6E / 7 (6 GHz) & Channel Bandwidth Spectrum Scanning
- **Status:** accepted
- **Context:** User requested Wi-Fi 6, 6E, 7 (6 GHz) and Channel Bandwidth Spectrum scanning if supported by the device and not yet implemented.
- **Implementation:**
  - Device capability detection via `WifiManager.is5GHzBandSupported()`, `is6GHzBandSupported()`, and `isWifiStandardSupported()` for 802.11ax and 802.11be.
  - ScanResult enrichment with channel width (20/40/80/160/320 MHz), center frequencies (`centerFreq0`, `centerFreq1`), and Wi-Fi standard.
  - Spectrum live view upgraded with band selection (2.4 GHz, 5 GHz, 6 GHz), 6 GHz sub-band controls (U-NII-5 through 8), device support status chips, filter toggle (ALL/WATCHED), view toggle (CURVES/BARS), OFDM bandwidth curves with flat top and skirts, channel tick grid, and interactive AP row list.
  - Graceful fallback when 6 GHz hardware is unsupported, with a clear hardware capability diagnosis.
- **Applies to:** project

## [D-028] 2026-11-03 — Passive Wi-Fi Security & WEP Notifications
- **Status:** accepted
- **Context:** User requested a passive way to notify when an AP has WEP or some sort of security issue.
- **Design Choices:**
  - Non-intrusive / passive delivery: zero alarms, zero audio chirps, zero buzzing.
  - System level: Silent `NotificationChannel` (`IMPORTANCE_LOW`, silent, no vibration) for security advisories plus live updates to the ongoing foreground notification with WEP / Insecure count summaries. Auto-cancels when APs drop off after 5 minutes. Notification intent passes `EXTRA_SELECT_MAC` to focus the AP in `MainActivity`.
  - In-App UI: `SecurityAdvisoryBanner` above Live views alerting to WEP/insecure APs with 1-tap "INSPECT", expandable list for multiple APs, and dismiss option; `RadarPanel` pulsing warning halos around WEP blips; `SpectrumPanel` and `BarsPanel` security badges (`[WEP ⚠️]`, `[OPEN ⚠️]`); `InfoPanel` comprehensive Security Advisory card explaining the cryptographic vulnerability and remediation.
  - Issue model: `SecurityIssue` with severity (`CRITICAL` for broken WEP, `HIGH` for cleartext Open and WPS-PIN, `MEDIUM` for WPS-PBC and TKIP, `LOW` for Ad-hoc).
- **Applies to:** project

## [D-029] 2026-11-03 — Root Status Detection
- **Status:** accepted
- **Context:** User requested checking root / superuser status without breaking app stability or security.
- **Design Choices:**
  - Multi-vector detection checking:
    1. OS Build tags for unofficial `test-keys`.
    2. Existence of `su` binaries across 16 canonical system paths and modern root solutions (Magisk `/data/adb/magisk`, KernelSU `/data/adb/ksu`, APatch `/data/adb/ap`).
    3. `PATH` environment directory inspection.
    4. Execution test via `which su`.
  - UI Header indicator: discreet `ROOT` (green) / `NON-ROOT` (muted) pill with instant tap-to-toast feedback explaining access state.
- **Applies to:** project

## [D-030] 2026-11-03 — WiGLE SQLite Database Merger
- **Status:** accepted
- **Context:** User requested merging newly discovered live findings back into the WiGLE SQLite backup file.
- **Design Choices:**
  - Two-way sync: Reads existing `network` and `location` tables from the linked WiGLE `.sqlite` file (via SAF persistent URI), prepares `NetworkRecord` and `SightRecord` instances from live `Sighting` objects, inserts or updates records with latest/best RSSI and coordinates, and safely writes the combined DB back.
  - Local database update: Updates internal `device` and `obs` tables, rebuilds spatial map cell indexes, and updates `SightingBus` known-device cache so newly merged networks are recognized as known.
  - Triggered via "Merge findings into WiGLE file" button on the Devices tab with status progress feedback.
- **Applies to:** project

## [D-031] 2026-11-03 — Network Counters, Density Tracker & Vendors Panel
- **Status:** accepted
- **Context:** User requested total network counter, new APs discovered count, vendor list, and rolling density metrics.
- **Design Choices:**
  - Top Summary Cards: `NetworkCountersBar` on the Live screen displaying Total Networks, New Discovered networks (unseen in WiGLE DB), and current rolling AP Density per minute (with session Peak).
  - Tappable navigation shortcuts: Tapping Total Networks jumps directly to the Vendors view; tapping Density jumps directly to the Density history graph.
  - Vendors Panel: Full-screen interactive breakdown grouping active devices by IEEE OUI / Bluetooth company IDs with monogram badges, radio distribution (Wi-Fi vs. BT), best RSSI, randomized MAC indicators, and tap-to-inspect drilldown.
- **Applies to:** project

## [D-032] 2026-11-05 — Merge Preparation: Remove Spectrum Analyzer, Condense UI, Minimize Notifications & 6-Tier Color Classification
- **Status:** accepted (supersedes Spectrum UI in D-019/D-027 and system security notifications in D-028)
- **Context:** User requested preparing the `testing` branch to merge into `main` by removing the Wi-Fi spectrum analyzer while keeping channel number + channel width across other tabs (such as Map), condensing/compartmentalizing the UI, minimizing notifications, and applying distinct color schemes for rarity, outdated WEP, potential attackers (Pwnagotchi, etc.), randomized MACs, and normal networks.
- **Design Choices:**
  - **Spectrum Analyzer Removal + Channel/Width Retention:** Removed `SpectrumPanel` (`LiveView.SPECTRUM`), kept `ChannelWidthHelper.formatChannelWidth` (`Ch X • YYMHz`), changed unknown `Sighting.channelWidth` default to `-1` (distinguishing unknown stored WiGLE records from `0` = `20 MHz`), and upgraded `DejaDb` to v7 (`freq`, `caps`) so channel and security details render across `Map`, `Devices`, `Live`, and `InfoPanel`.
  - **6-Tier Detection Classification (`DetectionClassifier`):** Priority-ordered categories: `AUDIT_TOOL` (`#FF2A55`, `"Possible audit tool (heuristic)"` / `"AUDIT"`, documented as unverified heuristics in `C-014`), `WEP` (`#FF6D00`), `RARE` (`#FFD600`), `UNCOMMON` (`#00E5FF`), `RANDOMIZED` (`#B388FF`), and `NORMAL` (`#39FF88`).
  - **UI Compartmentalization:** Consolidated Live into 4 views (`RADAR`, `SIGNALS`, `ACTIVITY`, `VENDORS`) with on-demand `DeviceDetails`; grouped `Devices` config into collapsible `Watch & Notification Rules` and `WiGLE Database Sync` cards; grouped `Map` toggles into a collapsible `LAYERS` menu.
  - **Minimal Notifications:** Removed `CH_SECURITY` channel, lowered `CH_ONGOING` to `IMPORTANCE_MIN`, suppressed alerts while foregrounded (`SightingBus.inForeground`), and added a 30 s rate-limited single `HIT_ID` summary controlled by `Prefs.notifyHits`.
- **Applies to:** project

## [D-033] 2026-11-05 — Passive Root / Promiscuous Mode Groundwork, Ethical Disclaimer & Codebase Optimization
- **Status:** accepted (extends D-021 & D-029)
- **Context:** User requested laying compartmentalized groundwork for root/promiscuous mode up to what a rooted Android system can do passively (control frame extraction, hidden SSID unmasking, client station tracking), adding an explicit legal/ethical disclaimer that the app does not condone illegal activities, and optimizing code across the app (removing unused symbols and redundant `if/else` checks).
- **Design Choices:**
  - **Compartmentalized Package (`com.dejascan.app.scan.promiscuous`):**
    - `Dot11FrameParser`: Zero-dependency libpcap stream reader (`DLT_IEEE802_11_RADIO`, `DLT_IEEE802_11`, `DLT_PRISM_HEADER`), Radiotap parser (extended bitmaps, channel MHz, dBm RSSI, `RADIOTAP_F_FCS` trim, `RADIOTAP_F_BADFCS` rejection), and IEEE 802.11 Management / Control / Data MAC header + IE parser. Payloads are never decoded.
    - `PromiscuousTracker` & `PromiscuousBus`: Bounded, thread-safe `StateFlow<PromiscuousSnapshot>` tracking control frame tallies (`RTS`, `CTS`, `ACK`, `BlockAck`, `PS-Poll`), passive `Deauth`/`Disassoc` counts + reason codes, hidden SSID unmasking (`Beacon` hidden BSSID correlated with `Probe Response` / `Assoc Req` / `Reassoc Req`), and client stations (`ToDS`/`FromDS` data headers, `PS-Poll`, probe requests).
    - `PromiscuousMonitor`: Safe, strictly passive `su` capability probe restricted to read-only system binary paths (`/system/bin`, `/system/xbin`, `/system_ext/bin`, `/product/bin`, `/vendor/bin`, `/sbin`) and tiered interface setup: (1) existing radiotap monitor netdev, (2) concurrent `iw` virtual monitor VIF `dejamon0` (`flags none`, preserving normal Wi-Fi), (3) secondary OTG adapter or opt-in Qualcomm `con_mode=4`, (4) `ip link promisc on` fallback, with synchronous `SharedPreferences` restore persistence so a crash or kill restores Wi-Fi on next start.
  - **UI Integration:** Added `ActivitySubView.MONITOR` (`MonitorPanel`) inside `Live -> Activity`, `RootPromiscuousCard` + legal footer in `Devices`, `-- 802.11 MONITOR --` in `InfoPanel`, and `ROOT • MON` status in `Header`.
  - **Ethical Boundary & Disclaimer:** Strictly receive-only (zero packet injection or deauth transmission); added `LEGAL_NOTICE_SHORT` in-app and a dedicated *Legal & Ethical Use Notice* section in `README.md`.
  - **Dead-Code & Control-Flow Cleanup:** Removed unused fields/methods/constants across `Track`, `SightingBus`, `PositionEstimator`, `DejaDb`, `WifiSecurity`, `WifiSpectrum`, `Theme`, `Terminal`, `Widgets`, `VendorPanel`, `MapPanel`, `InfoPanel`, and `Screens`.
- **Applies to:** project

## [D-034] 2026-11-07 — Pre-Merge Safety, Security Hardening, Deduplication & Build Hygiene
- **Status:** accepted
- **Context:** Review of `testing` prior to merging into `main` flagged backup-loss risk during WiGLE merge, duplicate `obs` rows across merges, root execution from writable folders, unpersisted monitor-mode teardown, unverified `"Threat / Potential Attacker"` labels and `C-008` source status, undocumented build changes, and main-thread / input-cleaning issues.
- **Design Choices:**
  - **Safe WiGLE Merge (`AppViewModel`, `WigleMerger`):** Added `Save merged as new file` (`CreateDocument`) as the primary non-destructive export path, plus pre-merge backup (`filesDir/backups/wigle-pre-merge.sqlite`), isolated working temp file, `WigleMerger.verifyIntegrity` (`PRAGMA integrity_check`), rollback on write failure, and backup export.
  - **Observation Deduplication (`DejaDb` v8):** Added `UNIQUE INDEX idx_obs_mac_time ON obs(mac, time)` with `INSERT OR IGNORE` and `NOT EXISTS` filtering across `placesOf`, `hitsOnDay`, and `loadTrail` so repeated merges never duplicate observations or link counts.
  - **Root & Monitor Hardening (`PromiscuousMonitor`, `Prefs`, `DejaApplication`):** Removed writable paths (`/data/local/tmp`, Termux) from root binary discovery; persisted synchronous `promiscRestoreNeeded` metadata before modifying interfaces and automatically restored on next app start.
  - **Fair Heuristic Labelling & Source Integrity (`DetectionClassifier`, `SOURCES.md`):** Renamed `ATTACKER` / `"THREAT"` to `AUDIT_TOOL` / `"Possible audit tool (heuristic)"` (`"AUDIT"`), scoped `"Flipper <name>"` heuristic to BT/BLE, added `C-014` (`UNVERIFIED`), and restored `C-008` to `UNVERIFIED`.
  - **Build Toolchain:** Removed accidentally generated `gradle/gradle-daemon-jvm.properties` (JDK 25 Foojay download) while keeping and documenting the AGP `9.5.0 -> 9.5.1` patch bump.
  - **Threading, API-Level & Unused-Code Hygiene:** Offloaded `ScanService.onHeard` SQLite lookups onto `io`, deferred `RootDetector` checks off the main thread in `AppViewModel` and `ScanService`, validated `EXTRA_SELECT_MAC` with `MacUtil.normalize`, cleaned over-the-air SSIDs/names/capabilities with `MacUtil.cleanAirText`, replaced API-33 `readNBytes` in `AppViewModel.importMapFiles`, and pruned remaining unused getters, helpers, fields, imports, and XML resources.
- **Applies to:** project
- **Sources:** C-004, C-008, C-014




