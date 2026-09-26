# DroidPilot AI — ProGuard / R8 keep rules
# =========================================================================
# Default Android project rules are merged from proguard-android-optimize.txt
# Add custom rules below.

# --- Keep kotlinx.serialization generated serializers ---------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep @Serializable classes and their serializers.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable model classes in this app.
-keep,includedescriptorclasses class com.droidpilot.ai.**$$serializer { *; }
-keepclassmembers class com.droidpilot.ai.** {
    *** Companion;
}
-keepclasseswithmembers class com.droidpilot.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp (already shipped with its own rules, but be explicit) ---------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# --- Kotlin metadata -----------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations

# --- Coroutines -----------------------------------------------------------
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
