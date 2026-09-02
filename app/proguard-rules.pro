# AURA Guard proguard rules.
# Keep TensorFlow Lite GPU delegate classes referenced reflectively.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
