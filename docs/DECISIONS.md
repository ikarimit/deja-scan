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
