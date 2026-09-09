# Signal Station in Pebble Inspector Lab

Signal Station is Luke Steuber's private Android and Pebble context experiment.
The phone is the workbench: type a question, trigger watch dictation or a survey,
read full reports, and explore local history. The watch collects selected signals
and displays a short answer. Replies are text only.

The Android fork preserves Core Devices' source, notices and history from
`d52101ad3d8940c5aa392d6f224e774cb6f5ce84`. The [upstream README](README.md)
describes the rest of the companion. The earlier Field Inspector audio experiment
remains in Git history; its choppy speaker playback is no longer part of this app.

## Set up

1. Install the verified `inspectorLab` APK. Its package is
   `coredevices.coreapp.inspectorlab`, labeled **Pebble Inspector Lab**. Keep the
   stock companion and its data. Bluetooth pairing is still shared hardware:
   keep only one companion actively connected to the test watch.
2. Complete watch setup, using **Skip sign in** for the account-free watch path.
   Leave Index disabled. Open **Signal Station** from the watch companion toolbar.
3. In Settings, select a provider, model, and its own key. OpenAI defaults to
   `gpt-4.1-mini`. Anthropic, Gemini, xAI, OpenRouter, and custom OpenAI-compatible
   Chat Completions endpoints are also supported. A custom endpoint must use
   HTTPS. Provider testing sends a small paid request only when tapped.
4. Choose **OpenAI** recognition and save its separate transcription key, or
   explicitly choose the stock companion recognizer. OpenAI recognition requests
   `gpt-transcribe`; the provider must support that model. There is no automatic
   fallback. App-specific OpenAI dictation currently requires exactly one connected
   watch, because the upstream speech hook identifies the app but not its watch. The watch's ordinary dictation stream is the only audio input.
5. Select the connected watch. Enable each desired source, then tap the
   permissions button. All collection sources start disabled. Permission and
   hardware restrictions remain visible as unavailable readings.
6. Install the matching Signal Station 1.1.0 PBW from the staged pair. Its UUID
   remains `e2fd86ec-dfb8-460c-afc1-ebe4d071657a`. The native bridge accepts only
   the bundled PKJS digest and selected watch; an older PBW needs updating.

**Ask** sends typed or confirmed dictated text. **Survey** collects enabled
sources once and requests an analysis. **Record on watch** starts dictation on
the selected watch. Conversation, History and Settings stay on the phone;
collection progress and a UTF-8-safe brief return to the watch. The complete
answer is saved independently of watch delivery.

## Sources and limits

Phone sources include individually selected Android sensors, location, device
status, Wi-Fi scans, and Bluetooth advertisements. Names and identifiers are
separate switches. Radio scans are bounded; there are no connections to nearby
peripherals, packet capture, ambient microphone sampling, camera use, or video.
Background restrictions can prevent fresh phone readings; cached or unavailable
results are labeled. Enabling a switch does not bypass operating-system consent.

Watch sources include motion, compass, battery and supported HealthService
metrics: steps, active time, distance, active/resting calories, sleep, restful
sleep, heart rate and activity. Daily observations distinguish today from the
seven preceding complete local calendar days. Last completed main sleep uses a
documented completed-episode heuristic; unavailable data is not replaced with
zero. Hardware capability, sampling age and wear gaps limit interpretation.
Direct watch readings preserve watch provenance; the companion's merged health
database is not used for these reports.

## History and privacy

History stays on this phone until deleted. Room stores encrypted report payloads;
Android Keystore protects the AES-GCM key and provider credentials. Backup and
device transfer are disabled for the lab package. No cloud history or embedding
index is created. Exports deliberately produce plaintext JSON or Markdown for
the destination selected in Android's share sheet.

Normal conversation uses a bounded recent thread and explicitly attached reports.
**Ask about history** retrieves local records and deduplicates daily health
observations before constructing bounded provider context. Reports cite source
record IDs and disclose coverage. Different provider/model settings start a new
thread. Crossing providers requires explicit attachment. Disabling a source also
excludes earlier text derived from that source from later requests; old reports
remain locally readable. Deleting a source record removes dependent reports.

Selected content goes directly to the selected provider. Keys stay in native
storage and never enter PKJS, the PBW, prompts, or exports. Cancelling stops the
local operation; a remote provider may already have received the request. An
interrupted app session is marked interrupted and never automatically retried.
There is no scheduled monitoring, text-to-speech, or external action execution.

## Build, verify and stage

Use Java 21 for Gradle, Java 17 for compilation, and Android SDK 37:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :pebble:testAndroidHostTest :libpebble3:testAndroidHostTest \
  :androidApp:assembleInspectorLab :androidApp:lintInspectorLab \
  -Dorg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_inspector_apk.py androidApp/build/outputs/apk/inspectorLab/androidApp-inspectorLab.apk
```

The companion's `tools/import-signal-watch.py` imports a reviewed PBW and pins its
PKJS SHA-256. Repeat import after any watch JS change. From committed source,
`bash tools/stage-inspector-lab.sh` builds and verifies a revision-named package
under ignored `dist/`. Lab revision 2 increments the APK version code and retains
the original runtime class namespace, separate PebbleKit provider authorities,
fake Firebase configuration, and disabled analytics/Crashlytics.

The Android encrypted-storage test runs only on the lab variant with
`-PsignalTests=true` and class
`coredevices.coreapp.signal.SignalStoreTest`. Use an emulator or designated test
phone; it uses a unique test-only database and key namespace.

No public store release is part of this experiment. Build, emulator, physical
watch, provider-account and pairing-recovery evidence are recorded separately in
[SIGNAL_VALIDATION.md](SIGNAL_VALIDATION.md).
