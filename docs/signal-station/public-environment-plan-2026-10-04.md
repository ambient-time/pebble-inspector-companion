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

- [ ] Existing persisted Home documents and HTTP/MQTT bindings remain compatible.
- [ ] Public connectors never read/send credentials or expose executable actions.
- [ ] Only selected categories are requested; no automatic source migration,
      polling service, model sharing, or device-location lookup is added.
- [ ] All listed weather fields, forecasts, daylight, AQI, UV and pollen work.
- [ ] NWS coverage/empty/expired/partial/failure cases are distinct and tested.
- [ ] NOAA station identity, prediction time, metric units and MLLW datum are shown.
- [ ] USGS results retain event times, magnitude type, depth, distance, radius,
      lookback and source; truncated or failed results never imply an all-clear.
- [ ] Timeouts, response limits, redirects, cancellation and partial failures
      are bounded and tested without model API calls.
- [ ] Phone setup, correction, preview/save, favorites, unit choice, refresh,
      stale/offline states and removal have observable success evidence.
- [ ] Watch favorites deliver readable source/age/unit summaries; full hazard
      instructions remain accessible on the phone without silent truncation.
- [ ] Captures preserve original typed evidence; no modeled time is relabeled
      as a sensor measurement and no old value becomes fresh on retrieval.
- [ ] Gadget integration is implemented and verified after ownership coordination,
      or explicitly remains open; a plan alone is not completion.
- [ ] Tests/builds/rendered checks/install evidence are recorded separately.

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
