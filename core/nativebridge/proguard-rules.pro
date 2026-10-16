# Consumer rules for :core:nativebridge (wired with consumerProguardFiles, so they reach the app's
# R8 run). Everything the native code reaches by name through JNI must survive shrinking and renaming.

# Keep native methods (their JNI symbol names derive from the class and method names).
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.hereliesaz.graffitixr.nativebridge.SlamManager { *; }

# GraffitiJNI.cpp buildFingerprintObject: FindClass("com/hereliesaz/graffitixr/common/model/Fingerprint")
# + GetStaticMethodID("fromNative", <frozen descriptor>). Nothing in Kotlin calls fromNative, so
# without this R8 removes it (and may rename the class): setWallFingerprint would then return null on
# every capture in release builds only.
-keep class com.hereliesaz.graffitixr.common.model.Fingerprint {
    public static com.hereliesaz.graffitixr.common.model.Fingerprint fromNative(
        java.util.List, java.util.List, byte[], int, int, int, byte[], java.util.List);
}

# GraffitiJNI.cpp: FindClass("org/opencv/core/KeyPoint") + GetMethodID("<init>", "(FFFFFII)V").
-keep class org.opencv.core.KeyPoint {
    <init>(float, float, float, float, float, int, int);
}
