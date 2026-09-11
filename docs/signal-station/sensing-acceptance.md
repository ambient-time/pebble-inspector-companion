# Sensing release acceptance

By Luke Steuber. September 10, 2026.

The sensing-release Android preview was **0.3.0-sensing-dev (8)**, from `7c5acfed46032639649c2a8b2115f00bed81a12c`. It is available at <https://lukesteuber.com/downloads/apps/signal-station/> and updates the earlier separate previews in place. Its APK SHA-256 is `a31c21000acc244d330494f77b880ce57d5bbfbd9db0a68a0d39d7dfa47558fe`. The package and signing certificate are unchanged.

Build 9 follows this checkpoint with [capture context and navigation fixes](capture-context-acceptance.md). The results below describe the earlier sensing release.

This record checks the [approved plan](sensing-release-plan.md) against implementation and measured evidence. Software and download preparation are complete. Physical acceptance remains open.

| Plan area | Implemented and checked | Remaining acceptance |
| --- | --- | --- |
| 0. Contracts and baseline | Stable source keys, package, encryption/record identities and watch UUID. Additive defaults and explicit source/external/provider choices. Version-specific permission paths below. | Named-device battery baseline. |
| 1. Acquisition and storage | Shared typed acquisition; per-source outcomes; fair record/frame budgets; numeric sensor features; step reboot/reset handling; cancelled-source filtering. Dense, stale, malformed and missing-source fixtures. | Actual radio/sensor accuracy and OEM behavior. |
| 2. Migration and comparisons | Resumable derived indexes; originals and corrections retained; compatible numeric trends, exact tables and deletion lineage. Distributed build 6 upgraded through 7 to final 8 without uninstalling; seeded key/settings/history/question/correction survived. | No additional software migration gate identified. |
| 3. Finite adaptive sessions | Standard/Saver schedules, freshness/cooldowns, monotonic deadlines, explicit gaps and Stop. Actual foreground-service sample/save/notification/stop exercised on API 28 and 31, with earlier API 36 coverage. | Stationary/moving, screen-off, Doze and battery/thermal measurements on a real phone. |
| 4. Nearby places | Local confirmed places; reviewed, bounded Overpass nearby/address queries; candidate attribution/uncertainty; encrypted cache and deletion checks. Live public-landmark queries returned 20 nearby and 9 address candidates. | Venue accuracy in real surroundings. |
| 5. Wireless environment | Wi-Fi security/channel/band/width/freshness; connected-internet distinction; retained-identity comparisons; bounded BLE observations and enrolled matching. Parser, malformed/duplicate and fairness fixtures. | Named-device scan coverage and rotating-address behavior. |
| 6. Cellular and radio location | Per-subscription callback attribution, explicit unattributed cache fallback, independent identifiers, no subscriber/phone IDs. Reviewed beaconDB-compatible estimate kept separate from the phone fix. Live made-up radio fixture returned no result with IP fallback disabled. | Actual multi-SIM/vendor readings and radio-location accuracy. |
| 7. Health and learning | Versioned reread; bounded timestamped HR/sleep detail; selected resting HR/RMSSD; origin separation; tombstones; local comparisons/proposals; authoritative corrections and reviewed sends. Mapping and encrypted-store integration passed. | Real Health Connect records, grants/history limits and updates/deletions on a named phone. |
| 8. UX and adjacent work | Key-free capture, source recovery, review, local tables, CSV/JSON/Markdown, diagnostics and download discovery. 200% text interaction checks; real TalkBack service focused and activated Today/Ask/Activity/Memory. | Human listening/focus-order assessment in longer workflows and physical small-screen use. |
| Android publication | Frozen signed artifact; 22 matching HTTP responses across both domains; correct attachment/MIME; minimal page, guide/source, manifests and checksums. Ten icon/social responses matched, including 1200×630 social artwork. Developer card and download groups remain alphabetical. | Physical testing does not follow from successful publication. |
| Pebble draft | Existing app remains Unlisted with 1.5.0 Draft. Six native builds; four native render targets; no PKJS or new pairing ownership. Public held 1.4.0 bytes are preserved. | Flint/Gabbro render acceptance, isolated incident investigation, stock-host interoperability, controlled physical acceptance and a later publication decision. |

## Android capability and permission paths

These describe current code, not a promise that every device supplies every source. Missing hardware, denied permissions and stale/throttled observations remain explicit outcomes. Selecting collection does not enable external lookup or model transmission. New source fields stay off during upgrade unless selected.

| Android version | Behavior |
| --- | --- |
| API 26–27 | Minimum supported app level; native location-provider fallback; legacy Bluetooth permissions. Health Connect is unavailable. API 26 itself has not been run in this release check. |
| API 28 | Android 9 emulator passed 28 regular integration/UI tests. Health Connect is offered only when its provider reports available; installing this app does not install or grant Health Connect. |
| API 29–30 | Step sources request Activity Recognition; earlier versions use their available sensor path. Version guards protect thermal and foreground-service APIs. |
| API 31–32 | Selected Bluetooth sources request Scan/Connect as well as required location access. API 31 emulator passed the 28 regular integration/UI tests. |
| API 33 | Selected observation/wake sessions request notification permission. Wi-Fi broadcast registration uses the newer receiver flags. Notification permission was exercised on the API 36 emulator. |
| API 34–35 | Foreground-service types are selected from the requested sources, including location, connected device and supported health/special-use paths. No permanent background-location permission is required for the bounded session. |
| API 36–37 target | Native heart-rate access uses the granular health permission instead of the earlier Body Sensors permission. API 36 emulator has final-build upgrade and TalkBack navigation evidence; the earlier build-7 regular suite also passed there. Compile/target is 37; API 37 hardware/runtime has not been tested. |

The API 28 and 31 virtual devices were stopped after testing. Their tests reported 29 cases each: 28 passed and the explicitly gated two-install upgrade fixture skipped. Host tests reported 165 passed and six opt-in live checks skipped during the ordinary suite. Android lint and 78 publishing tests passed.

## Live provider checks

Only a short synthetic prompt was sent. No saved conversations, phone readings, health records or device location entered these checks. The lookup fixture used the Eiffel Tower and made-up locally administered radio identifiers.

| Provider | Observed result |
| --- | --- |
| Gemini, `gemini-3.6-flash` | The production answer transport returned visible text. An earlier `gemini-2.5-flash` request returned 404 because that model was unavailable to this account. |
| OpenAI, `gpt-4.1-mini` | HTTP 429 with `insufficient_quota`. No successful answer is claimed. |
| OpenRouter, `openai/gpt-4.1-mini` | HTTP 402 payment error. Build 8 now gives a billing/usage remedy. |
| xAI | The environment credential was rejected, including by the model-list endpoint. Build 8 recognizes the observed string-shaped authentication error without displaying the upstream body. This is not a test of the credential stored on the owner's phone. |
| Anthropic | No environment credential was configured, so no live request was made. |

Mock transport tests cover all advertised provider families and failure/cancellation/size paths. Live checks are explicitly gated by `SIGNAL_LIVE_SMOKE=1`; normal tests do not read keys or contact services. Model selection remains editable rather than being silently changed for an existing account.

The lookup service contracts were checked against [beaconDB](https://beacondb.net/), its linked [Ichnaea interface](https://ichnaea.readthedocs.io/en/latest/api/geolocate.html), and the [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API). Public service availability and coverage remain external dependencies. Lookup is off by default, manual, bounded, cached and separately reviewed; there is no contribution-upload path or automatic retry.

## Watch boundary and next physical pass

Flint/Gabbro installation and explicit start requests did not produce the app screen. Additional emulator firmware output showed the existing watchface crashing during the switch/fetch sequence, followed by an app fetch for the Signal Station UUID. That narrows the emulator symptom; it does not diagnose the earlier physical settings wipe or establish app safety. No substitute clock image was used as an app screenshot.

No physical Android was attached for this release check. The next pass needs a named phone for real Health Connect import, stationary/moving and screen-off sessions, permission revocation, poor/offline location and battery/thermal observations. An additional OEM should be included when available. The recovered owner Pebble must remain untouched while its separate installation hold is open.
