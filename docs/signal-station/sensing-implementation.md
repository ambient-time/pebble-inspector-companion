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

These core checks did not establish physical sensor accuracy, real Health Connect import, battery performance, an in-place upgrade from the distributed build, or watch safety. The release checks below supersede the packaging and distribution status of that checkpoint.

## Delivered preview

Android **0.3.0-sensing-dev (7)** was frozen from `49fa5f7e86d61020501ba144d5087e07e440b145` and published on September 10. This documentation update is later than the frozen binary. The signing certificate matches public build 6. A two-install emulator fixture seeded the downloaded public build with settings, a synthetic key, original history, a saved question and a corrected memory, then verified those values after upgrading without uninstalling. The final distributed APK passed the verification phase again.

Two additional interaction tests cover nearby lookup review, numeric evidence tables and session records at normal and 200% text size, bringing Android instrumentation coverage to 28 tests. Settled screenshots were visually inspected. All 77 release-toolkit tests and two addon-builder contract tests passed.

The APK, guide, source archive and checksum files are published. Twenty-two public HTTP responses across `dr.eamer.dev` and `lukesteuber.com` matched the staged bytes, including both APK responses with the correct attachment and MIME headers. The minimal page was checked at desktop and 390-pixel widths. The Downloads index now includes a Signal Station source-archive card under For Developers; other products' data is preserved and lists remain alphabetical. Ambient Time already links to the same page in its collapsed beta-app section.

Install: <https://lukesteuber.com/downloads/apps/signal-station/>. APK SHA-256: `3e3e055b74103a39c7e93a38c3b71fd405985bd481f5e9cecb64217b9677964b`.

The messaging-only Pebble addon **1.5.0** is a saved **Draft** in the existing **Unlisted** app `37360ca4d9764881bd1d6f4d`. Its pinned watch source is `df0250600d237467e5b3525d40ad2cd4fcf825da`. All six declared targets build; UUID, companion package and absence of embedded PKJS were inspected. Fresh native menu images were verified for Basalt, Chalk, Diorite and Emery and added to the listing. Flint and Gabbro emulators stayed on a clock after installation/launch requests, so those attempts do not count as passing app render evidence. The pre-existing published 1.2.0 release inside the unlisted app was preserved. Public watch download 1.4.0 remains unchanged and installation-held.

The machine-readable Android receipt lives in the release toolkit at `docs/releases/signal-station-0.3.0-build-7.json`; the watch receipt is `store/publication-1.5.0.json` in the native watch repository. Both distinguish build, emulator, upload and physical evidence.

## Device follow-up

Real sensor accuracy, Health Connect account behavior, battery/long-session behavior and stock Pebble-host exchange require physical-device checks. The watch-settings reset is unresolved. The recovered owner watch was not installed or modified; its installation hold remains. Flint/Gabbro native render evidence and controlled watch acceptance are prerequisites to any public watch promotion.
