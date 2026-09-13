# kotlinx.serialization: keep generated serializers for the save format.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.pixeltown.** {
    *** Companion;
}
-keepclasseswithmembers class com.pixeltown.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pixeltown.**$$serializer { *; }
