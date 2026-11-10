# Developing Signal Station

Signal Station handles readings, local history and conversations. The existing
Pebble app handles pairing and watch management.

## Build Android

Use JDK 17, the Android SDK and the repository's Gradle wrapper. The version
catalog selects compile SDK 37 and minimum API 26 (Android 8). Configure your SDK
path in the environment or untracked `local.properties`.

```sh
./gradlew :signalApp:assembleDebug
```

The APK appears in `signalApp/build/outputs/apk/debug/`. The build prepares bundled
wake-recognition assets and needs network access for uncached dependencies.
No answer-provider key is needed to compile. A debug build is signed with the
local Android debug key and is for development only.

## Build a release

The published APK is signed with Signal Station's own release key and is never
debuggable. The key lives in the release toolkit's store, with its password in
the login Keychain, so the build runs through the toolkit:

```sh
~/workspace/android-release-toolkit/scripts/android-release \
  with-signing --profile signal-station -- scripts/build-signal-release.sh
```

Commit first. The script refuses a dirty tree, runs the app module's unit tests
and release lint, and stages
`dist/signal-station/signal-station-<version>-<code>-<revision>.apk` with
`SHA256SUMS.txt`. The APK records the revision it was built from, and the
published source archive is cut from that same commit.
`:signalApp:assembleRelease` fails without the signing values.

Builds up to 0.9.5-flip-dev (27) were signed with the Android debug key. Android
will not update across a signing key change, so an earlier build must be
uninstalled once before 0.9.6 or later is installed. That removes saved
history, keys and watch links.

| Directory | Role |
| --- | --- |
| `signalApp/` | Standalone Android entry point and selected Pebble host connection |
| `signal/` | Collection, chat, encrypted storage and shared screens |
| `scripts/` | Watch package builder and checks |
| `pebble/` | Inherited host and earlier integration |

Keep pairing, firmware and watch databases outside the standalone app. The older
lab companion is a separate target; its instructions do not build Signal Station.

## Build the watch package

Clone the watch repository beside this one and follow its `BUILD.md` for the
Pebble CLI and SDK versions. Run from this Android repository:

```sh
python3 scripts/build-signal-addon-watch.py ../pebble-field-inspector \
  --descriptor signalApp/watch-collection-preview.json \
  --output signalApp/build/watch-collection-preview
```

The descriptor pins the watch source. Its distribution and installation-hold
fields describe that build record, not the current Store listing. The published
watch package contains no PebbleKit JavaScript.

## Check a change

Run the affected module's tests and Android lint before packaging. The watch
repository's `npm run test:client` checks its client and native protocol. Run the
Python watch-builder checks here:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Use synthetic data. Device reports should name the exact packages, phone, watch,
host version and permissions. Check cancellation and reconnect behavior. Keep
keys and personal observations out of logs and patches.

[The user guide](personal-context.md) describes current behavior. Dated acceptance
notes record a particular build's results; they do not certify a later build.
