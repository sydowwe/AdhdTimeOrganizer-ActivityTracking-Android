# Keep Retrofit/Gson DTO models
-keep class com.timeorganizer.usagetracker.data.api.dto.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Stored as JSON in prefs: renamed fields would make an updated app unable to read what the previous one wrote
-keep class com.timeorganizer.usagetracker.ignore.PendingIgnoreOp { *; }
-keep enum com.timeorganizer.usagetracker.ignore.IgnoreOpKind { *; }

# Retrofit
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Preserve line numbers for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
