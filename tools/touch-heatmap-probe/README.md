# Touch heatmap probe

Read-only V4L2 probe for the Pixel touch heatmap (`/dev/v4l-touch0`). Prints format and a few raw frames.

~~~
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang -O2 -o probe probe.c
adb push probe /data/local/tmp/ && adb shell su -c "/data/local/tmp/probe /dev/v4l-touch0 5"
~~~

The app's capture helper (`core/nativebridge/src/main/cpp/heatmap/heatmap_helper.c`, shipped as
`libgraffux_heatmap.so`) does the same V4L2 steps and falls back to the Samsung sec factory interface.
It can be run by hand from the installed app to see what it reports (binary records on stdout; the
first is HELLO, INFO or ERROR with readable text):

~~~
adb shell su -c 'ls /data/app/*/com.hereliesaz.graffux*/lib/arm64/libgraffux_heatmap.so'
adb shell su -c '<that path> --ignore-stdin' | head -c 600 | od -c
~~~
