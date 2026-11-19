# MediaPipe keeps JNI entry points reachable
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# javapoet/autovalue inside the mediapipe AAR reference compile-only JDK types
-dontwarn javax.lang.model.**
-dontwarn autovalue.**
-dontwarn com.squareup.javapoet.**

# Compose runtime is reflection-free; nothing extra to keep
