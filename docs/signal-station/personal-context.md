# Signal Station on your phone

By Luke Steuber.

Signal Station saves observations about your surroundings and helps you make
sense of them over time. Your phone is enough. A Pebble watch can collect a
reading or give quick feedback through the Pebble app you already use.

Start on **Today**. Capture once to save a moment, or choose **Observe for a
while** for a 15-minute, one-hour or four-hour session. Select the sources you
want for that session. Android asks for the permissions those sources need.
You can decline a permission and continue using the other sources.

A session shows an ongoing notification with **Stop**. Standard mode samples about every five minutes at rest, with bounded follow-up
after a selected motion or network change. Battery saver uses a slower schedule.
Android-completed Wi-Fi scans can be reused; active Wi-Fi and weather requests
are spaced at least 30 minutes apart. Android can delay or deny a reading. A missing measurement is
shown as missing, never treated as zero. Sessions pause below 15% battery while
unplugged, with low storage, or when the phone is too warm. They finish at their
chosen end time and do not restart after Android stops the app. Wake listening
is a separate choice; observation sessions do not record ambient audio or video.

**Activity** contains saved captures, nearby checks, sessions and reports. Older
activity loads in pages. Search checks saved history on the phone. You can
inspect the original readings and their measurement dates before asking a
provider to analyze them. JSON exports contain records, memories, sessions and saved questions;
Markdown exports are for reading. CSV exports contain numeric values, units,
measurement times, quality and provenance. Exports include only evidence whose
sources remain enabled; page filters do not limit the export. Exports are temporary share files, removed
from the app cache after ten minutes or when affected history is deleted.

**Nearby** shows the saved wireless environment, cellular context, phone location
and optional map lookups. Wi-Fi channel counts describe received advertisements,
not channel utilization. An advertised open network does not establish internet
access. Cellular observations include technology, signal and registered or
neighbor cells; tower identifiers have their own switch. No phone number or
subscriber identifier is collected.

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
Sending any saved context to a language model is a separate action in Ask.

In a saved record, **What changed?** compares compatible earlier readings.
**Numeric history** shows retained measurements as individual dots, with the
same values in a table and links to evidence. Gaps stay gaps. Sensor readings
include sample counts, actual measurement windows and accuracy when available.
Step deltas use two cumulative readings within one phone boot, not daily totals.

Turn on learning when you want Signal Station to look for patterns. **Memory**
shows proposals, confirmed observations and personal notes. A proposal explains
which records support it. Confirm its wording, correct it, reject it, or leave
it for later. No model writes memories automatically. Proposals are limited to
three new patterns a day, using recent original observations:

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

In **Ask**, type or dictate a question and choose **Review context**. This step
runs locally, even without a provider key. It shows the record and memory
counts, message size, and the exact text prepared for your provider. History
uses a ranked selection of at most 30 matching records. Date and source
restrictions remove broader prose from the selected excerpts. Size limits can
omit records; the review tells you when that happens. The original records
remain available in Activity.

Choose **Send question** only after reviewing. Sending uses your chosen provider
and key, and may use credits. If evidence is deleted or changes before Send,
Signal Station asks you to review again. It keeps your draft after errors and
does not retry automatically. At most five confirmed memories are selected
locally; **Use memory** excludes them when switched off.

**Save question** gives a question a name. Find it under **Saved questions** on
Today. Opening one prepares an editable draft: it does not collect, enable a
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
from Activity when you want them removed from this phone too.

Deletion starts with a preview. Removing evidence also removes dependent
memories, corrections and reports. You can explicitly keep confirmed wording
as a new personal note, without its former evidence. Forgetting a memory also
removes reports that used it and suppresses that pattern from being proposed
again. Provider keys and unrelated personal notes stay in place. Nothing here
changes pairing, firmware or watch settings.

This is a development preview. Android storage, UI, mapping and service checks
run on an emulator; physical Android battery behavior, Health Connect account
access and the Pebble connection still need device testing. The earlier lab
companion remains withdrawn while its reported watch settings reset is
unresolved. Use the separate Signal Station package and the existing Pebble app.
