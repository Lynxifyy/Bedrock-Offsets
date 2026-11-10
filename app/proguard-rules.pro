# Keep JNI entry points: the Rust library resolves these by their exact names.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The engine loads by name and throws EngineFailure back across the boundary, so
# its name and constructor must survive shrinking unchanged.
-keep class com.chimerateam.offsetextractor.data.native.NativeLib { *; }
-keep class com.chimerateam.offsetextractor.data.native.EngineFailure {
    <init>(java.lang.String, java.lang.String);
}

# Gson reflects over the JSON models, so their field names must survive shrinking.
-keep class com.chimerateam.offsetextractor.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Extracted-offset payloads are serialised by name.
-keep class com.chimerateam.offsetextractor.data.model.** { *; }
