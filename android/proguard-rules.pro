# R8 rules for the release build. Libraries (OkHttp, CameraX, Compose, kotlinx.serialization)
# ship their own consumer rules; these cover our own serializable protocol classes.

-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# kotlinx.serialization: keep the generated serializers of our @Serializable classes.
-keepclassmembers @kotlinx.serialization.Serializable class com.taksolutions.simsensor.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.taksolutions.simsensor.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.taksolutions.simsensor.**$$serializer { *; }

# Readable stack traces from the field.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
