# Public environmental data

## Scope

Add generic, opt-in public environmental readings to Signal Station's existing
phone and Pebble favorites. Preserve room sensors, MQTT, captures and exact-action
permissions. Prepare a portable data boundary for Gadget Watch, whose concurrent
release owns its checkout and device tests. No public release is authorized here.

## Sequence

1. Add typed readings retaining source, units, modeled/event/prediction time,
   fetched time, validity window, expiry, availability and attribution.
2. Reuse and extend the existing weather provider: current conditions, pressure,
   dewpoint, gusts, visibility, cloud cover, next six hours, daily highs/lows,
   daylight, air quality and UV. Add seasonal European pollen with explicit
   coverage/missing states.
3. Add official NWS alerts with coverage checks; NOAA tide predictions for an
   explicitly selected station/datum; bounded recent USGS earthquake queries.
   Empty applicable results, unsupported coverage and failures remain distinct.
4. Add a credential-free, read-only Public environment connection. Default all
   categories off. Disclose outgoing search/place/station information before
   requests. Test then save the exact previewed configuration. Retain independent
   favorites, watch selection, capture selection and question consent.
5. Render readable phone cards and concise watch details. Keep every configured
   metric reachable; show attribution, forecast/event times and stale states.
   Keep provider text untrusted and never turn hazard reports into playful copy.
6. Integrate portable readings into Gadget's explicit tool/data context after its
   concurrent release ownership is resolved. Keep its chat-first presentation,
   with quiet optional weather context rather than a second sensing dashboard.
7. Run parser/transport/permission/compatibility tests, Android host and UI tests,
   lint, Android builds, shared iOS compilation and watch protocol checks. Inspect
   rendered normal/large-text/error states. Coordinate private device installation;
   record installed bytes separately from physical watch acceptance.
8. Commit and push scoped changes; record remaining hardware or regional-provider
   limitations. Public downloads and Store publication remain separate.

## Acceptance checklist

- [x] Existing persisted Home documents and HTTP/MQTT bindings pass compatibility tests.
- [x] Public connectors never read/send credentials or expose executable actions.
- [x] Only selected categories are requested; no automatic source migration,
      polling service, model sharing, or device-location lookup is added.
- [x] Listed weather fields, forecasts, daylight, AQI, UV and pollen pass parser fixtures.
- [x] NWS coverage/empty/expired/partial/failure cases are distinct and tested.
- [x] NOAA station identity, prediction time, metric units and MLLW datum are shown.
- [x] USGS results retain event times, magnitude type, depth, distance, radius,
      lookback and source; truncated or failed results never imply an all-clear.
- [x] Timeouts, response limits, redirects, cancellation and partial failures
      are bounded and tested without model API calls.
- [x] Phone setup, correction, preview/save, favorites and unit choice pass rendered
      checks; stale/offline and removal pass synthetic station/model checks.
- [x] Watch source/age/unit summaries pass native request and byte-bound tests;
      full hazard instructions remain on the phone. Physical acceptance is separate.
- [x] Captures preserve original typed evidence; no modeled time is relabeled
      as a sensor measurement and no old value becomes fresh on retrieval.
- [x] Gadget/Dick Tracy integration is implemented, tested and privately installed
      after ownership coordination; its source is on the canonical main branch.
- [x] Tests/builds/rendered checks/install evidence are recorded separately.

The [validation record](public-environment-validation-2026-10-04.md) identifies
measured coverage and installed hashes. Physical wearable acceptance, real
selected-place/model answers and TalkBack remain unverified; no public release
or iOS runtime parity is claimed. This closes implementation/private delivery,
not those separate acceptance stages.

## Assumptions and risks

Android remains the validated product shell; iOS compilation is a development
check, not runtime parity. NWS is the first official-alert provider, not a global
alert service. NOAA coverage is station-specific. Open-Meteo pollen is seasonal
and European. Public endpoint licensing, attribution and rate limits still apply.
These views are on-demand information, not emergency notification or navigation
services. No source can guarantee that no hazard exists.

Provider references: [Open-Meteo](https://open-meteo.com/en/docs),
[CAMS/air quality](https://open-meteo.com/en/docs/air-quality-api),
[NWS](https://www.weather.gov/documentation/services-web-api),
[NOAA data](https://api.tidesandcurrents.noaa.gov/api/prod/),
[NOAA stations](https://api.tidesandcurrents.noaa.gov/mdapi/prod/),
[USGS](https://earthquake.usgs.gov/fdsnws/event/1/).
