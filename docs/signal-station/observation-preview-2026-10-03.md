# Observation timestamp preview 1.8.1

By Luke Steuber. October 3, 2026. Private development package; not a public release.

## Frozen identity

This is a separate standalone watch preview from source
`12d06faecc73028c1a07eea299878ba10972ed81`, pinned by
`signalApp/watch-observation-preview.json`. The existing 1.8.0 descriptor and
artifacts are preserved.

| Artifact | SHA-256 |
| --- | --- |
| `signal-station-addon-1.8.1.pbw` | `e5a65e155ad66ae21ab8fce230bac1e9dfaebd81442eaf7733e80d2094ee1024` |
| `signal-station-addon-1.8.1-source.zip` | `4aab27dcff864418bcfa9f2bd7cbf7e010d709b6b54a8a50611c5e063b25927f` |

The app retains UUID `e2fd86ec-dfb8-460c-afc1-ebe4d071657a`, Android registration
`com.lukesteuber.signalstation`, and basalt, chalk, diorite, emery, flint and
gabbro targets. The PBW contains no embedded phone JavaScript. All six native
targets built successfully and the archive passed integrity checks. Existing
SDK linker RWX-segment warnings remain.

The frozen 1.8.0 PBW was rehashed and still matches
`6b5f8695a8c59edf189d6f7bd1d2eac8eaf00454c767053f48507b6f3942b0ca`.
The historical root-package PBW is not this standalone addon.

## Change and verification

Unavailable or denied scalar readings no longer claim a measurement timestamp.
Collection time and the attempted window remain available, while a real zero
remains data. A positive heart rate without known timing stays explicitly
undated. The production C formatter has 12 sanitizer-backed regression cases,
including a demonstrated failure before the correction.

The companion's new `SignalObservationCompatibilityTest` verifies that parsing
and validation preserve omitted measurement times, attempted windows, real zeroes
and explicitly undated heart-rate values. No Android production code or package
version changed for this preview; build 20's parser already supports omission.

A forced fresh test run completed with 303 Signal tests: 295 passed, eight
pre-existing skips, zero failures or errors. All 15 shared local-model tests
passed. The standalone release contracts passed two tests; reviewed-question and
Home watch-protocol suites passed 11 tests each. These are source/host checks,
not physical-device acceptance or a real downloaded-model inference result.

The exact PBW was then installed into a separate, logged-out Diorite emulator:

- The running app UUID and reviewed-question protocol negotiation matched.
- A null, unavailable heart-rate observation retained its collection time and
  attempted window but omitted `measuredAt`.
- A real battery observation retained `measuredAt` equal to its window end.
- An empty source selection completed with no observations.
- Both synthetic phone reports were acknowledged. Report, empty-selection,
  home and help screenshots were inspected; the app remained running after
  navigation. Longer report/help text uses the indicated scrolling controls.

The emulator used firmware v4.3, not the previously observed physical 2 SE's
v4.4.3-rbl. Its readings and phone replies were synthetic. No actual phone bridge,
provider, dictation, household action or wearer perception was exercised. The
isolated emulator processes were stopped after verification.

## Delivery boundary and next check

1.8.1 is built and emulator-validated, but not installed on a physical watch or
uploaded to the Store. Its installation hold remains. No Android reinstall,
pairing, firmware, provider setting, public binary or download page changed.
The earlier 1.8.0 Draft / 1.7.1 Published receipt is historical Store evidence;
the Store was not inspected again for this preview.

At the closing device check, Pixel 10 was connected and Pixel 9a was absent.
The Time 2 was reserved for Gadget Watch's pending wearer playback check and
was left alone. The recovered 2 SE's earlier pairing/reset incident remains a
separate boundary; do not replace its stock pairing owner to test this preview.

Next, obtain an uncontested session with the intended phone/watch pair, verify
its existing host and firmware, and conduct a bounded acceptance run using the
exact package above. Rendering, dictation, selected-source capture, question
review/cancel/send and reconnect on the physical 2 SE remain open. Store draft
staging and a public release remain separate from those checks.

## Subsequent receipts

The [2 SE installation record](pebble-2-se-install-2026-10-03.md) supersedes the
earlier no-physical-install checkpoint for this exact package: installation,
home rendering and phone/watch acknowledgement were verified on Pixel 9a and
the 2 SE. Full wearer acceptance remains open. Separate
[download and Store staging work](download-preview-2026-10-03.md) records later
distribution changes; this original packaging run did not perform them.
