# Signal Station release shrinking (R8).
# Libraries that ship their own consumer rules (kotlinx.serialization, Room, Ktor, ML Kit,
# Health Connect, AndroidX) need nothing here. These cover code reached by reflection or JNI.

# Vosk speech recognition talks to its native library through JNA.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**

# LiteRT-LM (on-phone Gemma) calls back into Kotlin/Java from native code.
-keep class com.google.ai.edge.litertlm.** { *; }

# Garmin Connect IQ companion SDK uses reflection over its message types.
-keep class com.garmin.android.connectiq.** { *; }

# ML Kit (Gemini Nano) discovers its components by reflection and needs their no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.common.internal.** { <init>(); }

# Keep line numbers so crash reports stay readable; the mapping file is kept with each release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
