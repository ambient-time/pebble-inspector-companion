# Capture retention and chat documentation

By Luke Steuber. September 14, 2026.

Build 15 addresses the phone-generated “readings omitted by the size limit”
receipt shown after watch Capture. Manual captures and recording-session samples
now use a local allowance of 2,048 readings / 1 MiB of serialized reading data.
Chat evidence keeps its separate, smaller request budget. This changes how much
selected data stays on the phone, without enabling sources or increasing model
request limits. Larger saved captures consume more local storage; no automatic
history deletion was added.

A synthetic 1,360-reading capture previously retained 200. It now retains all
1,360, survives encrypted persistence and paging, and remains intact when chat
prepares an excerpt below 64 KiB. When any budget does require trimming, motion
magnitude variability and mean precede optional axis detail. Source fairness
and omission accounting remain in place.

The receipt distinguishes readings excluded by the phone's capture limit from
additional radio results excluded during bounded scanning. Those scan limits
still exist. The phone's History shows source coverage; the Pebble receives a
short receipt, not the entire record. Successful scheduled captures also refresh
the top-level status instead of leaving “Starting…” visible.

The README, public user guide, watch README and download-page copy now explain
chat as a primary use: ordinary questions without sensor setup, follow-up turns,
saved conversations, optional readings/history/memories, review before phone
Send, chosen providers, and supported watch dictation with full answers on the
phone. Chat examples distinguish watch Ask from manually selecting attachments
on the phone.

Validation: 217 host tests passed, six existing skips, no failures. Android
assembly and lint passed. Nine emulator integration tests passed, including the
new encrypted broad-capture round-trip and existing question, observation and
scheduled-analysis tests. No live provider request or new physical-watch capture
was part of these checks. The Pebble binary remains 1.6.4. Publication is recorded
separately in the release toolkit's build 15 receipt.

Raw test output is outside Git under
`/Volumes/Galactus/drummer/signal-station/capture-chat-20260914/` and adjacent
`capture-budget-*-20260914.log` files. No device readings are in this document.
