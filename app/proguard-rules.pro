# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK's default ProGuard configuration.

# Add any project specific keep rules here:

# ── Chicory (WASM runtime for azphalt extensions) ────────────────────────────────────────
# Chicory's WASI module references its own compile-time annotation processor types and the
# Java 9 System.Logger API. Neither exists at runtime on Android: the annotations are
# source-retention build-time inputs, and android.util.Log stands in for System.Logger. R8
# treats the dangling references as errors and fails the whole release build, so the release
# APK/AAB could not be produced at all — only the unminified debug artifact.
#
# These are warning suppressions, not keeps: nothing needs preserving, R8 just needs telling
# that the absences are expected.
-dontwarn com.dylibso.chicory.annotations.Buffer
-dontwarn com.dylibso.chicory.annotations.HostModule
-dontwarn com.dylibso.chicory.annotations.WasmExport
-dontwarn java.lang.System$Logger
-dontwarn java.lang.System$Logger$Level
