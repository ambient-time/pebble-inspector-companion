# Foreground reading light

By Luke Steuber. Planned October 3, 2026.

Keep the Android screen on while Signal Station's activity is visible using the window screen-on flag. Android resumes normal timeout behavior when the activity is backgrounded. Preserve all saved connections, permissions, history and pairing.

The native Pebble app already holds its backlight while focused and releases it when covered or closed. This change needs only an Android update: development preview build 19. Keep the public build 17 download and the separately staged watch preview unchanged. Validate Android tests, lint and assembly, then install the update in place on the connected Pixel.
