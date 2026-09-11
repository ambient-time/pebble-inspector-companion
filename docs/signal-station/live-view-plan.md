# Live signal view

By Luke Steuber. September 10, 2026.

The first release adds a phone-only Live view under Now. Strong, medium and faint bands describe received signal strength, with no compass bearing, metre estimate, phone count or person count. Counts describe retained Bluetooth advertisers and Wi-Fi access points. Cached observations, omissions and scan failures remain explicit.

Only explicitly selected Bluetooth/Wi-Fi sources participate. Names, identifiers and service metadata retain their independent choices. A bounded foreground session refreshes five-second BLE windows and uses Wi-Fi cache between active requests at least 35 seconds apart. Android may further throttle scans. Stop, leaving the view, backgrounding, source changes and a five-minute deadline cancel collection. There is no ambient audio/video, cellular handset discovery, external lookup or automatic model request.

A bounded in-memory history maintains session-local entries and short signal trends; repeated cached timestamps are not new samples. Entries become stale after 15 seconds and leave the view after 60 seconds. Session labels are local to the current session. Explicit Save snapshot or Ask about this scene freezes fresh visible evidence and source outcomes into the existing encrypted capture format; Ask opens a reviewed draft using the build-9 attachment path. Leaving Live does not erase an explicitly saved capture.

Validation covers fake-clock aging, duplicate/cache handling, identity and source filtering, bounds, cancellation, no background persistence/transmission, exact snapshot request payload, and UI flows at normal and 200% text. Publication retains the existing preview signature and watch hold.

Later work: cooperative Android ranging can supply measured distance and supported direction; calibrated ESP32 nodes can add room/floor-plan estimates. These are separate capabilities, not simulated by this view. Reference implementations: https://github.com/BLE-Research-Group/MetaRadar and https://espresense.com/companion/. Platform reference: https://developer.android.com/develop/connectivity/ranging.
