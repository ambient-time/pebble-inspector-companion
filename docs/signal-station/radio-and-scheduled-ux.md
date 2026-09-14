# Radio views and scheduled collection

By Luke Steuber. September 13, 2026.

## Scope and behavior

Around me uses expandable section headers and compact device rows. My devices
starts with names, broad types, monitoring state and sighting summaries. Expand a
device to reach its switch, evidence, last-seen time and Forget action. Scan
options, latest results, places and saved context also collapse independently.
Expanding or filtering changes the view without changing source choices.

Live signals groups Wi-Fi and Bluetooth separately. Signal strength remains in
each row. The Wi-Fi filters are All, No password, Credentials and Unknown access.
No password includes open and OWE advertisements. Permission to use a network,
working internet and browser sign-in cannot be established by scanning. The
phone's saved connection state is shown separately with its capture time.
Snapshots continue to include all fresh selected radios, with this scope stated
beside the filter.

Tinted live rows have appeared in at least three fresh scan updates in the
current session. Counts reset when an observation expires from the view.
Packet count, cached repeats and long-term familiarity are
different quantities. Named-device tint uses at least three distinct sightings
in the loaded saved history; these are partial history counts, not lifetime
totals. Counts are written alongside color. A missed or old signal does not
establish departure, and a repeated signal does not establish trust or safety.

Broad Bluetooth types use recognized advertised services or beacon formats:
health/fitness, input device, environmental sensor or beacon. Generic services,
company IDs and names leave the type unknown. Wi-Fi observations are access
points. The app keeps the supporting advertisement visible in expanded details.

Record over time supports an explicit fixed interval and ongoing duration until
stopped, alongside finite sessions and the existing adaptive schedules. Local
comparisons and optional model analysis are separate choices. The visible
notification retains Stop, source choices are preserved, and interrupted
sessions do not restart themselves.

## Evidence

The combined Android build and lint pass. Host tests report 214 passed and six
existing skips, with no failures. All 25 selected emulator interaction and
integration tests pass, covering collapsed details, filter scope, 200% text,
custom intervals, ongoing sessions, local comparisons and model cancellation.
Host coverage also checks timing, repeat-count deduplication and unknown device
types. Model tests use a mock provider; no live provider request was made.

The foreground-service integration test saves two captures and their local
comparison, verifies Stop and deletes the report with its source evidence. It
requests its second capture directly; scheduled elapsed-time behavior on the
physical phone is recorded separately in the collection trial.

The watch button layout and backlight are separately built and verified in
`pebble-field-inspector`. A screenshot proves layout; physical illumination and
sensor behavior require observation on the watch.

## Follow-up review

The [September 14 implementation sweep](project-sweep-2026-09-14.md) records
confirmed comparison and retention limitations, the remaining screen-off session
probe, and independent Codex CLI/Grok CLI opinions. Its recommendations are
planning inputs; this document still describes the published build 14 behavior.

## References

- [Android network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
  explains internet validation and captive-portal detection for connected networks.
- [Android scan results](https://developer.android.com/reference/android/net/wifi/ScanResult)
  expose advertised security, including OWE.
- [Bluetooth assigned numbers](https://www.bluetooth.com/specifications/assigned-numbers/)
  defines the service identifiers used for broad category hints.
