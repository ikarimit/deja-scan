# Changelog

## 2026-11-05 — Passive Root / Promiscuous Mode Groundwork, Legal Notice & Codebase Optimization
- **Changed:**
  - **Passive 802.11 Monitor & Promiscuous Groundwork (`scan/promiscuous/`):** Added `Dot11FrameParser`, `PromiscuousTelemetry` (`PromiscuousTracker`, `PromiscuousBus`), and `PromiscuousMonitor` supporting root (`su`) capability probing, non-destructive `iw` virtual monitor VIF (`dejamon0` with `flags none`), external OTG radio monitor mode, opt-in Qualcomm `con_mode=4` fallback, and link-layer `promisc on` fallback via `tcpdump -U -s 256 -w -`.
  - **Control Frame Extraction, Hidden SSID Unmasking & Client Station Tracking:** Added live decoding and aggregation for 802.11 Control frames (`RTS`, `CTS`, `ACK`, `BlockAckReq`/`BlockAck`, `PS-Poll`, `CF-End`), passive `Deauth`/`Disassoc` counts and IEEE reason codes, automatic correlation of hidden-SSID beacons with `Probe Response` / `Association Request` / `Reassociation Request` frames (enriching `SightingBus` live AP names without allowing subsequent empty beacons to overwrite them), and bounded client station tracking (associated AP BSSID, uplink RSSI, probed SSIDs, randomized MAC status).
  - **Compartmentalized UI (`MonitorPanel`, `LiveViews`, `Screens`, `InfoPanel`):** Added `MONITOR` sub-compartment under `Live -> Activity`, collapsible `Root / Promiscuous Mode` configuration card on `Devices`, `-- 802.11 MONITOR --` inspector section on `InfoPanel`, and `ROOT • MON` header badge.
  - **Legal & Ethical Use Disclaimer:** Added explicit notice in `Devices` (`RootPromiscuousCard` and screen footer) and `README.md` stating that Deja Scan is strictly passive and does not condone illegal activities, unauthorized intrusion, or wireless interference.
  - **Codebase Optimization:** Removed unused properties, functions, enums, and imports across `Track.kt` (`centerFreq1`), `PositionEstimator.kt` (`keepPi`), `DejaDb.kt` (`PointCounts`, `counts`), `WifiSecurity.kt` (`isEncrypted`, `summary`, duplicate issue builders), `WifiSpectrum.kt` (`freqRange`, unused `capabilities` arg), `Theme.kt` (`mid`, `grid`), `Terminal.kt` (`magenta`), `Widgets.kt` (unused icon variants, `barsFor`, `signalColor`, `SignalBars`, unused imports), `VendorPanel.kt` (dual expand/collapse sets consolidated via `xor`), `MapPanel.kt`, `InfoPanel.kt`, and `Screens.kt`.
- **Why:** Lay concise, compartmentalized groundwork for passive root/promiscuous 802.11 telemetry, document ethical/legal boundaries, and remove dead code and redundant conditionals (D-033).
- **Status:** built, 80 unit tests pass.

## 2026-11-05 — Removed Spectrum Analyzer, Channel+Width Across Tabs, 6-Tier Detection Colors, Condensed UI & Quiet Notifications
- **Changed:**
  - **Removed Wi-Fi Spectrum Analyzer (`SpectrumPanel`):** Removed `LiveView.SPECTRUM` and OFDM spectrum curve/bar canvases while preserving `WifiBand`, `WifiStandardHelper`, and `ChannelWidthHelper` so Wi-Fi channel numbers and channel widths (`Ch 36 • 80MHz`, `Ch 1 (6G) • 320MHz`, frequency spans) are displayed directly across `Radar`, `Signals`, `Vendors`, `Devices` cards, `Map` selection HUD tags, and `InfoPanel`.
  - **Database v7 (`DejaDb`, `WigleImporter`):** Stored `freq` and `caps` on imported WiGLE `device` rows (`QUERY_FULL` with fallback for older schemas) so offline WiGLE APs on the `Map` and `Devices` tabs retain channel numbers and WEP/security classifications.
  - **6-Tier Detection Classification (`DetectionClassifier`, `Phosphor`):** Added unified color scheme across `Live`, `Devices`, `Map`, and `InfoPanel` for Potential Attackers (`THREAT` Crimson `#FF2A55`: Pwnagotchi, WiFi Pineapple `00:13:37` / PineAP, Flipper Zero, ESP32 Marauder, Deauther, rogue AP tools), Outdated WEP (`WEP` Orange `#FF6D00`), Rare Wi-Fi/BT (`RARE` Gold `#FFD600`: Wi-Fi 7 / 320 MHz, 6 GHz, Ch 14, IBSS, 802.11s Mesh, WiGig, Suite-B, rare BT Medical/Imaging/Toy classes, unique uncommon hardware vendor OUIs), Uncommon (`UNCOMMON` Cyan `#00E5FF`: OWE, 160 MHz, 802.1X, Passpoint, WPA1, Open, newly discovered fixed-MACs), Randomized MACs (`RAND` Purple `#B388FF`), and Normal (`NORMAL` Green `#39FF88`).
  - **Condensed & Compartmentalized UI (`Screens.kt`, `LiveViews.kt`, `MapPanel.kt`, `InfoPanel.kt`):** Consolidated Live views into 4 compartments (`RADAR`, `SIGNALS`, `ACTIVITY` combining Signal History + Density, `VENDORS`) with a slim scan header, clickable category pill strip, and on-demand `DeviceDetails`; collapsed `Devices` settings into `Watch & Notification Rules` and `WiGLE Database Sync` compartments with category filter chips; grouped `Map` layer buttons into a collapsible `LAYERS` menu with an on-map channel/category HUD label for selected dots.
  - **Minimized Notifications (`ScanService`, `MainActivity`, `Prefs`):** Removed `CH_SECURITY` advisory notifications and toast spam; downgraded ongoing scan notification to `IMPORTANCE_MIN` without badge; suppressed watched-device notifications while app is in foreground and consolidated background alerts into a single `IMPORTANCE_LOW` summary with a 30 s cooldown and user toggle (`notifyHits`).
- **Why:** Prepare `testing` branch for upstream merge with simpler compartmentalized UI, minimal notifications, channel + width visibility across tabs without the spectrum analyzer, and rarity/threat/WEP/randomized color coding (D-032).
- **Status:** built, 73 unit tests pass.

## 2026-11-03 — WiGLE Database Merger, Root Detection, Network Counters & Vendors View
- **Changed:**
  - **WiGLE SQLite Merger (`WigleMerger`):** Two-way synchronization allowing live scan findings and GPS sightings to be merged directly back into the linked WiGLE `.sqlite` database from the Devices tab. Updates `location` observations and `network` records (BSSID, SSID, frequency, capabilities, best level/coordinates, last seen time/coordinates), regenerates local spatial cell indexes, and marks merged MACs as known in the session.
  - **Root Status Detection (`RootDetector`):** Non-intrusive environment check inspecting `Build.TAGS` for `test-keys`, probing known su binary locations (`/sbin/su`, `/system/bin/su`, `/system/xbin/su`, `/data/local/xbin/su`, Magisk `/data/adb/magisk`, KernelSU `/data/adb/ksu`, APatch `/data/adb/ap`), validating `PATH`, and testing execution. Displays a `ROOT` / `NON-ROOT` indicator chip in the header with tap feedback.
  - **Live Network Counters Bar & Density Tracking (`NetworkCountersBar`, `DensityTracker`):** Added real-time summary cards at the top of the Live screen displaying Total Networks heard (Wi-Fi + BT), New Discovered networks (not previously stored in the WiGLE DB), and rolling AP Density (unique APs/minute with session peak). Tapping Total Networks jumps to the Vendors view; tapping AP Density jumps to the Density rolling graph.
  - **Vendors Panel (`VendorPanel` / `LiveView.VENDORS`):** Added a new live view grouping discovered devices by manufacturer using the IEEE OUI registry and Bluetooth SIG company tables. Shows device counts (Wi-Fi vs. BT), monogram badges, color coding, randomized MAC badges, and expandable device lists with 1-tap inspection.
  - **Permission UX & Background Indicator:** Refined permission flows to request required permissions just-in-time when starting the scanner, and added a visual `BG ON` / `BG OFF` status toggle button.
- **Why:** User requested checking root, merging into the WiGLE database, total network counter, new APs discovered, vendors shown list, and permission UX improvements.
- **Status:** built, 67 unit tests pass.

## 2026-11-03 — Passive Wi-Fi Security Vulnerability Notifications & WEP Alerts
- **Changed:** Added comprehensive passive notification system for Wi-Fi access points with WEP or security vulnerabilities (unencrypted Open networks, WPS PIN/brute-force exposure, deprecated TKIP ciphers, and ad-hoc networks).
  - Background/foreground `ScanService`: Creates silent `CH_SECURITY` notification channel (`IMPORTANCE_LOW`, no vibration, no sound) and automatically updates the ongoing foreground notification with detected insecure network tallies. Posts a silent security advisory notification with expandable details when vulnerable APs are heard; automatically dismisses when out of range. Tapping notification opens the app and selects the vulnerable AP in `MainActivity`.
  - In-App `SecurityAdvisoryBanner`: Displayed on `LiveScreen` (portrait and landscape) when insecure APs are in range. Alerts the user to broken WEP encryption or open networks with an "INSPECT" button, expandable list for multiple APs, and session dismiss action.
  - View Badges: `RadarPanel` blips draw pulsing warning rings around WEP/insecure APs and append `[WEP!]` to selected labels; `BarsPanel` displays red `WEP` / amber `OPEN` tags; `SpectrumPanel` AP list displays prominent `[WEP ⚠️]` and `[OPEN ⚠️]` chips; `VendorPanel` highlights insecure Wi-Fi suites.
  - `InfoPanel` Security Advisory: Detailed breakdown displaying vulnerability severity (CRITICAL, HIGH, MEDIUM, LOW), technical explanation (RC4 IV collisions, FMS/PTW key recovery, plaintext packet capture, WPS PIN brute force), and actionable mitigation recommendations.
- **Why:** User requested a passive way to notify the user when an AP has WEP or a security issue.
- **Status:** built, 67 unit tests pass.

## 2026-11-02 — Wi-Fi 6 / 6E / 7 (6 GHz) & Channel Bandwidth Spectrum Scanning
- **Changed:** Added device Wi-Fi capability detection (`is5GHzBandSupported`, `is6GHzBandSupported`, `isWifiStandardSupported` for 802.11ax and 802.11be), Wi-Fi standard detection (Wi-Fi 4, 5, 6, 6E, 7), channel bandwidth extraction and visualization (20, 40, 80, 160, 320 MHz). Rebuilt SPECTRUM live view into a full Channel Bandwidth Spectrum Scanner with 2.4 GHz, 5 GHz, and 6 GHz band selection, 6 GHz sub-bands (U-NII-5 through 8), device capability status chips, filter toggle (ALL/WATCHED), view toggle (CURVES/BARS), OFDM bandwidth curves with flat top and skirts, channel tick grid, and interactive AP row list. Info panel displays standard, band/channel, and frequency span with bonded center frequency.
- **Why:** User requested Wi-Fi 6 / 6E / 7 (6 GHz) and channel bandwidth spectrum scanning with device support detection.
- **Status:** built, 56 unit tests pass.

## 2026-11-01 — Prepared for GitHub
- **Changed:** Added README, MIT LICENSE, .gitignore (local.properties, build output, databases, map files, generated name tables), scripts/fetch_vendor_data.py (downloads the IEEE and Bluetooth SIG data; tested by download on 2026-11-01: companies identical apart from 5 names where double spaces are now collapsed, vendor records a superset with 6 newer entries). The name tables are no longer committed. A mention of a country-specific map file was generalised in this log.
- **Why:** User asked to publish privately with PII removed (D-026).

## 2026-10-30 — Chosen device: places, first/last seen, solo view, bolder links
- **Changed:** Choosing a device (map or Live) loads every place it was seen (WiGLE observations + own scans, places within 25 m merged, max 300). INFO tab has a new "SEEN IN N PLACES" section: first seen and last seen (time + coordinates), distance between them, then the numbered list of places with time, step distance and source. Map: new SOLO button (default on): with a device chosen only that device is shown, its places joined by a bold dotted line with a white halo, numbered, green = first, red = last; the view zooms to fit all its places. LINKS lines are bolder too (4.5 dp, white halo, bigger place dots). DB: `placesOf` now also returns the source (WiGLE or own scan).
- **Status:** built, 28 tests pass; not run on device.

## 2026-10-30 — Icon: outer border removed
- **Changed:** The user's launcher clipped the thick outer rounded-square line, so it was removed from the icon, splash and themed icon; only the thin inner rounded-square line remains.

## 2026-10-30 — Icon: rounded-square border
- **Changed:** The circular border rings were replaced by a double rounded-square border (thick outer line, thin inner line) kept inside the adaptive-icon safe area; Earth + sonar scaled to 88 % inside it. Same for splash and themed icon. Circle-shaped launcher masks will clip the corners of the square.

## 2026-10-30 — Icon: double border, two more orange dots
- **Changed:** Launcher icon, splash and themed icon: artwork scaled to 90 %, a thick inner border ring plus a thin outer border ring around the whole icon, and 3 orange blips (was 1). Previewed as a rendered image; not yet seen on the phone launcher.

## 2026-10-30 — Link view rework, map visible when zoomed out, new icon
- **Changed:** Screenshot showed the map blank above the 10 km scale because every zoom below the detail zoom was blank for big files. Now each big file is opened twice with disjoint zoom ranges: 0-7 (thin data, cheap) and the detail zoom (default 10) and closer; only zoom 8 to detail-1 stays blank. Link view: after every import the app finds all devices with 2+ WiGLE places at least 25 m apart (27,025 on the phone data; tables `linked` and `lcell`, DB v6). With LINKS on, every device without a link is hidden at every zoom: zoomed out you see numbered bubbles of linked devices only; from zoom 12 dotted lines (up to 600 devices, WiGLE places plus own scans) with tappable place dots. A link error, if any, is shown in the chip. New launcher icon and splash: vector Earth behind the sonar with a green border ring (monochrome variant for themed icons).
- **Status:** built, 28 tests pass; not run on device. Cause of the earlier "links not working" still unconfirmed (possible: view was below zoom 12 so the lines never loaded).

## 2026-10-30 — Map still slow (GC thrash), link view diagnostics
- **Found (phone logcat, no crash this time):** the heap sat at 300-350 MB with repeated multi-second GCs freeing 100-200 MB of small objects while a 3.2 GB country map file rendered at zoom 8-9 with 8 parallel tile workers.
- **Changed:** `Parameters.NUMBER_OF_THREADS = 2`; big map files are read from a user-set detail zoom (default 10, stepper in the MAPS dialog, range 6-14; was fixed 8); links candidate query now keeps only devices whose places are >= ~25 m apart, widest first (before: any 2 places, arbitrary 3000, many at the same spot); link chip says "looking..." / how many devices were found.
- **Checked on a copy of the phone DB:** 27,025 of 167,298 devices with observations have 2+ places 25 m apart; candidate + place queries for a dense 20x20 km view take about 0.2 s. So the data and queries are fine; the cause of "links not working" is NOT confirmed. Possible: view below zoom 12, or the map thrash above.
- **Status:** built, 28 tests pass; not run on device.

## 2026-10-30 — Out-of-memory crash, link view shows only multi-place devices
- **Changed:** Crash read from phone logcat: repeated `OutOfMemoryError` (256 MB heap) in Mapsforge's tile workers (`MultiMapDataStore.readAllMapData`, `PolylineContainer`). Cause: 6 map files, 5.4 GB in total (the largest alone 3.2 GB), all read for every zoomed-out tile. Fixes: map files over 100 MB are only read from zoom 8 (`MapFile.restrictToZoomRange`); tile cache back to 1x screen; `android:largeHeap="true"`. LINKS now hides all other dots and shows only devices seen in 2+ places (their places as dots joined by dotted lines; tap a place to open the device); chip shows the device count.
- **Status:** built, 28 unit tests pass; not run on device. Zoomed out below 8 the big files draw nothing (only a small overview file such as the 3 MB offline.map).

## 2026-10-30 — Day search, link lines, map type filters, tab fixes
- **Changed:** Devices tab: the device list is hidden until you type a search or pick a date; "Search by date" (Material3 date picker) lists devices seen that day (WiGLE observations + own scans + WiGLE last-seen date) with a mini map of every place (DayMap). Map: own WI-FI / BT / BLE toggles (the map used the Devices-tab "Filtered" chips by mistake, which were set to BLE only, so it showed 220,959 of 418,584 located devices), LINKS toggle draws dotted lines for all devices in view seen in 2+ places (zoom 12+, max 300 devices, 25 m minimum step). Info panel: no MAP tab on the Live screen; pressing TRACK again returns to INFO (stops the tracker). DB v5 adds indexes obs(time), obs(lat,lon), device(lasttime).
- **Why:** User requests; D-025.
- **Findings (phone DB copy):** 418,817 devices stored (the union of every file ever imported; the last import alone was 167,530), 418,584 have a position, 233 have none; observations 396,083 for 167,298 devices.
- **Status:** built, 28 unit tests pass, new SQL checked on a copy of the phone database; UI not checked on device.

## 2026-10-30 — PERMS / BG feedback, haptics
- **Changed:** Checked the phone: every permission is granted except background location, which is permanently refused (flag USER_FIXED), so Android silently ignores a new request. PERMS and BG now show a message ("already granted" / what is missing) and open the app's settings page when a permission can only be changed there. Tracker haptics now use alarm vibration attributes (phone shows a muted/do-not-disturb icon; plain vibrations are dropped there) and the HAPTIC switch gives one test buzz.
- **Status:** built; not tried on device. The haptic cause is a likely one, not confirmed.

## 2026-10-30 — Faster map, always-visible Live info box, softer borders
- **Changed:** Devices are now pre-counted into map cells (new `cell` table, DB v4, zoom 3-14, built in one pass after every import or on first start after the update; `CellGrid` + 3 unit tests); zoomed-out map is an indexed lookup, zoom 15+ queries single devices. Position estimates for live dots skip devices with fewer than 3 own sightings and are cached 15 s; index on sight(time). Map files: tile cache 2x screen, combined files use RETURN_ALL (was DEDUPLICATE, slower) so border overlaps may show duplicate labels. Live screen shows the info box (dimmed tabs + hint) when nothing is selected. Panel/chip/button frames use a softer colour (Phosphor.edge).
- **Why:** User feedback (slow device loading, slow maps, hidden info box, bright borders).
- **Status:** built, 28 unit tests pass; speed gain NOT measured on device. Rendering speed of the map itself depends on the .map files and the default render theme.

## 2026-10-30 — Crash fix on map tap, always-visible map box, all heard devices on the map
- **Changed:** Fixed crash (Mapsforge passes a null `layerXY` to `Layer.onTap`; parameters now nullable; found in phone logcat). Mini map clipped to its box. Map box is always shown (legend/counts when nothing is chosen). Every device heard is now reported (devices outside the watch rule are flagged `watched=false`: map only, no alerts, not on radar); OTHERS toggle; own sightings of such devices saved every 30 s (watched: 5 s). FIT fits everything stored; first start fits all stored devices when there is no GPS fix. DB query `bounds()` added.
- **Why:** User feedback: crash, mini map overlapping the tabs, box only on selection, wants all devices on the map.
- **Status:** built, 25 unit tests pass; not checked on device. Note: own-sighting table will grow faster now; no retention limit yet.

## 2026-10-30 — Map redesign: one dot per device, side-tab info panel, pinned Live controls
- **Changed:** Map now shows one dot per device (live = estimate, else fanned out around my position as hollow "approximate" dots; WiGLE = last known place, only those in view, clustered below zoom 17, 4000 cap). Tap a dot for the panel; layer toggles LIVE / WIGLE / TRAIL (trail of the selected device only, off by default). Checklist and ticking removed. Info panel has INFO / TRACK / MAP side tabs (TRACK = hot/cold tracker, MAP = mini offline map), scrollbar, large red X, no CLOSE button; shared by Live and Map. Live screen: START/STOP (+PERMS/BG) pinned, panel fills the rest and scrolls (portrait and landscape). Pager TRACKER view removed. DB v3 adds index on device(lat,lon). Secondary text colour Phosphor.sub for contrast.
- **Why:** D-024 (user feedback + questionnaire answers).
- **Status:** built, 25 unit tests pass; NOT checked on device (layout fit, tap hit-testing, mini map, clustering performance with a large WiGLE import).

## 2026-10-30 — Map tab fixes, multiple combined map files
- **Changed:** Map buttons and overlay text now on solid dark chips; map runs full width (landscape: list is a toggle panel over the map); tab survives rotation (rememberSaveable); several .map files can be added and are combined (MultiMapDataStore, DEDUPLICATE); header check rejects non-Mapsforge files; touch-listener stops parents stealing drags; redraw on GPS only when moved ~1 m.
- **Why:** User test feedback (unreadable text, map not full width in landscape, tab reset on rotate, one map file only, inconsistent scrolling). Overlap policy DEDUPLICATE chosen by me (not asked) so border tiles from neighbouring countries are not cut off; revisit if slow.
- **Sources:** Mapsforge 0.25.0 API (class present in the Gradle-resolved jar); behaviour not yet tested on device.
- **Status:** built, 25 unit tests pass; scrolling fix unverified.

## 2026-10-30 — Map tab, vendor names, position estimate, WiGLE observations
- **Changed:** Added Mapsforge 0.25.0 and AndroidSVG 1.4. New DejaApplication (graphics factory + vendor load), data/Vendors.kt with assets/vendors.tsv and companies.tsv, geo/PositionEstimator.kt (weighted centroid and path-loss fit, picks the better), DejaDb v2 (obs and sight tables), WigleImporter now imports all `location` observations, ScanService records GPS fixes and a sighting every 5 s per device, ui/MapPanel.kt (Map tab: offline map file, checklist, WiGLE dots, own-scan dots, last-known diamond, estimate circle, phone ring), VendorBadge (monogram, no logos), info panel shows vendor, estimated distance and a MAP button, radar/bars/history taps also tick the device on the map, Clear-my-scan-history button, map file picker. 25 unit tests pass.
- **Why:** D-023.
- **Sources:** C-009 to C-013
- **Status:** done for build; NOT run on a device. Untested: map rendering with a real .map file, importing a large observation set, GPS sample saving, estimate accuracy.

## 2026-10-30 — Fix crash: database locked during import
- **Changed:** DejaDb now enables write-ahead logging (onConfigure) and DejaDb.match catches SQLiteException.
- **Why:** Crash reported by user. Logcat from the phone showed SQLiteDatabaseLockedException (SQLITE_BUSY) in DejaDb.match called on the main thread from ScanService.readWifiResults while the ViewModel import held the write lock.
- **Sources:** none (cause read from device logcat)
- **Status:** built; fix NOT yet confirmed on the device.

## 2026-10-30 — Round 2: live link, approach alerts, five views, retro terminal UI
- **Changed:** Linked-file import (persistable URI, modified-time check on app open + Refresh button) replaces the broken WiGLE-folder read (VM/Prefs/MainActivity/Screens). New scan/Track.kt, ApproachTracker.kt (first sighting + +10 dB rule), SightingBus (history, best, count, advertised data). ScanService now collects Wi-Fi/BLE/Classic advertised data. New ui/Terminal.kt (phosphor palette, CRT scanlines/vignette, monospace), ui/LiveViews.kt (radar with tap-to-select, bars, history, Wi-Fi spectrum, hot/cold tracker with haptics+beeps), ui/InfoPanel.kt. Live screen rebuilt (auto-fits portrait/landscape, pager + dropdown switch, bottom list removed). Added VIBRATE permission. 15 unit tests pass.
- **Why:** D-017 to D-022.
- **Sources:** C-001, C-007, C-008
- **Status:** done for build; NOT run on a device (animation, tap targets, landscape layout, haptics/beeps, persistable file permission all untested). Probe capture parked.

## 2026-10-29 — Dark radar UI redesign
- **Changed:** New ui/Theme.kt (dark/light green scheme, ThemeMode), ui/Widgets.kt (hand-drawn icons, chips, signal bars, animated RadarView), rewritten ui/Screens.kt (cards, bottom navigation, segmented watch-mode selector, filter chips), MainActivity (splash, edge-to-edge, theme), Prefs/AppViewModel (theme toggle). Added res/ (colors, themes, adaptive launcher icon with monochrome layer, splash icon) and core-splashscreen 1.2.0.
- **Why:** D-016.
- **Sources:** C-006
- **Status:** done for build; NOT yet viewed on a device (appearance, radar animation, splash and icon untested).

## 2026-10-29 — Deja Scan project created and built
- **Changed:** Added Gradle project, manifest, data layer (DejaDb, Prefs, WigleImporter), ScanService, Compose UI, unit tests (MacUtilTest, WigleImporterTest). Installed platform 37.0 and build-tools 37.0.0 (libraries require compileSdk 37). Fixed BLE scan-mode constant and Kotlin method-name clashes found by the build.
- **Why:** D-001 to D-014.
- **Sources:** C-001, C-002, C-003, C-004, C-005
- **Status:** done for build; NOT run on a device or emulator.

## 2026-10-29 — Installed build tools
- **Changed:** Downloaded Android cmdline-tools (commandlinetools-linux-15859902_latest.zip, 181,833,628 bytes) to ~/Android/dl-cmdtools, installed at ~/Android/Sdk/cmdline-tools/latest; Gradle 9.8.2 (sha256 matches published) at ~/Android/gradle-9.8.2.
- **Why:** User approved downloads and versions (D-014 and follow-up questionnaire).
- **Sources:** C-004
- **Status:** in progress — SDK licence acceptance and platform install pending (user accepts licence).

## 2026-10-29 — Verified Android docs (C-002, C-003); version lookup (C-004)
- **Changed:** SOURCES.md entries updated with full-page reads.
- **Why:** D-005, D-006
- **Sources:** C-002, C-003, C-004
- **Status:** done

## 2026-10-29 — Research and decisions, no app code yet
- **Changed:** Created docs/DECISIONS.md, SOURCES.md, CHANGELOG.md.
- **Why:** Requirements gathering per D-001 to D-008.
- **Sources:** C-001, C-002, C-003
- **Status:** in progress — Android doc pages need full reads; app code not started.
