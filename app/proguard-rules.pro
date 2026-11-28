# Kaff R8 rules. Compose, coroutines, Hilt, Room and AndroidX ship their own consumer rules.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MediaPipe Tasks calls into Java through JNI and reflection-based protobuf parsing.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# MediaPipe logs through Flogger, which finds its caller by walking the stack for its own class
# names. Renamed by R8, it finds nothing and HandLandmarker fails to start in release builds
# ("no caller found on the stack", Google Play review, 2026-11-27).
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**

# kotlinx.serialization: keep generated serializers of the wire protocol.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class dev.kaff.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.kaff.app.**$$serializer { *; }
