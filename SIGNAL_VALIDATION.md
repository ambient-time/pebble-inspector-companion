# Signal Station integration evidence

By Luke Steuber. Checked September 8, 2026.

## Verified locally

- Android `inspectorLab` compilation and APK assembly pass. Package identity is
  `coredevices.coreapp.inspectorlab`; revision 3 is
  `1.12.0.1-inspector-lab.3`, version code `11200003`.
- Companion host suite: 126 tests, zero failures. Includes provider fixtures,
  history dates/provenance/deduplication, actual C watch-payload normalization,
  invalid/disabled watch observations, and eleven weather tests for independent
  switches, missing/stale data, forecast coverage, polar daylight, place choices,
  cancellation, request limits, migration, and history filtering.
- Library voice checks: two targeted tests pass for app UUID routing and real
  VoiceSessionManager disconnection cleanup. Other library tests were not rerun.
- Android lint: zero errors, six existing/upstream warnings (target SDK, upstream
  network security configuration, two PebbleKit providers, ignored upstream test).
- Real Android Keystore/Room instrumentation: one test passed on lab revision 2 on the ARM64 Android
  16 emulator. Verifies encrypted content at rest, restart persistence, independent
  answer/STT credentials, deletion, and clearing with an isolated test namespace.
  The first run timed out during app startup with a 2GB emulator under memory/disk
  pressure; a cold 6GB emulator rerun passed. No test was counted from the failed run.
- Seventeen Python package verification tests pass, including rejected mismatched
  watch script/PBW digests. The staged APK verifier checks signature, isolation,
  packaged backup exclusions, and the exact bundled PBW/PKJS source provenance.
- Watch 1.1.0 builds for Basalt, Chalk, Diorite, Emery, Flint, Gabbro. Twenty JS
  protocol tests plus C motion/UTF-8/DST checks pass. Emery and Chalk rendering,
  scrolling, duplicate delivery, and late reply cancellation were checked with
  synthetic messages. Watch evidence and screenshots live in the watch repository.

The matching watch source is `dc37e48acfc6018b825c13fa5da045208c191097`.
`androidApp/src/inspectorLab/assets/signal-station/watch-provenance.json` records
its executable script and PBW SHA-256. The revision-specific staged APK metadata
and checksum identify the exact installation package; rebuilds may change bytes.

## Companion walkthrough

With lab revision 2, the Android 16 emulator completed watch-only onboarding with sign-in skipped and
no watch paired. Conversation, provider settings, and individual source controls
were inspected. Enabling only phone battery produced one fresh emulator battery
reading, saved a clear missing-key failure without a provider request, and exposed
that reading through History after an APK update/restart. Confirmed deletion then
returned History to zero records. Screenshots are under `docs/signal-station/`.
The walkthrough also found and corrected the upstream uninstall warning/connection
block for the lab: stock installations can remain, with one active connection.

## Weather checks in lab revision 3

Current weather, next-six-hour forecasts, daylight, air quality, and UV compile
into the native companion with separate switches. The host fixtures pass, and
live requests for the public city centre of Portland returned the expected
weather, daylight, air-quality, UV, and geocoding fields. These HTTP checks did
not use the phone's location, user keys, or health information.

An independent source-archive build compiled all six watch targets and matched
the frozen PKJS script byte for byte. Native differences were confined to the
first 168 bytes; each remaining binary payload matched. The source archive and
frozen release keep their own checksums rather than claiming identical PBWs. Weather runs in the companion; the watch
renderer and executable script are unchanged from the prior emulator captures.

The new place-picker and weather controls still need an interactive phone check.
Signal Station 1.1.0 is saved in the Pebble dashboard with an Unlisted listing
and a Draft release. The saved description and three native emulator screenshots
each for Chalk and Emery were verified after reopening the listing. The other
four target media panels are empty.

The new-app submission unexpectedly published immediately. The release was
immediately returned to Draft and listing visibility disabled; both saved states
were verified. [Open the saved draft](https://developer.repebble.com/dashboard/apps/37360ca4d9764881bd1d6f4d/edit).

## Physical and account checks still required

No physical phone was attached to ADB during this run. No provider credentials
were supplied to the experiment, so no live provider or transcription request
was made. Emulator radio/health readings are not physical measurements.

On the designated Pixel 9a and Time 2, check the full typed/watch-dictated/survey
round trips, permissions revoked while working, phone locked/background behavior,
radio freshness, available health metrics and local dates, cancellation, reconnect,
watch delivery ACK, long readable replies, and stock pairing recovery. Test the
selected provider/model with its own key, and transcription with its separate key.
Other watch models require their own hardware run despite successful compilation.

OpenAI dictation requires one connected trusted watch; multiple connections fail
closed rather than routing speech under another watch's authorization. Unsupported
sensor/health data remains unavailable. No physical install, public release,
store publication, or live-provider success is implied by these build results.
