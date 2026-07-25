# LiteRT-LM uses JNI and reflection (kotlin-reflect + gson) internally.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Gson (transitive dependency of LiteRT-LM)
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
