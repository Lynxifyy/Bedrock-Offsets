# Keep JNI entry points: the Rust library resolves these by their exact names.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.chimerateam.offsetextractor.jni.** { *; }

# Gson reflects over the JSON models, so their field names must survive shrinking.
-keep class com.chimerateam.offsetextractor.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# The Rust library is loaded by name through System.loadLibrary.
-keep class com.chimerateam.offsetextractor.NativeLib { *; }
