# Licensing

This repository holds two bodies of work under different terms: my own Signal
Station code, and the Core Devices mobile app source it grew from.

## My original contributions: MIT

I offer my original contributions to Signal Station under the
[MIT License](LICENSE-MIT). They are in four directories, and each one carries
its own copy of that license:

| Directory | Contents |
| --- | --- |
| [`signalApp/`](signalApp/LICENSE) | The standalone Android app: Gradle project `:signalApp`, package `com.lukesteuber.signalstation` |
| [`signal/`](signal/LICENSE) | Shared features: collection, chat, Home connections, storage and screens |
| [`localmodels/`](localmodels/LICENSE) | On-phone answer engines |
| [`garmin/signal-station/`](garmin/signal-station/LICENSE) | The Garmin Connect IQ watch app |

This permission covers only material for which I hold copyright. It does not
relicense code, media, models or documentation owned by others. That includes
the third-party libraries and the speech model these modules use;
[NOTICE.md](NOTICE.md) lists what the Signal Station APK bundles and under
what terms.

### The package name in `signal/`

The Kotlin sources in `signal/` use the package name
`coredevices.pebble.signal`. That name is historical. The code began inside the
`pebble` module and moved into its own module at commit `504e3792`
(September 9, 2026); the package name stayed. It does not indicate Core
Devices authorship. None of those files comes from the upstream source.

## Core Devices software: GPLv3

The repository includes Core Devices software under [GPLv3](LICENSE), with its
existing [commercial terms](LICENSE-COMMERCIAL) and other upstream notices.
Those terms remain intact. A combined work derived from the GPLv3 code remains
subject to GPLv3; the MIT grant on my original contributions does not make the
whole Android fork MIT-only.

## Which builds are which

The published Signal Station APK is built from `:signalApp`. Among this
repository's projects, `:signalApp` depends only on `:signal` and
`:localmodels`, and the APK contains no Core Devices code. The app talks to a
Pebble through the separately installed Pebble app.

Builds of `androidApp` are GPLv3 works. That includes its `inspectorLab` build
type (package `coredevices.coreapp.inspectorlab`), the earlier lab companion,
which links the Core Devices `pebble` and `libpebble3` modules together with
`signal`.

## Reusing a part

Keep third-party license and copyright notices with their files. Separating a
component for MIT-only reuse requires checking its provenance and dependencies.
