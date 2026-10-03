# Foreground reading light

By Luke Steuber. Verified October 3, 2026.

Signal Station Android development preview build 19 keeps the screen lit while its activity is visible. It uses Android's window screen-on flag; backgrounding or locking the phone resumes normal behavior. Saved settings, history and app identities are unchanged.

The native Pebble app already holds its backlight while focused and releases it when covered or closed. No watch rebuild or public release was needed for this change. The public Android build 17 download and separately staged Pebble 1.8.0 preview remain unchanged.

- Android host suite: 297 cases, 289 passed, eight existing skips. SignalApp has no JVM test source. Lint and assembly passed.
- Pixel 10 replacement installation succeeded. Installed APK SHA-256 matches the local artifact: `0feafabc7ede81a980939cebb6b0d222bb1f5d00cf1e98252a62906559014083`.
- The Pixel's lock screen prevented a visible foreground timeout check. Physical duration and battery behavior remain unverified. No lock settings, brightness, volume or pairing were changed.

Implementation uses [Android's foreground screen-on flag](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).
