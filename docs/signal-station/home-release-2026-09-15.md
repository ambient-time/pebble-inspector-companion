# Home release — September 15, 2026

By Luke Steuber. Dated release record; later documentation commits do not change
these package identities. These are published experimental builds.

## Published packages

| Component | Version / source | SHA-256 |
|---|---|---|
| Android | `0.5.0-home-dev`, build 17; `abb39a3b491ae62e6b68be5901fd655d7a9f0a80` | `ac55046e7bb2e0e29c830b676f40f4a761e7cf2e2e990fc83e3cc7d0678b805e` |
| Pebble | 1.7.1; binary source `68e26c05478e5fa1281adee218bdd03fc946c6ad` | `0800b668bc83f120b51b25461dffa07bee6608e8f3f687b1c01c1ff41a1d886f` |

Observed: the [download page](https://dr.eamer.dev/downloads/apps/signal-station/)
and its [mirror](https://lukesteuber.com/downloads/apps/signal-station/) served
matching APK, PBW, source archives, guides and checksums: 26/26 public-byte
checks passed. The [Pebble listing](https://apps.repebble.com/37360ca4d9764881bd1d6f4d)
reported 1.7.1 Published and visible; its downloaded PBW matched the digest above.
Listing metadata commit `20a6226` followed the watch binary source commit.
Existing listing media and prior rollback packages were preserved.

Android package `com.lukesteuber.signalstation` retains build 16's signing
lineage. Certificate SHA-256:
`e1faa235dbdd132ac86856639b60b12ad9f9b658fc921fd8160db84ed8dfa28d`.
Pebble UUID remains `e2fd86ec-dfb8-460c-afc1-ebe4d071657a`, with basalt, chalk,
diorite, emery, flint and gabbro binaries. This package has no embedded PKJS;
keep the usual Pebble host app and separate Signal Station Android app.

## Implemented and checked

[Home setup](home-connections.md) covers multiple named Home Assistant, openHAB
and Geepers connections; catalog preview; configurable readings, controls and
scenes; room groups; accessible reordering; watch favorites; and optional Home
access in the existing chat package. Capture selection, shortcuts and agent
catalog access are independent. Home access and standing permissions start empty.

Native provider tools use stable IDs, declared capabilities, explicit pagination,
eight tool rounds and existing request/context budgets. Exact permissions are
checked at dispatch. Encrypted durable intents precede mutations; confirmations
expire after two minutes and are consumed once. Cancellation stops undispatched
work; already-sent uncertain actions remain visible without automatic retry.

| Evidence | Result and boundary |
|---|---|
| Measured: Android host suite | 280 discovered, 272 passed, eight skipped; assembly and lint passed. Two opt-in tests passed separately against disposable Home Assistant 2026.9.2 and openHAB 5.2.1. |
| Observed: Android emulator | Regression runner `OK (24 tests)`, including assumption-gated cases. Four Home station tests covered actual coordinator/encrypted-store integration. |
| Observed: accessibility | TalkBack 16 bound with touch exploration at 2× system text; synthetic grid/reorder/review test passed. Human listening and gesture usability remain open. |
| Measured / Observed: Pebble | Six targets compiled; list, detail, exact review, scrolling, cancellation and phone handoff inspected in emulators. Native C and JavaScript protocol checks passed. |
| Measured: Geepers / Node Hub | 36 dashboard tests; 295 hub tests, mypy over 31 files, targeted Ruff passed. |
| Observed: selected display | One color-puck refresh received HTTP 202 and a correlated completed-command receipt. This proves reported command handling, not visible appearance. |

The deployed Geepers gateway uses source `9807a2b`, with Node Hub `988932f`.
Its final observed catalog had 24 records: eight display registry records
(including unavailable/simulator entries), eight Matter endpoints and eight paired
Zigbee devices. All eight Zigbee records awaited readings in that gateway check.
Catalog presence and response time are not sensor measurements. Temporary phone
tokens were revoked; intentional setup needs a separate revocable token.

## Open acceptance and future platforms

Unavailable in this release record: a physical build-17/1.7.1 phone/watch Home
run, visual confirmation of the selected display, and human TalkBack review.
Earlier physical capture evidence belongs to earlier versions. Other actuators
need separately selected physical validation. Provider formats have fixture
coverage; this is not live-account proof for every supported model.

iOS and Garmin integration remain Planned, following the
[capability audit](platform-capabilities.md). Hardware work can now reuse the
implemented Home clients and gateway. No room relocation, new pairing or audio
activation is implied by publication.
