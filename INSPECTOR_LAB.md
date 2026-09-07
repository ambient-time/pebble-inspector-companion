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

## Build and inspect

Use the checked-in Gradle wrapper, Java 21 to run Gradle, a Java 17 compilation
toolchain, and an Android SDK with the licenses accepted. On the build Mac, Java 17 is at
`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`; the SDK is at
`/Users/luke/Library/Android/sdk`. The pinned source requires Android platform
37.0 and downloads its NDK/build-tool versions through Gradle when missing.

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :androidApp:assembleInspectorLab :androidApp:lintInspectorLab --no-daemon \
  -Dorg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_inspector_apk.py androidApp/build/outputs/apk/inspectorLab/androidApp-inspectorLab.apk
```

The lab source set carries a visibly fake Firebase configuration. It grants no
cloud access. Crashlytics collection and analytics are disabled for this variant,
and lab rules exclude cloud backup and device transfer. Keep provider credentials
out of build configuration and package resources.

Ordinary upstream `debug` and `release` retain their package, label, signing
configuration, and recognition path. Two PebbleKit providers also need separate
lab authorities. The classic provider reads its authority from the manifest so
its connection notifications stay with the correct installation. Third-party
clients using the old hardcoded authority continue addressing stock. The lab
also removes three obsolete PebbleKit permission declarations, whose names
belong to stock. Upstream no longer uses them to control access.

From reviewed, committed source, run `bash tools/stage-inspector-lab.sh`, adding
the same `-Dorg.gradle.java.installations.paths=…` argument if Gradle cannot
discover Java 17. It
builds, lints, checks the APK signature/identity, and stages a revision-named
package, metadata, checksum, and source revision under ignored `dist/`.
Build timestamps and the local debug signing key can change APK bytes; the
checksum identifies the exact package used for a device run.

## Install on Pixel 9a

Use the serial returned by `adb devices -l` and verify the phone model first.
The following serial identifies the authorized Pixel 9a in the September 6 run.

```sh
adb -s 54051JEBF00576 shell getprop ro.product.model
adb -s 54051JEBF00576 install -r androidApp/build/outputs/apk/inspectorLab/androidApp-inspectorLab.apk
adb -s 54051JEBF00576 shell am start -n coredevices.coreapp.inspectorlab/coredevices.coreapp.MainActivity
```

Install only after package verification succeeds. Check that both stock and lab
packages remain installed. The lab's application classes keep the original
`coredevices.coreapp` namespace; the installed package supplies isolation.
Grant permissions through the lab's ordinary setup screens. Do not copy stock
app data or credentials into it.

Both installations can handle Pebble links. Choose the intended companion in
Android's chooser and avoid a permanent default during the experiment. Keep
only one companion connected to the test watch. Follow the watchapp's
[setup and recovery guide](https://github.com/lukeslp/pebble-field-inspector/blob/main/docs/inspector-lab-setup.md)
for baseline measurements, the Time 2 handoff, and the return to both stock
pairings. Physical results belong in its
[device record](https://github.com/lukeslp/pebble-field-inspector/blob/main/docs/device-validation.md).
