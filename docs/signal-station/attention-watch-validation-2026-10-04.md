# Public-feed attention and watch diagnostics

By Luke Steuber. Private Android development preview 0.9.1-public-data-dev (23),
built from `0fd266e1`. No public download or Store release.

## Done

Public environmental connectors now populate the existing connection-warning
path for unavailable, partial, stale and unsupported readings. Warnings are
grouped by source; successful readings remain in the catalog. A valid empty
event report is not a failure. A successful refresh clears prior warnings.
No retry, background-fetch, permission or pairing policy changed.

The original defect was reproduced before the fix: three connector tests failed
because typed unavailable results returned no warnings. The fix is confined to
the connector's catalog method and warning property; the existing coordinator
and Connections screen consume it unchanged.

## Evidence

- Measured: 386 shared host tests discovered, 378 passed, eight opt-in skips,
  zero failures. New cases cover failure isolation, warning deduplication,
  recovery, empty event reports, unsupported coverage, partial forecasts and
  stale models.
- Measured: 19 Android instrumentation checks passed on an isolated emulator:
  five real station/coordinator/encrypted-store checks, five public-data UI
  checks, and nine Home UI checks. One additional opt-in TalkBack check was
  skipped; the runner's aggregate count includes that assumption skip.
- Measured: the real coordinator reports one connection needing attention on
  a synthetic HTTP failure and zero after recovery; model access and grants
  remain empty. A separate rendered UI fixture exposes the failed source and
  removes its warning after recovery.
- Measured: Android debug build and lint succeeded; zero lint errors and eight
  existing warnings. Shared iOS simulator compilation succeeded, not an iOS
  product-runtime test.
- Observed: Pixel 9a upgraded in place from build 22 to 23 with the same signing
  certificate. Saved place, public connection and watch favorites remained;
  a fresh fetch returned all 25 configured readings. No crash entry was found
  for Signal in the checked crash buffer.

APK SHA-256:
`4dcf9a639f37e524d16ed7310b3f5428cdb87133776a5669809c920c58c27636`.
Pixel 9a's installed APK matches. Pixel 10 installation was handed to the
Dick Tracy Watch task to avoid interrupting its active rendering checks; its
completion is not claimed here.

## Watch finding

The developer socket was closed at the start of this check. Temporarily enabling
the Pebble app's Dev Connection restored protocol access. The watch reported
Signal's UUID as the active app while screenshots showed a system Ping screen.

The earlier SDK ping was not a passive screen-neutral probe. PebbleOS's
[ping service](https://github.com/coredevices/PebbleOS/blob/6a294c1e62c22efdaeb8e5dac1be349d7d6d34b2/fw/services/ping/service.c)
pushes a modal named Ping. This explains why a running-app acknowledgement can
coexist with that screenshot. Reopening Signal did not dismiss the modal.
No watch binary, firmware, pairing or database was changed.

For future visual checks, use the watch-version or current-app protocol request
instead of SDK ping. If Ping is already visible, dismiss it on the watch before
checking the app. A protocol acknowledgement is not visual acceptance.

## Open

- Physical 2 SE Home favorite acceptance: press Back to dismiss Ping, open
  Signal if necessary, hold Down, select a favorite, and inspect its value,
  source and age. This still requires actual button/screen evidence.
- Pixel 10 upgrade receipt is owned by the coordinating Dick Tracy Watch task.
- TalkBack acceptance was not performed by this change.

Private captures, test logs and the previous installed APK are retained outside
Git on Galactus. The owned emulator was stopped, the temporary ADB forward was
removed, and Dev Connection was restored to its initial off state. Existing
public releases and neighboring product code were untouched.
