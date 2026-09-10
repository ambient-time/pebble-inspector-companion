# Reviewed questions preview

Android 0.2.2-learning-dev (6) retains the separate package and preview signing
identity. The phone work includes ranked history projections, local message
review, explicit Send, encrypted saved questions, local diagnostics and a
build/source identity. Existing capture, learning, observation, health and
provider features remain available.

The tokenizer was not broken. The actual retrieval defect used matching
records as a predicate, discarding ranking and filtered excerpts. Projection
now keeps original identity separately from sendable content. The phone sends
the reviewed messages unchanged, after checking evidence and memory again.

Fresh checks: 112 host tests and 22 Android instrumentation tests passed on
Field_Inspector_API_36_1 (Android 16). Instrumentation covers real encrypted
storage and station orchestration with a mock provider transport, exact
preview/payload parity, deletion between review and Send, saved-question
persistence, attachment retention through Settings, and the review flow at
200% font size. It does not establish live provider-account behavior or a
physical Android/Health Connect/watch result.

An independent implementation review found two state transitions that dropped
saved attachments or retained an incompatible history preview. Both were
corrected. Saving recipes follows the operation generation guard so a cancelled
save cannot restore an old draft after navigation.

The watch package remains 1.4.0. Its installation hold and the unresolved
settings-wipe report remain in effect. This change adds phone-side button help
and preserves acknowledgement distinctions; new watch haptics and physical
capture/answer/history/reconnect validation remain held. No pairing, firmware,
reset or watch-settings operations were added.

Build identity comes from signalApp/release.properties and the committed source
revision. Publication verifies those facts against the signed APK, checks the
previous certificate, then derives immutable artifact names and public metadata.
Final hashes are external receipts, never embedded into the APK itself.
