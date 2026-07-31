#!/usr/bin/env bash
#
# Boot the emulator, build Graffux, install it and launch it.
#
# Assumes scripts/setup-android-env.sh has run on this box. Headless by default
# so it works over SSH and in CI; pass HEADLESS=0 for a window.
#
# Usage:
#   scripts/run-android.sh
#   HEADLESS=0 scripts/run-android.sh
#   SCREENSHOT=/tmp/graffux.png scripts/run-android.sh
#
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
AVD_NAME="${AVD_NAME:-graffux}"
APP_ID="${APP_ID:-com.hereliesaz.graffux}"
LAUNCH_ACTIVITY="${LAUNCH_ACTIVITY:-$APP_ID/.MainActivity}"
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-300}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"

log() { printf '\n=== %s\n' "$*"; }
warn() { printf '\n!!! %s\n' "$*" >&2; }

for tool in "$ADB" "$EMULATOR"; do
    if [ ! -x "$tool" ]; then
        warn "Missing $tool — run scripts/setup-android-env.sh first."
        exit 1
    fi
done

# ── Emulator ─────────────────────────────────────────────────────────────────
if "$ADB" devices | grep -q "emulator.*device$"; then
    log "Emulator already running"
else
    log "Booting '$AVD_NAME'"
    EMU_ARGS=(-avd "$AVD_NAME" -no-snapshot -no-audio -no-boot-anim)
    if [ "${HEADLESS:-1}" = "1" ]; then
        # swiftshader_indirect renders on the CPU, which is what makes a headless
        # box work at all — the app is GPU-heavy Compose, so this is slower than
        # a real GPU but correct.
        EMU_ARGS+=(-no-window -gpu swiftshader_indirect)
    fi
    "$EMULATOR" "${EMU_ARGS[@]}" >/tmp/emulator.log 2>&1 &
    echo "Emulator PID $! — log at /tmp/emulator.log"

    "$ADB" wait-for-device

    log "Waiting for boot to complete (up to ${BOOT_TIMEOUT_SEC}s)"
    deadline=$(( SECONDS + BOOT_TIMEOUT_SEC ))
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        if [ "$SECONDS" -ge "$deadline" ]; then
            warn "Boot did not complete within ${BOOT_TIMEOUT_SEC}s. Tail of /tmp/emulator.log:"
            tail -30 /tmp/emulator.log >&2 || true
            exit 1
        fi
        sleep 3
    done
    echo "Booted."
fi

# Dismiss the lock screen so the launched activity is actually visible.
"$ADB" shell input keyevent 82 >/dev/null 2>&1 || true

# ── Build and install ────────────────────────────────────────────────────────
log "Building and installing"
cd "$REPO_ROOT"
ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_HOME" ./gradlew :app:installDebug

# ── Launch ───────────────────────────────────────────────────────────────────
log "Launching $LAUNCH_ACTIVITY"
"$ADB" logcat -c || true
"$ADB" shell am start -n "$LAUNCH_ACTIVITY"

# Give the process a moment to either come up or die, then say which it was.
sleep 5
if "$ADB" shell pidof "$APP_ID" >/dev/null 2>&1; then
    echo "Running (pid $("$ADB" shell pidof "$APP_ID" | tr -d '\r'))."
else
    warn "$APP_ID is not running — it started and exited. Recent crash output:"
    # An ABI mismatch surfaces here as UnsatisfiedLinkError; see the ABI check
    # at the end of setup-android-env.sh.
    "$ADB" logcat -d -t 200 '*:E' | tail -40 >&2 || true
    exit 1
fi

if [ -n "${SCREENSHOT:-}" ]; then
    log "Screenshot"
    "$ADB" exec-out screencap -p > "$SCREENSHOT"
    echo "Wrote $SCREENSHOT"
fi

log "Done"
echo "  Logs:      $ADB logcat"
echo "  Stop:      $ADB emu kill"
