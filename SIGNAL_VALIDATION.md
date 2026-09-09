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


## Companion sweep — September 9, 2026

Downloaded the public lab-3 APK and watch 1.1.0 PBW again; both matched their
frozen SHA-256 digests. Installed that exact APK on the Field Inspector Android
emulator with `adb install -r`. No physical device was listed by local ADB.

Completed watch-only onboarding with sign-in skipped. Devices has a Signal
Station toolbar action. Conversation, History, answer/recognition settings,
selected-watch controls, and the weather place form all rendered. The weather
search itself and paid provider calls were not exercised. The empty History
screen correctly reported zero records; the conversation correctly reported
that no selected watch was connected. No fatal exception or protected-storage
initialization failure appeared in the inspected runtime log.

Re-ran 126 companion host tests (zero failures/errors), 17 package-verification
tests, and 20 watch protocol tests plus the C motion/UTF-8/DST checks. Source
tracing confirmed the Android Koin binding, injected per-runner HTTP context,
watch UUID and PKJS digest checks, selected-watch gate, and text/config-message
transport. These checks do not establish a physical end-to-end connection.

The signed-in CloudPebble account contains an older `Signals` weather watchface
(project 26277, last built September 3), not a Signal Station project. No build
or install was triggered there. A screenshot probe using Cloud Dev Connection
waited for a phone; the New Cloud Dev Connection probe returned “Connection
interrupted.” Signal Station requires the lab companion to own the connection;
a stock-companion developer connection alone does not verify its native bridge.

The matching PBW is bundled for provenance validation, but no install action
reads that asset. Installation currently relies on opening the separate PBW
file. The watch currently exposes latest-report viewing, not a history browser;
Survey always requests model analysis. Sensor-only capture, watch history, and
new button shortcuts are proposals under discussion, not implemented behavior.


## Companion revision 4 and watch 1.2.0

This supersedes the prior sweep's proposed-feature notes. Signal Station now has
its own lab-only launch route and optional first-launch setup, source choices,
permission prompts, app icon and label. The package and signing identity remain
unchanged. Capture saves observations without calling a model provider; later
analysis creates a separate referenced report. Bundled installation checks the
PBW digest and requires one selected connected watch before installing and waiting
for the trusted script handshake. No physical install is implied.

The watch now exposes Up Capture, Select Ask and Down History on its home screen.
History is a bounded local read with its own delivery acknowledgment; capture
completion uses the existing durable-record acknowledgment. Provider and source
changes refresh an open watch's settings. The imported watch source is
`22e1f7799011e34f4aebaa37242742dbd3e39a15`.

Host tests cover old settings/record decoding, missing readings, history filtering
and UTF-8 bounds. All six watch targets and 25 protocol tests plus the native C
button/motion/UTF-8/DST harnesses passed. Android assembly and lint passed during
integration. Final artifact, interactive and publication evidence follows after
staging. CloudPebble's read-only screenshot probe still waited for the phone;
no older Signals weather-face build was installed.


### Frozen revision 4 verification

Final source: `a842da86b19ba7d53dfaf3755dc2a74e0c4113b6`.
APK: `1.12.0.1-inspector-lab.4`, code `11200004`, label Signal Station.
SHA-256: `18356b0fa73bb6948457fb9eb8785fb4a51b0403feb50542971849f846b7decf`.
The original signing certificate remains unchanged. APK verification passed with
its bundled watch 1.2.0/PKJS digests, manifest isolation and backup exclusions.
Assembly, lint, 131 host tests and 17 package tests passed.

On the Android emulator, the optional three-step guide ran after an in-place
upgrade. Selecting only phone battery saved a ready capture without any provider
key. The record survived subsequent APK updates and process restart. After
revoking location, the guide showed Android's location prompt; declining it still
saved battery and a `permission_denied` location observation, without inference.
Record detail explained the missing provider prerequisite for later analysis.
Final light and dark screens were visually checked. No fatal or Room identity
errors appeared in the inspected log. These are emulator observations.

The public download page, index, APK, PBW, both source ZIPs, licenses, checksums,
and both discovery manifests were fetched over HTTPS and matched staged bytes.
Unrelated catalog/manifest entries were preserved. The listing is Unlisted and
watch release 1.2.0 remains Draft, with required Android companion metadata,
custom icons and native Emery/Chalk/Diorite screenshots. CloudPebble still waited
for a phone connection. Physical pairing, installation, sensors and paid
provider/dictation round trips remain unverified.

## Lab revision 5: slow chat transport

The real OkHttp transport reproduced a successful short endpoint test followed
by a failed slow chat at 10.36 seconds. Its inherited read timeout expired before
the application-level 30-second deadline. The adapter now explicitly allows a
60-second socket wait and total answer deadline, with a 15-second connection
limit. Transcription retains its separate 14-second deadline. This keeps normal
collection plus inference within the current watch polling window.

`SignalProviderTransportTest` uses a loopback HTTP server, the Android transport,
and a synthetic key. Its second response waits 31 seconds, exercising both old
cutoffs. It passed after the transport change; existing provider wire-format,
redaction and cancellation tests also passed. This reproduces a cause consistent
with the report, not a confirmed round trip with the owner's xAI account. No
physical phone is attached; the exact reported error/model remain unconfirmed.

## Lab revision 6: wake phrase and presence

The opt-in microphone foreground service recognizes “go go gadget” and a following
English question locally with Vosk Android 0.3.75 and the pinned small English
0.15 model. It produces a memory-only review draft, never automatically submits
a provider request, and never saves or uploads microphone audio. Start requires
a visible permission flow. Stop invalidates late callbacks; reads are nonblocking.
Sessions stop after one hour or draft completion, and never restart on boot.

Presence provides bounded, on-demand Bluetooth/Wi-Fi scans and enrolled-device
sightings, conservative advertised Wi-Fi security, anonymous partial radio counts,
and accuracy-aware saved-place boundary checks. Explicit nearby-address lookup
uses Nominatim with attribution, caching and request spacing. SSIDs are clues,
not verified addresses. Continuous geofencing and occupancy detection are not
implemented; device/room/movement interpretations remain observations.

Measured: 141 companion host tests pass, including generation cancellation,
phrase filtering, presence freshness/coverage and geofence boundaries. One real
Android instrumentation test loaded the packaged Vosk weights/JNI and recognized
synthetic positive speech while rejecting unrelated speech. Assembly and lint
passed. Eighteen package verifier tests pass; the verifier requires the private
microphone service and exact speech model hash.

Observed on the Android 16 ARM64 emulator: lab 6 upgraded the existing installation,
retained captures, displayed the new controls, requested microphone permission,
started the microphone foreground service, kept it active after Home, and removed
it when Stop was tapped. Screenshot: docs/signal-station/lab6/wake-listening.png.
The emulator ran without host audio, so this is not physical microphone evidence.
Physical recognition accuracy, false activations, locked-screen battery behavior,
real radio/location sightings and live provider accounts remain unverified.

## Lab revision 7 and watch 1.3.0

Implemented local comparisons over compatible capture/presence records, with exact
source references and freshness/identity guards. Presets preview sources before
Apply and reset conversation context when sources change. Manual field-trial
records retain optional results, stop references and build identity in encrypted
history. Legacy saved-place Wi-Fi source dependencies are repaired on load,
including dependent reports.

Watch review binds an immutable short phone wake draft to its current runtime
token, selected watch, trusted runner and settings. Select confirms once; Back,
disconnect, timeout, settings changes and phone dismissal invalidate consent.
Long text stays on the phone without truncation. The disclosed send begins a
question-only conversation. No live provider request was used for verification.

Measured: 165 companion host tests passed, including 24 new comparison, preset,
manual-trial, provenance migration and one-use consent tests. Eighteen APK verifier
tests pass. Android assembly/lint passed. Watch 1.3.0 passed 32 protocol tests,
production C request/button harnesses and all six native targets. Integrated
physical runner replacement/cancellation interleavings remain a device test gap.

Observed on the Android emulator: lab 7 upgraded in place and retained earlier
captures. Walking preview showed its exact source list while current source count
remained unchanged. An unrecorded field trial survived Ask/Capture navigation,
saved in History, and offered Resume after force-stop/relaunch. A new local capture
and What changed summary saved without a provider key; battery was unchanged and
denied location stayed unknown. No physical trial results were entered.

Physical watch confirmation/delivery, wake recognition accuracy, false activations,
radio usefulness, battery cost and live provider accounts still require testing.
The field-trial UI provides a way to record those results without fabricating them.
