# Contributing to Signal Station

Small, explained changes are easier to review. Describe the problem, the behavior
before and after your change, and how you checked it. Include the Android version
and watch model when they matter. Say whether a result came from an emulator,
a test double or a physical device.

Start with [the developer guide](docs/signal-station/development.md). Keep source
choices, provider review and deletion behavior explicit. Changes to collection
or chat should cover cancellation, unavailable data and retry behavior.

Use synthetic readings in tests and screenshots. Never post provider keys,
private conversations, health records, precise locations or identifiable radio
scans. Send a sensitive report to [luke@lukesteuber.com](mailto:luke@lukesteuber.com)
before opening a public issue.

Follow the existing style and keep unrelated formatting out of your patch.
Explain the checks you ran and any you could not run. A passing build does not
prove watch compatibility or background behavior.

This fork retains its existing GPLv3 license and upstream notices. Changes sent
to Core Devices follow [their contribution rules](CONTRIBUTING-UPSTREAM.md),
including their contributor agreement. Those are upstream submission rules.

By Luke Steuber.
