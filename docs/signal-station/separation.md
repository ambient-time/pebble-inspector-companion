# Signal Station separation

By Luke Steuber. September 9, 2026.

Signal Station owns questions, provider keys, source choices, captures, history,
presence experiments and wake listening. The user's Pebble app owns pairing,
firmware, watch settings and the app locker. A narrow watch adapter carries
Signal Station messages and reports its capabilities.

Preserve the existing fork adapter and custom watch transcription while building
the independent Android shell. The shared feature module must not depend on
LibPebble, firmware services, BlobDB, Firebase, ring services or the parent app.
The independent shell must not instantiate them. Android PebbleKit2 can carry
watch app messages; raw dictation audio is not exposed by its public client API.
Do not present that capability as available through the new adapter.

The interface has Ask, Activity and Settings. Activity retains Capture, Nearby,
History and the labelled advanced experiments. First use offers a question or a
local capture; watch setup is optional. All sources remain individually opt-in.

Touch/keyboard: open app -> save provider -> ask -> see the answer or a retained
question with an error. Capture: choose sources -> capture -> inspect dated
readings -> explicitly analyze. Watch: existing pairing -> choose connected
watch -> open Signal Station -> capture/dictate/history -> quick feedback.

Acceptance: unchanged domain/protocol tests; adapter lifecycle/cancellation
checks; standalone dependency and manifest audits; isolated emulator walkthrough.
UI completion time and TalkBack usability remain unmeasured. A new adapter is
not proof of physical-watch safety. Keep the published installation hold and
preserve old package data until migration and capability parity are verified.
