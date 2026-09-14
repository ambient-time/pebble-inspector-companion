# Signal Station implementation sweep and horizon

By Luke Steuber. September 14, 2026 UTC (September 13 in California).

This is a review and next-step recommendation, not a new implementation or a claim of all-day validation. The current testing release is Android **0.4.0-ux-dev (14)** with Pebble addon **1.6.4**, available on the [download page](https://dr.eamer.dev/downloads/apps/signal-station/). Both source repositories were rechecked as private during this sweep.

## Recommendation

Make the next milestone **a dependable session that explains what it actually measured**. Prove screen-off timing and interruption behavior, correct reused-measurement comparisons, and preserve useful metrics under budget pressure. Wider collection and firmware work follow a demonstrated missing measurement. The phone/watch architecture remains a useful foundation.

## Baseline and scope

| Surface | Reviewed baseline | Evidence boundary |
| --- | --- | --- |
| Android feature and standalone shell | Runtime source `411663e718e3ea1f863d7314d53d09ad487f6bea`; documentation through `5055d40c` | Collection, lifecycle, storage, context, local analysis, radio views, watch transport and tests inspected locally |
| Pebble | `e65d4c2c29ce8bbfcd31e3dadd2f95a9e6086446`, addon 1.6.4 | C collection/math, minute history, button/backlight and protocol reviewed; no new hardware test during this sweep |
| Release toolkit | `29a712818caa9613cb33cd77da3ea8da9573a275` | Paired release receipt, source filtering, descriptors and staging reviewed |
| Download site | `cadd229` | Paired publication complete; prior verification matched 28 HTTP responses across two domains |

The review covers Signal Station's feature modules and integration/release boundaries. It is not a line-by-line audit of every inherited upstream mobile-app subsystem, a penetration test, or fresh validation on every supported Android/Pebble model.

Existing evidence: 214 host tests passed with six existing skips; 25 selected Android instrumentation tests and Android lint passed; 78 release-toolkit tests passed. Six Pebble targets build, with three emulator layouts inspected. The [physical receipt](collection-trial-2026-09-13.md) establishes a first capture, one automatic capture 313 seconds later, and their saved local comparison. Scheduled model analysis was off. This sweep adds the focused runtime probes below, not another full test-suite run or another physical session.

## What the sweep found

### 1. Reused measurements can count as unchanged — confirmed, medium priority

`SignalChanges.create` rejects an older/equal measurement timestamp only when the value or status also changes (`SignalChanges.kt:35`). Equal timestamp and equal value therefore increment `unchanged`, as does a newly measured equal value. The closing report text says old readings are unknown, so the implementation does not fully support its wording.

A JVM probe against the built feature classes compared two records saved ten seconds apart. Reusing measurement time 100000 and advancing it to 110000 both returned:

```text
0 changed; 1 unchanged; 0 unknown or not comparable.
```

This is a comparison-contract issue; it is not evidence that physical build 14 fabricated a reading. Give reused evidence its own outcome, or require a newer independent measurement before calling it unchanged. `SignalTrend.series` already deduplicates measurement timestamps and excludes overlapping windows; reuse that distinction rather than introducing a third interpretation.

### 2. Fair source budgets do not guarantee useful metrics — confirmed policy limitation, medium priority

`SignalBudget.retain` round-robins by source key, which prevents a dense radio from simply evicting every other source (`SignalAcquisition.kt:26`). Within a source it preserves input order. `SignalSensorFeatures.observations` emits axis statistics before magnitude statistics (`SignalAcquisition.kt:82`). Thus broad selection can preserve an early axis mean while consistently losing motion variability.

A synthetic JVM probe used 80 source groups, each with the 17 features produced by the actual accelerometer feature function. The actual 200-reading / 80 KiB retention path returned:

```text
input=1360, retained=200, omitted=1160, magnitude.stddev retained=0
```

This synthetic load is not a measurement of the Pixel's exact lost metrics. The physical run separately established budget overflow. The useful next policy is a small primary measurement set per selected source, followed by optional detail, with reported loss at both persistence and model-excerpt budgets. Preserve per-source fairness. Do not merely raise every limit or acquire new sources automatically.

### 3. Ongoing is not yet proven through screen-off and interruption — observed evidence gap

`SignalObservationService.kt:59–69` uses a bounded capture/request phase followed by coroutine delay or selected transition listeners. Fixed intervals are waits after work completes, not exact wall-clock appointments. The observation service has no wake mechanism for its fixed wait; the separate wake-phrase service's wake lock does not cover recording sessions. A visible foreground service alone does not establish that the CPU stays awake. Android documents [device suspension while a foreground service runs](https://developer.android.com/develop/background-work/background-tasks/awake) and [Doze restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby).

The app already warns that Android may delay readings, so delay itself is not a newly confirmed defect. The missing result is measured screen-off timing and explicit accounting for skipped attempts, pause periods, and unavailable watch windows. The existing plugged-in one-interval result cannot establish this. Choose between best-effort intervals and a more demanding timing contract after measuring cost; do not add a permanent wake lock by default.

Activation under a contended persistence lock, rapid Stop/start, and terminal-state ordering remain targeted test hypotheses. Network requests hold the persistence lock while in flight; cancellation tests with the actual transport remain relevant even though mock cancellation passed. No stale-session resurrection was reproduced in this sweep.

### 4. Watch motion needs a minimum usable window — confirmed quality gap, medium priority

The physical motion capture retained zero valid samples. Separately, the actual C math accepts one valid sample with zero elapsed span and variance zero. `main.c:223–224` labels motion fresh whenever count is nonzero. The result preserves counts/timestamps, but freshness is not sufficient evidence of a useful variance estimate.

The C probe returned `accepted=1 span_ms=0 variance=0`. Define a minimum sample count and observed duration, then test stationary/moving captures on the physical Time 2. Do not interpret a single sample's mathematical zero variance as established stillness. Keep raw counts and exclusions visible.

### 5. Recording feedback and release instructions can drift — confirmed, lower priority

The scheduled watch branch enters `startOperation`, setting the global status to `Starting…` (`AndroidSignalStation.kt:741`), but saves its successful result only into session status (`:1039–1041`). The physical UI showed the stale global wording even after two saved captures. Derive displayed recording progress from the session and its last completed phase.

The default addon builder still reads `signalApp/watch-release.json` (1.5.0), while the published collection descriptor is `watch-collection-preview.json` (1.6.4). Both still say `installationHold: true`; the builder requires that historical draft flag. The actual published receipt and bytes correctly identify 1.6.4. This is a future wrong-default/reproducibility risk, not a mismatch in the files just published. Reconcile the default descriptor and separate distribution status from validation evidence before the next release. The standalone Android host does not expose its unsupported install capability, so its legacy exception text is not a currently visible installation button failure.

## Implementation coverage

| Area | Assessment and remaining limit |
| --- | --- |
| Source consent and acquisition | Required dependencies feed one bounded acquisition; visible rows are filtered to selected keys. Scheduled online place/radio lookups are excluded. Permission failures have explicit outcomes. New source permission is not implied by a session. |
| Local storage | AES-GCM payloads use per-identity authenticated data; thread indexes are keyed; app backup is disabled. The 200-record UI page and 200-reading capture budget are not disk retention limits. Indefinite recording therefore needs understandable storage growth and user-controlled retention/export choices. |
| History and provider sends | Scoped projection strips excluded prose/references. Revision checks, ancestor eligibility and source gates are rechecked before send/export. Scheduled analysis is explicit opt-in, two-capture scoped, cadence-limited and separately cancellable. Physical paid-provider evidence is still absent. |
| Deletion and export | Apply computes dependency closure within an atomic store operation, rebuilds disposable learning frames and removes export caches. Startup also clears exports. Previously shared external copies naturally remain outside local deletion. |
| Learning and recurrence | Candidate memories remain proposals until reviewed. Learning uses limited hourly frames; local trends deduplicate measurements. Adaptive sampling and missing coverage still make event-frequency claims a separate problem. |
| Radio UX | Disclosures and view-only filters are implemented. Type hints cite advertised services/beacons; network login state is separate from Wi-Fi advertisements. Written counts accompany tint. Named-device counts describe loaded history; live counts reset with session expiry. Neither is a lifetime frequency measure. |
| Watch integration | Selected-watch/session identity, bounded messages, native validation, no embedded PKJS in the addon, and stock-host cooperation are present. Collection still depends on the watch app being open. Motion, dictation, reconnect and longer-run battery behavior need their own physical evidence. |
| Release | Private repos, matching development certificate, paired immutable artifacts, corresponding-source filtering and HTTP receipts are established. Production signing and Pebble Store state remain separate from this testing-download release. |

## Consensus on the next Signal Station milestone

**Voices:** my full local review; native Codex CLI 0.153.4 (OpenAI transport; this invocation's JSON events did not expose an exact model, so none is inferred); native Grok CLI 1.0.25 (session label `grok-4.6`, usage label `grok-4.6-build`). These are advisory opinions, not independent hardware results.

**Unavailable:** neither requested CLI remained unavailable. Grok's first invocation failed locally on a relative prompt path, before a provider call. After correcting the path, its automatic prompt-file chunking consumed the two-turn ceiling; the same session was resumed once to finish, without changing provider or questions. Native Grok reported about $0.0943 total for the two inference runs; Codex did not report a dollar cost.

Both voices received the same five questions and a 103,987-byte, 14-file excerpt brief. The supplied brief passed secret scanning and contained no device readings. The CLI outputs stayed outside Git. The reviewers did not receive the entire repository; omitted-context objections were checked against the full local implementation.

| # | Question | My review | Codex CLI | Grok CLI | Result |
| --- | --- | --- | --- | --- | --- |
| 1 | Scheduler and Stop ready for the next test milestone? | Foundation sound; screen-off/contended lifecycle evidence needed | Changes needed, medium confidence | Sound for more local testing, medium confidence | Split on readiness wording; agree that longer-run proof is missing |
| 2 | Selected scope and deletion preserved? | Sound in the reviewed send/delete paths | Insufficient excerpt evidence, high confidence | Sound, medium confidence | Full-code verification resolves several omissions; not a unanimous security certification |
| 3 | Honest change/recurrence claims? | Correct reused-measurement result; prioritize retained metrics | Changes needed, high confidence | Sound for sampled pairwise change; recurrence unproven, medium confidence | Partial disagreement; JVM probe confirms Codex's timestamp objection |
| 4 | Phone/watch split a sound base? | Yes; prove useful physical motion and app-open coverage | Sound, medium confidence | Sound, medium confidence | Agreement |
| 5 | Highest-payoff next milestone? | Session reliability plus useful local evidence | Reliability, then interpretation, then more sources | Session reliability, then retention/interpretation | Agreement on order; avoid a reliability milestone with no useful user result |

### Objections checked against the full code

- **Codex: exports survive restart because delayed cleanup dies.** Startup clears `signal-export` (`AndroidSignalStation.kt:125`), and deletion also clears it (`:1567`). The proposed persistence-after-relaunch defect is ruled out. Abrupt termination can still leave a cache file until the next launch; this is not a promise of secure erasure at an exact wall-clock deadline.
- **Grok: process death leaves a running session after reopening.** Startup marks saved running/paused sessions interrupted (`:137`). No automatic resume is intended. A fresh physical kill/reopen test remains useful, but the alleged missing recovery code exists.
- **Grok: cadence is demonstrably consumed by a concurrent busy change.** `analyzeObservation` checks busy before changing the counter, with no suspension before `startOperation`; service and station orchestration run on `Dispatchers.Main.immediate`. No interleaving establishing this defect was found. Keep it out of the confirmed findings.
- **Grok: deleting only a session document can orphan its samples.** Store edges alone do not link a session document to every sample, but no exposed delete-session-only operation was found. The actual full-history delete enumerates records, and apply cascades their descendants. This is a future API invariant, not a reproduced user-facing deletion defect.
- **Grok: unscoped local search exposes disabled-source history.** Local viewing deliberately retains original history; disabling a source gates subsequent sends, not erasure or local access. The send/export paths revalidate source eligibility. This is not evidence of an external disclosure.
- **Grok: Stop/onDestroy status race is demonstrable.** No such outcome was reproduced. Both use the guarded terminal-state method and the current main-thread execution order matters. Keep rapid Stop/start and final reason checks in the lifecycle test, without claiming a confirmed race.
- **Both: radio floods evict all other source families.** The full retention implementation already round-robins sources. The narrower, confirmed limitation is priority *within* each source, demonstrated above.

## Ranked horizon

1. **Blind spot — Ongoing recording needs a measured coverage contract [Observed].** One elapsed physical interval, process-local scheduling, watch-app-open dependence and budget omissions leave a gap between “recording is on” and “these sources were observed.” Show scheduled attempts, actual captures, reusable measurements, misses and last successful source times together. This changes what the user can trust before any expansion.
2. **Opportunity — Make the default result a useful local field report [Inferred].** Existing comparisons, trends, provenance and encrypted history are most of the foundation. The probes show precisely where interpretation loses value: reused timestamps and missing primary metrics. A short account of meaningful changes and unavailable evidence could answer the everyday question before a provider is involved.
3. **Opportunity — Turn existing watch minute history into a dated measurement timeline [Inferred].** Physical delivery of 15 completed minutes works, and `SignalWatchHistory` already validates/parses them. Current normalization does not turn the JSON-valued minute record into numeric trend/learning rows; `SignalChanges` consequently cannot compare its component measurements locally. Project those readings with their original minute timestamps, deduplicate overlapping windows and retain provenance. That can extract more value from today's SDK without a firmware change or more source permissions.

**Next experiment:** a single controlled one-hour field protocol using the existing selected sources, fixed five-minute waits and local analysis only. Include screen-off/unplugged time, a marked stationary-to-moving transition, one watch-app-closed interval, notification Stop, and a short restart/kill/reopen segment on an isolated test instance. Reconcile actual capture times, per-source retained primary metrics, reused measurements, omissions and final session state against the known events. Keep all raw evidence local. This experiment is proposed, not started by the review.

**Handoff:** `/craft:discuss --plan` to select the session-coverage/interpretation milestone and its acceptance thresholds before implementation.

## Reproduction artifacts

Raw CLI events, the bounded brief and its manifest, JVM/C probe source and output, runtime classpath and provider provenance are stored outside Git under `/Volumes/Galactus/drummer/signal-station/project-sweep-20260914/`. The brief SHA-256 is `cb4379ff9926201625fea7ba184553c15f4b9d0847a7344493e6e5963216bb1c`. No personal readings were added to this report. This sweep changes documentation only; it does not change either published binary or start a model-analysis session.
