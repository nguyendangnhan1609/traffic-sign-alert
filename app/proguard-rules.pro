# Minification is off by default (see build.gradle.kts). These rules matter only if it is turned on.

# TensorFlow Lite uses JNI and reflection.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# OkHttp optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (used by androidx.security:security-crypto) optional annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
