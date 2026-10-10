# Keep kotlinx.serialization generated serializers for the wire protocol models.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.sensirion.sps30.wire.** {
    kotlinx.serialization.KSerializer serializer(...);
}
