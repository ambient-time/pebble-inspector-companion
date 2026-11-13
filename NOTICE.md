# Third-party notices for the Signal Station APK

The Signal Station Android APK is built from `:signalApp` (package
`com.lukesteuber.signalstation`). My own code in it is under the
[MIT License](LICENSE-MIT); see [LICENSING.md](LICENSING.md). The APK also
bundles the third-party software and data listed here. Each item stays under
its own terms, which my MIT grant does not change.

This list describes 0.9.8 (30). I took the coordinates and versions from the
Gradle files and the resolved release runtime classpath
(`./gradlew :signalApp:dependencies --configuration releaseRuntimeClasspath`),
and the license names from each library's published POM. For the full terms,
see the library's own license. This file is a summary, not a substitute.

It covers the Signal Station APK only. Builds of `androidApp` are separate
GPLv3 works with their own dependencies.

## Not free software

The APK links these libraries, which are distributed under terms that are not
free software licenses:

| Component | Coordinates | Terms |
| --- | --- | --- |
| Google ML Kit GenAI Prompt, used for Gemini Nano answers on the phone | `com.google.mlkit:genai-prompt:1.0.0-beta4`, with `genai-common:1.0.0-beta4` and `common:18.11.0` | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |
| Libraries that ML Kit brings with it | `com.google.mlkit:genai-schema:1.0.0-alpha1`; Google Play services `play-services-base:18.5.0`, `play-services-basement:18.9.0` and `play-services-tasks:18.2.0` | [Android Software Development Kit License](https://developer.android.com/studio/terms.html) |
| Garmin Connect IQ companion app SDK, used for the Garmin watch link | `com.garmin.connectiq:ciq-companion-app-sdk:2.4.0` | Garmin's Connect IQ SDK License Agreement and Connect IQ Application Developer Agreement; see the [Connect IQ SDK page](https://developer.garmin.com/connect-iq/sdk/) |

## Offline speech recognition

| Component | Coordinates or source | License |
| --- | --- | --- |
| Vosk Android, including its native library `libvosk.so` | `com.alphacephei:vosk-android:0.3.75` | Apache-2.0 |
| Wake model `vosk-model-small-en-us-0.15`, Copyright 2020 Alpha Cephei Inc. | Downloaded at build time from the [Vosk model catalog](https://alphacephei.com/vosk/models) and pinned by SHA-256 in `signalApp/build.gradle.kts` | Apache-2.0 |
| Java Native Access (JNA), including `libjnidispatch.so` | `net.java.dev.jna:jna:5.18.1`, a dependency of Vosk | LGPL-2.1-or-later or Apache-2.0, at the recipient's choice; used here under Apache-2.0 |

The build packages the Apache License 2.0 text and the model notice as
`assets/signal-station/LICENSE-vosk.txt` and
`assets/signal-station/WAKE_MODEL_NOTICE.txt`, from
`signalApp/src/main/assets/signal-station/`. The published 0.9.6 (28) APK
predates that and does not contain the two files; later builds do. Vosk's
native library is built from other speech projects as well; see the
[Vosk source](https://github.com/alphacep/vosk-api) for their notices.

## Other bundled libraries

| Component | Coordinates | License |
| --- | --- | --- |
| PebbleKit Android 2 client | `io.rebble.pebblekit2:client:1.3.2`, with `client-api`, `common` and `common-api` 1.3.2 | Apache-2.0 |
| Ktor client, with its OkHttp and CIO engines and WebSockets | `io.ktor:ktor-client-core:3.5.1` and the other `io.ktor` 3.5.1 modules it resolves | Apache-2.0 |
| ktor-mqtt | `de.kempmobil.ktor.mqtt:mqtt-core:1.2.0` and `mqtt-client:1.2.0` | Apache-2.0 |
| LiteRT-LM, including `liblitertlm_jni.so`, used for Gemma answers on the phone | `com.google.ai.edge.litertlm:litertlm-android:0.17.0` | Apache-2.0 |
| OkHttp and Okio | `com.squareup.okhttp3:okhttp:5.3.2`, `com.squareup.okio:okio:3.17.0` | Apache-2.0 |
| Kotlin standard library and kotlinx libraries | `org.jetbrains.kotlin:kotlin-stdlib:2.4.10`; kotlinx-coroutines 1.11.0, kotlinx-serialization-json 1.11.0, kotlinx-datetime 0.8.0, kotlinx-io 0.9.1, atomicfu | Apache-2.0 |
| AndroidX and Jetpack Compose, including Room 2.8.4, DataStore 1.1.7 and the Health Connect client 1.1.0 | `androidx.*` | Apache-2.0 |
| Protocol Buffers runtime repackaged inside two AndroidX libraries | `androidx.datastore:datastore-preferences-external-protobuf:1.1.7`, `androidx.health.connect:connect-client-external-protobuf:1.1.0` | BSD-3-Clause |
| Compose Multiplatform | `org.jetbrains.compose.*`, `org.jetbrains.androidx.*` | Apache-2.0 |
| Kermit logging | `co.touchlab:kermit:2.1.0` | Apache-2.0 |
| Markdown rendering | `com.mikepenz:multiplatform-markdown-renderer-m3:0.43.0`, `org.jetbrains:markdown:0.7.5` | Apache-2.0 |
| Firebase component and encoder libraries and Google DataTransport, which ML Kit brings with it | `com.google.firebase:firebase-components:16.1.0`, `firebase-encoders:16.1.0`, `firebase-encoders-json:17.1.0`, `firebase-annotations:16.0.0`; `com.google.android.datatransport:transport-api:2.2.1`, `transport-runtime:2.2.6`, `transport-backend-cct:2.3.3` | Apache-2.0 |
| Guava, Gson and annotation libraries | `com.google.guava:guava:32.0.0-jre`, `com.google.code.gson:gson:2.14.0`, `com.google.errorprone:error_prone_annotations:2.48.0`, `com.google.j2objc:j2objc-annotations:2.8`, `com.google.code.findbugs:jsr305:3.0.2`, `javax.inject:javax.inject:1`, `org.jspecify:jspecify:1.0.0`, `org.jetbrains:annotations:23.0.0` | Apache-2.0 |
| Checker Framework qualifiers | `org.checkerframework:checker-qual:3.33.0` | MIT |
| SLF4J API | `org.slf4j:slf4j-api:2.0.18` | MIT |
| Reactive Streams | `org.reactivestreams:reactive-streams:1.0.3` | CC0 |

OkHttp also packages a copy of the Public Suffix List as
`assets/PublicSuffixDatabase.list`. That list has its own terms; see the
library's own notices.

## In the app

From 0.9.8 (30) the app shows these notices itself, with the full license
texts, under Settings, About. That file is
`signalApp/src/main/assets/signal-station/THIRD_PARTY_NOTICES.txt`, written by
`scripts/signal_notices.py` from the resolved release classpath; the script's
`--check` mode fails when the file is stale.

## What is not in the APK

The APK contains no Core Devices code. Model files for Gemma are not bundled:
the app downloads or imports one only when asked, and each model has its own
terms. Weather, place and model providers are network services, not bundled
software.
