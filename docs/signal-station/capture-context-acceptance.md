# Capture context and navigation: build 9

By Luke Steuber. September 10, 2026.

Android **0.3.1-context-dev (9)** fixes evidence preparation and replaces the crowded top-level controls with Now, Ask and History. The package and development signing lineage remain unchanged. The matching public artifact and digest are recorded separately in the release toolkit; a source commit is not a publication receipt.

## What changed

A saved capture could exceed the old whole-record 64 KiB analysis allowance and disappear from a request. Evidence preparation now produces dated, source-balanced excerpts, preserving record identity, source coverage and honest omission counts. The original encrypted record stays unchanged. An attachment that cannot contribute evidence produces an actionable error before any request is sent. Provider-specific JSON encoding is included in the final request-size check.

Capture once saves locally and attaches the result to Ask. Analyze, Compare and Capture and prepare open drafts for review; they do not make a paid request. The review shows included captures, readings, omissions and previous turns. Each attachment can be inspected or removed without deleting history or starting a new conversation. Successful questions and failed-request retries retain attachments; reopening a conversation restores its linked evidence. Changed or deleted evidence is checked again before sending. Disabled sources cannot be sent through an older attachment.

Now puts capture, observation sessions and the latest reading first. Ask shows the question, readable responses and visible attachments. History retains search, sessions, patterns, comparisons, exports and deletion controls. Nearby, source selection, wake phrase and watch controls remain available through these destinations and Settings. No source is silently enabled. The Pebble installation hold and existing pairing are unchanged.

## Verification

The host suite checks 176 cases: 170 passed and six explicitly gated live-service cases skipped. Android lint and both APK builds passed. Real encrypted storage and the production question/request builder were exercised with synthetic data and a mock HTTP transport: capture, oversized capture, comparison, follow-up, exact-payload retry, provider changes, attachment removal, disabled/deleted evidence, empty capture, and restored conversation evidence.

All 33 ordinary Android integration/UI cases passed on the Android 16/API 36 emulator. They include normal and 200% text sizes, keyboard dismissal, attachment removal, reviewed sends, numeric evidence and nearby lookups. A separate opt-in run with the installed TalkBack service passed accessibility focus, activation and destination checks for Ask, History and Now; its original service settings were restored. Settled test screenshots cover Now, attached Ask, review and History. Runtime and upgrade acceptance are recorded with the release receipt after the final signed build is frozen.

No real provider credential, physical phone sensor reading or Health Connect account was validated in this change. Earlier service and Android-version observations in the [sensing acceptance record](sensing-acceptance.md) remain dated evidence, not a fresh test of build 9. Watch safety and physical-device acceptance remain open.
