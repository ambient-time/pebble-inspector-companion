# On-device answers

Implementation plan, October 3, 2026. Not a delivery receipt.

Add explicit Gemini Nano and Gemma choices through the existing reviewed-question provider path. Preserve one-use consent, evidence selection, existing cloud providers and keys. Local inference must never silently fall back to cloud. Nano requires the top foreground activity; Gemma runs in a separate non-exported process after a user-requested download/import.

Reuse LocalType's verified download patterns and pinned Gemma artifact through a source-vendored Android module shared with Gadget Watch. Each app owns its own model storage. Reject oversized local context before review and execution, rather than silently trimming evidence. Local Home tool execution remains disabled, including manual overrides.

Verify provider routing, no network/secret reads, cancellation, readiness, checksum and disk-space failures, and phone lifecycle behavior. Keep store previews private; no speaker claim is added to the Signal watch app.
