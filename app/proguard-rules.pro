# Preserve network models, DTOs, and JSON response data classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keep class com.example.model.** { *; }
-keepclassmembers class com.example.model.** { *; }

-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Preserve OkHttp and Kotlin reflection / serialization if used
-dontwarn okhttp3.**
-dontwarn okio.**

