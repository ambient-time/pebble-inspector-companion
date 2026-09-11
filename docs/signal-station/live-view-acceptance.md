# Live signal view: build 10

By Luke Steuber. September 10, 2026.

Android 0.3.2-live-dev (10) adds Now → Live view. Received signal strength is
grouped into three bands without fabricated direction, distance or handset
counts. Entries retain bounded recent samples, age, scan outcomes and optional
advertised metadata. Session labels do not enroll persistent device identities.

The controller has no storage or provider capability. Only selected base radios
and their separately selected metadata sources enter its acquisition. Leaving,
backgrounding, changing sources or reaching five minutes cancels the session.
A new session requires an explicit start. Snapshots retain fresh observations,
measurement times and stable reading IDs. Save stays local; Ask prepares an
attached draft for the existing Review and Send path.

Validation uses synthetic radio arrivals and virtual clocks, plus the real
Android encrypted store and station with a mock provider transport. Host tests
cover repeated cached timestamps, aging, bounds, metadata exclusion, source
changes, cancellation, five-minute limits and Wi-Fi request spacing. The Android
integration checks no implicit persistence or transmission, exact saved reading
values in the reviewed request, original preservation, and source-change clearing.
UI checks cover start, details, labels, save, Ask and leaving at normal and 200%
text sizes. Build, Android lint and publication checks accompany the release.
Detailed run results, immutable artifact facts and public verification belong in
the release toolkit receipt rather than this source document.

The preview package and signing identity are retained. Upgrade validation seeds
published build 9 and verifies settings, a synthetic credential, history, a saved
question and corrected memory after installing build 10 without uninstalling.
No new watch bytes, pairing changes or Pebble installation are part of this
release. Physical Android radio behavior, battery cost and real environment
accuracy remain unverified; emulator fixtures are not radio field evidence.

Cooperative ranging and optional calibrated ESP32 positioning remain future
capabilities, described in [the plan](live-view-plan.md). The user workflow is in
[the guide](personal-context.md#live-signals-around-you).
