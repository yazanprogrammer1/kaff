# kotlinx.serialization: keep generated serializers of our @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class dev.kaff.desktop.**$$serializer { *; }
-keepclassmembers class dev.kaff.desktop.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Coroutines on the Swing main thread are found through ServiceLoader.
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
-dontwarn org.jetbrains.skiko.**
-dontnote **
