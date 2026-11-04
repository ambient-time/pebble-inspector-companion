# Foreground reading light

By Luke Steuber. Verified October 3, 2026.

Signal Station Android development preview build 19 keeps the screen lit while its activity is visible. It uses Android's window screen-on flag; backgrounding or locking the phone resumes normal behavior. Saved settings, history and app identities are unchanged.

The native Pebble app already holds its backlight while focused and releases it when covered or closed. No watch rebuild or public release was needed for this change. The public Android build 17 download and separately staged Pebble 1.8.0 preview remain unchanged.

- Android host suite: 297 cases, 289 passed, eight existing skips. SignalApp has no JVM test source. Lint and assembly passed.
- Pixel 10 replacement installation succeeded. Installed APK SHA-256 matches the local artifact: `0feafabc7ede81a980939cebb6b0d222bb1f5d00cf1e98252a62906559014083`.
- After reconnection, the post-commit build 19 was installed successfully. Its source-revision stamp is `bb1dc5cb`, and installed SHA-256 matches `a86fac973384bde2bf6c376ce3d24a87ad8da0ac076a55298962f9677d4368f7`. The original first-install timestamp is retained. This supersedes the earlier installed bytes above; the lighting implementation is identical.
- The pinned standalone Pebble 1.8.0 preview was also installed successfully, using SHA-256 `6b5f8695a8c59edf189d6f7bd1d2eac8eaf00454c767053f48507b6f3942b0ca`. A run-state request confirms Signal Station's UUID. A notification covered its screenshot, so foreground interaction remains unverified. The Store draft and public 1.7.1 release were not changed.
- The Pixel's lock screen prevented a visible foreground timeout check. Physical duration and battery behavior remain unverified. No lock settings, brightness, volume or pairing were changed.

Implementation uses [Android's foreground screen-on flag](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).
