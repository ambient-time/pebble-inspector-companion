# Reviewed-question private delivery — October 3, 2026

By Luke Steuber. Development preview; the new Pebble release is not public.

## Frozen packages

| Component | Identity | SHA-256 |
| --- | --- | --- |
| Android | `0.6.0-consent-dev`, build 18; source `f62bd6c7` | `4756fb2aeba3f15011e4ceeb7b1916a4976d9ef3c12d726062da78635ec62768` |
| Pebble | 1.8.0; source `428c09fd42531765cf37009a553ae683370c3e59` | `6b5f8695a8c59edf189d6f7bd1d2eac8eaf00454c767053f48507b6f3942b0ca` |
| Pebble portable source | Matching 1.8.0 source archive | `a3d131fe7e7a8a2d68d5df32429f8a472fe213efe74e31a59ecd93ef647e6f0a` |

Android retains package `com.lukesteuber.signalstation` and certificate SHA-256
`e1faa235dbdd132ac86856639b60b12ad9f9b658fc921fd8160db84ed8dfa28d`.
The established certificate is labeled Android Debug; its identity matches
the installed and previously distributed builds. No new signing identity was
introduced. The implementation is unchanged from `c1f95056`; later changes
stamp build 18 and extend the upgrade fixture.

The watch package retains UUID `e2fd86ec-dfb8-460c-afc1-ebe4d071657a` and all six
targets: basalt, chalk, diorite, emery, flint and gabbro. It contains no embedded
phone JavaScript. Use the standalone preview, not the older root-package build.

## Installation and upgrade evidence

- Assembly, Android test compilation and lint passed. A fresh populated emulator
  successfully upgraded the exact previously installed build 17 to build 18.
  Synthetic encrypted history, saved questions, credentials, Home connection,
  standing grant and watch favorite survived. No controller or provider was used.
- The first fixture attempt failed because it referenced a getter absent in
  build 17. The fixture now gates that getter on build 18; a fresh seed and
  verification each passed. Failed attempts are retained, not counted as passes.
- Pixel 10 replacement installation succeeded without uninstalling or clearing
  data. Package readback reports build 18 and preserves the original first-install
  timestamp. An on-device SHA-256 of the installed APK exactly matches the table.
- The existing phone-to-watch connection answered a read-only ping. It reports
  Emery / Time 2 firmware 4.38.4. Paired watch installation and foreground phone
  acceptance are deferred because concurrent Gadget Watch work is using the same
  hardware. No watch reset, new pairing, permissions or host replacement occurred.

A scoped pre-upgrade app-data snapshot and old APK were retained locally before
replacement. This is not a portable backup of Android Keystore material or a
guarantee that an APK downgrade can reverse the Home journal migration. No private
app data is included in source or public artifacts.

## Store and public boundary

The authenticated dashboard for existing app `37360ca4d9764881bd1d6f4d` shows
**1.8.0 Draft**, after saving and reloading. Publish immediately was disabled.
Existing **1.7.1 Published**, listing visibility, copy and media were preserved.
The public [Signal Station listing](https://apps.repebble.com/37360ca4d9764881bd1d6f4d)
was independently read after staging and still reports version 1.7.1 with its
existing PBW link. No new public download endpoint or Android catalog entry was
deployed. Local package hashing and dashboard version/status are verified;
the draft's server-side stored bytes were not downloaded and hashed.

## Remaining acceptance

The [implementation acceptance record](consent-and-wrist-acceptance.md) retains
the detailed logic, native storage, accessibility and watch-protocol checks.
Its earlier no-install/no-upload statements describe that earlier checkpoint;
this record supersedes them only for the specific actions above.

Next hardware checks need an uncontested phone/watch session: install the pinned
1.8.0 package, launch build 18, confirm negotiation, inspect review/cancellation
and phone handoff without sending personal content or operating household
devices. Dictation, audible/tactile perception, battery behavior and complete
wearer acceptance remain unverified. A public release remains a separate decision.
