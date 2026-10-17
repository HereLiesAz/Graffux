# Consumer rules for :core:nativebridge (wired with consumerProguardFiles, so they reach the app's
# R8 run). Everything the native code reaches by name through JNI must survive shrinking and renaming.

# Keep native methods (their JNI symbol names derive from the class and method names).
-keepclasseswithmembernames class * {
    native <methods>;
}
