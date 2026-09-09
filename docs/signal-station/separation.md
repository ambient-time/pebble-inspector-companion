# Signal Station separation

By Luke Steuber. September 9, 2026.

Signal Station owns questions, provider keys, source choices, captures, history,
presence experiments and wake listening. The user's Pebble app owns pairing,
firmware, watch settings and the app locker. A narrow watch adapter carries
Signal Station messages and reports its capabilities.

Preserve the existing fork adapter and custom watch transcription while building
the independent Android shell. The shared feature module must not depend on
LibPebble, firmware services, BlobDB, Firebase, ring services or the parent app.
The independent shell must not instantiate them. Android PebbleKit2 can carry
watch app messages; raw dictation audio is not exposed by its public client API.
Do not present that capability as available through the new adapter.

The interface has Ask, Activity and Settings. Activity retains Capture, Nearby,
History and the labelled advanced experiments. First use offers a question or a
local capture; watch setup is optional. All sources remain individually opt-in.

Touch/keyboard: open app -> save provider -> ask -> see the answer or a retained
question with an error. Capture: choose sources -> capture -> inspect dated
readings -> explicitly analyze. Watch: existing pairing -> choose connected
watch -> open Signal Station -> capture/dictate/history -> quick feedback.

Acceptance: unchanged domain/protocol tests; adapter lifecycle/cancellation
checks; standalone dependency and manifest audits; isolated emulator walkthrough.
UI completion time and TalkBack usability remain unmeasured. A new adapter is
not proof of physical-watch safety. Keep the published installation hold and
preserve old package data until migration and capability parity are verified.

## Implemented boundary

- `:signal` contains the existing models, providers, encrypted store, collectors,
  presence tools, weather, history, wake listening and shared Compose screens.
  It has no LibPebble dependency. Class names and encrypted storage identifiers
  are preserved. Initialization is explicit and idempotent.
- `SignalWatchLink` exposes watch status, a session message channel, refresh and
  app launch, plus capability reporting. It exposes no pairing, firmware, reset,
  notification synchronization or watch-database operations. Installation remains
  blocked by both current adapters while the wipe investigation is open.
- `:pebble` retains `LegacySignalStation` and `SignalWatchTranscription`. The old
  transcription hook, including caller checks and the separate recognition key,
  is preserved there. Its Android source compiles against the extracted module.
- `:signalApp` is a separate Android package, `com.lukesteuber.signalstation`.
  The user explicitly selects an existing Pebble app. The lab companion is not
  offered as a host. PebbleKit2 1.1.0 carries app messages and app-open events;
  the listener checks the selected host using Binder caller identity. This was
  checked in SDK source tag `1.1.0`, commit `6905823c5adb8fb6f32571f3ea62ae3fe5916362`.
- The independent app runs the existing credential-free protocol in a local
  WebView with network, file access, content access and navigation disabled.
  Session identity and delayed-open invalidation protect reconnect and host
  changes. Acknowledgement requires successful JavaScript dispatch and serial
  transport acknowledgement. Closing a session cancels pending work.
- The optional 1.4.0 watch preview declares only the new Android package and
  contains no PKJS. `scripts/build-signal-addon-watch.py WATCH_CHECKOUT` builds it
  separately; it does not replace the released 1.3.0 package. Its C source
  includes the non-recursive collector correction.

## Capability preservation and remaining acceptance

| Capability | Independent app | Existing adapter |
| --- | --- | --- |
| BYOK chat and text responses | Shared implementation retained; provider mocks pass | Retained |
| Phone sensors, location, weather, presence, places, beacons | Shared implementation retained | Retained |
| Local history, comparisons, exports, source switches, field trials | Retained | Retained |
| Go go gadget, local wake recognition and draft review | Retained; model bundled | Retained |
| Watch readings, health, buttons, history and quick feedback | Message adapter and package built; stock-host exchange still requires validation | Retained |
| Watch dictation through Pebble | Uses the selected Pebble app's service; not yet validated end to end | Retained |
| Custom BYOK watch transcription | Not exposed by the public client API; not advertised as available | Preserved in the legacy adapter |
| Pairing, firmware and full watch synchronization | Owned by the existing Pebble app | Existing host responsibilities; not part of the extracted feature module |

The new package starts with empty private storage. It does not read, overwrite,
uninstall or migrate the old package. Export/import and recognition capability
parity are release gates, not implied by this extraction. The shared UI/domain
remain multiplatform; the Android-specific sensors and new PebbleKit adapter do
not establish an iOS implementation.

## Verification on September 9

- Independent debug APK builds; Android lint reports zero errors and four
  warnings (target SDK, available newer SDK library, exported SDK listener, and
  version catalog placement). The exported listener's caller check was reviewed.
- 96 feature tests pass, including three delayed-session lifecycle regressions.
  The local protocol adapter test covers readiness, native request routing,
  serialized sends and duplicate acknowledgements. A real Android WebView
  instrumentation test also passes for startup, key translation, refresh,
  incoming dispatch and rejection after session closure. It uses an isolated
  sender double, not a stock Pebble host or physical watch.
- The retained legacy Android adapter compiles. This is not a physical watch or
  real provider-account test.
- All six watch targets build. Inspection confirms the new companion package
  registration and absence of embedded PKJS.
- The standalone runtime dependency report excludes LibPebble, the parent
  companion, ring modules and Firebase. Packaged manifest inspection confirms
  the app listener and user-started wake service, with backup disabled.
- A read-only Android emulator overlay opens the app without pairing or sensor
  prompts. Ask, Activity and Settings are visible. A five-source local capture
  saves without a watch or provider key; missing permission/enrollment inputs
  remain represented separately. An in-place development APK update preserves
  its saved settings and capture. No owner hardware was connected.
- Grok 4.5 reviewed the new adapter through the CLI. Its delayed-open race and
  JavaScript-result findings were checked and corrected. The advisory
  response (whitespace normalized) is retained in `separation-evidence/grok-review.txt`.

UI path evidence establishes discoverability in the emulator. Completion time,
TalkBack, battery impact, background watch delivery, migration and physical
watch behavior remain unmeasured.

## Development distribution

Luke requested uploading the separated previews on September 9. Android
0.1.0-separation-dev (1) and watch 1.4.0 from `504e3792` are now available at
https://dr.eamer.dev/downloads/apps/signal-station/ with matching source and
checksums. The Android package is separate and debug-signed. The old lab 8
companion remains withdrawn; the settings-wipe cause is unresolved. Publication
does not establish migration, stock-host exchange or physical-watch behavior.

Android 0.1.1-separation-dev (2) restores the protocol when the chosen Pebble app
already reports Signal Station open, including after Android recreates the
process. The former adapter only started sessions on an app-open event and
missed an already-open watch app. The adapter now observes the selected host's
read-only active-app provider for each connected watch. Duplicate open events
retain the current session; app closure, disconnection and host changes close
and invalidate the old session.

The new Android instrumentation regression timed out before this correction
and passes afterward. It uses the real WebView protocol with a PebbleKit
information/sender test double; it covers initial restoration without an open
callback, duplicate callbacks, app closure, reconnection, host switching and
explicit disconnection. It does not substitute for a stock host or physical
watch. Cross-package history import and custom watch transcription remain open.
