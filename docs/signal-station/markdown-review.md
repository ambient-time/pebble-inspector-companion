# Response rendering and watch connection review

Luke reports that phone features work in development build 2, answers display
raw Markdown, and watch validation is difficult. The exact watch symptom is
pending. Grok 4.5 and Claude Opus 4.8 were asked for independent advisory reviews
of the current message adapter, native handshake, provider setup and response UI.

Use one native Compose Multiplatform response renderer in Ask and saved details.
Preserve the original response in storage and exports. Keep selection, heading
semantics, lists, code and tables; remote images must not load automatically.
Link activation is explicit and limited to web URLs.

Investigate watch validation through local protocol tests and actionable status.
Do not change pairing, firmware, or the recovered watch to reproduce the report.
Review findings are hypotheses until checked against the implementation.
