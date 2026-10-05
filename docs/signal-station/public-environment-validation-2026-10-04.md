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

Two independent read-only source reviews found issues with timeout isolation,
location precision, hazard-end semantics, partial forecast coverage, unit/depth
validation, capture consent after place changes, rolling favorite labels, search
cancellation and tide identities. Each was checked against source and corrected.
Review opinions did not substitute for tests or device evidence.

Public example schemas were checked against Open-Meteo/CAMS, NWS, NOAA and USGS.
No device location, private conversation, real model key or model API was used in
these checks. NWS normalizes points to four decimal places; the queried point is
retained explicitly. European pollen outside coverage remains unsupported.

## Open

Physical phone upgrade, installed-byte verification and real watch favorite
acceptance are separate delivery checks. TalkBack remains unverified. The Dick
Tracy Watch data port is tracked independently; this Signal milestone does not
complete that work or authorize public release.
