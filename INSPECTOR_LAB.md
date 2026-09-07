# Pebble Inspector Lab

Private Android companion experiment for Field Inspector, by Luke Steuber.
Based on [Core Devices' mobile app](https://github.com/coredevices/mobileapp) at
`d52101ad3d8940c5aa392d6f224e774cb6f5ce84`.

The first build isolates the test installation on Pixel 9a. It preserves stock
recognition and watch transport while the baseline and pairing recovery undergo
physical checks. OpenAI recognition, Terra responses, direct phone requests,
and voice-provider settings follow the gates in the
[approved plan](https://github.com/lukeslp/pebble-field-inspector/blob/main/docs/voice-experiment-plan.md).

| Item | Selection |
|---|---|
| Experimental Android package | `coredevices.coreapp.inspectorlab` |
| Private test label | Pebble Inspector Lab |
| Build variant | `inspectorLab` |
| Test phone | Pixel 9a |
| Primary watch | New Pebble Time 2 |
| Secondary watch | Pebble 2 SE |
| Watchapp UUID | `e2fd86ec-dfb8-460c-afc1-ebe4d071657a` |

Keep one companion actively connected to the test watch. Preserve Pixel 10's
stock installation and the Pixel 9a stock app/data. A different package ID
separates app storage; Bluetooth pairing still needs a deliberate handoff.

Upstream source, history, notices, and licenses remain intact. The
[upstream README](README.md) describes the original application. This fork is
for private device testing; it is not a store release.
