# Environmental preview — delivery intake and evidence

By Luke Steuber. October 4, 2026.

## Intake

Signal Station is the standalone native Android application in `signalApp`, with
shared Kotlin/Compose features in `signal`. Original contributions are MIT;
inherited GPLv3/commercial and third-party terms remain as described in
`LICENSING.md`. The MQTT client is ktor-mqtt 1.2.0, Apache-2.0, by Ulrich Kemp.

The authorized target is an Android development preview, preserving the existing
package, signing identity and app data. The connected Pixel 9a is the physical
installation target. The Pixel 10 and other projects' emulator remain untouched.
No new Pebble binary is needed; existing Home favorites carry formatted readings.
No public download, Store staging or submission is authorized by this slice.

Accessibility checks cover labeled controls, merged reading semantics, explicit
non-drag reorder buttons, reachable form fields and large-text layouts. Automated
semantics checks do not establish a complete physical TalkBack user journey.

Builds use Gradle/JDK 17 and explicit-serial Android SDK commands. A separate
read-only Android API 36.1 emulator was used for synthetic fixtures; these tests
did not use personal brokers or actuators. No new permissions or services were
added to the Android manifest.

## Tracks

| Target | Current evidence | Remaining gate |
| --- | --- | --- |
| Android preview | Device-tested: emulator flows; Pixel 9a install and startup | Real household sensor/broker acceptance and long-session battery behavior |
| Shared iOS source | Validated: simulator-arm64 shared source compiles | No validated Signal iOS app shell or runtime |
| Pebble Home favorites | Existing protocol unchanged; 11 protocol tests pass | Physical environmental reading acceptance |
| Public distribution | Unchanged | Separate explicit authorization |

## Verification record

The full Android host suite discovered 342 tests: **334 passed, eight skipped,
zero failures**. This includes eight actual loopback WebSocket/MQTT broker cases:
retained delivery, no-message waiting, denied authentication, denied subscription,
silent handshake timeout, parent cancellation during handshake, foreground
cancellation and oversized packet rejection. No application PUBLISH was sent.
Mapper/framer tests additionally cover exact topics, scalar/nested JSON,
malformed payloads, units, physical ranges, sample timestamps and unknown-age
retained messages. Compatibility tests preserve existing serialized Home data
and HTTP action-connection bindings.

Android APK/test-APK builds and `lintDebug` pass: **zero errors, eight warnings**.
Shared `compileKotlinIosSimulatorArm64` passes. This check also found and fixed an
existing JVM-only sorting call in the shared Home confirmation screen, without
changing parameter ordering.

The combined Home UI/integration/encrypted-storage suite passed 24 cases; its
opt-in TalkBack case was initially skipped, then enabled and passed separately
with TalkBack verified bound throughout. Six environment UI cases pass, including
unit selection without source-data mutation, reading-only favorites, stale and
unknown-age states, MQTT setup and failed-preview draft preservation/retry.
Those six were rerun successfully against the final build-21 APK. Three journeys
also passed with Android system font scale 2.0; actual dialog screenshots were
inspected, not just the Compose density override. This is not a complete physical
screen-reader usability study.

Review fixes include physical-range validation, retryable connector-owned
timeouts, genuine parent-cancellation propagation, retained setup drafts, bounded
WebSocket queues and transport cleanup. Initial broker tests exposed an Android
WebSocket engine that could not enforce the requested frame size. The dedicated
transport uses CIO on Android and Darwin on iOS with default certificate
validation, 64 KiB messages/packets, bounded 16-frame queues, 16 KiB reading
payloads and at most 20 explicitly selected topics.

## Private artifact and physical installation

- Version: **0.8.0-environment-dev (21)**.
- Package: `com.lukesteuber.signalstation`.
- Embedded implementation source: `e192c383efbd1676e0bbc970bce654f42a82c567`.
- APK SHA-256: `c4ad1210d24158467cf65744745e119e86d517302c3acb1aa09f056b7ad37b8d`.
- Signing certificate SHA-256: `e1faa235dbdd132ac86856639b60b12ad9f9b658fc921fd8160db84ed8dfa28d`.
- Debug-signed private preview; not a store-release artifact.

Pixel 9a / Android 17 accepted an in-place replacement. Installed version and APK
hash match the artifact; the original first-install date is unchanged. Launch
returned success and the expected activity was resumed. No uninstall, data clear,
new source enablement, broker configuration, watch installation or pairing change
was performed. This proves installation/startup, not physical sensor accuracy or
real-broker interoperability.

The Pixel 10, existing other-project emulator, public download pages and Store
listings were not changed. The disposable verification emulator was stopped.

## Open boundaries

MQTT requires a **version-5 WebSocket listener**. MQTT 3 and raw MQTT TCP/TLS ports,
discovery, scripts, wildcard subscriptions and device-command publishing are not
implemented. Real TLS/authenticated broker interoperability and physical watch
environmental readings remain acceptance work. Android ambient hardware is
optional; neither weather nor battery temperature is presented as a replacement.
No previously missing phone ambient sensor is created by this update.
