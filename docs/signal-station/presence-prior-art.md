# Presence building blocks

Signal Station uses the phone's existing, explicitly requested five-second scan. A Pebble or ESP32 is optional. Radio observations and beacon names remain local until a record is explicitly submitted for model analysis.

## Implemented

- A small, independently written Kotlin parser recognizes iBeacon manufacturer payloads and the AltBeacon v1.0 format. It keeps the complete advertised beacon identifier for devices the user enrolls. Existing address-based enrollments still load unchanged. If the same enrolled beacon identifier appears at multiple addresses in one scan, the result is marked ambiguous.
- Candidate details show the advertised format, numeric Bluetooth company identifiers, and readable names for six standard GATT services. Unknown UUIDs remain visible. This metadata describes advertisements; it does not verify a device's manufacturer or owner. No downloaded OUI or company-name dataset is bundled.
- Each scan retains up to 32 timestamp-distinct RSSI samples per retained address and reports their median. Samples expire after 15 seconds and are discarded when the scan ends. At most 64 addresses are retained.
- The local presence view derives last-seen time and repeated sightings from saved original captures. Two independent measurement timestamps within five minutes support “observed repeatedly” only while the latest sighting is still fresh. Missing scans retain the historical last-seen time. Stale or unavailable current evidence is unknown. Disabling a source or enrolled target hides its derived evidence; deleting records removes their contribution.

These observations do not provide continuous arrival alerts, person counts, verified identity, or distances. Fixed receivers and room calibration remain a possible optional extension.

## Sources and reuse

Reviewed September 9, 2026:

- [AltBeacon protocol specification v1.0](https://github.com/AltBeacon/spec): manufacturer data layout and 20-byte identifier. The implementation follows the published wire format; specification prose and code were not copied.
- [Android Beacon Library](https://github.com/AltBeacon/android-beacon-library), Apache-2.0: reference for beacon layout parsing and separate signal filtering. No library source or binary is embedded. The `BeaconParser` layout documentation informed interpretation of manufacturer-data offsets.
- [Espressif ESP-IDF iBeacon example](https://github.com/espressif/esp-idf/blob/master/examples/bluetooth/bluedroid/ble/ble_ibeacon/main/esp_ibeacon_api.h), Unlicense or CC0-1.0 for this example file: reference for the iBeacon manufacturer-payload layout. No example code was copied.
- [Bluetooth SIG assigned numbers](https://www.bluetooth.com/specifications/assigned-numbers/): six standard GATT service UUID/name pairs. Company IDs remain numeric; no registry dataset is distributed.
- [Android Network Survey](https://github.com/christianrowlands/android-network-survey), Apache-2.0: reference for presenting manufacturer and service metadata as observations. No source or assets were copied.
- [Bermuda](https://github.com/agittins/bermuda), MIT: architecture reference for separating observations, freshness, and estimates. No Python code or dependencies were copied.

All new Kotlin implementation and tests are original Signal Station code under this repository's existing license. No external runtime, beacon SDK, automatic lookup, or additional scanning service was added.
