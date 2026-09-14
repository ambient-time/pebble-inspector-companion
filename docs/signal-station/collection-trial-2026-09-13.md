# Watch collection trial

By Luke Steuber. September 13, 2026, America/Los_Angeles.

## Result and target

The physical Pebble Time 2 and Pixel 9a saved all 15 requested completed history
minutes through the existing stock Pebble app. The bundle retained movement,
orientation, light categories and an available heart-rate record; missing heart
rate remained unknown. Raw personal readings remain outside Git.

Time 2 is the primary target for expanded collection and firmware experiments.
The 2 SE remains a compatibility target: it lacks the heart-rate sensor present
on Time 2. The package includes native binaries for both, alongside four other
platforms. See [heart-rate support](https://developer.repebble.com/guides/events-and-services/hrm/)
and [Time 2 specifications](https://repebble.com/watch).

The initial SE connection attempt repeatedly timed out during Bluetooth bonding.
After the Time 2 reconnected, its identity was verified as Emery with firmware
4.36.2. Only that watch was connected when addon 1.6.2 was installed. The stock
Pebble Android host remained version 1.12.0.1. No reset, unpair, firmware flash,
provider request or public publication was performed.

## Corrections found by the trial

- Diorite QEMU 4.3.0 returned 11 minute records spanning 04:47–04:58 UTC for a
  request ending at 04:57 UTC. The initial package rejected the whole batch.
  The corrected collector validates its shape, excludes the extra current
  minute and retains completed records inside the request. Original SDK bounds,
  count and exclusions remain labelled metadata. A separate emulator-only
  diagnostic build exposed the bounds; it was not installed on physical hardware.
- The main source picker now excludes minute history from bulk enable and shows
  its full description, matching the older settings page. The final Android
  binary passed a manual bulk-enable check: three other watch sources on,
  minute-history switch still off. It was enabled individually on the phone.
- A large phone selection produced a 1,348-byte watch source message, exceeding
  the watch's 900-byte buffer. Capabilities and commands now send only supported
  watch keys. Physical host logs verified the message fell to 256 bytes with the
  new history source retained. Phone source settings remain intact.

## Measured evidence

- Watch: 32 bridge protocol tests plus C motion, button, DST, batching and actual
  collector tests passed. The new regression covers the observed extra-minute
  case, an entirely later batch and malformed windows. Six native targets build;
  the largest synthetic snapshot is 948 bytes.
- Android: 206 host tests discovered, 200 passed, six existing skips, no failures
  or errors. This includes actual C-generated clipped-history JSON and a source
  buffer regression with 200 unrelated phone keys. Debug build passed.
- Diorite QEMU: stock-host acknowledgement, capture saved on phone, all 15 history
  minutes retained in the corrected package, and Down opened recent history on
  the watch. Packet metadata showed no reset-endpoint commands. This is emulator
  evidence, separate from the physical result.
- Pixel 9a: Android 17/API 37; in-place update from development build 10 preserved
  saved history and source choices. Signing certificate continuity was checked
  before updating. The new history source was observed off before individual
  enablement. The Time 2 acknowledged the app after installation.
- Physical capture at 22:13:50: 80 selected sources, 200 retained readings, 168
  available and 32 unavailable/partial; 1,106 omitted by the documented size limit.
  The new history bundle was retained with all 15 requested minutes, no invalid
  or missing minutes, and a measured window ending before collection.
- Physical motion: zero accepted of 90 received; 50 marked as vibration, 40
  rejected for timestamps. The UI showed unavailable and no measured variance.
  This validates exclusion reporting, not successful motion measurement.

Private local evidence: `/Volumes/Galactus/drummer/signal-station/collection-trial-20260913/`.
It contains build logs, UI captures, synthetic wire metadata and a separate
`physical/` directory. Personal watch identifiers and raw health/radio values are
not reproduced in this receipt.

## Candidate identity and repositories

Both repositories were checked with authenticated GitHub metadata and are private:
[`ambient-time/pebble-field-inspector`](https://github.com/ambient-time/pebble-field-inspector)
and [`ambient-time/pebble-inspector-companion`](https://github.com/ambient-time/pebble-inspector-companion).
GitHub reported both repository transfers during the trial; their old remote
URLs redirect to the verified private destinations. Work uses `codex/signal-station`.

- Watch source: `df62defa6614e5d90d6a2195fe37569fd0830bf8`.
- Addon: `signalApp/build/watch-collection-preview-1.6.2/signal-station-addon-1.6.2.pbw`,
  139,218 bytes, SHA-256 `b02b802a747ecdda73c9589fdbce588b1d4398e52d2b499c86b63985d0289632`.
- Source ZIP SHA-256: `73cc819f7ec7e4b89c883217ad343ecaef6d7a012c56348ba9e0dd19e9b15881`.
- Android source: `27205af9` (includes picker correction `b9120381`). Development
  0.4.0-ux-dev/build 13; embedded source identity verified after rebuilding.
- APK: `signalApp/build/outputs/apk/debug/signalApp-debug.apk`, 108,779,314 bytes,
  SHA-256 `b64eed88b5ea3b76105261f0fd8eec5c9992af4ad20ca1df559320294709901e`.

At completion of the collection trial, the physical phone had this APK and the
Time 2 had addon 1.6.2. The existing public
APK/PBW release descriptors and download bytes remain unchanged. This is still a
development trial, not full release acceptance.

## Next probe

Use the Time 2 to inspect accelerometer timestamp ordering and vibration flags,
then repeat stationary/moving captures with fewer selected sources. Measure
battery impact and verify cancel/disconnect cleanup, locked-phone operation,
dictation, accessibility and runtime stack margin before a broader release.
The [firmware roadmap](https://github.com/ambient-time/pebble-field-inspector/blob/codex/signal-station/docs/watch-collection.md)
keeps heart-interval quality and fresh-light APIs ahead of gyro experiments.
SE compatibility does not establish Time 2 sensor behavior, or vice versa.

## Foreground backlight follow-up

Addon 1.6.3 holds the light while Signal Station is visible, releases it before
losing focus or exiting, and restores it on return. Watch source:
`6ed7f7872001f582f37c8c92112cac9a0d896fa8`. All 32 protocol tests, existing C
harnesses and six target builds passed. Keeping the light on uses more battery.

The 139,816-byte no-PKJS package has SHA-256
`e49fdaba5fee73096a7a97790209a6e4b7108f37cab5bcd0baff93e9af9e69eb`;
the source ZIP has SHA-256
`ff26f08edb88b426f90597aa6d4c4322ef629cffb4668fdc10e21db402a7dd44`.
The stock host handled the package at 22:31:43 and the sole connected Time 2
reported the app running at 22:31:47. Subsequent app messages establish transport,
but physical backlight illumination and automatic timeout after exit still need
visual observation. The Android APK did not change for this follow-up.

## Side-button layout follow-up

Addon 1.6.4 retains the foreground backlight and replaces the home icons with
explicit UP / SELECT / DOWN badges beside Capture / Ask / History. The watch
source is `e65d4c2c29ce8bbfcd31e3dadd2f95a9e6086446`.

The 142,594-byte package has SHA-256
`ab7cc3cf2265a42782d8513e75e7a39fa674e37ff5da3fc9c61a8278ffc37204`;
the source ZIP has SHA-256
`14a55d3bf8acda9c705e4a883d8e6f440b3b4a5c6ac95b0ddaef06d953bd4ab4`.
All six builds and existing watch tests passed. Home, help, scroll and Back were
checked in Emery, Diorite and Chalk emulators. The stock host handled this
package at 22:53:14 and the sole connected Time 2 reported it running at 22:53:16.
The separate [radio and scheduled collection update](radio-and-scheduled-ux.md)
records the companion changes and their validation.
