# Signal Station for Garmin

By Luke Steuber.

A Connect IQ watch app with a glance. It shows your Signal Station Home favorites, reads one, and runs its action only after you review the exact action and confirm it in the watch's own confirmation dialog. It also shows recent saved replies and can open the Home view on the phone.

The phone does all the work. The watch talks only to the Signal Station Android app, through Garmin Connect, and uses the same requests as the Pebble app. Keys, Home connections and action permissions stay on the phone. Questions, dictation and capture remain on Pebble and the phone; Garmin's microphone access (SDK 9.3) is limited to newer watches and is not used here.

## Build

Requires the Connect IQ SDK and a developer key.

```sh
SDK="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks/<sdk>"
"$SDK/bin/monkeyc" -r -w -f monkey.jungle -d venu2s -o bin/signal-station-venu2s.prg -y <developer_key.der>
"$SDK/bin/monkeyc" -e -r -w -f monkey.jungle -o bin/signal-station.iq -y <developer_key.der>
```

The manifest lists 104 watch models: those whose device files allow a watch app at API 3.2 or later.

## Try it in the simulator

`simulator-replies.jungle` builds a review copy for the simulator only. It keeps requests waiting so replies can be pasted into **Simulation → Phone App Message**, and debug builds print each message to the console. Never sideload or package that build.

Garmin's documented phone link for the simulator (`adb forward tcp:7381 tcp:7381`, then **adb Connection → Start**, with the Android app built using `-PsignalGarminTethered=true`) did not carry messages on macOS 27: the 9.2 simulator crashed on the first message and the 9.3 simulator stopped responding. See `docs/signal-station/garmin-watch-app-2026-11-02.md`.

## Messages

Every message is a dictionary with `v` (1) and `s`, a number chosen for each run of the app.

- Watch to phone: `{"t": "hello"}`, `{"t": "bye"}`, and `{"t": "req", "id": n, "route": r, "body": {...}}` with `route` one of `capabilities`, `history`, `home` or `clear`.
- Phone to watch: `{"t": "res", "id": n, "status": code, "data": {...}}` and `{"t": "reset"}`, which makes the watch check the connection again.

The phone answers through `AndroidSignalStation.handleWatchRequest`. A new run's `hello` or `capabilities` request starts its session; anything else from an unknown run gets 403. A sent confirmation is never resent; if no reply arrives, the watch says the outcome is unknown.
