# Sensing implementation

By Luke Steuber. September 10, 2026.

The approved scope is in [the release plan](sensing-release-plan.md). This is the implementation record, not a public release receipt.

## Collection and evidence checkpoint

One acquisition now supplies ordinary readings and local presence. Location is shared with weather and saved-place checks. Presence receives typed radio candidates instead of parsing display text. Internal radio/location dependencies are filtered from saved observations unless their own sources are selected. Acquisitions are serialized; cancelled listeners are removed.

Sensor features carry metric, numeric value, unit, observed interval, sample count and accuracy. Scalar summaries include median and variability; motion includes magnitude. Angles and rotation vectors retain their latest components instead of using an invalid arithmetic average. Cumulative steps remain explicitly since reboot. Separate deltas use two endpoints within one boot and reset after cancellation or source-selection changes.

Captures and session samples use a fair source budget with per-source coverage. The 64-reading learning frame uses the same allocation. Disposable frames have a versioned rebuild and resumable cursor; existing encrypted originals, settings, keys and corrections retain their identities. Old ambiguous sensor text stays text.

Local comparisons accept observation sessions and Health Connect records, and compare overlapping source selections. Source/origin/watch, metric, unit and compatible duration still constrain comparison. Missing, cached and duplicate measurements remain unknown. Today and saved-record details expose comparisons, source outcomes and permission/radio recovery.

Health imports preserve the existing summary ID and add timestamped heart-rate samples and sleep stages. Resting heart rate and RMSSD have separate opt-in sources. A mapper-version checkpoint forces a bounded reread of unchanged provider records. Local deletion tombstones are still checked. Detailed series retain at most 512 entries spread over the interval, with explicit omissions and original timestamps.

## Context and session checkpoint

Nearby now shows saved wireless and cellular context, phone fixes, and separate external estimates. Named OSM places and address candidates use explicit bounded Overpass queries. Radio location uses an explicit beaconDB-compatible query. Sources and external lookup consent are independent; each request has a destination/payload review and send-time freshness, scope and source-revision checks. No ambient audio/video collection was added.

Address candidates use Overpass rather than public Nominatim: this keeps the small manual query bounded without implying that every installed client can independently consume Nominatim's app-wide rate allowance. Both services are configurable HTTPS endpoints. Results retain attribution, uncertainty, timestamps, expiry and original-record references. Encrypted bounded caches preserve intermediate lookup lineage. Disabling a feature cancels pending work; deleting evidence removes dependent caches/results.

One per-session scheduler uses monotonic cooldowns and a bounded duration, including time spent granting permissions. Standard and Battery saver use selected motion/network events with a timed fallback. Active Wi-Fi and weather have separate cooldowns; Android-completed Wi-Fi scans may be reused. BLE can filter enrolled address targets, with bounded broad scanning for general observations or beacon identity matching. Source outcomes distinguish deferred/imported/external work.

Cellular reads distinguish subscription-specific callback data from a single device-wide cache with unknown SIM attribution. No subscriber IDs or phone numbers are collected. Radio comparisons join independently retained identifiers, never scan positions or names. Numeric trends exclude radio identities, stale/conflicting/overlapping samples and cumulative counters. Plots have exact-value tables and links to originals. CSV adds timestamps, units, quality and spreadsheet-safe text. JSON/Markdown/CSV sharing now checks current source/provenance eligibility.

Health detail includes optional device metadata and recording method. Session health import receipts link imported records without rewriting their original identity. Settings includes build identity and the stable download link. Collection diagnostics contain timing, source outcome counts and scheduler state; no observed values or identifiers.

## Verification

September 10, before release preparation:

- 164 host tests passed, including typed features, budget/migration compatibility, lookup request/response limits, consent, scheduler, radio comparison, CSV, health mapping and 11 numeric-trend fixtures. The final core rerun also passed the duration-clamping fixture.
- 26 Android instrumentation tests passed on `Field_Inspector_API_36_1`, Android 16 emulator. Real encrypted storage and station tests verified exact reviewed lookup transmission, cancellation on disable, original/intermediate cache deletion lineage, and reopening/rebuilding derived indexes while preserving originals and corrected wording. Network payloads and health readings in these tests are synthetic.
- Android lint passed. Standalone debug APK built.
- Independent runtime validator/critic reviews identified and drove fixes for SIM attribution, source-off step deltas, cache lineage, delayed permissions, session history search, hidden lookup errors and session health feedback. The bounded Claude and Grok CLI review attempts returned empty-content/service errors and supplied no usable opinions.

These checks do not establish physical sensor accuracy, real Health Connect import, battery performance, an in-place upgrade from the distributed build, or watch safety. No public files or store entries have been updated at this checkpoint.

## Remaining work

Final visual/upgrade/package checks, release descriptors, download publication and the existing Pebble hidden draft remain in progress. The recovered Pebble watch remains excluded from installation tests.
