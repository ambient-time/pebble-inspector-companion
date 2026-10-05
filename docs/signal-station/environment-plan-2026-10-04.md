# Generic environmental readings — implementation plan

## Scope

Extend Home's existing connection, reading, shortcut and watch-favorite path.
Support arbitrary households through Home Assistant, openHAB, Geepers and a
read-only MQTT 5 over WebSockets connector. No addresses, sensor identities,
room assignments or model brands are preconfigured. Phone readings work without
a watch. Existing Android ambient-temperature/humidity collection stays opt-in
and hardware-dependent; weather and battery temperature are separate sources.

## Sequence

1. Shared environmental presentation: source units or °C/°F; prominent temperature
   and humidity; truthful report/receipt timestamps; original capture data intact.
2. MQTT connector: a maintained shared protocol library, configurable WSS broker,
   explicit private-WS opt-in, encrypted optional credentials, exact topics,
   scalar/JSON field mapping, optional sample timestamp and stale threshold.
3. Reuse test/preview/save, foreground subscription lifecycle and bounded watch
   reads. MQTT exposes no commands and publishes no application messages.
4. Render the phone flow and test source formatting, malformed inputs, retained
   data, offline states, configuration binding, cancellation and watch details.

## Acceptance criteria

- Existing serialized Home data loads with defaults; HTTP connector behavior and
  action permissions remain unchanged.
- MQTT topics and field paths are bounded, configured explicitly and never
  guessed. A connection with no received sample has a truthful waiting state.
- Retained messages without publisher time have unknown measurement age.
  Refresh/reconnect does not relabel them as fresh measurements.
- Temperature conversion changes display only. Phone and watch use the same
  preference; original units/timestamps remain in captures.
- Readings can be favorited without authorizing any action. Cancellation closes
  live transport; no new always-on/background service is introduced.
- Automated tests, Android build, rendered emulator evidence and any physical
  installation are reported separately. No public release is part of this slice.

## Assumptions and risks

MQTT uses version 5 with a broker WebSocket listener. Raw MQTT TCP and MQTT 3 are
not included. No discovery, scripting, wildcards, retained command publication
or broker administration is included. The iOS shared target must compile, but
Signal has no validated iOS application shell. Existing controller timestamps
have different meanings: source receipt, HTTP receipt and measurement time must
remain distinguishable. A source's reported state age is not automatically a
measurement age. Tests use synthetic data and disposable local brokers.

## Evidence

Observed: clean companion checkout at `0a805ddd`; existing three connectors,
phone Home cards and native watch favorites; Android ambient sensor discovery.
Planned: implementation, broker fixtures, regression tests and rendered UI.
