# Public environment validation

Private development build 22, version 0.9.0-public-data-dev. No public download,
Store submission or new physical-watch acceptance is claimed by this record.

## Measured

- Shared Android host tests: 374 passed, eight opt-in skips, zero failures out of
  382 discovered. Coverage includes typed provider, response bounds,
  timeout isolation, cancellation, unit validation, partial coverage, NWS point
  precision and alert-end/expiry distinctions, Home binding, read-only enforcement,
  original capture evidence, tide identity and watch UTF-8 bounds.
- Shared iOS simulator code compiled; this is not an iOS product-runtime check.
- Android debug and instrumentation APKs assembled. Lint: zero errors, eight
  warnings. No additional permission or always-on service was introduced.
- Isolated emulator batch: 23 tests passed; one opt-in TalkBack test skipped.
  This includes four real station/coordinator/encrypted-store integration tests
  and setup, consent, retry, favorite, unit-conversion and existing Home UI tests.
- Integration checks used synthetic public HTTP and foreground state. They verify
  zero requests before agreement/in the background, cancellation, no credentials
  in public requests, capture consent cleared by location change, and a trusted
  watch request returning source/age/basis without action or model access.
- The first watch fixture was rejected with HTTP 403 because it had not selected
  the test watch. Selecting it fixed the fixture; trust checks were not weakened.

## Observed

Native screens were inspected at normal and double-size text. The first large
text disclosure screenshot revealed a keyboard obscuring controls. Moving focus
and keyboard handling into the dialog fixed it; the revised disclosure and
Preview/Cancel controls were visible. Card timestamps are compact by default,
with exact times retained in expanded details.

The final fixture was tightened again after discovering that a Compose-only
density override did not enlarge Android's separate dialog window. The test now
uses an Android configuration context so both page and dialog scale. All four
Outdoor UI tests passed again, and the genuinely doubled dialog, consent switch,
Preview and Cancel controls were inspected. This test-only correction does not
change the installed build 22 application bytes.

Two independent read-only source reviews found issues with timeout isolation,
location precision, hazard-end semantics, partial forecast coverage, unit/depth
validation, capture consent after place changes, rolling favorite labels, search
cancellation and tide identities. Each was checked against source and corrected.
Review opinions did not substitute for tests or device evidence.

Public example schemas were checked against Open-Meteo/CAMS, NWS, NOAA and USGS.
No device location, private conversation, real model key or model API was used in
these checks. NWS normalizes points to four decimal places; the queried point is
retained explicitly. European pollen outside coverage remains unsupported.

## Private phone delivery

Build 22 was rebuilt from committed source `395d7b13` and upgraded in place on
Pixel 9a and Pixel 10. Both report 0.9.0-public-data-dev (22), launch successfully,
and their installed APK SHA-256 matches the local artifact:
`3c6bfdcb3db0bdddec453c83a413c9b1745c12c2c124538e5362a8b8ab7272b1`.
The signing certificate matched both previous installations. Pairing, stored
settings, enabled sources and public downloads were not changed. No synthetic
fixtures were installed on physical phones.

The existing watch test suite also passed: 34 JavaScript protocol cases plus
native C observation, bounds, Home/question UI, replay and transport checks.
There is no watch binary change for this feature.

## Remaining acceptance

Real watch favorite acceptance remains separate from synthetic transport tests
and phone installation. TalkBack remains unverified. Dick Tracy Watch's portable
data module and Outside brief are now implemented on its main branch and privately
installed as build 12 on both Pixels. Its own validation record reports 209 host
tests, five synthetic render checks and unchanged watch builds; it does not imply
Signal/Dick Tracy physical acceptance or authorize public release.
