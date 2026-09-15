# Signal Station: iOS and Garmin capability audit

By Luke Steuber. Checked September 15, 2026. Source/API audit; future support below is Planned unless explicitly identified as implemented. No iOS/Garmin Signal port or physical validation is claimed. See the [published Android/Pebble release](home-release-2026-09-15.md) and [Home guide](home-connections.md).

## Purpose and architecture

Signal Station collects individually selected observations, keeps local history and conversations, and optionally supplies bounded context and Home tools to the user's chosen model.

The released Android build uses shared Kotlin/Compose models, UI, provider formats, Home connectors and an action-permission engine. Android supplies collectors, encrypted persistence, lifecycle services, transport policy and the station coordinator. The Pebble program exchanges bounded packets with that phone implementation. An iOS port can reuse shared logic, but still needs real iOS adapters and a product shell. Geepers is a separate Capacitor application with a Swift Foundation Models bridge and its own research tools and voice hooks. Ambient Time's Garmin faces are separate Monkey C applications. Neither Geepers nor those Garmin faces currently implements Signal Station Home.

Sources inspected: Signal Android `abb39a3b`, watch `20a6226` (binary source `68e26c05`), Geepers checkout `9984d31`, and refreshed Ambient Time `origin/main` `4aef30be`. Garmin SDK 9.2.0 and installed Venu 2S definition (API 5.0.0, firmware definition 1905) were inspected. Those definitions do not prove today's installed watch firmware.

## Platform boundaries

- Using Geepers as the iOS host remains an undecided product choice. Signal's own module already declares iOS device and simulator targets. It has no iOS collector/coordinator implementation or iOS SignalStation DI registration yet. A Gradle target is not a working app.
- Garmin faces are not uniformly noninteractive. `WatchFaceDelegate.onPress` receives touch-and-hold on supported products, including Venu 2S. Complications can open an associated watch app. The newer `onTap` is restricted to face-configuration mode; it is not a general tap handler for arbitrary controls. A face can therefore be a useful entry point, with substantial interaction handled in the watch app. [WatchFaceDelegate](https://developer.garmin.com/connect-iq/api-docs/Toybox/WatchUi/WatchFaceDelegate.html), [Complications](https://developer.garmin.com/connect-iq/api-docs/Toybox/Complications.html).
- The existing documented personal Garmin is Venu 2S. It has **no built-in microphone or speaker**. Music playback uses Bluetooth headphones. Newer watches with voice hardware must be audited separately; hardware does not grant third-party audio capture. [Garmin comparison](https://www.garmin.com/en-US/compare/?compareProduct=707572&compareProduct=730659), [Venu 2 series audio/sensors](https://www8.garmin.com/manuals/webhelp/GUID-D93137A9-B374-4A24-8A4D-A66C9AC91265/EN-US/GUID-1E3CECCF-0343-431C-95F0-5716E0341C75.html).
- iOS 26 adds an explicit Live Activity route for retaining foreground Bluetooth privileges in the background, including unfiltered scans and duplicate advertisements. Older coalescing/service-filter restrictions remain relevant outside that route. This does not guarantee indefinite execution or identical Android observations. [Core Bluetooth](https://developer.apple.com/documentation/corebluetooth).
- Garmin's iOS Mobile SDK communicates with the watch independently after Garmin Connect handles initial device discovery; it is not identical to the Android relay architecture. Current SDK guide: `doc/docs/Core_Topics/Mobile_SDK_for_iOS.html` beneath the installed SDK.

## Feature-by-feature disposition

“Possible” below means a supported platform path, not implemented or physically verified Signal Station parity.

| Feature | iOS | Garmin face / watch app |
|---|---|---|
| HA, openHAB, Geepers catalogs/readings/actions | Possible with native networking, token storage and Home engine integration. No Matter controller or Zigbee coordinator needed. | Bounded requests to phone or hub. Faces show selected cached state; watch app owns detailed navigation. Existing faces have no Home transport. |
| Apple Home | Additional optional HomeKit connector is possible. Requires its own entitlement and authorization. It is separate from the three existing server connectors. | Consume the authorized phone's results; no direct Apple Home entitlement on Garmin. |
| Chat, selective evidence, conversations, capture retention, exports, local learning | Shared logic can be reused; persistence, file sharing, deletion and lifecycle must be ported and validated. Geepers chat is a separate implementation. | Short replies, saved prompts, history receipts and handoff are feasible. Phone retains full records, model credentials and context budgets. |
| Agent tools, scenes, exact action grants | Preserve native tool formats, bounded queries, source selection and shared execution checks. A model's prose is never a command. | Request stable IDs with bounded arguments and deduplication. Exact review in app or phone handoff; no truncated safety-critical review. |
| Scheduled captures / background Home events | Foreground refresh/subscription works. Background scheduling is OS-managed, not an exact one-minute timer or permanent socket. User-visible ongoing work needs the appropriate supported lifecycle. | Face background services are brief; temporal wakes are at least five minutes apart and services are terminated after 30 seconds. Event wakes are distinct from timed refresh. |
| Wi-Fi fingerprints | No general iOS Wi-Fi scan API. Current-network information is separately restricted. Android's nearby SSID/BSSID/channel survey cannot be promised. | No general Connect IQ Wi-Fi scanner identified. Communications is not access to a Wi-Fi inventory. |
| Bluetooth sightings / external sensors | Core Bluetooth provides permitted advertisements, RSSI, services and peripheral identifiers. These identifiers are not Android MAC addresses. iBeacon uses its own Core Location path. | BLE and ANT/ANT+ APIs exist for compatible app types and hardware; raw BLE is not a watch-face permission. Do not equate BLE with Zigbee or Matter support. |
| Location, nearby places, radio-based location | Core Location plus explicit network lookups are feasible. Nearby map lookup can port; Android Wi-Fi/cell evidence cannot simply be recreated. | An app can acquire GPS; a face cannot start location updates. Faces can use permitted cached location/weather data and must retain age and absence. |
| Phone/watch motion | Core Motion supports available accelerometer, gyroscope, magnetometer, attitude/gravity, pedometer and altimeter capabilities. Enumerate support rather than presenting Android's complete SensorManager list. | A watch app can use available sensor events and sampled motion. A face uses supported history/summary APIs rather than the live Sensor module. |
| Light, pressure, temperature, humidity, proximity | Pressure/altitude and proximity have platform-specific paths. Do not promise a general lux, ambient-temperature or humidity feed on iPhone. Battery thermal state is not room temperature. | Venu 2S has barometer, motion/compass and ambient-light hardware, but hardware presence is not an exposed field. Sensor.Info has no ambient-light field. Garmin describes ambient temperature via tempe; wrist/device temperature must not be labeled room temperature. |
| Health | HealthKit can supply authorized samples from existing sources: activity, sleep, heart rate and others. It does not make the iPhone a heart-rate or SpO2 sensor. Preserve source and measurement windows. | ActivityMonitor and SensorHistory expose device-dependent summaries/history including steps, calories, floors, heart rate, stress, Body Battery, oxygen saturation, pressure and temperature. Nullable/history-limited values are not zero or live measurements. |
| HRV, PPI, ECG, raw optical data | HealthKit HRV is SDNN; Android's imported RMSSD is a different metric. Apple Watch supplies some samples. Do not promise raw optical data or silently relabel algorithms. | Beat-to-beat interval API exists for supported sensor/app combinations. Do not promise built-in optical intervals, continuous PPG, ECG or complete sleep-stage export from that fact alone. Venu 2S has no ECG app in Garmin's product comparison. |
| Phone microphone / dictation | Supported with explicit recording permission and purpose text. Transcription requires the selected engine's permissions, availability and disclosure; on-device availability varies. | No documented public Connect IQ microphone/raw-recording/dictation API found in the installed or current public API. Venu 2S lacks the hardware in any event. Use phone input or saved prompts. |
| Speaker / spoken answers | Native speech synthesis and audio playback are possible; output alone does not require microphone permission. Handle audio route, interruption, Stop and silent/volume behavior. Existing Geepers voice uses web speech/media hooks, not a verified new native Signal audio adapter. | Attention tones are device/app-dependent, not speech. Media is an audio-content-provider facility, not a general face TTS API. Do not promise spoken replies from Venu 2S. |
| Wake phrase | Android currently has a user-started local Vosk microphone session, bounded to one hour, producing a reviewable draft. iOS needs a deliberate supported audio-session design; ordinary background tasks cannot implement an always-available system assistant. | No Venu 2S mic; no promised third-party Garmin wake phrase. |
| Camera/video/audio surveillance | Existing Signal observation sessions do not capture ambient audio or video. Adding camera images or sound measurements is a new source and consent flow. | Not part of the current port; hub camera data remains a separately authorized capability. |
| Phone battery, time, OS, network context | Basic status is possible; expose only what the public API returns. Cellular network/tower IDs and Android-style signal surveys are not portable by assumption. | Basic watch status is possible. This does not reveal the whole phone's sensor inventory. |

Platform references: [iOS Wi-Fi boundary](https://developer.apple.com/documentation/technotes/tn3111-ios-wifi-api-overview), [Core Motion](https://developer.apple.com/documentation/coremotion), [iOS background strategies](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app), [Garmin Sensor](https://developer.garmin.com/connect-iq/api-docs/Toybox/Sensor.html), [Sensor.Info](https://developer.garmin.com/connect-iq/api-docs/Toybox/Sensor/Info.html), [SensorHistory](https://developer.garmin.com/connect-iq/api-docs/Toybox/SensorHistory.html), [HeartRateData](https://developer.garmin.com/connect-iq/api-docs/Toybox/Sensor/HeartRateData.html), [Background](https://developer.garmin.com/connect-iq/api-docs/Toybox/Background.html), [Media](https://developer.garmin.com/connect-iq/api-docs/Toybox/Media.html), [Attention](https://developer.garmin.com/connect-iq/api-docs/Toybox/Attention.html).

## iOS faces, widgets and companion surfaces

If “iOS faces” means the existing Ambient Time display surfaces, its shared `ClocksCore` rendering can consume a bounded Home snapshot through `FaceEnvironment`; this is different from adding the full Signal notebook to that app. Home Screen/Lock Screen widgets and Apple Watch complications are also possible surfaces. App Intents support buttons/toggles on supported surfaces, but the same grant/confirmation checks must apply. WidgetKit refresh is budgeted and the extension is not continuously active. A face or widget must display freshness rather than imply a live hub stream. [Widget interaction](https://developer.apple.com/documentation/widgetkit/adding-interactivity-to-widgets-and-live-activities), [Widget refresh](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date).

Current Geepers iOS declares microphone and speech-purpose strings, but not Local Network, Bluetooth, Motion or HealthKit purposes/capabilities for this feature. Existing speech/media hooks are source evidence only; no new iPhone audio run occurred. Android Signal's inspected product code includes the local wake recognizer and watch dictation bridge; it does not establish a built-in phone text-to-speech reply player. Speaker hardware should not be described as an already shipped Signal feature.

## Permissions and data handling

| Access | Required boundary |
|---|---|
| iOS local hubs/discovery | Local Network purpose string/authorization, Bonjour service declarations if browsing, and ATS configuration plus the app's trusted-local connection policy. Local Network access and HTTPS policy are separate. Retain normal TLS validation and reject credential-forwarding redirects. |
| Bluetooth | Bluetooth purpose/authorization and supported background mode or explicit iOS 26 Live Activity path. Starting Home must not automatically enable radio scanning. |
| Location / motion | Location authorization and accuracy appropriate to the feature; Motion & Fitness purpose string for protected motion access. Ask when selected, not at upgrade. |
| HealthKit | Entitlement, read purpose and per-type authorization. Request writes only if implementing writes. Empty reads cannot reliably distinguish denial from no data; never claim “permission granted” from empty results. [Health authorization](https://developer.apple.com/documentation/healthkit/authorizing-access-to-health-data). |
| Microphone / speech | Microphone purpose string and explicit grant. Legacy Apple Speech recognition has separate authorization; local recognizers have their own availability requirements. Cloud dictation needs a clear outgoing-audio choice. [Recording permission](https://developer.apple.com/documentation/avfaudio/avaudioapplication/requestrecordpermission(completionhandler:)), [Speech](https://developer.apple.com/documentation/speech/recognizing-speech-in-live-audio), [Speech synthesis](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer). |
| Apple Home | HomeKit entitlement, purpose string and user authorization only when adding an Apple Home connection. REST access to HA/openHAB/Geepers does not by itself require HomeKit authorization. [HomeKit setup](https://developer.apple.com/documentation/homekit/enabling-homekit-in-your-app). |
| Garmin | Manifest permissions are constrained by application type: Home networking needs Communications, face networking needs Background, history needs SensorHistory, live sensor app access needs Sensor, optional BLE/ANT/Positioning and complication permissions are separate. No extra permission can turn a face into an unrestricted app. |
| Sending context / controlling devices | OS access, selected context sent to a model, and permission to execute an exact device action are three separate choices. Preserve disabled-by-default Home access, exact grants, two-minute one-use confirmations, durable intent IDs, cancellation, unknown outcomes and no mutation retries. |

Further precision: [HealthKit SDNN](https://developer.apple.com/documentation/healthkit/hkquantitytypeidentifier/heartratevariabilitysdnn) and [ATS local networking](https://developer.apple.com/documentation/bundleresources/information-property-list/nsapptransportsecurity/nsallowslocalnetworking). Newer documentation can describe APIs above the intended deployment target; check availability rather than copying a sample wholesale.

## Key implementation files

- `pebble-inspector-companion/signal/build.gradle.kts`: existing shared and iOS target definitions.
- `signal/src/commonMain/.../SignalModels.kt`, `SignalProviders.kt`, `SignalAgentTools.kt`: observation model, provider support and native tools.
- `SignalHomeFactory.kt`, `SignalHomeEngine.kt`, connector classes: shared catalog/action semantics and grants.
- `signal/src/androidMain/.../AndroidSignalStation.kt`, `SignalCollectors.kt`, `SignalStore.kt`, `SignalHealthConnect.kt`: Android-specific work that needs real iOS equivalents.
- `SignalWakeService.kt`: bounded local microphone/wake-phrase implementation.
- `pebble/src/iosMain/.../watchModule.ios.kt`: upstream Pebble services exist; SignalStation is not registered.
- `geepers-app/ios/App/App/Info.plist`, `FoundationModelsPlugin.swift`, `GeepersTools.swift`: native bridge and current iOS permissions/tools.
- `geepers-app/client/src/hooks/useSpeechRecognition.ts`, `useServerSTT.ts`, `useTTS.ts`: existing voice paths.
- `ambient-time/appstores/garmin/faces/*/manifest.xml`, `supported-devices.json`, `circular-batch.json`, `DEVICE-EVIDENCE.md`: app type, actual target sets and dated hardware evidence.

## Implementation gates

Choose the iOS shell before implementing native storage, collectors, audio and
lifecycle adapters. Integrate a Garmin transport separately; preserve existing
pairings. Start with the named iPhone/Venu 2S pair and audit voice-capable watches
individually. No background cadence or sensor source is guaranteed by a compile.

Validate denied/revoked permissions, missing sensors, stale readings, empty
HealthKit reads, app suspension, watch reconnects, audio interruptions, exact
review, duplicate packets and unknown mutation outcomes. Keep OS permission,
model context disclosure and exact action grants separate. Models cannot create
or broaden grants. The Android/Pebble implementation remains available while
these ports are deferred and LAN-device work resumes.
