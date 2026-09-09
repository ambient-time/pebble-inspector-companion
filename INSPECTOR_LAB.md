# Signal Station

I built Signal Station to ask questions from a Pebble and give those questions
a little context. Speak on the watch or type on the phone, choose the readings
to include, and get a text reply. The phone keeps the full conversation and
capture history; the watch collects signals and shows a short answer.

By Luke Steuber. This is an experimental Android companion and Pebble watchapp.

The Android fork preserves Core Devices' source, notices and history from
`d52101ad3d8940c5aa392d6f224e774cb6f5ce84`. The [upstream README](README.md)
describes the rest of the companion. The earlier Field Inspector audio experiment
remains in Git history; its choppy speaker playback is no longer part of this app.

## Set up

1. Install the Signal Station test APK. The package remains
   `coredevices.coreapp.inspectorlab`, so it updates Pebble Inspector Lab in place.
   Keep only one companion actively connected to the test watch.
2. The first launch explains local captures, lets you choose individual sources,
   and offers Android permission prompts for those choices. Every source is
   optional; setup can be skipped and revisited in Settings.
3. Open **Devices** to pair the watch. Select it on the Capture screen, then tap
   **Install watch app** to install the bundled Signal Station 1.2.0 and verify
   its connection. Install with only the selected watch connected.
4. Tap **Capture readings** to save enabled sources on the phone. No provider
   key is needed. Open the capture in History to inspect each reading and choose
   **Analyze readings** when you want to send it to a model.
5. For analysis, choose a provider, model and key in Settings. OpenAI defaults to
   `gpt-4.1-mini`; Anthropic, Gemini, xAI, OpenRouter and custom HTTPS
   OpenAI-compatible Chat Completions endpoints are supported. Provider testing
   sends a request only when tapped and may incur charges.
6. For watch dictation, choose OpenAI recognition and its separate transcription
   key, or explicitly choose the stock recognizer. OpenAI requests `gpt-transcribe`;
   the account must support that model. There is no automatic fallback. App-specific
   dictation requires exactly one connected watch because the upstream speech hook
   identifies the app but not the watch. Ordinary dictation is the only audio input.

On the watch home screen, **Up captures**, **Select asks**, and **Down opens
recent history**. Up and Down scroll reports; Back returns home or cancels an
active request. Recent history shows up to five eligible records from this phone
for the selected watch. Full records and deletion controls stay on the phone.

**Ask** sends typed or confirmed dictated text. **Capture & analyze** collects
and requests analysis together. A plain capture never requests model analysis;
enabled weather sources still contact their weather service. Capture completion
is acknowledged only after the phone saves the record.

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

## Weather and location

In Settings, choose **A chosen place**, enter a city and country, tap **Find
places**, then select a result. Search sends only that text to Open-Meteo.
Alternatively, choose **Near this phone** and grant location permission.

Current weather, the next six hours, daylight, air quality, and UV each have their
own switch. They all start off. Capture fetches only the selected groups and saves
the readings with the report. Changing the place starts a new conversation so a
previous city's report does not quietly become the context for a new one.

Device-based weather sends a position rounded to 0.01 degree (roughly a kilometre)
to Open-Meteo. A cached fix must be no older than 15 minutes. The model receives
the weather and a description of the location source, without the coordinates.
The separate **Location** switch includes phone coordinates in model context when
you want them. A manually chosen city's name appears in its weather report.

Weather readings come from models, so they can differ from conditions outside.
Reports preserve units, valid times, missing fields, and forecast coverage.
Sunrise and sunset are calculated; polar dates can lack either event. Air-quality
and UV estimates are regional, not measurements made by the watch.

Data and attribution: [Open-Meteo weather](https://open-meteo.com/en/docs),
[Open-Meteo / CAMS air quality and UV](https://open-meteo.com/en/docs/air-quality-api),
and [GeoNames place data](https://www.geonames.org/) through
[Open-Meteo geocoding](https://open-meteo.com/en/docs/geocoding-api).
The lab uses Open-Meteo's free noncommercial service. See its
[terms](https://open-meteo.com/en/terms) before repurposing the app commercially.
Weather requests never include health readings, radio identifiers, or provider keys.

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
under ignored `dist/`. Lab revision 4 increments the APK version code and retains
the original runtime class namespace, separate PebbleKit provider authorities,
fake Firebase configuration, and disabled analytics/Crashlytics.

The Android encrypted-storage test runs only on the lab variant with
`-PsignalTests=true` and class
`coredevices.coreapp.signal.SignalStoreTest`. Use an emulator or designated test
phone; it uses a unique test-only database and key namespace.

[Signal Station draft](https://developer.repebble.com/dashboard/apps/37360ca4d9764881bd1d6f4d/edit)
is maintained as an Unlisted listing with a Draft release. Installing the
watchapp alone does not supply the experimental Android companion. Build, emulator, physical
watch, provider-account and pairing-recovery evidence are recorded separately in
[SIGNAL_VALIDATION.md](SIGNAL_VALIDATION.md).
