# Signal Station: direction and reuse

By Luke Steuber. Reviewed October 5, 2026.

Signal Station is intended to combine conversation, speech, sensing and optional
home/device tools in one Android/Pebble experience. Generic integrations must
work with user-chosen endpoints and devices, not a particular household. This
direction supersedes the earlier sensing-only reuse recommendation. The table
below describes current source and dated evidence, not promised feature parity.

## Current capability and evidence

| Area | Current state | Remaining boundary |
| --- | --- | --- |
| Reviewed conversation | Observed: phone and wrist questions, bounded context, one-use consent, saved replies and phone handoff | Dictation depends on watch and host; a transport acknowledgement is not wearer acceptance |
| Speech | Observed: phone wake-listening and watch dictation input; replies are text-only | No Signal phone TTS, watch speech, voice selection or Auto-read implementation |
| Home and environment | Observed: Home Assistant, openHAB, Geepers, public feeds and read-only MQTT 5 over WebSockets | Exact topics; no raw MQTT TCP, MQTT 3, command publication, direct ESPHome client or firmware provisioning |
| Local answers | Observed: Gemini Nano and Gemma adapters use the same review path | Nano needs the app on screen; Gemma full download/import/inference remains untested; local Home tool execution is disabled |
| Private delivery | Measured in the [build 24 receipt](private-build24-2026-10-05.md): matching installed APKs on both Pixels; 381 shared tests and 15 local-model tests passed | No fresh phone UI acceptance from locked/dozing startup checks; physical 2 SE favorites and current TalkBack journey remain open |
| Distribution | Observed in dated receipts: October 3 testing downloads are Android 20 / Pebble 1.8.1; published Store watch record is 1.7.1 | Build 24 is private; this documentation change neither rechecks the live Store nor releases a package |

Watch preview 1.8.1 remains unchanged. Dick Tracy's separate physical Time 2
home-screen check is not Signal acceptance. Text and favorites remain useful
without a speaker; any future audio targeting must be capability- and
firmware-checked, not inferred from a product family's name.

## Architecture and navigation

The Android shell in `signalApp/` uses shared Compose screens, provider adapters,
Home models and permission logic in `signal/`. Android adapters own collection,
encrypted storage, foreground/background coordination and the chosen Pebble host
connection. The watch sends bounded requests and opaque review identifiers; the
phone retains credentials, full replies and execution authority. The existing
Pebble app continues to own pairing. An iOS compilation target is not an iOS
Signal product; Garmin transport is also unimplemented.

Key implementation locations under `signal/src/`:

- `androidMain/kotlin/coredevices/pebble/signal/AndroidSignalStation.kt`: reviewed
  question lifecycle, watch dispatch and orchestration.
- `commonMain/kotlin/coredevices/pebble/signal/SignalProviders.kt`: provider
  responses and rejection of unsupported or failed tool completions.
- `commonMain/kotlin/coredevices/pebble/signal/SignalHomeEngine.kt` and
  `SignalHomeConnectors.kt`: exact-action permissions and existing controller adapters.
- `commonMain/kotlin/coredevices/pebble/signal/SignalMqttConnector.kt` and
  `SignalMqttWebSocketEngine.kt`: bounded MQTT readings and transport.
- `androidMain/kotlin/coredevices/pebble/signal/SignalWakeService.kt`: explicit
  phone microphone session; not a reply-audio engine.

Start with [development](development.md), [environmental readings](environmental-readings.md),
[Home connections](home-connections.md), [local models](local-models.md) and the
[platform audit](platform-capabilities.md). Preserve dated acceptance records
rather than rewriting past results into current success claims.

## Reuse now

Decision: reuse component behavior and tests inside Signal's existing consent and
execution flow. Do not create a parallel conversation dispatcher. No source was
ported as part of this review.

| Candidate, inspected October 5 | Fit and maintenance evidence | Limits |
| --- | --- | --- |
| Dick Tracy `Speech.kt`, `SelectedSpeech.kt`, `SpeechPolicy` and speech-session tests at [source revision 9de9a6f](https://github.com/lukeslp/gadget-watch/tree/9de9a6f/android/app/src) | Local MIT source by Luke Steuber; active October 5 history. Both Android shells use minimum API 26, compile SDK 37 and target SDK 36 | Adapt settings, lifecycle and explicit initialization to Signal conventions; do not copy its constructor work or claim accessibility parity |
| Dick Tracy `WatchAudioSender.kt`, `AudioPcm.kt` and corresponding tests at the same revision | Existing bounded transfer, cancellation and playback-receipt implementation; use after a phone-speech slice | Different watch protocol and app identity; no drop-in wire compatibility or Signal speaker acceptance |
| Signal Home/MQTT adapters and permission tests at companion `1e64ae35` | Already integrated with credentials, freshness and explicit actions; supported by the build 24 suite | Generic ESP32 sensors still need a compatible controller or broker configuration and a real interoperability check |
| Existing `localmodels/` and provider guards | Already reused; provenance is in the [reuse receipt](dick-tracy-reuse-2026-10-05.md) | No second port needed; no new model download authorized by this review |

Preserve MIT attribution on original components and all dependency notices. The
combined Android fork retains its [licensing scope](../../LICENSING.md), including
inherited GPLv3 obligations; it is not an MIT-only application.

## Learn from

- Android's [TextToSpeech contract](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
  requires completed initialization and resource shutdown, and documents the TTS
  service query for newer targets. Keep readable answers available when speech
  fails. An installed voice is not proof of audible output.
- ESPHome's [native API](https://esphome.io/components/api/) is a custom
  protobuf-over-TCP protocol with encryption support and a documented Python
  client. Existing Home Assistant integration is the first reuse route to assess.
  A direct Kotlin implementation needs its own compatibility and security review.
- ESPHome's [web API](https://esphome.io/web-api/) offers state reads and event
  updates where that component is enabled. Current documentation describes
  identifier differences across firmware generations; a future adapter needs
  versioned fixtures, not hard-coded sensor names.
- ESPHome's [MQTT component](https://esphome.io/components/mqtt/) documents retained
  discovery and availability messages. Signal currently subscribes to exact
  selected readings, not that discovery catalog. Broker receipt time must not
  become an invented sensor measurement time.

These primary references were checked October 5. They demonstrate documented
interfaces, not a tested ESP32-to-Signal installation. No new external client
library was selected or licensed for copying; that check is not applicable to
this documentation-only review. A maintained Kotlin native-API client remains
an unverified lead. Formal accessibility conformance is unverified: the existing
TalkBack gate stays open, and future Listen/Stop/status controls need their own
assistive-technology and audio-interruption checks. Android's
[Compose semantics guidance](https://developer.android.com/develop/ui/compose/accessibility/semantics)
provides the implementation reference for control roles and announced state;
it does not establish a completed accessibility evaluation.

## Conventional vs niche

The conventional first route uses Android's platform TTS and the existing
controller/broker integrations. Their documented APIs and local implementations
avoid another runtime. Direct ESPHome native-protocol support and Pebble buffered
speech are specialized alternatives with additional lifecycle, authentication and
hardware obligations. Neither should block text-only use.

## Considered and skipped

- Reviving the September streamed-speech experiment unchanged: its physical
  speech-quality gate failed. Preserve it as historical evidence.
- Importing Dick Tracy's complete skills/MCP/host execution stack wholesale:
  likely duplicates Signal's review and authority boundaries. Component or
  adapter reuse remains an option, not a rejection of the broader product.
- A custom household-specific ESP32 gateway: conflicts with generic support and
  bypasses already available Home/MQTT contracts.
- Automatic topic discovery, MQTT command publication or a permanent listener:
  outside the current read-only/foreground contract; each needs a deliberate
  permission and lifecycle design.

## Gap and proposed build order

Planned, not implemented: first add explicit phone Listen/Stop for saved answers
using the reusable offline speech engine, without making another model request.
Then assess watch playback and optional hosted voices with separate destination
and disclosure controls. Reuse exact reviewed snapshots and the existing action
engine when considering richer tools; spoken prose never becomes a command.

In parallel planning, validate one generic ESP32 reading through a supported
controller or MQTT 5 WebSocket broker before choosing a direct ESPHome adapter.
Keep connect, capture, model sharing and action grants independent. The phone's
battery temperature and outdoor weather cannot substitute for absent ambient
temperature hardware.

Release evidence still needed: physical favorites, current TalkBack recovery,
authenticated real-broker/reconnect behavior, Gemma transfer/import/inference
and sustained background/battery checks. Feature proposals do not close these
gaps or authorize public release.

## Ranked opportunities and blind spots

1. **Opportunity — one reviewed turn, multiple outputs (Inferred).** Luke's
   broader product direction plus the existing consent engine favor adding
   speech as an output of a saved answer, rather than a second conversation or
   permission system. This reduces divergence as tools are added.
2. **Blind spot — speaking changes disclosure (Inferred).** Dick Tracy's hosted
   voice path sends answer text to another service. A response can contain
   selected private readings. Model-send consent cannot silently authorize a
   new speech service or unexpected speaker output; offline voice and explicit
   destination are useful first boundaries.
3. **Opportunity — integrate the sensor contract, not the household (Inferred).**
   The existing Home/MQTT adapters and source/time/unit fields already cover
   much of the generic ESP32 route. A real retained/stale/missing-reading check
   can expose the actual gap before another protocol client is built.

Next experiment: use Dick Tracy's existing offline phone Listen with one
synthetic saved answer containing a fresh outdoor reading, a stale room reading
and missing humidity. Observe audibility, retained qualifications and Stop,
without a model request, household read or configuration change. This is a
proposed reuse probe, not Signal speech acceptance or an experiment run here.

Handoff: Discuss the bounded phone-speech plan and its privacy/lifecycle checks;
then Compose the selected slice. This wrap changes documentation only.
