# Keep JNI entry points: the Rust library resolves these by their exact names.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The engine loads by name and throws EngineFailure back across the boundary, so
# its name and constructors must survive shrinking unchanged. The single-string
# constructor is the one JNI `ThrowNew` resolves; the two-argument constructor is
# what the Kotlin side builds directly.
-keep class com.chimerateam.offsetextractor.data.native.NativeLib { *; }
-keep class com.chimerateam.offsetextractor.data.native.EngineFailure {
    <init>(java.lang.String);
    <init>(java.lang.String, java.lang.String);
}

# Gson builds these models by reflection: it calls a constructor and assigns
# fields by name. R8 can see neither the call nor the assignment, so without
# these rules it removes the constructor and marks the class abstract -- which
# makes Gson throw "Abstract classes can't be instantiated!" -- and renames the
# fields, which silently breaks the JSON mapping.
#
# The packages below are every package that holds a Gson model. Class names are
# kept too, so the wire format stays inspectable in a shrunk build and
# scripts/verify_release.py can prove the models survived.
-keep class com.chimerateam.offsetextractor.data.native.** {
    <init>(...);
    <fields>;
}
-keep class com.chimerateam.offsetextractor.data.export.** {
    <init>(...);
    <fields>;
}
-keep class com.chimerateam.offsetextractor.data.cache.** {
    <init>(...);
    <fields>;
}
-keep class com.chimerateam.offsetextractor.data.patterns.** {
    <init>(...);
    <fields>;
}
-keep class com.chimerateam.offsetextractor.ui.state.** {
    <init>(...);
    <fields>;
}

# Generic fields such as List<PatternHit> and Map<String, TypeVTable> lose their
# element type when the Signature attribute is stripped, which Gson reports as a
# silent type error rather than a crash.
-keepattributes Signature
-keepattributes *Annotation*

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
