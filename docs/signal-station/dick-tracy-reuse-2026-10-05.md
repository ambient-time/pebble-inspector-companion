# Dick Tracy reuse — October 5, 2026

By Luke Steuber. Source changes only; no new installed or published package.

## Pulled into Signal

Dick Tracy's `AgentToolAdapters.calls` rejects failed provider completions and
unsupported tool requests before dispatch. Signal already rejected most failure
states, validated whole batches, deduplicated call IDs and rechecked permissions.
Two missing guards were adapted into its existing shared response parser:

- OpenRouter/custom Chat Completions `error` and `error_limit` finish reasons
  reject the answer and any accompanying Home calls, including uppercase forms.
- OpenAI/xAI Responses containing `computer_call`, `mcp_approval_request` or
  `shell_call` reject the entire response before any Home call executes. These
  tools are not part of Signal's declared Home interface.

Both ordinary chat and Home tool chat use this parser. Fixed error messages do
not expose response bodies. Existing permissions, retries, request budgets,
provider support and local/cloud selection are unchanged.

Prior art: Dick Tracy `35f337937ad96ac867db3a85ac5171c44c464b66`,
`android/app/src/main/java/com/lukesteuber/gadgetwatch/AgentToolAdapters.kt`,
reviewed at repository tip `3191eb2327441dd005c32a2a47b897aba28254ef`.
Original contributions in both projects are by Luke Steuber under MIT.

## Already shared

The local-model module matches byte-for-byte, excluding generated build/cache
directories. Its Gemini Nano/Gemma lifecycle and download safeguards need no
second port. Dick Tracy's five-file public-data manifest passes its pinned
source-parity check against Signal revision `395d7b13`; the public-data client
originated in Signal. All five manifest hashes also match current Signal source.
Neither module was copied or changed here.

## Keep separate

Dick Tracy's imported skills, MCP/OAuth, host tasks, schedules and notification
delivery form a separate execution system. Importing that stack is not needed
for Signal's sensing, provenance and per-question context selection. The
Outside brief's source coverage, freshness and empty-report tests are useful
reference cases for a future reviewed context-snapshot exchange; no automatic
sharing or new data collection was enabled.

## Verification boundary

Three regression tests failed before the production change. They cover failed
completion dispatch, mixed unsupported/supported tool batches in either order,
and rejection of unfinished text in ordinary chat. Fixtures are synthetic and
make no live provider or household requests.

After the fix, all 27 provider/tool tests pass. The complete shared host suite
discovered 389 tests: 381 passed, eight opt-in skips, zero failures or errors.
Android app lint and shared iOS simulator compilation passed. The latter is
shared-source validation, not Signal iOS product or runtime acceptance.

This is a source-level safety port, not a device acceptance or release receipt.
The existing private build 23 installs, public downloads, watch package and
pairings are untouched. Physical favorites and current TalkBack acceptance
remain open as recorded in the [build 23 receipt](attention-watch-validation-2026-10-04.md).
