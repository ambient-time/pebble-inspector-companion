# Explicit phone reply playback

By Luke Steuber. October 5, 2026. Source and isolated-emulator check; not a phone
installation or public release receipt.

## Use

Current standalone Android source adds **Listen on phone** below a completed,
saved reply in Ask and in its details. Tap it to read that answer, then **Stop
reading** to stop, including while the engine is preparing. The saved text stays
available. Working, failed, empty and raw-capture records have no Listen control.

This feature is not in the previously installed build 24 or the October 3 public
testing download. No phone, watch, pairing or download catalog was changed for
this implementation. Release properties were not bumped; emulator test APKs are
not a new numbered release and must not replace the archived build 24 artifact.

## Audio and privacy

Listen passes the saved answer to the installed Android text-to-speech engine.
Only voices that the engine marks as installed and not requiring a network are
eligible. Selection prefers the installed default voice, then the phone's locale,
then another eligible installed voice. Android's current media route determines
the output: phone, headphones or Bluetooth. Signal does not raise volume or force
the speaker. Check the output route before reading private content aloud.

No new language-model request, hosted speech request, automatic voice download,
watch-audio transfer or household action is made by Listen. A third-party
engine's offline metadata is not an independent audit of that engine's privacy
behavior. The answer is shared with that installed engine, not kept exclusively
inside Signal's process. No reply text is added to Signal's speech logs or files.

Stop, replacement playback, changing conversation/view, opening an Ask panel,
changing/deleting the answer, starting a wake recording or leaving the Activity
ends playback. Audio-focus loss and disconnected audio output also stop it.
Scrolling the reply out of composition can stop it; background continuation and
automatic resume are intentionally absent. Stop remains available while other
Signal work is busy.

Missing voice data, initialization failure, muted media, denied audio focus and
timeouts produce bounded messages. Install or select offline voice data in
Android's text-to-speech settings yourself, then tap Listen again. Signal does
not silently switch to another speech service.

## Implementation and provenance

- `SignalSpeech.kt`: eligibility, installed-voice selection, exact text chunks,
  per-attempt ownership, cancellation and bounded initialization/playback.
- `SignalSpeechControls.kt`: labeled minimum-48dp controls and polite status
  semantics, injected into existing Ask/detail screens.
- `AndroidSignalSpeechEngine.kt`: lazy native initialization, audio focus,
  interruption handling and idempotent shutdown. `MainActivity` owns its lifetime.
- The shared screen's default speech dependency is absent. Inherited CoreApp and
  iOS callers do not gain nonfunctional controls. No serialized settings,
  provider/tool dispatch, credentials, database or consent contracts changed.

Offline selection and cancellation patterns were adapted from Luke Steuber's
MIT-licensed Dick Tracy `Speech.kt` at
[`9de9a6fa`](https://github.com/lukeslp/gadget-watch/tree/9de9a6fa9879d9bac0e0f63b806612296fec7c6a/android/app/src).
No new library dependency was added. The combined application's existing
[licensing scope](../../LICENSING.md) remains in force.

## Verification boundary

Synthetic tests cover exact saved-answer text, preservation of stale/missing
qualifications and surrogate pairs, offline voice filtering, explicit start,
Stop during preparation, replacement playback, timeouts and stale callbacks.
Regression tests caught and fixed late callbacks after timeout and callbacks
during shutdown that could incorrectly reactivate the playback indicator.

The final shared host run discovered 400 tests: 392 passed, eight opt-in tests
skipped, zero failures/errors. All 15 local-model tests passed. Android assembly,
Android lint and shared iOS simulator compilation passed; compilation does not
establish an iOS speech product.

The isolated API 36.1 emulator used a fresh data directory and airplane mode.
Six UI tests exercised explicit Listen/Stop, busy-state Stop, navigation,
changed/deleted answers, recording/thread transitions and 100%/200% text.
A native-engine probe completed start/done callbacks using an installed English
(United States) voice without network access. That result establishes native
callback completion, not acoustic quality or physical-device acceptance.
The final instrumentation run passed all nine tests: six speech UI checks, the
native-engine probe and two existing reply-rendering regressions. Screenshots
were inspected at both text scales, including the disclosure and Stop control.

Reproduce the build and shared checks with:

```sh
./gradlew :signal:testAndroidHostTest :localmodels:testDebugUnitTest \
  :signalApp:assembleDebug :signalApp:assembleDebugAndroidTest \
  :signalApp:lintDebug :signal:compileKotlinIosSimulatorArm64
```

Run `SignalSpeechUiTest`, `SignalSpeechEngineTest` and
`SignalResponseRenderingTest` from the Android instrumentation package only on
an isolated emulator. The engine probe writes whether native playback completed
or voice support was unavailable; a green test alone does not distinguish those
outcomes. Do not run fixture instrumentation over a user's phone data.

Physical audibility, real headphone/Bluetooth interruption, Activity transitions
on the Pixels and TalkBack announcements still need acceptance. Watch speech,
hosted voices, a voice picker and Auto-read are not implemented by this slice.

Next: package a distinct private build, install it in place on an available
phone, and run one synthetic Listen/Stop/interruption/TalkBack journey. Do not
replace installed build 24 or release publicly based on emulator evidence alone.
