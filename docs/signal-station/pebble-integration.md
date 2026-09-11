# Pebble integration evidence

By Luke Steuber. September 11, 2026.

The standalone app uses the existing Pebble host. It does not own pairing,
firmware, watch settings or installation. The 1.5.0 messaging-only watch draft
remains held; no owner hardware was modified for these checks.

## Tested path

- Android emulator: Field_Inspector_API_36_1, disposable read-only overlay on
  port 5556. No provider key or account login used.
- Host: preserved `coredevices.coreapp` 1.11.0.3 split APKs, not the old lab
  companion. Its ordinary Files sideload flow installed the 1.5.0 draft.
- Watch: isolated Diorite SDK 4.33.1 emulator, running firmware 4.3. Its blank
  serial was replaced by `SS-EMU-00001` in the local test relay. This adaptation
  is necessary for selection and is not physical Bluetooth evidence.
- Watch draft SHA-256:
  `7847e7b06dad4fc333d254e4a40604ee86730d6d35bfe7b7e3a603598b648a50`.

| Check | Result |
| --- | --- |
| Select host and watch, Check connection | Watch opens and acknowledges the configuration message |
| Up from home, phone foreground | Saves four requested readings: phone battery, watch battery and motion available; compass unavailable |
| Up from home, phone background | Saves four outcomes: two available, two unavailable or partial; no provider request |
| Down from home | Shows the latest saved capture through the host link |
| Stop and manually reopen Signal Station while watch app remains open | Discovers active watch app and acknowledges connection again |
| Provider failures, instrumented regression | Both watch-list and active-app failures retire old sessions and recover without host reselection; disconnect cancels retries |
| Protocol and adapter instrumentation | Six tests pass using real WebView execution and sender/provider doubles |

The host must finish its permission/setup flow before this path is useful.
During setup, a transient socket connection was visible and then disappeared;
after completing setup, foreground and background captures succeeded. The
connection chooser now exposes the selected host, connected-watch count and an
Open action, alongside explicit setup steps. An unavailable installation action
is no longer rendered as an unexplained disabled button.

The adapter previously caught a provider failure, emitted disconnected state,
and ended the observation flow permanently. It now retries after one second,
invalidating stale sessions before resubscribing. Coroutine cancellation still
stops discovery when the host is disconnected or changed. This retry applies
only to local host discovery, never to a provider request or watch installation.

Evidence, screenshots and packet metadata are retained under
`/Volumes/Galactus/drummer/signal-station/pebble-integration-20260911/`.
The wire trace records endpoint/type/length metadata rather than question or
sensor payloads. Final release validation is recorded alongside the release.

## Remaining device acceptance

Use a separately designated test watch and phone, preserving their existing
host pairing. Confirm compatible firmware and record the stock host version.
Validate physical connection, selected health permissions and readings,
dictation with transcript confirmation, an explicitly reviewed provider request,
text scrolling, cancellation, disconnect/reconnect and locked-phone operation.
Record watch settings before and after an ordinary app update. No factory
reset, firmware transfer or pairing migration is part of this test.

The recovered owner watch is not a reset-reproduction target. The historical
settings-wipe report is not explained by these emulator results. Flint and
Gabbro startup evidence and physical dictation remain outstanding. Keep the
listing unlisted/draft and the installation hold until those gates are resolved.

A separate fresh Flint emulator (firmware 4.33.2) connected to the preserved
host, but its provider reported platform `emery` and revision `unknown`. That
host/firmware combination cannot establish correct Flint package selection.
The mismatch is recorded in `flint-host-report.txt`; no successful Flint
launch is claimed and no watch identity was changed to disguise the mismatch.
