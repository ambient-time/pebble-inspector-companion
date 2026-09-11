# UX preview acceptance

By Luke Steuber. September 11, 2026.

Target: Android preview 0.4.0-ux-dev (11). This checklist defines the release
checks. Measured results belong in the release receipt; a listed check is not
itself evidence that it passed.

| Journey | Required result |
| --- | --- |
| First use | Skip setup without starting collection. Review a preset before applying it; only explicit source choices persist. |
| Capture | Capture now saves evidence with source status and timing. Inspect it, attach it, and return to the originating task. |
| Signals | Start explicitly, inspect stable rows, keep an opened sample visible after expiry, and stop on leaving Signals or backgrounding. Returning does not restart collection. |
| Permissions | A denied or unavailable source has a relevant remedy. Granting permission returns to the task without a surprise scan or send. |
| Record over time | Show an active recording and Stop first. Preserve duration, mode, source subset, notification and completed/interrupted outcomes. |
| Ask | Keep the composer reachable with the keyboard and large text. Retain the draft, attachments and review when visiting provider setup. Sending still requires review. |
| History | Filter the full encrypted store. Browse beyond the first page; Ask, compare, export and delete use the same frozen selection. |
| Restricted evidence | Exact source keys constrain readings. Exclude broad prose, prior conversation and memories from scoped questions. Revalidate revisions and source permission before sending or sharing. |
| Navigation | Back returns to the task and preserves in-memory drafts, filters and scroll positions across rotation. Do not put sensitive task state in Android saved-state bundles. |
| Accessibility | Exercise 200% text, narrow width, keyboard, and real TalkBack focus/activation on an emulator. Keep controls reachable and avoid announcing every radio tick. |
| Upgrade | Install over published build 10 with the same package and signing certificate. Retain settings, encrypted history, saved questions and corrected memories. |
| Distribution | Match the APK's embedded source revision, verify immutable artifact hashes, and compare public bytes across both domains. |

## Capability preservation

The navigation pass must retain these entry points and controls:

- Collection presets, individual sources and groups, permission remedies, local
  change summaries, capture inspection and the advanced 20-minute field trial.
- Live source/subfield choices, session-only labels, freshness and trends,
  snapshot saving and explicit reviewed questions.
- Nearby-place/address and radio-location requests with destination/data review,
  external estimate uncertainty, saved boundaries and familiar-device management.
- Provider/model/custom endpoint configuration, keys and diagnostics, separate
  transcription credentials, threads, attachment removal, saved-question editing,
  citations, retries and proposed-memory review.
- Learning switches, confirmed/corrected/rejected memories, personal notes,
  evidence drilldown, dependency-aware deletion and all-data export in Storage.
- Health Connect data-type/history/background permissions and imports; weather
  chosen-place/device mode and attribution; voice and wake-phrase controls.
- Optional watch selection and checks, speech confirmation, reduced motion,
  compatibility messaging, setup revisit and version/guide links. The existing
  pairing and watch installation hold remain unchanged.

## Evidence limits

Synthetic records and emulator radios establish software behavior, not physical
sensor accuracy, real Health Connect account behavior, radio range, watch safety,
or long-running battery use. Those checks need named physical devices. Public
preview availability must not be described as physical-watch validation.

## Validation before packaging

The final host run passed 181 tests; six opt-in external-provider checks were
skipped. Standalone APK and test-APK assembly succeeded. Android lint reported
zero errors and seven existing dependency, target and exported-service warnings.

The complete API 36 emulator suite passed 43 tests, including two opt-in skips.
API 28 and 31 also ran the complete suite. Initial lazy-list lookup and
history-loading test timing failures were corrected without weakening their
assertions. Their affected paths passed in subsequent runs. The final window
resize change was followed by all 17 UI journeys on APIs 28, 31 and 36; API 28
also reran all five question-pipeline tests in the same successful 22-test run.
Seven scoped-history/question/memory integration tests passed separately on API
31. The scoped-history fixture includes full-store pagination, exact projection,
reopened conversations, deletion, corrected-memory invalidation and immediate
attachment removal before review.

Keyboard validation waits for visible, settled IME insets, verifies Review is
above the keyboard and activates it with pointer input. The 320-dp, 200% text
screenshots were inspected in light and dark themes. Real TalkBack focus and
activation passed on API 36. The build 10 upgrade fixture passed without an
uninstall, retaining settings, a synthetic key, original readings, a saved
question and corrected memory. Packaging and public download hashes are recorded
separately in the build 11 release receipt.

The upstream CI workflow targets the legacy companion. These results came from
explicit standalone Signal Station build and test tasks; upstream CI is not used
as evidence for this release.
