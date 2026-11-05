# Flip cover layout — November 4, 2026

Status: **installed; cold cover launch, keyboard, dialog and physical unfolding checks passed**.
Corrected six-case UI rerun: **6 passed**. Final-build refold/relaunch and
wide/tall/full camera-clearance checks passed. TalkBack remains unverified. Source is the isolated Napoleon
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
six-case rerun passed all six tests in 8.64 seconds. The coordinating session's
receipt is `/tmp/signal-cover-corrected-ui-tests-20261104.log` on Napoleon.
Fixed-size fixtures isolate the viewport from the
inner-screen IME; actual keyboard evidence is recorded separately below.
AOSP documents the inset dispatch flow in [View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java);
Compose’s [text test actions](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-test/src/commonMain/kotlin/androidx/compose/ui/test/TextActions.kt)
explicitly focus the field before replacing text.

### Physical cover and unfolding evidence

The coordinating session cold-launched Signal Station from its real SystemUI
App Tray icon on the closed Flip's display 1. Compact routes worked. In the real
cover keyboard, touching `s` appended to the synthetic, unsent draft
`Cover screen test drafts`; Back dismissed the keyboard. The draft survived
wide → tall → full resizing and app switches. In the full frame, the Save
question dialog's Cancel control remained above the cameras and dismissed the
dialog. Nothing was saved or sent.

Luke then physically opened the phone. The device reported state 3; the same
Activity task, 133, automatically continued on the inner display. The identical
draft remained visible and normal phone navigation returned. The temporary
screenshot is `/tmp/signal-unfold-final.png` on Napoleon.

After the final APK update and test cleanup, Luke refolded the phone, normally
unlocked the cover and opened Signal Station from the App Tray. Android reported
actual CLOSED state 0; the final APK checksum was re-read and matched the
`c22fc5b5` artifact below. The Now screen was visible on cover display 1, task
155. Wide → tall → full → wide passed on this final build. In full mode the
visible window covered `[0,0][948,1048]`; app content ended above the camera strip
near y839 and the reserved lower area was black. The app was left ready on Now
in the wide frame, with no synthetic draft, collection or new permissions.
Final cover evidence is retained in Ambient Time at
`docs/reviews/assets/flip-apps-20261104/signal-full.png`.

This establishes final-build refold/relaunch and camera clearance. The earlier
draft test establishes cover-to-inner retention; a draft roundtrip through both
physical fold directions remains a separate, unverified check. TalkBack was
subsequently skipped at Luke's request; see the quiet follow-up below.

Artifacts:

- `signalApp/build/outputs/apk/debug/signalApp-debug.apk`
- `signalApp/build/outputs/apk/androidTest/debug/signalApp-debug-androidTest.apk`
- `signalApp/build/reports/lint-results-debug.html`
- `signalApp/build/test-results/testDebugUnitTest/`
- `signal/build/test-results/testAndroidHostTest/`

The Android Debug certificate SHA-256 is
`e1faa235dbdd132ac86856639b60b12ad9f9b658fc921fd8160db84ed8dfa28d`.
Reverify both artifact checksum and installed signer before any in-place upgrade.
The initial version-27 APK was installed in place on the Flip after verifying
continuity with its existing debug-preview certificate. Its SHA-256 is
`8f751d4489cfc217b77847a9fd62a337285cc14b6284997a239354b2a6c81586`;
the embedded production revision is `e24c8f03`. Signal Station is selected in
MultiStar’s cover App Tray, preserving prior selections. Source integration is
`codex/signal-station`; GitHub Android and iOS CI passed
[run 41960975280](https://github.com/ambient-time/pebble-inspector-companion/actions/runs/41960975280).
The final installed `323074cf` source candidate has APK SHA-256
`c22fc5b5810752f8dfbd66ecb50a7cd968a0991b8f2c5056efa3fd5f93ee7e46`.
Installation succeeded before the corrected six-case native UI run, which passed.
Afterwards, the coordinating session removed only the instrumentation package
`com.lukesteuber.signalstation.test` and launched the normal production app on
the inner display. Its Now screen showed 0 sources, without a fixture overlay
or synthetic draft on screen. Android and iOS CI passed
[run 41967479199](https://github.com/ambient-time/pebble-inspector-companion/actions/runs/41967479199).
No new public release was made. The coordinating Ambient Time record is
`docs/reviews/flip-apps-20261104.md` in `ambient-time/clocks-app`.

Durable evidence is retained in the [coordinating handoff](https://github.com/ambient-time/clocks-app/blob/7466a6dd8fbb0cdaef20292289b22f82bf8f655f/docs/reviews/flip-apps-20261104.md),
[UI execution receipt](https://github.com/ambient-time/clocks-app/blob/7466a6dd8fbb0cdaef20292289b22f82bf8f655f/docs/reviews/signal-cover-ui-tests-20261104.log)
and [final full-frame screenshot](https://github.com/ambient-time/clocks-app/blob/7466a6dd8fbb0cdaef20292289b22f82bf8f655f/docs/reviews/assets/flip-apps-20261104/signal-full.png).
Local `/tmp` captures are transient; a source rollback preserves these records.

## Quiet follow-up and current gate

The November 4 session continued past midnight on November 5 (Pacific).
TalkBack testing was canceled at the owner's request and was not resumed.
The USB connection dropped before the temporary observer established a passing
runtime result. Its uncommitted source and test package were removed. Scoped
settings reads confirmed accessibility enabled `0` and enabled services `null`.
Neither keyboard checks in other apps nor the incomplete observer log establish
Signal screen-reader acceptance.

Signal's installed build 27 was reread and still matches the `c22fc5b5` artifact
above. No runtime source or binary changed in this follow-up. The actual closed
cover now has an unsent synthetic `Flip fold test 1104` draft ready for an
unfold/refold check. No source/provider was enabled; the draft was not saved,
reviewed or sent. The phone remains CLOSED0 pending the owner opening it.
The full two-direction draft roundtrip remains unverified. Do not infer it from
the earlier one-way proof or from adb setup/resume commands.

The [quiet cross-app receipt](https://github.com/ambient-time/clocks-app/blob/49088b8f742c960d9533ec99d68ad425c94d0000/docs/reviews/flip-quiet-checks-20261104.md)
retains the new artifact/state boundaries and keyboard evidence for Hexsweeper
and Attractive. Continue with Signal in the foreground, then check background
apps on resume separately. Clear only the synthetic draft afterward. Keep
TalkBack off; no collection, microphone, model execution or sensitive permission
is needed for the layout/continuity test.

## Rollback

Reverse only this branch's cover-layout patch, including the compact-navigation
helper, dialog wrapper/call-site changes, Android boundary/inset helper and tests.
Preserve this dated record and its evidence when reverting the implementation.
Existing storage and permission schemas are unchanged. Never
uninstall the production package to roll back an upgrade without a separate
owner decision: uninstalling removes its app data.
