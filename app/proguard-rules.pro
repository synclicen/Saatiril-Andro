# Socket.io
-keep class io.socket.** { *; }
-keep class com.saatiril.andro.data.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# ── MediaPipe Tasks Vision ──
# Must keep all MediaPipe classes — native .so files call into these via JNI.
# R8 would otherwise strip them as "unused" since native code isn't analyzed.
-keep class com.google.mediapipe.** { *; }
-keep class com.mediapipe.** { *; }

# MediaPipe protobuf — R8 reports missing class CalculatorProfileProto$CalculatorProfile
# These are referenced by MediaPipe framework but the proto classes are in a separate AAR
# that may not be on the classpath during R8 shrinking. Tell R8 to ignore these.
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.protobuf.**

# TensorFlow Lite (used by MediaPipe internally)
-keep class org.tensorflow.** { *; }
-keep class tflite.** { *; }

# UVCCamera — native JNI calls
-keep class com.serenegiant.usb.** { *; }
-keep class com.serenegiant.common.** { *; }

# ── R8 missing-class workarounds ──────────────────────────────
# Newer R8 (AGP 8.2+) is strict about missing classes referenced from
# compile-time-only shaded libs that leak into the runtime classpath.
# AutoValue shades JavaPoet (autovalue.shaded.com.squareup.javapoet) which
# references javax.lang.model.* (JDK annotation-processing classes that are
# NOT available on Android). These are never actually invoked at runtime —
# they're only used during annotation processing at build time — so it is
# safe to suppress the warnings.
-dontwarn javax.lang.model.**
-dontwarn javax.annotation.processing.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-dontwarn com.squareup.javapoet.**

# General safety: ignore missing classes from shaded/optional deps
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.checkerframework.**
