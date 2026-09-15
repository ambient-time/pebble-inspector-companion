# Home connections

Home is available in Android 0.5.0-home-dev (build 17), with optional favorites in
watch package 1.7.1. These are validation packages; this change does not publish
them or change the public download. Existing data and signing identities remain
in place. Nothing is connected or preauthorized during upgrade.

## Connect and choose controls

Open **Home → Connections → Add connection**. Choose Home Assistant, openHAB or
Geepers, enter a distinct name, server URL and token, then test and review the
catalog before saving. Home Assistant uses a user-supplied long-lived token;
openHAB uses an API token; Geepers uses its separate revocable phone token.
Selections in this app do not narrow a token's server-side permissions.

HTTPS and existing VPN access work directly. A trusted private HTTP connection
requires an explicit setting on that connection. Redirects cannot forward its
credentials, certificate validation stays enabled, and model requests remain
HTTPS-only. The Android manifest allows local cleartext so this dedicated Home
policy can admit selected private destinations; other provider endpoints retain
their own HTTPS validation.

**All devices** lists the connection's catalog, including unavailable devices and
unsupported controls. Add reading or action shortcuts, choose labels and rooms,
and mark watch favorites. A long drag moves a card; **Move earlier** and **Move
later** offer the same ordering without a drag. Control parameters come from the
system's declared capabilities: on/off, numbers/ranges, choices, commands and
named scenes. The app does not infer a command from a device's name.
Home Assistant controls must be both in the app's supported device-action set
and declared by the server; firmware installation, scene editing and controller
administration stay unsupported. Entity choices and numeric limits narrow the
declared parameters. openHAB command options take precedence over generic Item
commands, with declared bounds and units preserved.

Three choices remain independent:

- **Capture readings:** select devices in All devices, then enable Home readings
  in capture sources. Removing a grid shortcut does not remove this selection.
- **Grid and watch favorites:** shortcuts choose the controls at hand.
- **Home access in Ask:** selected systems expose their full catalogs for that
  user-initiated turn, whether or not their devices have shortcuts.

The app refreshes when Home opens and on explicit capture, watch or chat queries.
Home Assistant WebSocket and openHAB event connections run only while Home is
visible and the app is foreground. Background capture/watch work is bounded.
Readings keep their units, source, availability and timestamps. Phone collection,
source arrival and reported state update times are separate from measurement
time; unknown measurement times stay unknown.

## Ask with Home access

In the existing Ask evidence package, enable **Home access** and select systems.
Review names of those systems before sending. Relevant data is retrieved on
demand and sent to the chosen model provider. The complete inventory is not
attached to every question. Turning access off cancels pending queries and
undispatched chat actions. Requests already sent remain in activity.

Responses, Anthropic Messages, Gemini and compatible Chat Completions use native
typed tool calls and results. Unsupported models keep ordinary chat and attached
readings; agent controls are disabled. Custom model support can be explicitly
enabled in answer settings. A server rejection does not fall back to executing
text. Device names and attributes are treated as untrusted data.

Tools search a bounded catalog page, read an exact entity, list its supported
actions or request one action. Every target uses a stable connection/entity ID;
ambiguous names cannot dispatch. Each turn has at most eight tool rounds under
the existing 60-second request deadline and 256 KiB encoded context limit.

## Permissions and receipts

Every action starts with confirmation. The review shows the exact system,
device, action and normalized parameters, expires after two minutes and is
consumed once. **Allow this exact action without future confirmation** grants
that exact combination, including locks or scenes when explicitly chosen.
Review and revoke grants in **Home → Activity & permissions**. Models cannot
create or broaden these permissions. Grid, chat and watch share the same engine.

Scene permission invokes the server's current named definition. Detectable
identity, label or capability changes invalidate permission; hidden server edits
may not be detectable. Replacing a connection revokes its grants. Administrative
configuration, shell commands, firmware updates and automation authoring are
outside Home.

An encrypted intent is saved before sending. Repeated tool calls and watch
packets are deduplicated; uncertain mutations are not retried. The watch replay
journal retains up to 4,096 requests without eviction; if full it refuses new
mutations. Reconnecting does not erase that protection.

Activity distinguishes sending, hub accepted, matching state observed, failed
and outcome unknown. Read-only reconciliation lasts at most 30 seconds. A hub's
command-complete receipt alone remains accepted with an explanatory message;
matching reported state is a separate observation and still does not prove
physical movement. Cancellation does not undo a sent command.

Geepers keeps Matter/Zigbee controllers and credentials on the hub. Its Home API
exposes their observations and supported Node Hub display commands. Phone
display controls require the hub's bounded-dispatch capability; older hubs remain
read-only. Sleeping/offline targets cannot queue a new phone action. A short
deadline prevents delayed first dispatch; already-transmitted network messages
cannot be recalled by the phone.

## Pebble

Up/Capture, Select/Ask, Down/History and existing help remain. Long-Down at home
opens a paginated favorites list. Up/Down navigate; Select opens details and the
chosen action. Actions with standing permission can execute directly; others
show the exact review before Select confirms. Oversized or unsupported reviews
hand off to the phone. The watch holds no hub credentials, catalogs or grants.
Older watch builds retain their existing behavior through capability negotiation.

## Implementation and validation

The `SignalHome*` connector/engine classes reuse Kotlin models and Ktor;
`AndroidHomeCoordinator` stores Home state in the existing encrypted document
store. `SignalAgentTools` owns native provider formats. Provider and connector
tests use fixtures; two opt-in host tests exercised disposable Home Assistant
2026.9.2 and openHAB 5.2.1 instances with synthetic devices and live events.

The validation run passed the Android host suite, Android build/lint, seven Home
UI emulator tests (including double text), four real station/storage integration
tests, and twelve existing chat/history/storage/capture checks. UI checks verify
accessibility semantics and actions; they are not a claim of a person's TalkBack
usability review. An additional opt-in emulator test verified that TalkBack stayed
bound with touch exploration active during grid reordering and exact confirmation
at 2× system text. The separate watch package compiled all six targets and passed
native C and JavaScript protocol tests. All six targets have inspected Home
favorites, details, complete confirmation, scrolling, cancellation and handoff
screens. Physical acceptance and release receipts
are recorded separately from source/build results.

Provider references: [Responses function calls](https://developers.openai.com/api/docs/guides/function-calling),
[stateless reasoning](https://developers.openai.com/api/docs/guides/reasoning),
[Anthropic tools](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools),
[Gemini function declarations](https://ai.google.dev/api/generate-content#FunctionDeclaration).
Connector references: [Home Assistant REST](https://developers.home-assistant.io/docs/api/rest/),
[WebSocket](https://developers.home-assistant.io/docs/api/websocket/),
[openHAB REST](https://www.openhab.org/docs/configuration/restdocs.html),
[API tokens](https://www.openhab.org/docs/configuration/apitokens.html).
