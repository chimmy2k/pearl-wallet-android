# Kotlin metadata / coroutines
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata { *; }

# Keep our own classes: reflection-free, but R8 must not rename the entry point
-keep class dev.pearlwallet.MainActivity { *; }
-keep class dev.pearlwallet.core.** { *; }
-keep class dev.pearlwallet.logic.** { *; }

# ZXing: QRCodeWriter is reached through a factory, keep its constructors
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# org.json is part of the platform on Android
-dontwarn org.json.**
