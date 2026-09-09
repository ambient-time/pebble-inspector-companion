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
