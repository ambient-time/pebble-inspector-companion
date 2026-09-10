# Signal Station on your phone

By Luke Steuber.

Signal Station saves observations about your surroundings and helps you make
sense of them over time. Your phone is enough. A Pebble watch can collect a
reading or give quick feedback through the Pebble app you already use.

Start on **Today**. Capture once to save a moment, or choose **Observe for a
while** for a 15-minute, one-hour or four-hour session. Select the sources you
want for that session. Android asks for the permissions those sources need.
You can decline a permission and continue using the other sources.

A session shows an ongoing notification with **Stop**. It attempts a sample
about every five minutes; Wi-Fi and weather checks are spaced at least 30
minutes apart. Android can delay or deny a reading. A missing measurement is
shown as missing, never treated as zero. Sessions pause below 15% battery while
unplugged, with low storage, or when the phone is too warm. They finish at their
chosen end time and do not restart after Android stops the app. Wake listening
is a separate choice; observation sessions do not record ambient audio or video.

**Activity** contains saved captures, nearby checks, sessions and reports. Older
activity loads in pages. Search checks saved history on the phone. You can
inspect the original readings and their measurement dates before asking a
provider to analyze them. JSON exports contain records, memories and sessions;
Markdown exports are for reading. Exports are temporary share files, removed
from the app cache after ten minutes or when affected history is deleted.

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

In **Ask**, type or dictate a question and review the suggested memories beside
Send. At most five relevant, confirmed memories are selected locally. Switch
**Use memory** off to exclude them. Disabled sources and memories awaiting
review are excluded. Sending uses your chosen provider and key. Continuing a
conversation can include its eligible recent messages; searching history or
attaching a report includes the chosen evidence. Capturing, importing health,
learning and reviewing memories do not call a language model.

For **Health Connect**, enable individual data types in Settings, then review
health access. The app can read steps, distance, active and total calories,
exercise sessions, sleep sessions and heart-rate intervals. Choose seven,
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
