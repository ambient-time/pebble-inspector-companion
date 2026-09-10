# Sensing implementation

By Luke Steuber. September 10, 2026.

The approved scope is in [the release plan](sensing-release-plan.md). This is the implementation record, not a public release receipt.

## Collection and evidence checkpoint

One acquisition now supplies ordinary readings and local presence. Location is shared with weather and saved-place checks. Presence receives typed radio candidates instead of parsing display text. Internal radio/location dependencies are filtered from saved observations unless their own sources are selected. Acquisitions are serialized; cancelled listeners are removed.

Sensor features carry metric, numeric value, unit, observed interval, sample count and accuracy. Scalar summaries include median and variability; motion includes magnitude. Angles and rotation vectors retain their latest components instead of using an invalid arithmetic average. Cumulative steps remain explicitly since reboot.

Captures and session samples use a fair source budget with per-source coverage. The 64-reading learning frame uses the same allocation. Disposable frames have a versioned rebuild and resumable cursor; existing encrypted originals, settings, keys and corrections retain their identities. Old ambiguous sensor text stays text.

Local comparisons accept observation sessions and Health Connect records, and compare overlapping source selections. Source/origin/watch, metric, unit and compatible duration still constrain comparison. Missing, cached and duplicate measurements remain unknown. Today and saved-record details expose comparisons, source outcomes and permission/radio recovery.

Health imports preserve the existing summary ID and add timestamped heart-rate samples and sleep stages. Resting heart rate and RMSSD have separate opt-in sources. A mapper-version checkpoint forces a bounded reread of unchanged provider records. Local deletion tombstones are still checked. Detailed series retain at most 512 entries spread over the interval, with explicit omissions and original timestamps.

## Verification

September 10, before the next context-feature checkpoint:

- 126 host tests passed, including new typed-feature, fair-budget, old-format, comparison and health-mapping fixtures.
- 22 Android instrumentation tests passed on `Field_Inspector_API_36_1`, Android 16 emulator. Existing encrypted migration, deletion/memory propagation, response rendering, reviewed-question and observation lifecycle checks passed.
- Standalone Android debug package built. No public files or store entries updated yet.

These checks use synthetic data and an emulator. They do not establish physical sensor accuracy, real Health Connect import, battery performance, an in-place upgrade from the distributed build, or watch safety.

## Remaining work

Adaptive sessions, structured wireless summaries, cellular context, separate reviewed nearby/address/radio-location lookups, cache lineage, richer local reports/exports, release upgrade checks, download publication and the existing Pebble hidden draft remain in progress. The recovered Pebble watch remains excluded from installation tests.
