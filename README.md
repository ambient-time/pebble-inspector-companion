# Signal Station

Capture signals. Notice what changes.

[![Download Signal Station](https://img.shields.io/badge/Signal_Station-Download-2563eb?style=flat-square)](https://dr.eamer.dev/downloads/apps/signal-station/) [![Ambient Time](https://img.shields.io/badge/Ambient_Time-Collection-181717?style=flat-square)](https://github.com/ambient-time)

I built Signal Station as a field notebook for Android, with a Pebble interface
for your wrist. Explore nearby Wi-Fi and Bluetooth signals, save sensor readings,
and compare observations over time. Ask your chosen language model about what
you find, or start a conversation without collecting anything.

[Download for Android and Pebble](https://dr.eamer.dev/downloads/apps/signal-station/) ·
[User guide](docs/signal-station/personal-context.md)

## Start here

Install the Android app from the download page. Capture and local history need
no provider key. For chat, open **Ask → Set up answers**, choose a provider and
model, and add your own key. Provider usage charges apply.

Pebble is optional. Keep your usual Pebble app installed and your watch paired
there; Signal Station uses that connection. Install the watch package, then open
**Settings → Connected devices → Watch connection** in Signal Station and tap
**Check connection**. Up captures, the middle button asks, and Down opens history.
Hold the middle button on a reply to open its full text on your phone.

The watch app is still in testing. The current downloads contain Android build
16 and watch version 1.6.6. The guide explains compatibility, permissions and
which features have physical-device evidence.

## What you can do

**Explore around you.** See nearby wireless signals, repeated sightings and broad
device types where available. Name your own devices and expand rows for details.
A nearby advertisement cannot establish a device's owner or exact location.

**Keep a record.** Save a capture or record at a chosen interval, for a fixed
duration or until you stop. Local comparisons show changes. Scheduled model
analysis has a separate opt-in and uses your provider account.

**Talk about it.** Attach selected readings or history to a question, review the
outgoing context, and send. Markdown replies keep their full formatting on the
phone; the watch shows a compact, scrollable version. Chat also works on its own.

## Your data

Sources start off. You choose what to collect, keep and share. Captures,
conversations and provider keys use protected phone storage. Sending a question
shares the reviewed context with your chosen provider. Optional weather and
place lookups contact their named services. The [guide](docs/signal-station/personal-context.md)
covers exports, deletion, background recording and lookup controls.

## Build and contribute

The standalone Android app lives in `signalApp/`; shared features live in
`signal/`. The [developer guide](docs/signal-station/development.md) covers build
commands and the boundary with the existing Pebble app. Read
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a change.

This repository retains the Core Devices mobile app source it grew from.
[UPSTREAM.md](UPSTREAM.md) preserves its architecture, platform setup, credits
and licensing notices. The inherited iOS and full-companion targets are separate
from the Signal Station Android app.

## License and credits

My original contributions are available under [MIT](LICENSE-MIT). The Android
fork also includes code under [GNU GPLv3](LICENSE); see
[licensing scope](LICENSING.md). Core Devices' original notices
and [commercial license](LICENSE-COMMERCIAL) remain intact; see
[upstream licensing](UPSTREAM.md#copyright-and-licensing).
Public-source preparation is tracked in [the release checklist](docs/signal-station/open-source.md).

Upstream software by [Core Devices](https://github.com/coredevices/mobileapp).

## Around here

[Luke Steuber](https://github.com/lukeslp) · [Data Poems](https://github.com/data-poems) · [Ambient Time](https://github.com/ambient-time) · [Actually Useful AI](https://github.com/actually-useful-ai) · [One Impossible Thing](https://github.com/one-impossible-thing)

Made by [Luke Steuber](https://lukesteuber.com). Questions or collaboration:
[luke@lukesteuber.com](mailto:luke@lukesteuber.com).
