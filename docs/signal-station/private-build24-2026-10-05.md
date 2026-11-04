# Private build 24 installation

By Luke Steuber. October 5, 2026.

Android **0.9.2-public-data-dev (24)** is installed in place on Pixel 9a and
Pixel 10. This carries the [provider-response safety guards](dick-tracy-reuse-2026-10-05.md)
and the existing public-feed attention fixes. No public download, Store release
or watch package was changed.

## Artifact

- Package: `com.lukesteuber.signalstation`.
- Committed build source: `6764a60afb216fbbff1f9ff8f7ed6a2ea666412e`.
- Embedded identity: `0.9.2-public-data-dev (24) · 6764a60a · development preview`.
- APK SHA-256: `9f48f33e3c97aa182e52d817a8afc51f3456709c4e8bf2b8115a9b28fb10be12`.
- Signing certificate SHA-256: `e1faa235dbdd132ac86856639b60b12ad9f9b658fc921fd8160db84ed8dfa28d`.
- Private debug-signed preview; not a store-release artifact.

Packaged identity and signature were inspected before installation. The signer
matches the previous installed build 23, and the packaged permission list is
unchanged. The prior APK was preserved outside Git with its recorded checksum.

## Validation

- Signal shared host suite: 389 discovered, **381 passed**, eight opt-in skips,
  zero failures or errors.
- Local-model module: **15 passed**, no skips or failures.
- Android debug assembly and lint passed: zero lint errors, eight existing warnings.
- The safety guards' three regressions previously failed before the fix; all
  27 provider/tool tests pass. No live provider or household request was made
  by those tests.

## Device receipt

Both phones started on build 23 with matching installed APK checksums. Explicit
device-targeted replacement installs succeeded; no uninstall or data-clear was
used. Pixel 9a used USB, and Pixel 10 used its existing authorized wireless
debugging connection. The Pixel 10 identity was checked before installation.

| Device | Installed version | Installed APK | Data-preservation evidence |
| --- | --- | --- | --- |
| Pixel 9a | 0.9.2-public-data-dev (24) | Matches artifact SHA-256 above | Original first-install time and credential/device-encrypted data-directory inode identities unchanged |
| Pixel 10 | 0.9.2-public-data-dev (24) | Matches artifact SHA-256 above | Original first-install time and credential/device-encrypted data-directory inode identities unchanged |

Both launch requests returned success and both app processes were present. No
Signal entry matched the inspected recent crash buffers. Pixel 9a remained behind
its lock screen; Pixel 10 was dozing. These are package/install/startup checks,
not fresh visible interaction or complete data-content verification.

No provider settings, source selections, action grants, model downloads, watch
pairings, firmware or other applications were changed. Private APKs, build logs
and diagnostic captures remain outside Git on Galactus.

Physical 2 SE favorites, current TalkBack acceptance, real-broker interoperability,
Gemma inference and longer-running battery/background checks remain separate.

## Subsequent watch checks — October 5

Observed: the Pixel 9a developer route returned the 2 SE's firmware
`v4.4.3-rbl`, board `silk21` and hardware platform 14. Its current app was not
Signal Station. The captured screen showed **Fully Charged**, so neither Signal's
home screen nor its favorites were visually accepted in that check. No Ping,
watch launch, install, pairing change or firmware change was sent. The temporary
USB forward used for the check was removed; developer mode was left as requested.

Next physical check: press Back (single left button) to dismiss the overlay,
open Signal Station, hold Down (bottom-right), then open a selected reading.
Check its value, units, source and age against the phone. A developer response
or active UUID alone cannot establish screen fit or button behavior.

Separate evidence: Dick Tracy 1.9.0 installation, active UUID and visible
**AGENT COMMAND** home screen were verified on the Pixel 10 / Time 2 pair after
the overlay was dismissed. Its [dated receipt](https://github.com/lukeslp/gadget-watch/blob/9de9a6f/docs/live-acceptance-2026-10-05.md)
belongs to that product. It establishes neither Signal rendering nor Signal
speech support. Private physical screenshots remain outside Git.
