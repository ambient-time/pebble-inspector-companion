# Watch collection integration

By Luke Steuber. September 13, 2026.

## Scope

This increment adds one optional source, `watch.minute_history`, for the preceding
15 completed minutes of watch movement, light and heart-rate records. It remains
off by default and is not part of a preset. The existing `watch.motion` source
gains movement variation, actual sample timing and exclusion counts.

The canonical collection contract and firmware roadmap are in
[`pebble-field-inspector/docs/watch-collection.md`](https://github.com/lukeslp/pebble-field-inspector/blob/main/docs/watch-collection.md).
The watch baseline is `8872957`; this companion baseline is `003bd5a4`.

## Sequence and acceptance

1. Add the source to the existing source selector and supported watch keys.
2. Accept only bounded schema-1 minute history with correct UTC windows and
   explicit partial/invalid/unknown records. Preserve readable history and
   provider evidence without interpreting categorical light as lux, orientation
   as posture or pulse intervals as stress.
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
- **Planned:** implementation, host tests, native builds and a local addon candidate.
- **Unavailable:** physical watch/phone acceptance and measured battery effects.

The existing public artifacts and release descriptors remain separate from local
development evidence. See [Pebble integration](pebble-integration.md) for the
historical emulator results and outstanding device checks. Firmware quality/light
APIs, gyro support, Duo pressure and offline voice evaluation remain separate
roadmap items; no firmware or physical-device change is made by this increment.
