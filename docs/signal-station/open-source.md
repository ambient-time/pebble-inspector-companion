# Preparing public source access

The repository remains private. The download page offers source archives for
published previews; making the full Git history public is a separate step.

The README now covers Signal Station's installation, collection, chat and data
controls. The developer guide separates the app from the inherited Pebble host.
Upstream documentation and contribution rules remain in `UPSTREAM.md` and
`CONTRIBUTING-UPSTREAM.md`. License files are unchanged.

Before changing visibility:

- Classify the 17 credential-scanner matches in inherited upstream history.
  Every flagged commit belongs to the locally retained `upstream/master` history.
  That establishes origin, not whether a key is harmless or still usable.
  Matches include test fixtures, service configuration and lockfile entries.
- Review personal captures, identifiers, logs and screenshots across branches
  and tags. A credential scan cannot clear personal data or media rights.
- Choose the branch visitors should see. GitHub defaults to `inspector-lab`;
  current work is on `codex/signal-station`. Review the merge or default-branch
  change before publication.
- Verify a fresh-clone build, dependency and model notices, source packaging and
  current instructions. This documentation edit did not rebuild Android or repeat
  physical-device testing.
- Confirm the final public name and organization against the ongoing portfolio
  survey, then change visibility explicitly.

Luke Steuber's original contributions now have an MIT grant. Keep GPLv3 and
Core Devices notices for inherited software. The watch repository now has its
own MIT license; neither grant relicenses third-party material.

Review date: September 14, 2026. Both repositories were private. The Android
history scan covered 2,262 commits with 17 matches, all inherited from upstream.
The watch scan covered 45 commits with no matches. These counts are scan results,
not publication clearance. Detailed redacted findings stay outside source while
review continues.
