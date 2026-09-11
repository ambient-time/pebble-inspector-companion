# Asking, capture, and watch connection

By Luke Steuber.

Signal Station is an independent Android app. Today offers capture and saved
questions; Ask prepares and reviews context before an explicit Send. A phone,
provider key and model are enough for answers. Capture and local review need no
provider key. See [the phone guide](personal-context.md).

The existing Pebble app owns pairing, firmware and watch settings. Signal
Station uses PebbleKit2 messages through that host. It does not replace the
Pebble app or pair a watch itself. Up captures, Select asks, and Down opens
history. Phone readiness is distinct from an actual watch acknowledgement.

The former companion fork is withdrawn following the unresolved settings-wipe
report. Its custom transcription hook is not part of the independent app.
Watch installation remains held. Stock-host exchange now has isolated Android
and Diorite emulator evidence for connection, capture, background capture,
history and reopening the phone app. Physical-watch validation and live
dictation remain separate. See [integration evidence](pebble-integration.md).

Question drafts survive navigation and provider failures in memory. Saved
questions persist encrypted until deleted. Opening a saved question performs no
capture or provider request. Evidence is rechecked at review and Send. No
automatic retry, provider fallback, reset, unpairing or firmware action occurs.
