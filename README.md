# Deja Scan

An Android app that imports the database of the WiGLE Android app and checks, live, whether any of the
saved devices (Wi-Fi access points, Bluetooth Classic, Bluetooth LE) are close by. "Close by" means the
device was actually heard in a live scan; the signal strength is only a rough hint of proximity.

Not affiliated with WiGLE, IEEE, the Bluetooth SIG or OpenStreetMap.

## Features

## Features

- **WiGLE SQLite Import & Two-Way Merge:** Import WiGLE `.sqlite` backups with observation histories. Merge newly discovered live networks and GPS location sightings directly back into the linked WiGLE database from the Devices tab.
- **Live Scanning & Real-Time Metrics:** Foreground scanning service for Wi-Fi, BLE, and Bluetooth Classic with real-time counter bar (Total Networks, New Discovered networks, rolling AP Density per minute, and session Peak Density).
- **Wi-Fi Security Suite Analysis & Passive Advisories:** Automatic classification of Wi-Fi security suites (WEP, Open, OWE, WPA, WPA2, WPA3, WPS, TKIP). Passive / silent system notifications and in-app advisory banners for vulnerable or unencrypted networks (e.g., WEP alerts with cryptographic risk explanations and remediation suggestions).
- **Six Live Views:**
  1. *Radar:* 360° signal-level radar with pulsing warning rings on insecure/WEP APs and tap-to-inspect.
  2. *Signal Bars:* Ranked signal strength meters with security and type badges.
  3. *Signal History:* Real-time signal fluctuation graphs over time.
  4. *Spectrum Scanner:* Wi-Fi channel bandwidth & spectrum scanner (2.4 GHz, 5 GHz, 6 GHz with Wi-Fi 6 / 6E / 7 standards, 20/40/80/160/320 MHz bandwidth curves, and hardware capability detection).
  5. *AP Density:* Rolling per-minute AP discovery and density timeline graph.
  6. *Vendors:* Live breakdown and search of active devices grouped by manufacturer (IEEE registries and Bluetooth company IDs) with monogram chips and randomized MAC flags.
- **Root Status Detection:** Automatic non-intrusive environment check detecting superuser/root privileges (su binaries, Magisk, KernelSU, APatch, test-keys) with a header status indicator.
- **Hot/Cold Proximity Tracker:** Proximity tracking with configurable audible beeps and haptic vibration feedback.
- **Offline Map:** Mapsforge vector map rendering with clustering, movement-based position estimation, multi-location device tracking (solo view, links), and day search.
- **Privacy First:** 100% offline, no cloud connectivity, zero internet permission (`android.permission.INTERNET` is not declared).

## Build

Requirements: JDK 17 or newer, Android SDK platform 37 and build-tools 37.0.0 (see `app/build.gradle.kts`).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties     # not committed
python3 scripts/fetch_vendor_data.py                     # downloads the manufacturer name tables (see below)
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The app also runs without the name tables;
manufacturer names are then simply missing.

## Data you supply

- A WiGLE database backup (from the WiGLE Android app).
- Offline map files in Mapsforge format, e.g. from <https://download.mapsforge.org/>. Add them with the MAPS
  button on the Map tab. Map files over 100 MB are only drawn from a chosen zoom level (default 10) to keep
  memory use down.

## Manufacturer name tables

`scripts/fetch_vendor_data.py` builds `vendors.tsv` and `companies.tsv` from the public IEEE OUI registries and
the Bluetooth SIG company identifiers. They are not in this repository because their redistribution terms were
not confirmed; check the publishers' terms before redistributing them.

## Documentation

`docs/DECISIONS.md` (what was decided and why), `docs/SOURCES.md` (every outside source, with how far it was
verified) and `docs/CHANGELOG.md`. Several results are marked unverified there, for example the Wi-Fi channel
formulas and the path-loss exponent used for position estimates.

## Licence

MIT, see `LICENSE`. The Mapsforge libraries used are LGPL-3.0 with a linking exception, see `docs/SOURCES.md`.
