# Sources

## [C-001] WiGLE Android client — DatabaseHelper.java, NetworkType.java, FileUtility.java
- **Publisher / author:** WiGLE.net (wiglenet/wigle-wifi-wardriving)
- **URL / location:** https://github.com/wiglenet/wigle-wifi-wardriving (path wiglewifiwardriving/src/main/java/net/wigle/wigleandroid/db/DatabaseHelper.java, model/NetworkType.java, util/FileUtility.java), branch main
- **Published / last updated:** DatabaseHelper.java last commit 2026-09-22 (GitHub API)
- **Accessed:** 2026-10-29
- **Tier:** 1
- **Why used:** Authoritative definition of the `network` table (bssid PK, ssid, frequency, capabilities, lasttime, lastlat, lastlon, type, bestlevel, bestlat, bestlon, rcois, mfgrid, service), type codes (W wifi, B bluetooth, E BLE, G/C/L/D/N cell), DB filename `wiglewifi.sqlite`, backup folder `sqlite`.
- **Where used:** chat answer 2026-10-29: schema and import design
- **Verification:** UNVERIFIED (single source; pass 2 cross-check pending, e.g. against a real exported .sqlite from the user's phone)
- **Notes:** FileUtility.hasSD() returns false after Android 10 (Q), so DB lives in app-private storage and backups in context.getFilesDir()/sqlite, unreadable by other apps (read 2026-10-30; see D-017). Read from `main`; may differ from the installed app version. Cell rows use `_` in bssid (query stem `instr(bssid,'_')`). Open GitHub issue #82 reports bad best-location values.

## [C-002] Wi-Fi scanning overview
- **Publisher / author:** Android Developers
- **URL / location:** https://developer.android.com/develop/connectivity/wifi/wifi-scan
- **Published / last updated:** 2026-10-28 (page footer)
- **Accessed:** 2026-10-29
- **Tier:** 1
- **Why used:** Scan throttling (4 scans / 2 min foreground, Android 9+; same on 10+), startScan deprecated in API 28.
- **Where used:** chat answer 2026-10-29: limitations
- **Verification:** Full page read 2026-10-29: Android 9 foreground 4 scans/2 min, background 1/30 min combined; Android 10+ same limits, developer option to disable. Within 12-month limit. Single Tier 1 source; second source (WifiManager reference) not yet read.

## [C-003] Bluetooth permissions
- **Publisher / author:** Android Developers
- **URL / location:** https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- **Published / last updated:** 2026-10-28 (page footer)
- **Accessed:** 2026-10-29
- **Tier:** 1
- **Why used:** BLUETOOTH_SCAN runtime permission (Android 12+); ACCESS_FINE_LOCATION needed on 11 and lower and for results when targeting Q+.
- **Where used:** chat answer 2026-10-29: permissions
- **Verification:** Full page read 2026-10-29: BLUETOOTH_SCAN needed on 12+; neverForLocation filters some beacons; FINE_LOCATION needed if deriving location. Within 12-month limit. Android 11-and-lower rule not yet cross-checked on a second page.

## [C-004] Google Maven / Maven Central version metadata
- **Publisher / author:** Google (dl.google.com/dl/android/maven2), Maven Central
- **URL / location:** .../androidx/compose/compose-bom, com/android/tools/build/gradle, kotlin-gradle-plugin maven-metadata.xml
- **Published / last updated:** live metadata, 2026-10-29
- **Accessed:** 2026-10-29
- **Tier:** 1
- **Why used:** Pick library versions. `release` tags point to alpha/beta (AGP 9.6.0-alpha02, Kotlin 2.5.0-Beta2); stable versions not yet chosen.
- **Where used:** app/build.gradle.kts, build.gradle.kts (AGP 9.5.0, Kotlin 2.4.21, Compose BOM 2026.09.00, activity-compose 1.13.0, lifecycle-viewmodel-compose 2.11.0)
- **Verification:** Versions confirmed by successful build 2026-10-29. Build also required compileSdk 37 (Gradle error message).

## [C-005] Foreground service types
- **Publisher / author:** Android Developers
- **URL / location:** https://developer.android.com/develop/background-work/services/fgs/service-types
- **Published / last updated:** 2026-10-28 (page footer)
- **Accessed:** 2026-10-29
- **Tier:** 1
- **Why used:** `location` type needs FOREGROUND_SERVICE_LOCATION, startForeground type FOREGROUND_SERVICE_TYPE_LOCATION, and fine/coarse location granted.
- **Where used:** AndroidManifest.xml, ScanService.kt
- **Verification:** Single Tier 1 source, full page read.
- **Notes:** Not tested on a device.

## [C-006] androidx.core:core-splashscreen version metadata
- **Publisher / author:** Google (Google Maven)
- **URL / location:** https://dl.google.com/dl/android/maven2/androidx/core/core-splashscreen/maven-metadata.xml
- **Published / last updated:** live metadata, 2026-10-29
- **Accessed:** 2026-10-29
- **Tier:** 1
- **Why used:** Latest stable is 1.2.0 (1.0.0, 1.0.1 also stable); user approved 1.2.0.
- **Where used:** app/build.gradle.kts
- **Verification:** Build succeeded with 1.2.0. Splash behaviour not yet seen on a device.

## [C-007] Android PCAP (Kismet)
- **Publisher / author:** Kismet Wireless
- **URL / location:** https://www.kismetwireless.net/static/android-pcap/
- **Published / last updated:** unknown, appears ~2013 (older than the 12-month limit set in D-006)
- **Accessed:** 2026-10-30
- **Tier:** 2
- **Why used:** States raw 802.11 capture on Android needs custom firmware and root for the internal Wi-Fi, but a userspace USB driver (RTL8187 adapters) works without root.
- **Where used:** chat answer 2026-10-30: probe requests / non-root monitor mode
- **Verification:** UNVERIFIED — older than the age limit, no Tier 1 corroboration found; Play Store app "USB WiFi Monitor" (AR9271 adapters) is a product listing, not a Tier 1 source.
- **Notes:** Flagged for the user per the age rule.

## [C-008] IEEE 802.11 channelization (2.4/5/6 GHz formulas)
- **Publisher / author:** IEEE
- **URL / location:** not consulted
- **Accessed:** 2026-10-30
- **Tier:** 1 (intended)
- **Why used:** channelFor() in scan/Track.kt uses centre frequency = 2407+5n (2.4 GHz), 5000+5n (5 GHz), 5950+5n (6 GHz), plus ch14 = 2484 MHz.
- **Where used:** app/src/main/java/com/dejascan/app/scan/Track.kt
- **Verification:** UNVERIFIED — written from prior knowledge, not checked against the standard or Android docs. Unit-tested only against the same formulas. 5 GHz and 6 GHz channel numbers overlap and are not told apart.

## [C-009] Mapsforge (library, licence, map providers)
- **Publisher / author:** mapsforge project
- **URL / location:** https://github.com/mapsforge/mapsforge (README, docs/Mapsforge-Maps.md, docs/Changelog.md); Maven Central org.mapsforge:*:0.25.0
- **Published / last updated:** repo pushed 2026-10-28; release 0.31.0 on GitHub 2026-10-28; Maven Central latest 0.25.0 (2025-04-04)
- **Accessed:** 2026-10-30
- **Tier:** 1
- **Why used:** Offline vector map rendering from .map files; licence LGPL-3.0 (GitHub API) with an exception described in the README (search summary only — exception text not read in full). Map providers listed in docs/Mapsforge-Maps.md.
- **Where used:** app/build.gradle.kts, ui/MapPanel.kt
- **Verification:** API signatures checked with javap against the 0.25.0 jars; app compiles. Version 0.25.0 is older than the 12-month limit (D-006) — user accepted (D-023). LGPL obligations not reviewed in detail.

## [C-010] IEEE Registration Authority OUI registries
- **Publisher / author:** IEEE Registration Authority
- **URL / location:** https://standards-oui.ieee.org/oui/oui.csv, /oui28/mam.csv, /oui36/oui36.csv
- **Published / last updated:** Last-Modified 2026-10-30 (files updated daily)
- **Accessed:** 2026-10-30
- **Tier:** 1
- **Why used:** Manufacturer name from the first 24/28/36 bits of a MAC. Processed into app/src/main/assets/vendors.tsv (prefix + name only; addresses dropped; 54,337 records, 0 malformed).
- **Where used:** assets/vendors.tsv, data/Vendors.kt
- **Verification:** Sizes match the HEAD response; sha256 of the raw files: oui.csv 1ab54b02f07a44fba5af30243ce1b91ecfbdb8fbda802181b4bb635541b32504, mam.csv 5d3905e44223a19b645e5a069c26c598ce775fb8c14b318f52e526d9a4121239, oui36.csv e1126ac9f5e5987258afad90015378c92a664d8942467e19b77f6694b63c6a7f.
- **Notes:** Redistribution/terms of use NOT confirmed (UNVERIFIED), so the processed tables are NOT stored in the repository; scripts/fetch_vendor_data.py rebuilds them (re-tested 2026-11-01: 54,343 records, 6 newer than the 2026-10-30 copy). Many entries are 'Private' (undisclosed assignees). Data goes stale; refresh by re-downloading.

## [C-011] Bluetooth SIG Assigned Numbers: company identifiers
- **Publisher / author:** Bluetooth SIG
- **URL / location:** https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml
- **Published / last updated:** fetched 2026-10-30 (file date not shown)
- **Accessed:** 2026-10-30
- **Tier:** 1
- **Why used:** Names for BLE manufacturer IDs. Processed into assets/companies.tsv (4,060 entries). sha256 of raw file: 0683ea988bf949e4d9f38b790b3176a6ecabcf51753f837918b7fc6fb6120b98.
- **Where used:** assets/companies.tsv, scan/ScanService.kt (bleAdv)
- **Verification:** UNVERIFIED terms (table also not stored in the repository; rebuilt by scripts/fetch_vendor_data.py, raw file sha256 unchanged on 2026-11-01) — Bluetooth SIG updated its Terms of Use on 2024-10-29 (search summary); redistribution rights not checked.

## [C-012] OpenStreetMap Foundation vector tile usage policy
- **Publisher / author:** OpenStreetMap Foundation Operations
- **URL / location:** https://operations.osmfoundation.org/policies/vector/
- **Accessed:** 2026-10-30
- **Tier:** 1
- **Why used:** States bulk (offline) downloading of OSM tiles from the foundation's servers is prohibited; reason an offline file-based map was chosen.
- **Where used:** chat answer 2026-10-30: map options
- **Verification:** UNVERIFIED — from a search-result summary; the raster policy's 250-tile threshold was not confirmed.

## [C-013] Mapsforge / osmdroid / MapLibre version metadata (Maven Central)
- **Publisher / author:** Maven Central
- **URL / location:** https://repo1.maven.org/maven2/org/mapsforge/mapsforge-map-android/maven-metadata.xml and similar
- **Accessed:** 2026-10-30
- **Tier:** 1
- **Why used:** osmdroid 6.1.20 (2024-08-18), mapsforge 0.25.0 (2025-04-04), MapLibre 2026.10.19-globe.
- **Where used:** chat answer 2026-10-30: map options
- **Verification:** Build resolved the Mapsforge 0.25.0 artifacts.
