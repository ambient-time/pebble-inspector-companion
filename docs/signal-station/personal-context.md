# Signal Station on your phone

By Luke Steuber.

Signal Station is a field notebook for the signals around you. Save selected
readings, compare observations over time, and discuss them with your chosen
language model. You can also chat without taking a capture. Your phone is enough. The optional Pebble preview uses
the Pebble app you already have. The watch app is still in testing; a Time 2
and Pixel 9a have completed a physical capture trial.

## Home connections and platform support

Android build 17 and Pebble 1.7.1 add optional
[Home connections, controls and chat access](home-connections.md). Hold Down
on the watch home screen for favorites chosen on the phone. Home readings,
shortcuts and chat access are separate selections, all with no automatic action
grant. The earlier physical capture trial does not validate this new Home path;
see the [September 15 release record](home-release-2026-09-15.md).

Signal Station currently ships for Android and Pebble. The
[iOS/Garmin capability matrix](platform-capabilities.md) describes future work,
including sensor, audio, permission and background limits.

## Install the Android app

Open the [Signal Station download page](https://dr.eamer.dev/downloads/apps/signal-station/)
on your Android phone and tap **Download for Android**. Open the downloaded APK,
allow your browser to install apps if Android asks, then tap **Install**. Install
over an existing Signal Station preview to keep its saved history. Open Signal
Station and choose **Ask → Set up answers** to add your provider, model and key.
You can chat before choosing any sensors or connecting a watch.

For wrist access, keep your usual Pebble app installed and your watch paired
there. Download the Pebble test build from the same page and open its PBW file
with your Pebble app. Follow **Optional Pebble connection** below to connect
Signal Station. Watch use needs both Android apps.

## Chat on your phone or Pebble

Open **Ask** to start a conversation. Type a general question, brainstorm an idea,
ask for an explanation, or discuss something you have saved. For example: “Help
me think through a weekend project,” “Explain that more simply,” or, with two
captures attached, “What changed between these stops?” You do not have to enable
sensors or record a session to use chat.

Choose your provider and model in Settings and add your own key. Supported
connections include OpenAI, Anthropic, Gemini, OpenRouter and a compatible
custom HTTPS chat endpoint. Provider usage charges apply. **Review question**
shows the outgoing text, recent conversation turns, selected readings and any
selected memories before **Send**. Removing an attachment changes the next
request without deleting its saved capture. Full replies render as Markdown.

Continue in the same conversation to ask follow-up questions. Recent eligible
turns and attached evidence provide context within the request budget; very long
conversations are not included in full. Open a saved conversation to continue it,
or start a new one for a different subject. Save a useful question to reuse it.
Source, provider or evidence changes can require a fresh review.

On a supported Pebble, press the **middle right button (Select)** for **Ask**.
Dictate a question, confirm the transcript when that setting is enabled, and
read the short reply on your wrist. Up/Down scroll; Back cancels or returns home.
Hold **Select** while reading a reply, then open Signal Station on your phone
and tap **Open full reply**. This opens that exact saved reply, even if a newer
one exists, without replacing an unsent phone draft. Choose **Resume conversation**
when you want to continue it. The handoff is kept while the phone app is running;
if the app restarts or the reply is no longer available, find it in **History**.
The watch backlight stays on while the app is active and returns to automatic
behavior when it loses focus or closes.

The full answer and conversation are on the phone. Watch dictation depends on
the watch and your existing Pebble app. Pressing Ask is distinct from Capture:
use the phone's attachment controls when you want to choose saved readings for a
question. Phone chat works independently of the watch.

## Capture and record

Start on **Now**. **Capture now** saves a moment and attaches it in Ask.
Choose **Ask about this** on the saved observation to open an editable question.
The capture summary distinguishes readings that were fresh when collected,
older or cached readings, estimates, partial results and missing sources. Imported
period records retain their measurement dates. Inspect readings for exact times;
a saved capture is not a live measurement.
A local capture has a separate allowance from a chat request: up to 2,048
readings and 1 MiB of serialized reading data. Broad captures can therefore keep
more detail on the phone while chat uses a smaller reviewed excerpt. When space runs short, the app keeps
motion variability before optional axis detail.
The watch receives a short receipt, not the complete capture. A “phone capture
limit” notice means some readings were not saved; a scan-retention notice means
additional radio results exceeded the collector's bounded scan. Inspect source
coverage in phone History. Increasing local retention does not remove radio
scan limits or establish complete coverage.

Use **Record over time** for a finite session or **Ongoing · until stopped**.
Choose a fixed interval from 1 to 1,440 minutes, or use an adaptive schedule.
Select the sources for that session; expanding its source list does not enable
new sources. Android asks for the permissions those sources need.

A session shows an ongoing notification with **Stop**. Fixed mode waits for the
chosen interval after a capture and any scheduled analysis finish. Adaptive mode
usually waits five minutes, with shorter follow-up after selected motion or
network changes. Adaptive battery saver usually waits fifteen minutes. Android
may throttle Wi-Fi requests; cached results keep their actual measurement times.
Weather refreshes at most every thirty minutes.

**Local change analysis** saves comparisons with the preceding capture in the
same session. **Scheduled model analysis** is a separate opt-in and has its own
frequency in saved captures. Review the provider, model and selected sources
before starting it. It sends only the two latest session captures, with bounded
readings and explicit omissions; provider charges may apply. Model analysis is
off by default. Its reports keep links to both source captures.

The app labels missing measurements instead of displaying zero. Sessions pause below 15%
battery while unplugged, with low storage, or when the phone is too warm. Finite
sessions end at their chosen time; ongoing sessions have no scheduled expiration.
Neither restarts after Android stops the app. Changing collection settings stops
the current session so its choices can be reviewed again. Wake listening is a
separate choice; observation sessions do not record ambient audio or video.

**History** contains saved captures, nearby checks, recording sessions and reports.
Use **Today**, **Last 7 days** or **Last 30 days**, or filter by type, capture
date, source or text. **More actions** holds export and deletion. Dates describe when a
record was captured or saved; individual readings retain their own measurement
dates. Results come from the full saved history, including older pages.

**Ask about these results**, **Compare selected** and **Export these results**
use the same selection shown on the page. Review shows which evidence fits in a
model request and what was omitted. A narrower source selection excludes broader
prose that could disclose other readings. Explicitly selected captures and saved
questions retain their evidence choices; changing sources or deleting evidence
requires a fresh review.

Scoped JSON and Markdown exports contain the selected evidence. CSV contains
numeric values, units, measurement times, quality and provenance. **Export all
data** in Storage is a separate action that includes eligible records, memories,
sessions and saved questions. Exports remain temporary share files, removed from
the app cache after ten minutes or when affected history is deleted.

In Ask, an empty question offers starting prompts when evidence is attached.
A question with no readings can still be sent; **Add evidence** lets you choose
readings first. Suggestions prepare a draft and never send it.

Open **Around me** from Now. **Save snapshot** saves live readings to History
without changing an existing question or its attachments. **Ask** saves a
snapshot and prepares a question for review. **Signals** shows live radios and separately labeled
saved wireless and cellular context. **Places** holds phone location, map lookups
and familiar places. **My devices** holds enrolled devices and their saved sightings.
Section headers and named-device rows expand to show details and controls.
Tinted radio rows have repeated scan sightings; the written counts explain their
scope. A broad device type appears when its advertisement supports one, with
unknown types kept explicit. Wi-Fi filters distinguish no-password advertisements,
credentials and unknown access. Browser sign-in is unknown for nearby networks;
the phone's connected-network evidence is shown separately with its capture time.
Wi-Fi channel counts describe received advertisements,
not channel utilization. An advertised open network does not establish internet
access. Cellular observations include technology, signal and registered or
neighbor cells; tower identifiers have their own switch. The app does not collect phone numbers or
subscriber identifiers.

For nearby businesses or addresses, enable **Nearby map places**, capture a
phone location, then choose a lookup. Review the destination and the exact
outgoing data before **Send lookup**. The default OpenStreetMap/Overpass service
receives the location rounded to four decimal places and a bounded radius.
Results are candidates, with attribution and age. Select one to edit and save
as a local place; a nearby business is not proof that you are inside it.

**Radio location estimate** is a separate choice. The default experimental
beaconDB service can estimate location from selected Wi-Fi or cellular evidence.
The review shows identifiers, network names and signal information being sent.
Hidden and `_nomap` Wi-Fi networks are excluded. Phone coordinates, Bluetooth
and health are not part of this lookup. The service sees the connection's IP
address; the request disables IP location fallback. Returned uncertainty stays
visible and the estimate never replaces the phone fix. Lookups can fail or
return no match. Original captures stay saved.

Lookup services and radius are editable under the collapsed service settings.
Each lookup requires a reviewed send; timed sessions do not perform these
lookups automatically. Recent results may be reused from the encrypted local
cache. Deleting their source evidence also deletes dependent results and caches.
Send saved context through a reviewed question in Ask, or explicitly enable
scheduled model analysis for a recording session.

In a saved record, **What changed?** compares compatible earlier readings.
**Numeric history** shows retained measurements as individual dots, with the
same values in a table and links to evidence. Gaps stay gaps. Sensor readings
include sample counts, actual measurement windows and accuracy when available.
Step deltas use two cumulative readings within one phone boot, not daily totals.

Turn on learning when you want Signal Station to look for patterns. **Patterns** in History
shows proposals, confirmed observations and personal notes. A proposal explains
which records support it. Confirm its wording, correct it, reject it, or leave
it for later. No model writes memories automatically. The app proposes at most
three new patterns a day from recent original observations:

- A saved place and an enrolled device observed together in at least three
  separate sessions across two days.
- A saved place recurring in a two-hour window across at least five sampled
  days spanning a week, with consistent inside-boundary observations.
- A numerical baseline with at least ten comparable measurements across five
  days. Different origins, units and measurement periods stay separate.

Learning keeps compact hourly samples from the past 28 days; full original
history remains until you delete it. Sampling cannot establish where a person
was during unobserved time. Health patterns are descriptive observations, not
health assessments. Corrected wording stays accepted until the underlying
pattern changes.

In **Ask**, type or dictate a question and choose **Review question**. This step
runs locally, even without a provider key. It shows the record and memory
counts, retained readings, omitted readings, earlier conversation turns and the
exact text prepared for your provider. Large captures keep their identity and
a bounded selection of readings from their sources. A selected capture is never
silently dropped because it is too large; if no safe excerpt fits, preparation
stops and explains how to narrow the selection. The original records remain
available in History.

Open the **Evidence** summary to see each observation’s time and coverage.
**Inspect** opens its original readings; **Remove observation** detaches it without deleting the capture or
starting a new conversation. Attachments remain available for follow-up questions
and retries. Reopening a conversation restores its linked evidence. Source and
deletion checks apply again before sending.

**Ask about this**, comparing captures, and **Capture and prepare question** all
open a draft. They do not contact a model until you review and choose Send.
General history questions use a ranked selection of at most 30 records.
Questions opened from filtered History use that selection, with visible omissions
when a request limit is reached. Source restrictions omit broader prose from
the selected excerpts; unselected conversation turns and memories stay out.

Choose **Send question** only after reviewing. Sending uses your chosen provider
and key, and may use credits. If evidence is deleted or changes before Send,
Signal Station asks you to review again. It keeps your draft after errors and
does not retry automatically. For ordinary questions, at most five confirmed
memories are selected locally; **Use memory** excludes them when switched off.

**Save question** gives a question a name. Find it under **Saved questions** in
Ask. Opening one prepares an editable draft: it does not collect, enable a
source, ask for a permission, or contact a provider. Saved source choices and
attachments are resolved against current data. Disabled or deleted selections
need your attention. **Use current context / remove saved attachments** lets
you deliberately revise those choices. **Save changes** updates the recipe;
**Save as new** keeps both. Deleting a recipe leaves its evidence alone.

In Settings, **Check answer setup** checks local storage and saved configuration.
**Test with provider** sends a small test question and may use credits. Neither
runs a sensor capture. A shared diagnostic report contains only the build,
stage, result, elapsed time, message size, source outcome counts and scheduler
state. It excludes your questions,
answers, keys, health readings, coordinates and radio identifiers.

For **Health Connect**, enable individual data types in Settings, then review
health access. The app can read steps, distance, active and total calories,
exercise sessions, sleep stages, heart-rate samples, resting heart rate and
heart-rate variability (RMSSD). Choose seven,
30 or 90 days; older history and background reads require separate Android
permissions when supported. Import reads only granted types. It preserves the
origin and measurement interval, handles updates and deletions, and never adds
overlapping origins together. Pebble health and Health Connect are separate
sources. Revoking access in Android stops new reads; delete imported copies
from History when you want them removed from this phone too.

Deletion starts with a preview. Removing evidence also removes dependent
memories, corrections and reports. You can explicitly keep confirmed wording
as a new personal note, without its former evidence. Forgetting a memory also
removes reports that used it and suppresses that pattern from being proposed
again. Provider keys and unrelated personal notes stay in place. Nothing here
changes pairing, firmware or watch settings.

## Optional Pebble connection

Keep using the Pebble app that already manages your watch. In Signal Station,
open **Settings → Connected devices → Watch connection**. Choose **Use Pebble**
(or the name of your existing compatible app), select your connected watch,
then tap **Check connection**. The chooser shows the selected app and how many
watches it reports; **Open Pebble** takes you there if a watch is disconnected.
“Watch acknowledged the connection” confirms a message reached the watch.

On the watch's home screen, **Up** saves selected readings, **Select** starts
dictation when the watch and Pebble service support it, and **Down** shows recent
saved history. Up and Down need no provider key. Within a report, Up and Down
scroll; Back returns home or cancels an active request. Choose individual watch
and health sources on the phone before capturing. When the phone is in the
background, restricted phone sources are marked unavailable rather than silently
reusing old readings. Watch readings can still be saved through the host link.

The stock Pebble app, standalone Android app, and Diorite emulator have completed
connection, button capture, background capture, history and app-restart checks.
These are emulator results. The separate watch app is available from the download
page and is still in testing. A Time 2 and Pixel 9a have also completed a physical
capture trial on an earlier build. As checked September 15, the public Pebble
Store listing serves version 1.7.1. The new Home flow still needs a physical run.
The download page also offers the watch package directly. Listing availability
does not establish installation on every supported watch.

This is a development preview. Android storage, UI, mapping and service checks
run on an emulator; physical Android battery behavior, Health Connect account
access and the Pebble connection still need device testing. The earlier lab
companion remains withdrawn while its reported watch settings reset is
unresolved. Use the separate Signal Station package and the existing Pebble app.


## Live signals around you

Open **Around me → Signals** on Now, choose Bluetooth signal strength or Wi-Fi signal and
frequency in Sources, review radio permissions, then tap **Start scanning**.
The view groups observations into strong (at least −60 dBm), medium (−75 to
−61 dBm), and faint (below −75 dBm) bands. Tap an observation for its recent
strength samples, age, advertised details and a label for this scan session.
Names, identifiers and Bluetooth services each retain their own source switch.
A session label is included in a saved snapshot only when that radio's names
source is enabled. It does not enroll a device for future recognition.

This is useful for comparing signal strength while placing your own beacon,
seeing which access points are visible, and saving a scene to compare later.
Bluetooth counts are advertisers and Wi-Fi counts are access points. Silent
phones are invisible, one device can advertise several identities, and changing
addresses can appear as new observations. Cellular context describes network
cells, not nearby handsets. No phone count, people count, bearing or distance
is established by this view.

Collection stops on leaving Signals, switching Around me tabs, backgrounding the app, changing sources,
or after five minutes. Bluetooth uses bounded scan windows. Wi-Fi requests a
new scan at most once every 35 seconds and shows available cached results in
between; Android can throttle or deny updates. Fresh readings are at most 15
seconds old. Older readings fade and disappear after 60 seconds. Repeated
cached readings do not create new samples. The view retains up to 128 entries
and twelve recent strengths per entry; it is not a complete radio census.
An open detail keeps the inspected values until you close it. Its age stays
visible, and fresh-only actions become unavailable when it expires.

Nothing is saved or sent automatically. **Save snapshot** freezes fresh readings
into local history. **Ask about this scene** saves and attaches those readings
to a draft, then opens Ask. Review and Send remain separate actions. Snapshots
include measurement times and coverage; stale readings are excluded. Paired
watch behavior is unchanged. Cooperative ranging and optional ESP32 positioning
are future work and are not simulated by the strength bands.


## Moving between tasks

Now, Ask and History stay available at the bottom of the screen. Back returns to
the task you came from. Changing sources or setting up answers preserves your
question and attachments. In Ask, the typing area stays separate from the
scrolling conversation; open the attachment summary to inspect evidence before
Review and Send. Saved questions live in Ask. Recordings and Patterns are in
History. Presets sit with collection choices, and the field trial is under
Record over time → Advanced.

First use offers Capture now, Around me or Ask a question. You can also skip
setup and explore. Phone basics selects battery and time; Radio signals selects
Bluetooth and Wi-Fi strength. Review a preset before applying it. It neither
collects nor grants access. Permissions appear for the chosen task, and declining
one leaves other available sources usable. Live collection does not restart when
you return from settings or permissions: choose Start when ready.
