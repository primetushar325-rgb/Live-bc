# FFmpegKit ships its own consumer rules; keep its classes intact if minify is ever enabled.
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# FFmpegKit references smart-exception at runtime (NoClassDefFoundError if removed).
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.smartexception.**

# Keep serializable stream configuration.
-keep class com.videolive.app.model.** { *; }
