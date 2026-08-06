# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK's default ProGuard configuration.

# Add any project specific keep rules here:
-dontwarn com.dylibso.chicory.annotations.Buffer
-dontwarn com.dylibso.chicory.annotations.HostModule
-dontwarn com.dylibso.chicory.annotations.WasmExport
-dontwarn java.lang.System$Logger$Level
-dontwarn java.lang.System$Logger

