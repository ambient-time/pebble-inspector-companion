# Reviewed wrist questions and Home consent

By Luke Steuber. October 3, 2026. Development preview; not published or installed on personal devices.

## Behavior

Every wrist question has a native-owned draft and a two-minute, one-use review. The review names the question, provider/model/endpoint, exact saved evidence, omissions and selected Home systems. Capture questions and follow-ups use the selected record and its bounded lineage; they never inherit the phone composer's attachments, memories, conversation or Home selection. The phone handoff has its own screen and leaves the unsent phone question intact.

Home defaults to None for each question. Read access and action permission are separate on both surfaces. Exact standing grants remain available only during an explicitly action-enabled model turn. Direct phone and watch controls always require action confirmation, even when a matching grant exists. Replacing a system, withdrawing permission, ending a turn or losing the originating watch session revokes its remaining authority. An explicit phone handoff can finish on the phone without keeping the watch connected.

Question and action confirmations cannot recreate expired, cancelled or consumed authority. Home review is non-actuating. The watch and bridge renegotiate after runtime replacement. Immediate and asynchronous transport failures share a four-attempt budget, then pause. Long watch reviews require reaching the end; phone confirmation follows all parameters in reading/scroll order and remains reachable at 200% text size.

Learned numeric baselines now reuse the scalar trend comparability rules: source/device/method/boot identity, compatible intervals, quality, deduplication and overlap checks. Angular readings are excluded. Old baselines stay visible but cannot enter provider context until valid recomputation and review; accepted wording and rejected/forgotten decisions are retained.

Home history no longer grows inside one executable encrypted row. At most 128 recent outcomes and 64 live/pinned intents remain in the active projection; older outcomes move atomically into individual encrypted audit documents. The ledger has a 384 KiB budget, the active state 768 KiB, and each intent 48 KiB. Temporary live saturation refuses new work without deleting evidence. Archived intents cannot be confirmed or dispatched. A separate, explicit Home activity export includes the complete retained audit, including targets and parameters, but not saved connection/provider credentials. General history exports are unchanged.

## Reuse

Gadget Watch source `0ffef1e` informed the envelope-specific retry budget, paused transport and production-function sanitizer tests. The adaptation is attributed in `signal_transport.h`. Its monotonic request high-water scheme, artwork, audio and provider defaults were not imported. Gadget Watch's concurrent working files were not changed.

## Evidence

Measured checks are distinct from inferred usability and physical acceptance.

| Area | Evidence |
| --- | --- |
| Kotlin logic | 297 tests: 289 passed, zero failures, eight opt-in live-service tests skipped. Covers frozen consent, Home authorization/replay, learning and scalar comparability. |
| Native Android | Final integrated run: 53 passed and one opt-in TalkBack test skipped. Two subsequent opt-in TalkBack checks passed, including that skipped test. Real station/encrypted-store tests use synthetic evidence and mock HTTP; no external provider or controller receives a request. |
| Encrypted storage | Actual Room/Android encryption tests stored 10,001 terminal intents, retained all 9,873 archived outcomes plus 128 recent entries, exported all 10,001, refused archived execution and rolled back interrupted transactions. A full 4,096-entry legacy replay journal and a legacy Home row exceeding 2 MiB migrated without lost evidence or CursorWindow-sized reads. |
| Phone experience | Compose interaction tests exercise normal and 200% text, keyboard, exact-context handoff, long action parameters and expiry recovery. Screenshot inspection is separate from screen-reader interaction. |
| Accessibility | Installed TalkBack was bound for focus/activation of main destinations and for the 200% Home grid/action review. Home actions used a synthetic UI callback. This is not a comprehensive speech, perception or accessibility-conformance audit. |
| Watch protocol | 34 historical JavaScript regressions, 11 standalone Home tests and 11 standalone question tests passed. Production C parser/button/transport tests run with address/undefined-behavior sanitizers. |
| Retry regression | 1,000 immediate outbox failures stop after exactly four attempts; asynchronous failures share the same budget. Cancellation retention and stale callbacks are checked. |
| Watch rendering | Synthetic packets on Chalk and Diorite exercise long/short review and draft states. Round long-review and rectangular first-paint controls were inspected. |
| Standalone artifact | Addon 1.8.0 built from clean watch commit `428c09fd42531765cf37009a553ae683370c3e59`, with six binaries, unchanged UUID/companion package and no embedded JavaScript. |

The preview descriptor is `signalApp/watch-consent-preview.json`. Earlier release/collection descriptors and published packages are unchanged. PBW SHA-256: `6b5f8695a8c59edf189d6f7bd1d2eac8eaf00454c767053f48507b6f3942b0ca`.

Android implementation: `c1f95056e5491639060055102afcc023b87564dc`, including baseline changes from `f9cf46ff`. The final debug APK embeds `0.5.0-home-dev (17) · c1f95056 · development preview`; its APK signature verifies as Android Debug. APK SHA-256: `c7d7866e77c097c81ffe1f7c9347cab3451e2640d2ee38c40727ebf212cbf133`. Its bundled bridge matches the checked-in protocol byte-for-byte, SHA-256 `d32a0ca72fcd9b27f52dda001d5458ca0ca7ac023ae2c88692d60aa37af5e86a`. The integrated emulator suite used identical implementation code before the final source-identity stamp; the rebuilt artifact was inspected separately.

Local logs, checksums, source archive and screenshots are retained at `/Volumes/Galactus/signal-consent-verification.xmFRSt`. Android's old generated build directories were moved, not deleted, to `/Volumes/Galactus/signal-consent-build-cache.tFSfjq` after stale pre-migration absolute paths caused a dex transform failure. Rebuilding the generated outputs resolved it.

## Limits and next acceptance

The planned automated implementation checks are complete. Earlier failed runs are retained in the logs: a missing JUnit Unit return, a wake fixture in the wrong phase, an initial-history readiness race and large-text lazy-list/layout issues. The final run includes the corrections; failed attempts are not counted as passes. Provider-consent storage-failure injection was not performed; durable write ordering was inspected, and ambiguous provider failure/restart and Home transaction rollback were tested separately.

Unavailable in this pass: physical watch dictation/perception/battery acceptance, real provider responses and real actuator outcomes. No store upload, deployment, release signing or personal-device installation was performed. A controller receipt or emulator screenshot is not physical acceptance.

Next: separately authorize and run paired phone/watch acceptance on the intended hardware, then decide whether to prepare a release. Existing Home Assistant/openHAB identity limitations and the combined collection/historical-use setting remain documented design constraints.
