# Deja Scan

An Android app that imports the database of the WiGLE Android app and checks, live, whether any of the
saved devices (Wi-Fi access points, Bluetooth Classic, Bluetooth LE) are close by. "Close by" means the
device was actually heard in a live scan; the signal strength is only a rough hint of proximity.

Not affiliated with WiGLE, IEEE, the Bluetooth SIG or OpenStreetMap.

## Features

- Import of a WiGLE `.sqlite` backup (linked file, refreshed when it changes), including WiGLE's observations.
- Live scanning in a foreground service: Wi-Fi, BLE and Bluetooth Classic discovery. Alerts only when a device
  appears for the first time or gets clearly closer.
- Live views: radar, signal bars, signal history, Wi-Fi channel spectrum; hot/cold tracker with haptics and beeps.
- Manufacturer names from the MAC prefix (IEEE registries) and Bluetooth company IDs.
- Offline map (Mapsforge `.map` files, several can be combined): one dot per device, numbered clusters when
  zoomed out, position estimate from your own movement, a chosen device's places with first/last seen, dotted
  lines for devices seen in several places, and a day search on the Devices tab.
- Everything stays on the phone. The app declares no internet permission.

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
