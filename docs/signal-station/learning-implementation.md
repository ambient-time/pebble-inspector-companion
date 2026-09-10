# Signal Station personal context release

By Luke Steuber. Implementation of the accepted September 9 plan.

The product observes selected sources, compares dated evidence, proposes memories,
lets the user confirm or correct them, and supplies visible context for explicit
provider requests. Android is primary; the Pebble app owns pairing and firmware.

Decisions: optional 15-minute, one-hour or four-hour observation sessions;
confirmation before inferred knowledge becomes established; retain history until
deleted; preview deletion dependencies and optionally keep selected confirmed
statements as personal notes; suggest up to five eligible memories before Send.
Health Connect and the existing Pebble adapter are included. ESP32 is deferred.
No ambient audio/video or autonomous provider calls. Existing wake listening is
separate. All collection sources remain individually optional.

Implementation checkpoints:

- [x] Additive encrypted storage migration, typed observations, pagination,
  memory/evidence/corrections and deletion propagation.
- [x] Deterministic place/device, recurring-situation and numeric-baseline
  proposals, bounded review queue, correction and evidence inspection.
- [x] Optional bounded observation service, source status and Health Connect.
- [x] Today, Ask, Activity and Memory; visible chat context and accessible flows.
- [x] Upgrade, privacy, lifecycle, large-history and emulator verification.
- [x] Commit/push and publish matching Android preview, source and checksums.

Implemented in Android preview 0.2.0-learning-dev (4). Validation: 109 feature
checks and 16 Android instrumentation checks pass, including encrypted v1
migration, transitive deletion, 100,000-row indexed paging, real foreground
service capture/Stop, correction persistence, explicit retained notes and 200%
font navigation. Storage retains encrypted history; learning uses compact
hourly frames instead of loading all observations on startup. The source-based
provider privacy checks remain active for resumed threads and attached reports.

Physical Android battery/thermal behavior, real Health Connect account access,
stock-host exchange and physical Pebble validation remain unverified. No watch
pairing, reset or firmware change was performed. JSON exports stream the full
history and include memories/sessions. The build uses the established preview
package and debug signing identity. Publication is tracked by the final item.
See [the guide](personal-context.md) and [verification](learning-evidence/verification.json).

Review provenance: Opus 4.8 and Grok 4.5 agreed on the product and architectural
spine; their memory-lifecycle and discoverability concerns informed the plan.
The Luna swarm completed one of four scouts and was excluded from consensus.

Publication completed September 9: Android build 4 from `23a9cade31a43b4abb29bb9ac6124416b63705d5`
is available at [Signal Station downloads](https://dr.eamer.dev/downloads/apps/signal-station/).
The APK keeps the existing preview certificate. Its SHA-256 is
`6ac5a43775d96c869dcddd7fd79aa6b3ef113fe0c43cc7b4a1828f5161ddb151`.
All 12 published artifacts and metadata files returned HTTP 200 with exact
staged hashes, including the source ZIP, guide and checksums. Other catalog
entries and older APK/checksum history were preserved. The 75 publisher tests
passed. The unchanged watch package remains 1.4.0; no new watch validation is claimed.


September 9 connection correction, Android 0.2.1-learning-dev (5): first-time
Pebble host selection attempted to close an unused PebbleKit 2 1.1.0 sender.
That SDK unconditionally unbinds its Android service, which throws "Service not
registered" before the selected host can be saved. The old dialog mislabeled
this as an unavailable Pebble app. A regression test using the actual SDK
sender reproduced the failure. Cleanup now accepts an already-unbound sender;
selection is serialized and can finish while protected storage is opening.
The chooser prevents overlapping taps, clears stale errors when reopened and
handles disconnect errors through the same path. Pairing remains with Pebble.
All 18 Android instrumentation tests pass after this correction; the unchanged
109 shared feature checks remain green. The watch package remains unchanged.
The screenshot identifying 0.1.1-separation-dev is an older preview, but this
connection bug also existed in build 4. The regression establishes the software
failure and recovery; exchange with a physical watch still needs validation.
