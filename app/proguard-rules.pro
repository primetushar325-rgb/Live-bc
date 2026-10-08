# FFmpegKit ships its own consumer rules; keep its classes intact if minify is ever enabled.
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Keep serializable stream configuration.
-keep class com.videolive.app.model.** { *; }
