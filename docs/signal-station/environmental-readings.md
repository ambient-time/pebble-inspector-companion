# Temperature, humidity and MQTT

Home readings work on the Android phone without a watch. Connect a supported
system, review its devices, and add reading shortcuts to **Home → Favorites**.
Choose **Source units**, **°C** or **°F** under Temperature units. This preference
also applies to watch-favorite details; captured observations retain their
original values, units and timestamps.

Home Assistant, openHAB and Geepers remain supported. Temperature and humidity
are identified from declared metrics or units, not a sensor's name or brand.
Room groups are labels you assign. A thermostat target is not an ambient reading.

## MQTT connection

This preview supports **MQTT 5 over WebSockets**. It requires a broker WebSocket
listener, usually an address such as `wss://broker.example/mqtt`. MQTT 3 and raw
`mqtt://` / `mqtts://` TCP connections are not supported in this version.

1. Open **Home → Connections → Add connection** and choose **MQTT over WebSockets**.
2. Name the connection and enter its broker URL. WSS validates the server
   certificate. Unencrypted `ws://` requires explicit trust and a private host.
3. Choose anonymous access only if the broker permits it. Otherwise enter a
   username and password. Use a broker account restricted to reading your topics.
   Credentials are kept in the phone's encrypted credential storage.
4. Add each reading's exact topic and label. No wildcard subscription or automatic
   household discovery occurs. Map temperature and/or humidity; unused fields
   can be blank. Source temperature units are configured per reading.
5. **Test & preview**, inspect the mapped readings or waiting state, then save.
   Add shortcuts from All devices. **Show on watch** is optional.

For a payload such as `{"temperature":22.5,"humidity":48}`, use `temperature`
and `humidity` as the fields. For nested JSON such as
`{"data":{"temperature":22.5}}`, use `data.temperature`. For a plain numeric
payload, use `$` in the appropriate field and leave the other field blank.
Separate scalar topics can be added as separate readings. Field paths select
data; they do not run expressions, scripts or templates.

Under **Timestamp & freshness**, optionally map a publisher-defined sample
timestamp. Supported timestamps are ISO 8601 with a timezone, Unix seconds or
Unix milliseconds. Only use this mapping when it actually describes sampling.
Choose a stale-after interval appropriate to the sensor's reporting schedule.

MQTT is read-only: it exposes no controls and sends no application publications.
It does not configure the broker or devices. Broker-level access rules still
apply; selecting a topic in this app does not restrict the broker credential.

## Freshness and connection behavior

A retained value is a broker's saved report. Without a sample timestamp, its
measurement age is unknown. A new untimestamped live message can establish receipt
time, but not measurement time. Refreshing or reconnecting does not manufacture
a measurement timestamp. Missing, malformed, expired and disconnected readings
remain visibly unavailable or stale rather than turning into zero.

Home Assistant/openHAB report update times and phone fetch times are not
automatically measurement times either. The card labels the available timestamp
basis. Original timestamps remain available to captures.

Live MQTT subscriptions run while Home is visible and the phone app is foreground.
Preview, refresh, capture, chat and watch reads use bounded sessions. There is no
new always-on subscription or background service. For a sensor that publishes
infrequently without retained state, a short read may show **Waiting for a
reading**; keep Home open for live delivery or configure an appropriate broker
retained report independently. Signal Station does not publish one for you.

## The phone's own temperature sensor

Android ambient-temperature and humidity sensors remain separate optional
Capture sources. They appear only when Android exposes that hardware. Battery
temperature and outdoor weather are not substitutes for an ambient sensor.
Missing hardware is shown as unavailable. No sensor is enabled by this upgrade.

## Compatibility

The existing Home-favorites watch protocol is unchanged and does not require a
speaker. The shared source targets Android and iOS; Signal's iOS application
shell is not yet validated. Build, automated checks, physical installation and
public release are separate milestones. See the dated implementation record for
this preview's measured evidence.

Protocol references: [MQTT 5](https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html),
[shared client library](https://github.com/ukemp/ktor-mqtt),
[Android environmental sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_environment).
