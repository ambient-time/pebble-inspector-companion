# Garmin watch app — November 2, 2026

By Luke Steuber. Source and simulator check on branch `garmin-signal`. Nothing installed on a watch or phone, and nothing published.

## What was built

- `garmin/signal-station`: a Connect IQ watch app with a glance. Home favorites list, reading, exact action review, the watch's own confirmation dialog, result, recent replies, and the phone Home view. 104 watch models.
- `signalApp`: `GarminAppLink` carries the app's Garmin Connect messages into `handleWatchRequest`, the handler the Pebble app already uses, so Home review, confirmation binding, cancellation and unknown outcomes are unchanged. `CombinedWatchLink` lists Pebble and Garmin watches together; a Garmin watch is chosen under Settings → Watch. `ConnectIqConnection` wraps Garmin's companion SDK 2.4.0 and stays idle when Garmin Connect is not installed.

The Garmin app has no questions, dictation or capture. The documented Venu 2S has no microphone or speaker; Garmin's SDK 9.3 Audio module (October 2026) records raw audio on newer watches only and has no speech recognition.

## Checks

| Check | Result |
| --- | --- |
| `:signalApp:testDebugUnitTest` | 11 new tests pass: message parsing and limits, sessions, late messages from an earlier run, disconnects, reset, combined list |
| `SignalHomeIntegrationTest` on emulator (API 31) | 7 of 7 pass, including the new Garmin test: the app's exact message sequence through `GarminAppLink` into the real station, Home engine and encrypted store, with only HTTP mocked. One dispatch per confirmation, no dispatch on review, cancel by request number, and a new app run cannot confirm an earlier run's review |
| `:signalApp:assembleDebug` | Builds |
| `monkeyc -e -r` package, SDK 9.2.0 | 177 of 177 device builds. Warnings only for launcher-icon scaling and for older watches that do not offer glances to watch apps; those show the app without a glance |
| Release Venu 2S PRG | `c9b1471f8a4032524d51eaa4aff3305c476392d20ab9237a8520d7c67e3b5f7c` |
| Package | `ebd2fe8bdc8058a241836a99461c53edef7c628604408c037d18152ce937882a` |
| Simulator, Venu 2S, SDK 9.3.0 | Glance, menu, favorites, review, confirmation and result screens, driven by replies pasted into Simulation → Phone App Message. The watch sent the expected requests, including `home-confirm` with the reviewed intent and action |

The integration test caught one bug before any device test: the connection check carried a request number in its address, which the station refuses. Fixed.

Screens are in `garmin-evidence/`. The review screenshot predates a shorter hint; the old hint was clipped at the bottom of the round screen.

## Not verified

- **Phone link.** Garmin's simulator link over adb did not work on macOS 27.2. The 9.2 simulator crashed on the first message from any app, including a one-word test on Venu 2S and Forerunner 265 profiles (four crash reports). The 9.3 simulator stopped responding instead. The SDK-to-SDK path is therefore tested only on each side, not end to end.
- **Real watch.** Needs the Venu 2S on a phone with Garmin Connect, the PRG sideloaded, and a Signal Station build with the Garmin link on that phone. Installing a debug build over the private build 24 on either Pixel would need Luke's go-ahead.
- **Background delivery.** The Garmin SDK delivers messages to the running app. Whether Garmin Connect wakes Signal Station when it is not running is untested.
- Small and monochrome screens (Instinct) compile but were not looked at.

## Private build 25 on Pixel 10

Luke approved a real-watch test. Android **0.9.3-garmin-dev (25)** was built from `2391366b` with `-PsignalGarminTethered=false` and installed in place on the Pixel 10 over build 24.

- APK SHA-256 `8fbfadd516c88f1505e3be37a9506d76cc51ae555a570ecd3d8d7d2a1a16c1f6`; the installed file matches. Signer `e1faa235…` is the same as builds 23 and 24. Copy kept outside Git on Galactus.
- Permissions are unchanged from build 24. Garmin's SDK adds a query for Garmin Connect, its exported binding service and its install-prompt activity.
- Data kept: first install time (October 2, 08:51:33) and both data-folder inode numbers (71663, 71666) are unchanged. No uninstall or data clear.
- The app launched and stayed running, with no crash entries.
- Build 25 also carries the offline Listen/Stop for saved replies that was already on `codex/signal-consent`.

Neither Pixel has Garmin Connect installed, and the Venu 2S was not connected to either Mac, so the watch side has not started. Pixel 9a has no Signal Station app installed.
