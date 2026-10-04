# On-device answers

Private preview, October 3, 2026. Android 0.7.0-local-dev (20).

Settings → Answers offers On phone · Gemini Nano and On phone · Gemma alongside existing cloud providers. Existing provider choices, keys, history and pairing remain intact on upgrade. Changing sources starts a new conversation under the existing settings rules; prior history is kept. No automatic cloud fallback is permitted.

Local answers use the same review, durable one-use consent, cancellation and history path as cloud answers. The exact reviewed messages reach the selected engine. Context over 6,000 bytes is rejected before a usable review and again at dispatch, not silently trimmed by the local adapter. Local Home tool execution is disabled, including manual overrides; explicitly attached readings remain available.

Nano uses Android AICore and requires this app on screen, not merely a foreground service. Checking availability is deferred until Nano is selected or checked. Pixel 10 returned a real synthetic answer; Pixel 9a reported unsupported. Leaving the screen cancels a Nano turn, including preflight. Android controls availability, download eligibility, quotas and model version.

Gemma uses a separate non-exported LiteRT-LM process with bounded loading/generation and cancellation. Its optional 2,588,147,712-byte Gemma 4 E2B download is pinned by revision and SHA-256, uses unmetered transfer by default, and activates only after verification. Portable .litertlm imports are byte-, disk- and RAM-bounded. Models are stored in this app's noBackupFilesDir; no cross-app sharing of files or private data occurs. The source-vendored localmodels module matches Gadget Watch's module.

Verification: 293 Signal host tests passed and 8 were skipped; 15 module tests, Android builds and module lint passed. Device checks passed in both apps on Pixel 10 and Pixel 9a. A separate Android integration test confirmed exact review/send parity, one-use consent on success and failure, and rejection of oversized local evidence. Setup panels were inspected at 130% text scale. Native process startup was checked without model files.

No Gemma download was started. End-to-end Gemma transfer/import/inference, sustained background behavior and wearer-led watch acceptance remain open. Installed files and a successful Test on this phone result are deliberately separate checks. Speech recognition and external lookups retain their own network settings. This does not add speaker playback to Signal's watch app or publish a Store release or public APK.
