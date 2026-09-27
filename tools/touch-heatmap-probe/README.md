# Touch heatmap probe

Read-only V4L2 probe for the Pixel touch heatmap (`/dev/v4l-touch0`). Prints format and a few raw frames.

~~~
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang -O2 -o probe probe.c
adb push probe /data/local/tmp/ && adb shell su -c "/data/local/tmp/probe /dev/v4l-touch0 5"
~~~
