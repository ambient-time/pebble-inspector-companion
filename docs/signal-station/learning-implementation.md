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

- [ ] Additive encrypted storage migration, typed observations, pagination,
  memory/evidence/corrections and deletion propagation.
- [ ] Deterministic place/device, recurring-situation and numeric-baseline
  proposals, bounded review queue, correction and evidence inspection.
- [ ] Optional bounded observation service, source status and Health Connect.
- [ ] Today, Ask, Activity and Memory; visible chat context and accessible flows.
- [ ] Upgrade, privacy, lifecycle, large-history and emulator verification.
- [ ] Commit/push and publish matching Android preview, source and checksums.

Observed: build 3 source is 691d38d5; the existing store encrypts full record
payloads and loads all history; there is no memory engine or background sensor
session. Existing tests passed before this work (99 feature, five instrumented).
Planned: the checks and delivery above. Unavailable so far: physical Android
battery evidence and physical Pebble validation. Never label simulation as a
hardware result. Physical watch pairing/reset is outside this implementation.

Review provenance: Opus 4.8 and Grok 4.5 agreed on the product and architectural
spine; their memory-lifecycle and discoverability concerns informed the plan.
The Luna swarm completed one of four scouts and was excluded from consensus.
