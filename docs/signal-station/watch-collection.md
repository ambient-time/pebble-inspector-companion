# Watch collection integration

By Luke Steuber. September 13, 2026.

## Scope

This increment adds one optional source, `watch.minute_history`, for the preceding
15 completed minutes of watch movement, light and heart-rate records. It remains
off by default and is excluded from presets and bulk enable. The existing
`watch.motion` source gains movement variation, SDK-supplied timing and exclusion counts.

The canonical collection contract and firmware roadmap are in
[`pebble-field-inspector/docs/watch-collection.md`](https://github.com/ambient-time/pebble-field-inspector/blob/codex/signal-station/docs/watch-collection.md).
The watch baseline is `8872957`; this companion baseline is `003bd5a4`.

## Sequence and acceptance

1. Add the source to the existing source selector and supported watch keys.
2. Accept only bounded schema-1 minute history with correct UTC windows and
   explicit partial/invalid/unknown records. Preserve readable history and
   provider evidence without interpreting categorical light as lux, orientation
   as posture or heart rate as stress.
3. Retain the existing collection transaction, source revocation, cancellation,
   selected-watch binding and reviewed-provider-send behavior.
4. Verify C-generated JSON through the companion parser/validator, generic source
   selection and source presets. Run relevant host tests and an Android build.

The standalone app uses the existing AppMessage session. The separate native-host
DataLogging implementation has no Signal Station receiver and acknowledges data
before processing. Offline replay remains planned work, not a capability supplied
by this change.

## Evidence

- **Observed:** source baseline and installed SDK APIs inspected September 13.
- **Done:** source selection, strict schema-1 validation, readable minute records,
  motion summary and source-aware history lookup. Entirely invalid histories keep
  the returned window but no measurement time; zero returns have no measured window.
  The bundle stays out of fresh scalar trends. Older watches that omit its key
  produce the existing explicit unavailable-source outcome.
- **Measured:** 198 host tests passed, six existing tests skipped, zero failures or
  errors (204 discovered, including 15 additions). Standalone debug build passed.
  See the final build receipt
  below for counts and candidate identity. Parser tests consume JSON produced by
  the actual C collector, including null versus zero and partial coverage.
- **Trial:** the September 13 [collection trial](collection-trial-2026-09-13.md)
  saved all 15 requested history minutes from both Diorite QEMU and a physical
  Time 2 through the stock Pebble host on Pixel 9a. The SE could not complete its
  Bluetooth bond. The physical motion sample was unavailable with explicit
  vibration/timestamp exclusions.
- **Planned:** remaining physical acceptance and the firmware improvements in the roadmap.
- **Unavailable:** clean physical motion differentiation and measured battery effects.

The existing public artifacts and release descriptors remain separate from local
development evidence. See [Pebble integration](pebble-integration.md) for the
historical emulator results and outstanding device checks. Firmware quality/light
APIs, gyro support, Duo pressure and offline voice evaluation remain separate
roadmap items. The subsequent named-device trial installed Android development
code and addon 1.6.2 on the Time 2. No firmware was flashed.

## Initial 1.6.0 build receipt

September 13, 2026. Watch code: `62daf0a2699e26110f2af002b11916f6a48a7b07`.
At this checkpoint the separate collection-preview descriptor pinned addon 1.6.0
to that clean source. It now pins corrected 1.6.2; see the dated trial receipt for
current bytes. The existing 1.5.0 release descriptor is unchanged.

```sh
ANDROID_HOME=/Users/luke/Library/Android/sdk ./gradlew \
  :signal:testAndroidHostTest :signalApp:assembleDebug --no-daemon --no-parallel
python3 scripts/build-signal-addon-watch.py ../pebble-field-inspector \
  --descriptor signalApp/watch-collection-preview.json \
  --output signalApp/build/watch-collection-preview
```

The builder verified all six native targets, unchanged UUID, required Android
package `com.lukesteuber.signalstation`, and no embedded JavaScript. ZIP integrity,
metadata and checksums were checked again after packaging. The watch's checked-in
C fixture matches the Android parser fixture exactly as JSON.

- PBW: `signalApp/build/watch-collection-preview/signal-station-addon-1.6.0.pbw`,
  137,562 bytes; SHA-256 `8f0df646f82235a8acd8fc9adef4999f98a453f824a77d212261fdf0a514f5ff`.
- Source archive SHA-256:
  `e35161a3aa61aa5d1d503b6e462171e750e604ecdd86683ef7751801704e0f4a`.
  The same directory holds `provenance.json` and `SHA256SUMS.txt`.
- Android: `signalApp/build/outputs/apk/debug/signalApp-debug.apk`. This is a local
  debug build with the existing development version label/code (0.4.0-ux-dev/13),
  not a replacement for the distributed build 13. No version freeze or publication
  occurred here.

Review corrected four evidence/presentation issues: bounded JSON buffers now
avoid the watch callback stack; SDK timestamps are identified as reconstructed
batch timing; empty motion results do not display measured zero variance; and
minute histories are classified as recorded-period, partial or unavailable.
Provider guidance retains historical windows and explains the categorical fields.

Next: investigate the Time 2 motion vibration flags and SDK timestamps, then run
stationary/moving, cancel/reconnect and battery checks. Time 2 is the primary
collection/firmware target; SE remains a compatibility target. Heart-interval
quality and fresh-light interfaces remain proposed firmware work. Physical
TalkBack/large-text layout and runtime stack watermark also remain open.
