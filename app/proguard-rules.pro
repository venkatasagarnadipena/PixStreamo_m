# Chaquopy rules
-keep class com.chaquo.python.** { *; }
-keep interface com.chaquo.python.** { *; }

# Ktor rules
-keep class io.ktor.** { *; }
-dontwarn io.ktor.util.debug.IntellijIdeaDebugDetector
-dontwarn java.lang.management.**

# SLF4J rules
-dontwarn org.slf4j.impl.**

# Coil rules
-keep class coil.** { *; }

# Room rules
-keep class * extends androidx.room.RoomDatabase
-keep class * { @androidx.room.Entity *; }

# Google Cast
-keep class com.google.android.gms.cast.** { *; }
