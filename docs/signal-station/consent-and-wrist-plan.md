# Consent and wrist implementation

By Luke Steuber. October 3, 2026.

The paired implementation plan is `pebble-field-inspector/docs/consent-and-wrist-plan.md`, checkpointed before implementation. Baseline: Android `3014f332`, watch `4816736`.

Implement frozen per-request context, isolated phone/wrist/wake drafts, per-question Home read/action authority retaining explicit model standing grants, always-reviewed wrist questions, exact capture/follow-up, wrist Home scope selection, non-mutating Home review, bounded native-intent replay, unified scalar comparability and versioned baseline eligibility. Preserve stored originals and grants; no publication or personal-device installation.

Reuse existing PreparedQuestion, SignalEvidenceSelection, SignalTrend and SignalHomeEngine contracts. Reference Gadget Watch `0ffef1e` for MIT-licensed transport pause/identity and sanitizer tests; do not copy its monotonic request high-water policy, artwork, audio or provider defaults.

Verification must distinguish host tests, Android instrumentation/emulators, Pebble builds/rendering, artifact identity and physical acceptance. New protocol keys are append-only and negotiated. Legacy peers retain safe capture/history with phone-review/update guidance where the new wrist review contract is absent. No Room schema change is planned solely for replay retirement.
