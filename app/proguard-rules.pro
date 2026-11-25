# gomobile: the Go runtime calls these classes through JNI by name.
-keep class go.** { *; }
-keep class com.lookee.core.** { *; }

# kotlinx.serialization models.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.lookee.tv.data.**$$serializer { *; }
-keepclassmembers class com.lookee.tv.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# libVLC: its native code (libvlcjni) finds these classes, fields and callbacks by name.
# The AAR ships no consumer rules, so without this R8 strips them and the release build
# crashes the first time VLC starts.
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
