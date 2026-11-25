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
