# Signal Station: collection, context, analysis and release plan

By Luke Steuber. September 10, 2026.

Status: planning complete after code inspection, prior-art research and independent planning/release reviews. All implementation, device testing and publication below are planned, not completed by this document.

September 13 update: the bounded [watch collection increment](watch-collection.md)
implements optional 15-minute Health history and improved motion variation/timing,
with companion ingestion and host validation. It also links the firmware roadmap
for heart-interval quality, fresh light, gyro, Duo pressure and offline delivery.
Its evidence is separate from the September 10 baseline and the wider release
checklist below; physical watch acceptance and firmware changes remain open.

## Outcome and scope

Make Signal Station useful as an Android field notebook: capture reliable observations, understand the surrounding place and radio environment, compare changes locally, and ask a chosen language model about reviewed evidence. Keep the phone useful on its own. The watch remains a collection trigger and quick-feedback surface through the user's existing Pebble app.

The complete planned release includes **nearby places, wireless environment, cellular context and geolocation enrichment**. These are required deliverables, not optional research items. They remain independently enabled and available without enabling unrelated sources. External lookups and sending context to a chat provider are separate choices.

Retain Today, Ask, Activity and Memory; individual source controls; local encrypted history; reviewed sends; corrected memories; saved questions; optional wake phrase; existing watch buttons and phone-only onboarding. No ambient audio/video collection. Wake listening retains its existing separate control. No mandatory ESP32, Google account, hosted Signal Station account or new Pebble pairing.

Ship Android independently when its gates pass. Prepare the existing Pebble listing as a hidden draft in parallel. Public watch availability requires the separate watch-safety and interoperability gate. The historical erased-settings report remains unresolved; separating the Android app is not proof of physical-watch safety.

## Current evidence and ownership

Observed September 10:

- Android source is `/Users/luke/workspace/pebble-inspector-companion`, clean at `4a2c5121912b50607ead0fcdd7179b8baacba665`. `signalApp/release.properties` declares `0.2.2-learning-dev`, build 6, package `com.lukesteuber.signalstation`. Android minimum API 26; compile SDK 37.
- Both public app pages return HTTP 200 with identical HTML SHA-256 `3ec30fa51e1883270d9b3fe7164d7fba4bef106c4a49a1f2c084c4cd006c29ed`. They show build 6, the new icon/OG assets and the minimal page design.
- `SignalCollectors` and `SignalPresenceCollector` can independently acquire the same radios/location for one capture. Hardware summaries are text; `SignalLearning.normalize` only infers a number from a wholly numeric string. Session storage applies `readings.take(200)` and learning frames apply `.take(64)`.
- Existing `SignalChanges` already implements local comparisons, but currently excludes observation sessions/health imports and most structured sensor/radio values. Extend it rather than create a competing report subsystem.
- Existing `SignalObservationService` has finite sessions, Stop, no automatic restart, and battery/storage/thermal guards. Existing Health Connect import preserves origins, intervals and updates/deletions, but flattens sleep/heart-rate detail.
- Watch development source is `/Users/luke/workspace/pebble-field-inspector`, clean at `d3eeabb`, package 1.3.0. Companion `scripts/build-signal-addon-watch.py` produces a separate no-PKJS 1.4.0 addon for the independent Android package.
- The saved store receipt records app ID `37360ca4d9764881bd1d6f4d`, Unlisted / Draft 1.2.0, checked September 9. The public URL returned an application-not-found body during this review; a later fetch was denied. Neither is dashboard confirmation. Re-read the authenticated dashboard during release preparation.
- The actual Ambient page is `https://ambient-time.com/watch/pebble/`. It already contains Signal Station in a collapsed beta section. Its current source is the clean `/Users/luke/workspace/ambient-time-pebble-apps-faces` worktree, branch `codex/pebble-beta-preview`, commit `c2f4e625`. The primary Ambient checkout has unrelated dirty watch work and older page-generation code. Do not deploy from that stale generator.

Historical, not rerun in this planning task: build 6 documentation records 112 host tests, 22 Android instrumentation tests and 77 publishing tests. These are a baseline inventory, not validation of future changes or physical devices.

| Responsibility | Canonical location |
| --- | --- |
| Features, Android shell, encrypted storage, provider flow | `pebble-inspector-companion/signal/`, `signalApp/` |
| Native watch code, protocol, incident evidence, store metadata | `pebble-field-inspector/` |
| Android release descriptors, artifact registry, staging and audits | `android-release-toolkit/` |
| Curated app page and icon/OG source | `android-release-toolkit/assets/signal-station/` |
| Website mirror, main index, developer cards | `downloads-site/` |
| Ambient apps/faces/beta discovery | Reconcile `ambient-time-pebble-apps-faces` into canonical Ambient source first |
| New Drummer staging, backups and verification output | Mounted `/Volumes/Galactus/drummer/`; stop dependent writes if unavailable |

## Implementation sequence

### 0. Freeze contracts and establish the baseline

Deliverables:

- Record source revisions, current public APK/PBW hashes and signing identity. Inspect all affected worktrees before edits; isolate unrelated changes. Commit a reviewed checkpoint before major behavioral work.
- Define source capability and permission matrix by Android version. Keep existing persisted source keys; new metrics are not new implicit permission grants.
- Define separate state for collection selection, external-enrichment selection and provider-send selection. Document when an internal dependency needs identifiers for local matching without retaining or sharing those identifiers generally.
- Record the current acquisition count, duration, retained/dropped observations and local report behavior using deterministic fixtures. Measure real battery behavior later on named hardware.
- Pin a small set of regression fixtures: dense radio environment, stationary/moving phone, missing permissions/hardware, old cached scan, clock change, health overlap, interrupted upgrade and malformed external records.

Acceptance: baseline is reproducible; package, source keys, record identities, encryption identifiers and watch UUID remain stable; no planned test requires the recovered owner watch to be reinstalled.

### 1. One acquisition, structured evidence, fair storage

Introduce a small acquisition coordinator and typed internal results. One capture/session tick owns a location request and at most one necessary Wi-Fi/BLE acquisition. Presence, weather and ordinary records derive from that snapshot. Avoid a generalized plugin framework.

Additive data contract:

- Snapshot/session ID, source key, metric identifier, numeric/boolean/vector value and unit.
- Collection time, measurement time, monotonic timing/reboot scope where relevant, window start/end and timezone/origin.
- Sample count, sensor accuracy, location uncertainty, retained/omitted count and reason.
- Explicit state: observed, cached, permission denied, disabled, hardware unavailable, throttled, timeout, empty observation or interrupted. Empty scans never establish absence.
- Version for the derived feature/parser; parent record/observation IDs for every derived fact.

Keep prose as presentation, not as the communication format between collectors. Preserve original radio capability flags and parsed fields separately. Use stable local entity identities where permitted; do not use scan ordinals or advertised names as durable identities.

Apply bounded budgets per family plus a total cap. Always retain an outcome for every enabled source. Fairly allocate detailed records and learning-frame slots; count omissions before summaries are generated. Detailed health/sample series may use bounded encrypted child records with deletion references. Do not retain unrestricted raw streams.

For hardware, start with meaningful metrics: acceleration magnitude/variability, light/pressure min/median/max, step-counter deltas within one reboot, and orientation-specific handling. Do not average every Android SensorEvent axis indiscriminately. Select sensor capabilities explicitly rather than assuming every phone has the same hardware.

Primary files: `SignalCollectors.kt`, `SignalPresenceCollector.kt`, `AndroidSignalStation.kt`, `SignalModels.kt`, `SignalCapture.kt`, `SignalLearningRepository.kt`.

Acceptance: tests establish one acquisition per source, shared timing, truthful omissions and preserved source outcomes with hundreds of radio results. Disabling a source or cancelling a capture prevents late persistence. Radio identifiers required for a selected local presence check do not enter general storage or model context unless separately selected.

### 2. Safe migration and a useful local comparison engine

- Decode new fields with defaults. Preserve existing encrypted originals, IDs, settings, keys, saved questions, confirmed wording and evidence links.
- Parse only unambiguous legacy numeric values. Leave ambiguous legacy strings visible and mark them unsuitable for numeric comparison.
- Version and rebuild disposable derived indexes with a resumable cursor. Never rewrite historical observations to make a new classifier look accurate. Confirmed memories retain their wording; changed evidence can request review.
- Extend `SignalChanges` to captures, sessions and health. Compare the compatible intersection when source selections differ; show the coverage difference separately. Match source/origin/device, metric, unit and duration/period. Equivalent windows do not need identical timestamps. Repeated/cached samples are not independent evidence.
- Provide local differences, median/range, simple trend and coverage. Start with current deterministic methods; no vector database or new model runtime is needed.
- Add session-level coverage: attempts, accepted readings, gaps, omissions and last successful sample. Show unsuitable comparisons instead of inventing a zero.
- Generate compact charts with an equivalent text/table view. Reports retain links to original evidence and use existing deletion propagation.

Acceptance: a build-6 upgrade preserves representative data and corrections; interrupted index work resumes; source deletion invalidates reports and already-prepared sends; repeated imports/scans do not inflate baselines; comparisons work with no key and no network.

### 3. Adaptive, finite collection

Extend the existing service and retain its 15/60/240-minute sessions, Stop notification, elapsed-time deadline and nonsticky lifecycle. Do not use WorkManager as a continuous sensor loop.

- Reuse Android-completed Wi-Fi scans during an active session. Request a scan only when due and allowed; track attempted and successful collection independently.
- Add a small per-source scheduler with freshness reuse, cooldowns, deadlines and explicit skipped reasons.
- Use available motion/step/network changes to adjust acquisition. Stationary periods use fewer expensive requests; plausible transitions trigger bounded follow-up sampling. Provide a low-rate fallback without motion hardware or permission.
- Keep Google Play Services optional. If activity transitions or fused location are used, isolate them behind an adapter with a native fallback. Do not raise minimum Android support silently.
- Distinguish brief broad BLE surveying from filtered observation of enrolled devices. Both stay within the user's chosen session.
- Recheck permissions and current source selections before each acquisition and before committing results. Stop/deadline/revocation closes listeners, callbacks, jobs and pending external work. Do not require permanent background-location permission for a bounded foreground session; request additional permissions only for a chosen feature that actually requires them.
- Expose Standard and Battery saver presets with editable source selections. Keep sampling internals out of the ordinary flow.

Acceptance: deterministic tests cover motion, cooldown, revocation, screen-off interruption, clock changes, stop/restart and stale callbacks. Instrumentation verifies service cleanup and no provider request. Physical stationary and moving sessions measure actual coverage, battery and thermal behavior against the existing build; do not advertise an unmeasured battery claim.

### 4. Nearby places: required

- Match saved, confirmed places locally first. Account for the phone's location uncertainty and boundary ambiguity.
- Extend the existing explicit reverse lookup into a provider interface with address lookup and nearby candidate search as separate operations. Use OSM/Overpass for bounded nearby candidates; reverse geocoding is not a business-identification oracle.
- Return name, category, location, distance/uncertainty, source attribution, retrieved time and stable source ID. Present candidates in a list; a map can remain a secondary view.
- Permit user confirmation/correction. A matching SSID can contribute evidence but cannot confirm a business. Multiple venues within the accuracy radius remain ambiguous.
- Cache by approximate area, query/radius and provider in bounded encrypted storage, rather than only process memory. Keep retrieval date, expiry and stale state. Track private query/evidence lineage so deletion also clears its dependent enrichment/cache entries. Deduplicate in-flight requests, honor response limits/timeouts/429, and cancel abandoned work.
- External place lookup is separately enabled and shows which location will be sent. No default periodic reverse geocoding. Manual place selection works without phone location permission.

The public Nominatim endpoint has an application-wide 1 request/second ceiling, requires identification/attribution/caching, disallows autocomplete and requires a switchable service. A per-phone delay is not an application-wide quota. Preserve it only for a compliant explicit lookup; before broader/default use, choose a suitable provider or a bounded Drummer proxy with aggregate rate limiting and no raw-location request logging. Do not make shared public service availability a requirement for local capture.

Acceptance: dense-building, poor-accuracy, offline, expired-cache and no-result fixtures give truthful candidates/unknown; confirmation survives reopening; no lookup occurs while the external switch is off; lookup results cannot overwrite confirmed places silently.

### 5. Wireless environment: required

- Structure Wi-Fi band/frequency/channel/channel width where exposed, RSSI, security flags and measurement age. Display 2.4/5/6 GHz only when supported by the device.
- Show advertised network protection (including OWE) separately from the connected network’s validated internet and captive portal status. Do not infer usable internet from an unconnected AP's advertisement.
- Add scan/session changes, observed channel crowding, repeat sightings and first/last observed times. AP count is not channel utilization or person count.
- Extend existing BLE beacon parsing with timestamped advertisement summaries, service/company metadata and sample distributions. Unknown values remain unknown. Register datasets require version and license provenance before bundling.
- Preserve rotating-address ambiguity. Local identifiers can be scoped/pseudonymized, but do not claim hashing makes location histories anonymous. Retention and sharing remain explicit.
- Treat all SSIDs/device names/vendor strings as untrusted data in display, exports and LLM context. They cannot introduce instructions or automatic external requests.

Acceptance: channel/security parser fixtures cover modern/unknown flags, OWE and malformed data; duplicates do not become extra devices; new/absent observations show coverage; enrollment matching remains stable; dense radio results cannot starve health/location/device outcomes.

### 6. Cellular context and geolocation enrichment: required

Cellular collection:

- Add serving/registered and neighboring cell observations where Android exposes them: radio technology, operator/network codes, cell identity, signal metrics, timestamp and subscription context.
- Separate subscriptions without retaining phone numbers, IMSI or SIM serials. Handle no SIM, multi-SIM, approximate-location grants, permission denial, unsupported radio types and stale vendor results.
- Preserve unknown sentinels; never convert unavailable signal values into real measurements. Cell changes describe observed transitions, not guaranteed handovers between missed samples.
- Reuse the shared location fix and quality fields. Satellite/fix-quality metadata can improve interpretation; raw GNSS research processing is outside this release.

Geolocation enrichment:

- Implement a replaceable radio-geolocation provider, initially beaconDB after current endpoint/policy checks. This is a real release feature even though each user must enable external lookup separately.
- Show the identifier/location categories leaving the phone, distinct from LLM context. A request to geolocate is not permission to contribute scans to a public database. No crowdsourcing upload path is enabled by this plan.
- Prefer local confirmed places/cache and a usable phone fix. External estimates remain separate records with provider, returned accuracy, retrieval time, expiry and method where known.
- Never silently replace a precise phone fix with an approximate cell/IP estimate. If the provider does not identify its fallback method, say the method is unspecified. Results outside usable accuracy remain insufficient for venue identification.
- Persist local observations before an optional enrichment request. Failure preserves that evidence and reports unavailable/rate-limited/provider-error. No unbounded retries, hidden provider fallback or automatic collection expansion. A late result cannot attach to a deleted capture or stopped/replaced session.

Acceptance: mocked Wi-Fi/cell/IP/no-result responses retain uncertainty; live service smoke tests use synthetic/public fixtures or explicitly selected data; disabled lookup sends nothing; denied cellular access does not break Wi-Fi/phone capture; repeated requests reuse the cache correctly.

### 7. Richer health, persistent learning and questions

- Retain existing Health Connect permissions, origin separation, change tokens and deletion handling.
- Add a health-mapper version checkpoint and bounded, resumable reread of the selected, currently permitted window. Existing change tokens do not revisit unchanged records, so they cannot populate newly supported detail by themselves. Keep local deletion tombstones keyed to provider/origin/record through remapping and background import; restoring intentionally deleted copies requires an explicit reimport choice.
- Store supplied sleep stages and timestamped heart-rate samples in bounded detail records; derive stage durations, sample count/range and comparable interval summaries. Session duration is not automatically time asleep.
- Add resting heart rate and HRV when the originating app supplies them and the user selects those types. Preserve the actual metric definition; do not derive HRV from averaged heart-rate values.
- Handle midnight/timezone changes, overlapping origins, incomplete stages and historical permission limits. Keep each origin visible and avoid double counting.
- Extend deterministic memory proposals with comparable baseline changes and confirmed-place associations. Keep minimum coverage and independent-session requirements. Account for sampling gaps and time-of-day differences before calling a pattern changed.
- User edits/confirmations remain authoritative; model-generated text cannot automatically create accepted memories. A newer analysis version can propose a revision without silently replacing a correction.
- Maintain deletion lineage across originals, detailed child records, local features, memories, reports, lookup caches and reviewed messages. Concurrent imports/remapping cannot resurrect deleted evidence.
- Use the existing ranked history and exact context review for local findings. Add templates such as “What changed here?”, “Compare these sessions” and “Which readings support this pattern?” Opening a template neither collects nor sends.
- Provider work: exercise the actual chat path, not just endpoint tests, for every advertised provider. Cover unsupported model, authentication, quota, timeout, cancellation, oversized context and malformed responses. Retain draft and recoverable error. Live accounts are tested only where configured and authorized; mock tests are labeled as such.

Acceptance: repeated imports are idempotent; source updates/deletions remove dependent detail/aggregates/reports; no cross-origin sums; no medical/causal conclusion is inferred from a correlation; reviewed context equals the sent message after all source/revision checks.

### 8. Adjacent improvements that belong in this release

| Improvement | Scope and acceptance |
| --- | --- |
| Source status and recovery | Show enabled/permission/hardware/last-reading states with one direct remedy; declining one permission leaves other tasks usable. |
| Simple first success | Today -> Capture -> inspect evidence works without a key/watch. Put Compare beside the latest usable capture/session; retain existing navigation. |
| Presets | Editable selections with additions visible before applying; never auto-enable new fields during upgrade. |
| Session feedback | Explain what was actually observed, missing or omitted; distinguish saved locally, sent for analysis and delivered to watch. |
| Accessibility | TalkBack labels/focus/order, 200% font, small screens, high contrast, non-color status, reduced motion and chart tables. Do not infer accessibility from Compose usage alone. |
| Diagnostics | Add collector duration/outcome, capability, scheduler state and build/source identity; exclude keys, prompts, health values, coordinates, SSIDs and radio IDs. |
| Exports | Keep existing JSON/Markdown; add CSV for numeric series with units/timestamps/provenance and safe spreadsheet escaping. Exports obey source selection/deletion and existing expiry. |
| Build discovery | Keep app version/build in Settings/diagnostics and a simple link to the stable download page. No new update banner or automatic install flow. |
| Metadata cleanup | Fix catalog icon, truthful watch-dictation wording, public source links and decimal MB versus MiB mismatch. |
| Release automation | Replace hardcoded build-6 release copy and hardcoded addon 1.4.0 with descriptors. Reject dirty watch source and changed bytes under an existing versioned filename. |

Larger adjacent work remains separate: ESP32/Home Assistant read-only import, CSI, always-on collection, cloud history sync, raw GNSS research, automatic entity graphs, vector databases, cross-package legacy migration and Play Store/F-Droid submissions. Do not remove existing export paths or tell users to uninstall to overcome signing incompatibility.

## Pebble workstream and gate

Keep Up = capture, Select = ask/dictate where supported, Down = recent history, Back = cancel/return. Improve in-app/button help, readable bounded history and distinct acknowledgement states. The phone owns full records and analysis. Capability negotiation determines what is displayed; custom BYOK watch transcription is not exposed by the public client API and must not be advertised as available through this adapter.

1. Consolidate the addon release descriptor across package version, filename, transformation, source revision and companion registration. Preserve UUID `e2fd86ec-dfb8-460c-afc1-ebe4d071657a`; allocate the next watch version at release freeze. Preserve historical 1.3.0/1.4.0 artifacts.
2. Audit the standalone APK/dependency graph and adapter for no pairing, firmware, reset, watch database or locker-management ownership. Preserve existing trusted-host/session-generation and stale-message protections.
3. Complete isolated reset-investigation work using existing incident evidence and seeded emulator/transport fakes. The corrected recursive collector is evidence for a capture crash, not a diagnosis of the settings wipe. Do not reproduce by reinstalling on the recovered owner watch.
4. Exercise real stock-host exchange, app already open at phone startup, disconnect/reconnect, host switch, malformed/duplicate/delayed messages, sparse/no-source capture, full history and cancellation. Pin tested phone/watch protocol versions and stock-host versions in the release receipt. Watch dictation is a separate measured capability.
5. Build all six currently declared targets; inspect actual UUID/version/companion metadata, no embedded PKJS, packet keys, memory/stack behavior, text wrapping and round/rectangular layouts. Restrict public compatibility if a target cannot meet acceptance rather than invent coverage.
6. Conduct controlled physical acceptance only when the incident disposition and test-device choice permit. Record exact phone/host/watch/firmware and pre/post state. Capture, return text, history, cancellation and reconnect must work without changing pairing or watch settings. Preserve the hold until this gate is deliberately closed.
7. Refresh the **existing** dashboard app as Unlisted / Draft with the new package and actual native screenshots/icons. Remove lab-4 installation instructions. State that Signal Station Android is required for this addon and the existing Pebble app manages the watch. Use plain store text and a working download-page companion link.
8. Reopen and verify saved draft/visibility/media state. The historical dashboard incident shows “Submit App” could publish immediately; do not create a replacement listing or assume save means draft.
9. After the safety gate and the owner's publication decision, publish the intended release, inspect the public listing/download and record a dated receipt. Only then add a public store button and promote its Ambient entry into Watch apps. Retain beta history without duplicating the product unnecessarily.

Android completion is not blocked by steps 3–9. “Draft prepared,” “emulator passed,” “stock-host exchange passed,” “physical device passed” and “publicly published” are distinct statuses.

## Android and website publication

At release freeze, choose the next version/build above all existing distributed builds; do not reserve or reuse a build number merely from this plan. Preserve the established preview certificate and package. A future production-signing transition needs an explicit upgrade/data path; do not silently replace the certificate.

| Surface | Required update and verification |
| --- | --- |
| Android package | Build from committed source; inspect package/version/min SDK/source identity; compare signing certificate with public build 6; test in-place upgrade with encrypted data and keys. |
| Public files | Immutable APK, source archive, build instructions, user guide, release descriptor, upstream notices and SHA256SUMS. Verify local signatures/hashes and remote served bytes. |
| Release registry | Update toolkit `scripts/live_artifacts.json` and Signal Station entries in catalog/manifests only; derive copy from the release descriptor. |
| App page | Preserve minimal header, icon, one-line description, Android download, version/requirements, short install note, guide/checksums and collapsed Pebble/source sections. Keep favicon/OG/Twitter assets. No marketing sections or update banner. |
| Download index | Keep alphabetical ordering, identical public-domain content, accurate size units and exactly one current Signal Station product entry. Preserve other curated HTML and developer tab. |
| Developer resources | `downloads-site/index.html#panel-install`, `repo-cards.json`, `scripts/update_repo_cards.py`. Use a public repo only if genuinely public; otherwise use revisioned downloadable source/build instructions. Do not expose private repository links as usable public sources or change visibility automatically. |
| Ambient Pebble page | Reconcile the existing apps/faces/beta worktree into canonical source before generation. Preserve max six columns, truthful native captures/frames, alphabetical groups and initially collapsed beta apps. Update Signal Station's existing beta link now; promote only after actual store publication. |
| Pebble Store | Existing app ID, accurate optional-watch capabilities, native media, companion download URL, preserved draft state until the separate gate passes. |

Deployment order:

1. Confirm mounted Galactus staging/backups and inspect current Git/deployment ownership.
2. Snapshot live catalog/page/manifest pointers without altering unrelated files.
3. Use `prepare_signal_station.py` and `stage_signal_station.py` against committed source and a fresh live snapshot. Preserve curated layouts and historical checksum entries.
4. Upload immutable files first; verify bytes and modes; then publish HTML/catalog/manifests. No broad `--delete`, force-generated HTML or Caddy change is needed for existing routes.
5. Audit both `https://lukesteuber.com/downloads/` and `https://dr.eamer.dev/downloads/`: indexes, app page, manifests, guide/source, checksums, APK and icon/social assets. Verify semantic version/link correctness in addition to HTTP 200 and hashes.
6. Browser-check mobile layout, keyboard access, collapsed sections and link targets. Confirm social-card dimensions/metadata; cached third-party previews may refresh independently.
7. Commit/push reviewed source and registry/site changes with explicit paths. Verify canonical remote refs. Reconcile Ambient's existing branch before any deploy; preserve unrelated ongoing watch changes.
8. Record immutable artifact receipts separately from later mutable-page verification. Roll back bad public pointers to the prior verified release if needed; preserve history. APK downgrades are not a data-recovery strategy.

The final delivery receipt must list public page/APK/Pebble URLs where available, exact versions/hashes, signing continuity, physical-device evidence and remaining limitations. A private draft URL is not a public installation link.

## Acceptance matrix

| Area | Required evidence |
| --- | --- |
| Collection | Single acquisition; fair budgets; omissions; stale/cached/empty distinctions; source off during capture; dense and malformed radio fixtures. |
| Data | Build-6 upgrade; old format readability; resumable backfill; correction retention; encrypted-store integrity; delete while capture/import/report/send is pending. |
| Analysis | Compatible metrics/windows/origins; numeric sensor baselines; independent sample counting; timezone changes; coverage visible; offline operation. |
| Context | Four required areas exercised; each control independent; local results available offline; external requests bounded; attribution/accuracy/cache/failure states; no contribution upload. |
| Health | Detailed stages/samples, missing data, overlap, idempotency, updates/deletions, grant/history limits and actual named-device import. |
| Android lifecycle | API 26/28 fallback, 31 Bluetooth permissions, 33 notifications, 34+ foreground-service requirements and current target behavior; denied/revoked permissions and absent services. Test selected representative versions, not every API integer. |
| Physical Android | Named devices; stationary/moving and screen-off sessions; battery saver/Doze; offline/poor fix; at least one additional OEM where available. Report untested combinations. |
| Provider | Existing mocks plus bounded real chat smoke tests where accounts are configured; exact preview/send parity; safe cancellation and retained drafts. |
| UX/accessibility | First capture without watch/key; direct source recovery; TalkBack, 200% font, narrow screen, reduced motion and chart alternatives. |
| Watch | Isolated incident investigation, stock-host exchange, native target evidence, then controlled physical acceptance; no destructive watch operations. |
| Publication | Signature/version continuity; served-file parity; correct links/size/source; real listing state; no regression of Ambient gallery or downloads developer tab. |

## Scope, dependencies and checkpoints

Build in this order: baseline -> structured collection/migration -> local comparison and scheduler -> four context capabilities -> health/learning/UX integration -> Android release. Health mapping and radio/place provider work can proceed independently after the record contract is frozen; one owner integrates orchestration/storage changes. Watch protocol/listing preparation and website-source reconciliation can proceed separately throughout.

Use three reviewable checkpoints, not an all-or-nothing rewrite:

1. Collection integrity, additive migration, meaningful local comparison and existing-behavior regression coverage.
2. All four requested context capabilities, adaptive sessions, richer health, learning and usable source recovery.
3. Physical acceptance, final packaging, downloads deployment and Pebble draft/publication work according to its gate.

An intermediate Android preview can be published once its own acceptance passes, but that does not mark the full plan complete before checkpoint 2 and the promised distribution work. ESP32/CSI does not delay the required phone release.

## Prior art and primary guidance

Reviewed September 10. Reuse selected patterns or narrow licensed components; do not embed a whole research app.

- [Android Network Survey](https://github.com/christianrowlands/android-network-survey), Apache-2.0, Android API 26+: typed radio/GNSS records, measurement age, exports; release 1.58 September 2.
- [NeoStumbler](https://github.com/mjaakko/NeoStumbler), MIT, Android 10+: passive/motion-aware collection, public-service boundaries; release 2.4.1 September 7. Adapt patterns for our older API fallback.
- [phyphox](https://github.com/phyphox/phyphox-android), GPL-3.0: sensor-specific features and inspectable experiments; release 1.2.1 September 4.
- [CARP Mobile Sensing](https://github.com/carp-dk/carp.sensing-flutter), MIT: modular measurements/context/health architecture; active September 10. Flutter is not introduced into the Kotlin application.
- [WiGLE Android](https://github.com/wiglenet/wigle-wifi-wardriving), BSD-3-Clause: geolocated survey records and export patterns. Database/API rights are distinct from application-source licensing.
- [Android Wi-Fi scans](https://developer.android.com/develop/connectivity/wifi/wifi-scan), [BLE background work](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [TelephonyManager](https://developer.android.com/reference/android/telephony/TelephonyManager), [Health Connect sleep](https://developer.android.com/health-and-fitness/health-connect/experiences/sleep).
- [OSM Overpass](https://wiki.openstreetmap.org/wiki/Overpass_API), [Nominatim policy](https://operations.osmfoundation.org/policies/nominatim/), [beaconDB](https://beacondb.net/). beaconDB describes itself as experimental; confirm current service conditions before setting a default.
- Later: [Bermuda](https://github.com/agittins/bermuda), MIT; [ESPresense](https://github.com/ESPresense/ESPresense), AGPL-3.0; [Espressif CSI](https://github.com/espressif/esp-csi). External receiver support remains a separate optional extension.

## Evidence envelope and handoff

- **Done:** comprehensive scope, source ownership, implementation order, release paths and acceptance criteria recorded. Two independent read-only planning/release passes and a third critic pass informed the plan. The companion reconsideration report records accepted findings and one deliberately deferred navigation suggestion.
- **Evidence:** Observed current code/release descriptors/site content; Measured identical live page hashes; Inferred engineering risks are marked by their source conditions; all future behavior and validation are Planned.
- **Open:** live authenticated Pebble draft state; original wipe cause/disposition; named-device battery/health/stock-host behavior; final geocoder deployment choice; any unavailable provider accounts. These do not invalidate the plan or imply tests passed.
- **Next:** implement checkpoint 1 with additive source contracts and migration fixtures, while reconciling release metadata/site-source ownership and preparing the existing Pebble draft package off-device.

This is a plan artifact only. No application code, binary, store listing, public site, device state or repository visibility was changed in this planning task.
