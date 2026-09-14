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

September 9 implementation and review disposition:

- Ask and saved response details share a native Markdown renderer, pinned to
  multiplatform-markdown-renderer 0.43.0. Original text remains in storage and
  exports. Headings expose accessibility semantics; text stays selectable;
  lists, tables, emphasis and code render as formatted content. HTTP(S) links
  open on a tap. Other schemes and URLs with credentials are rejected. The
  image transformer performs no remote fetches.
- A confirmed launch bug compared the new runner against the connection ID
  captured before opening the watch app. The wait now reads the current watch
  connection on each pass, rejecting disconnected, closed, stale or different
  watch sessions. A regression covers the ID change during launch.
- Settings now has Check connection and separate phone-runtime, phone-handshake
  and watch-acknowledgement status. The action starts no dictation or capture.
  A watch acknowledgement is shown only after the host reports successful
  delivery of BridgeReady. This is transport evidence, not proof of sensor,
  dictation or physical-watch behavior.
- A failed local runtime can be recreated on retry. An in-flight runtime is
  awaited instead of issuing a duplicate start. Status callbacks are gated to
  the current session. Pairing and firmware paths are unchanged.

Requested advisory passes used the installed review CLI with explicit models:
`anthropic/claude-opus-4-8` and `xai/grok-4.5`. Opus returned findings on response
rendering and handshake diagnostics. Its response ended during its fifth
finding; it is not an exhaustive audit. The first Grok request timed out; a
smaller retry completed with three watch-session findings. Full outputs are
retained with the local release evidence.

Both reviewers' runtime-versus-watch-handshake distinction was accepted for
status. The proposal to require a watch ACK before accepting the capabilities
request was rejected: that would prevent the capabilities response that creates
the initial handshake. Native session identity, selected-watch and route checks
remain required. The suggested disabled-install-button issue was checked and
rejected: this adapter advertises install=false. Wide numeric conversion was
not demonstrated to fail for this protocol, whose structured readings travel
as JSON strings and whose numeric wire fields are bounded integers.

Validation: 99 shared feature tests pass, including live-connection regression
and unsafe-link rejection. Five Android instrumentation tests pass and exercise actual WebView
protocol startup, session lifecycle, success/failure acknowledgement states,
and Compose Markdown output with link clicks. Tests use an isolated emulator
and simulated watch transport. No physical Pebble was operated or re-paired.
The exact user-reported watch-validation symptom remains pending.


September 14 release follow-up:

The exact published Android build15 passed both existing response-rendering
instrumentation tests on an isolated API36 emulator: heading semantics, emphasis,
lists, tables, code, explicit web link activation and blocked unsafe links. No
Android source or APK replacement was necessary.

Watch addon1.6.5 from b83a66c5 adds compact Markdown blocks, readable emphasis
text, literal fenced/indented code, link labels and an explicit shortened-reply
note. Original responses remain in Android history and exports. Unsupported
nested markup can remain literal; rich tables and clickable links belong on the
phone. Native frames from all six targets and32 protocol tests plus C sanitizer
regressions passed. Four code-preservation bugs found in the CLI review were
reproduced and fixed. No current physical watch rendering or battery result.

The Store saved1.6.5 unlisted with30 screenshots, six GIFs and six banners.
Public Store routes still returned404. The direct download page describes the
separate Android installation and links the Store with its availability caveat.
The private watch repository holds the publication receipt and media manifest.

All 100 exact public-byte checks passed across dr.eamer.dev and lukesteuber.com, including the new watch package, source, guide and media. Next: verify a physical install-to-chat path and a long reply continued on Android; consider a direct conversation handoff and a reading-specific backlight timeout after that probe.


September 14 continuation follow-up:

Watch 1.6.6 adds Hold Select on a displayed reply. The authenticated bridge binds
that request to its saved record and offers Open full reply in the Android app.
It does not launch the phone, change its conversation, cancel work or send a
provider request. Opening the exact local record preserves unsent drafts; Resume
conversation remains explicit. The handoff is in memory; after process restart,
cache eviction or deletion, use History. Backlight remains on throughout app
focus and returns to automatic behavior on focus loss or exit.

Setup now routes directly to Answers or Connected devices, with no automatic
permission request, collection or provider test. Stale installation-pause wording
was replaced with the current download guide and still-in-testing description.

Measured: 223 host tests (six existing skips), 34 watch protocol tests, actual C
button/backlight/collector checks and Markdown sanitizers; Android lint and five
focused emulator tests passed. These include older-reply identity, stale-session
and deletion rejection, draft preservation, and setup at 200% text. Native
six-target builds passed; physical handoff and battery use remain unmeasured.
