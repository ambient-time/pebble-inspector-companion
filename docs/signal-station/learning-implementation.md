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
- [ ] Commit/push and publish matching Android preview, source and checksums.

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
