# Signal Station integration evidence

By Luke Steuber. Checked September 8, 2026.

## Verified locally

- Android `inspectorLab` compilation and APK assembly pass. Package identity is
  `coredevices.coreapp.inspectorlab`; revision 2 is
  `1.12.0.1-inspector-lab.2`, version code `11200002`.
- Companion host suite: 115 tests, zero failures. Includes provider fixtures,
  history dates/provenance/deduplication, actual C watch-payload normalization,
  and invalid/disabled watch observations.
- Library voice checks: two targeted tests pass for app UUID routing and real
  VoiceSessionManager disconnection cleanup. Other library tests were not rerun.
- Android lint: zero errors, six existing/upstream warnings (target SDK, upstream
  network security configuration, two PebbleKit providers, ignored upstream test).
- Real Android Keystore/Room instrumentation: one test passes on the ARM64 Android
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

The matching watch source is `991e6be02b10dd0a412e49138044ef246fc3e824`.
`androidApp/src/inspectorLab/assets/signal-station/watch-provenance.json` records
its executable script and PBW SHA-256. The revision-specific staged APK metadata
and checksum identify the exact installation package; rebuilds may change bytes.

## Companion walkthrough

The Android 16 emulator completed watch-only onboarding with sign-in skipped and
no watch paired. Conversation, provider settings, and individual source controls
were inspected. Enabling only phone battery produced one fresh emulator battery
reading, saved a clear missing-key failure without a provider request, and exposed
that reading through History after an APK update/restart. Confirmed deletion then
returned History to zero records. Screenshots are under `docs/signal-station/`.
The walkthrough also found and corrected the upstream uninstall warning/connection
block for the lab: stock installations can remain, with one active connection.

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
