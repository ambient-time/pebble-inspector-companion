# Asking, capture, and watch connection

By Luke Steuber. September 9, 2026.

## Product decision

Signal Station opens with a question field. Phone chat requires a saved provider
and model; it does not require a watch, an ESP32, or sensor permissions. Capture
and Nearby offer their own source choices. The watch is an optional input and
feedback device. Fixed room receivers remain an optional extension.

The current Android test build is a companion fork. It owns Bluetooth pairing,
intercepts the Signal Station watch script's native requests, and provides a
custom watch transcription hook. An update preserves the existing package,
credentials, history and pairing.

The independent Android development shell now lives in `:signalApp`, backed by
the extracted `:signal` feature module and a PebbleKit2 message adapter. It uses
the existing Pebble app's pairing. The optional watch preview declares the new
Android package and carries no PKJS. The custom OpenAI watch-transcription hook
is retained in the legacy adapter; stock-host exchange, custom speech parity,
and cross-package migration remain release gates. See [the separation record](separation.md).
Public installation remains paused following the settings-wipe report.

## Task paths

Before: touch or keyboard user opens Capture, discovers Ask among five peers,
passes wake controls, types a question, and loses the draft on Send. A failed
record displays only its state until opened in History.

After: the same user opens Ask, sees provider readiness and the question field,
sends, and reads the answer or failure beside the question. The draft survives
navigation and failure in memory. Saving provider setup is one action. A reply
clears only its matching submitted draft; no automatic retry occurs.

Preserved: individual source choices, explicit sending, keyboard dictation,
watch dictation, wake controls, cancellation, history inspection and deletion.
Active wake listening and pending voice drafts remain visible when options are
collapsed. Capture tools and optional watch setup retain labelled discovery.

Changed: fewer discovery steps before typing; failure recovery keeps the input.
Completion time and assistive-technology usability are unmeasured. Source
inspection establishes labels and native control semantics, not TalkBack
conformance. Moving advanced tools adds one explicit expansion step for those
users; core questions and captures become easier to find.

## Acceptance checks

- Open Ask with no paired watch and no enabled sources.
- Type, visit Settings, return, and retain the same question.
- Block Send until a model and saved key are available.
- Save model and key together; test only saved values.
- Fail a request and show its fixed diagnostic beside the retained question.
- Keep pending submission tracking while switching tabs.
- Open recent captures before advanced tools; reach every prior tool by label.
- Preserve active Stop listening and draft review controls.
- Expire nearby current-state claims while retaining historical last-seen time.

Physical watch pairing, real provider-account replies, keyboard and TalkBack
walkthroughs, and measured completion times require their own evidence.
