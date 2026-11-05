# Flip cover layout — November 4, 2026

Status: **built and installed; cover launch validation pending normal unlock**. Source is the isolated Napoleon
worktree `/Users/luke/workspace/signal-station-flip-cover`, branch
`codex/flip-cover-20261104`, based on `a20a1e89`. The original checkout under
`/Users/luke/workspace/android-builds/appcat-20261103/pebble-inspector-companion`
was read for its cached build asset and was not modified.

## Change

The fixed brand header and bottom navigation previously consumed much of a
379 × 336 dp cover window before any readings or conversation appeared. The
shared Compose shell now uses one labelled navigation row below 480 dp available
height and 600 dp width. Now, Ask, Home and History remain available in its route
menu, with Back and Settings controls. Normal-height phone navigation is unchanged.
The compact conversation places its composer in the scrolling list, allowing
the question, evidence options and review control to remain reachable. It uses
the existing in-memory UI session, routes and consent flow.

The thin Android shell observes size and window-inset changes through a parent
`SignalCoverFrame`, forwarding the insets to Compose. Bottom cutouts are reserved
only where their rectangles intersect the view. A Samsung SM-F766/SM-F776 window
within 3% of the full 948:1048 cover aspect also receives the measured bottom
209/1048 keep-out when Samsung reports no cutout. This uses the physical Flip8
evidence from the Ambient Time continuation. The 1422 × 1259 wide frame, whose
camera rectangle begins below the view, receives zero additional padding.
Consumed bottom insets prevent the shared navigation padding from adding them
again. A common `SignalAlertDialog` wrapper propagates the cover reserve into
the dialog's independent window; its existing callbacks and review contents
are unchanged. Default dialog padding is zero on other surfaces.

No sources, capture permissions, Home permissions, collectors, answer providers,
models, speech behavior, stored data were changed. The local development candidate advances to
`0.9.5-flip-dev` (27), above the separate cached build 26. The original build-26
branch is preserved. The built APK is a local debug preview,
not a new public release.

## Checks and limits

JDK 17 and Android SDK 37 were used with Gradle offline. The existing 39 MB
checksum-pinned wake-model ZIP was copied into this worktree's ignored build
directory; no model was downloaded or executed.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/luke/Library/Android/sdk ./gradlew \
  :signalApp:assembleDebug :signalApp:assembleDebugAndroidTest \
  :signalApp:testDebugUnitTest :signal:testAndroidHostTest \
  :signalApp:lintDebug --offline --console=plain
```

The final pass built the debug and test APKs, passed all 16 app JVM
tests (including five new camera-inset cases), and ran 400 shared host tests:
392 passed, eight skipped, no failures. Android lint passed with eight existing
warnings and no errors.

`SignalCoverUiTest` contains synthetic 379 × 336 and 399 × 441 dp fixtures,
double-text question review, route access without collection, draft retention
across resize, same-size inset changes, and harmless dialog dismissal. The first native run on the Flip inner screen passed four of six. Two fixture
defects were isolated: direct `onApplyWindowInsets` invoked Android’s legacy
redispatch and lost the synthetic cutout; the actual inner-screen IME measured
1116 px (372 dp) and overwhelmed the artificial 336 dp window. The corrected
fixture uses `dispatchApplyWindowInsets`, verifies cutout forwarding, and
intercepts platform text input only in fixed-size fixtures. Review controls
still require visible, enabled, 48 dp targets and an actual touch. The corrected
six-case rerun remains pending; this does not establish real keyboard safety.
AOSP documents the inset dispatch flow in [View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java);
Compose’s [text test actions](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-test/src/commonMain/kotlin/androidx/compose/ui/test/TextActions.kt)
explicitly focus the field before replacing text. TalkBack, keyboard presentation, physical unfolding and
dialog placement against the real cameras still require runtime evidence.
Until full-panel dialogs are checked, the wide frame is the conservative setup.
Status remains built rather than device-tested.

Artifacts:

- `signalApp/build/outputs/apk/debug/signalApp-debug.apk`
- `signalApp/build/outputs/apk/androidTest/debug/signalApp-debug-androidTest.apk`
- `signalApp/build/reports/lint-results-debug.html`
- `signalApp/build/test-results/testDebugUnitTest/`
- `signal/build/test-results/testAndroidHostTest/`

The Android Debug certificate SHA-256 is
`e1faa235dbdd132ac86856639b60b12ad9f9b658fc921fd8160db84ed8dfa28d`.
Reverify both artifact checksum and installed signer before any in-place upgrade.
The local version-27 APK was installed in place on the Flip after verifying
continuity with its existing debug-preview certificate. Its SHA-256 is
`8f751d4489cfc217b77847a9fd62a337285cc14b6284997a239354b2a6c81586`;
the embedded production revision is `e24c8f03`. Signal Station is selected in
MultiStar’s cover App Tray, preserving prior selections. Source integration is
`codex/signal-station`; GitHub Android and iOS CI passed
[run 41960975280](https://github.com/ambient-time/pebble-inspector-companion/actions/runs/41960975280).
No new public release was made. The coordinating Ambient Time record is
`docs/reviews/flip-apps-20261104.md` in `ambient-time/clocks-app`.

## Rollback

Reverse only this branch's cover-layout patch, including the compact-navigation
helper, dialog wrapper/call-site changes, Android boundary/inset helper, tests and
this dated record. Existing storage and permission schemas are unchanged. Never
uninstall the production package to roll back an upgrade without a separate
owner decision: uninstalling removes its app data.
