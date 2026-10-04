# Pebble 2 SE installation and connection check

By Luke Steuber. October 3, 2026.

## Observed result

The exact standalone Signal Station 1.8.1 PBW from the
[observation preview](observation-preview-2026-10-03.md) installed successfully
on the physical Pebble 2 SE through the Pixel 9a's existing stock Pebble app.
The watch reported Signal Station's UUID as its running app, and its home menu
was captured and inspected. The phone's Check connection action completed with
both "Phone link ready" and "Watch acknowledged the connection."

| Item | Verified value |
| --- | --- |
| Watch platform | Diorite / Pebble 2 SE |
| Running firmware | `v4.4.3-rbl`, unchanged before/after |
| Recovery firmware | `v4.0.1-prf6`, unchanged before/after |
| Watch package | 1.8.1, UUID `e2fd86ec-dfb8-460c-afc1-ebe4d071657a` |
| PBW SHA-256 | `e5a65e155ad66ae21ab8fce230bac1e9dfaebd81442eaf7733e80d2094ee1024` |
| Signal Android | `0.7.0-local-dev` build 20, embedded source `fe5a79ad` |
| Installed APK SHA-256 | `5a2258e38ab2f5204ab4b21118e09543ec196a78f697f1557b63b54063ea2d76` |
| Stock Pebble Android | 1.14.0.1, build 11400001 |

The PBW checksum identifies the supplied installation bytes, not a binary
readback from watch storage. The installed phone APK was hashed on the device.
No phone APK was reinstalled; its original first-install and last-update
timestamps remained unchanged.

## Connection and screen sequence

The existing stock-host developer server needed restarting before the first
watch query. No new Bluetooth pairing or alternate pairing owner was used.
A later USB reconnection removed the temporary development tunnel; a new scoped
tunnel restored access. Both events are transport/setup observations, not
evidence of a watch firmware reset.

The initial watch screenshot showed a Fully Charged notice. Two screenshots
after installation were blank despite the correct running-app UUID. After the
owner unlocked the phone and dismissed the watch notice, a later screenshot
showed the full Signal menu. No rendering code changed, and this sequence does
not establish why the intermediate captures were blank.

Signal already selected the stock Pebble host, which reported one connected
watch, but Signal's saved watch ID referred to an older selection. Choosing the
currently connected 2 SE corrected that app-level setting. The host choice,
pairing, source selections and answer-provider settings were not changed.

The watch footer advanced from "Open phone app" to "Hold DOWN: Home". The phone
showed acknowledgement after selection and again after Check connection.
The watch still reported Signal Station as active afterward. A bounded
15-second Signal-only application log check emitted no matching messages;
this is not proof that no earlier fault occurred. Log streaming was disabled
afterward and the temporary USB tunnel was removed.

## Remaining acceptance

This establishes physical installation, home-screen rendering and a native
phone/watch configuration acknowledgement on this pair. It does not establish
dictation, capture/sensor behavior, reviewed question delivery, cancellation,
extended reconnect reliability, battery behavior or wearer acceptance.
No model request, personal capture or household action was started.

The earlier settings-wipe report remains unresolved. This owner-authorized,
bounded preview installation does not lift the general installation warning or
prove a cause or complete remedy for that incident. No firmware, recovery,
factory reset, phone-data clearing or historical pairing companion was used.

Screenshots and device-specific receipts remain private. This run made no Store
or download-page changes. Separate publication work is documented in the
[testing download receipt](download-preview-2026-10-03.md); its observations are
not a new Store inspection by this installation run.
