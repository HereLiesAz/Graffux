#!/usr/bin/env bash
#
# Provision a Linux box to BUILD, and where the host allows it RUN, Graffux.
#
# Running the app needs a host that exposes hardware virtualisation (/dev/kvm).
# The Android emulator is a QEMU guest; without KVM it falls back to pure
# software emulation, which on a typical CI-sized box takes tens of minutes to
# boot if it boots at all. Installing qemu-kvm does not create virtualisation
# support — it only consumes support the host already exposes.
#
# This script DEGRADES rather than fails when KVM is absent: it provisions the
# SDK for compiling, skips the emulator and the AVD, says so loudly, and exits
# 0. That matters because it is meant to be usable as an environment setup
# script, which runs on every container start — a hard failure there would break
# provisioning on any host without nested virtualisation. Set REQUIRE_KVM=1 to
# turn a missing /dev/kvm back into a hard error.
#
# Idempotent: re-running re-uses an existing SDK rather than re-downloading one,
# and will not duplicate lines in the shell profile.
#
# Usage:
#   scripts/setup-android-env.sh
#   ARCH=arm64-v8a scripts/setup-android-env.sh    # on an ARM host
#   REQUIRE_KVM=1 scripts/setup-android-env.sh     # fail if the emulator can't run
#   REPO_ROOT=/path/to/Graffux scripts/setup-android-env.sh
#
set -euo pipefail

# ── Configuration ────────────────────────────────────────────────────────────
# ANDROID_HOME defaults to the location the Gradle Android plugin already uses
# when it provisions the SDK itself. Pointing this somewhere fresh means
# re-downloading the platform, the build-tools AND the ~2 GB NDK, so only
# override it if you mean to.
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"

# Pinned to what the project actually declares. Keep in lockstep with:
#   app/build.gradle.kts                 compileSdk / targetSdk
#   core/nativebridge/build.gradle.kts   externalNativeBuild.cmake.version
# A mismatch here does not fail fast — Gradle silently downloads what it needs
# on first build instead, which is what makes a wrong pin easy to miss.
SDK_PLATFORM="${SDK_PLATFORM:-platforms;android-37.0}"
SDK_BUILD_TOOLS="${SDK_BUILD_TOOLS:-build-tools;36.0.0}"
SDK_NDK="${SDK_NDK:-ndk;28.2.13676358}"
SDK_CMAKE="${SDK_CMAKE:-cmake;3.22.1}"

# The emulator image. x86_64 is the only choice KVM can accelerate on an x86_64
# host; on an ARM host (Apple Silicon, ARM CI runners) use arm64-v8a so the
# guest matches. A foreign-architecture image gets no acceleration from KVM no
# matter what is installed.
case "$(uname -m)" in
    aarch64|arm64) HOST_ARCH="arm64-v8a" ;;
    *)             HOST_ARCH="x86_64" ;;
esac
ARCH="${ARCH:-$HOST_ARCH}"
EMULATOR_API="${EMULATOR_API:-35}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-${EMULATOR_API};google_apis;${ARCH}}"
AVD_NAME="${AVD_NAME:-graffux}"

# Google publishes the command-line tools under a build number, not a version.
# Override if this one is superseded; the integrity check below says plainly
# when the download is not a zip.
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-13114758}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"

log() { printf '\n=== %s\n' "$*"; }
warn() { printf '\n!!! %s\n' "$*" >&2; }

# ── Locate the checkout ──────────────────────────────────────────────────────
# Only the ABI check needs this, and that check must never guess: reporting the
# ABI as fine because the file could not be read is worse than not checking, so
# a failure to locate the repo is reported rather than assumed away.
#
# Tried in order: an explicit REPO_ROOT, the script's own parent (running from a
# checkout), the working directory, then a shallow scan of the usual clone
# locations — which is the case that matters when this is pasted into an
# environment's setup-script box, where the script has no path of its own.
find_repo_root() {
    local candidate
    if [ -n "${REPO_ROOT:-}" ]; then
        echo "$REPO_ROOT"; return
    fi
    if candidate="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." 2>/dev/null && pwd)" \
        && [ -f "$candidate/app/build.gradle.kts" ]; then
        echo "$candidate"; return
    fi
    if [ -f "$PWD/app/build.gradle.kts" ]; then
        echo "$PWD"; return
    fi
    for candidate in \
        "$(find /home /workspace /root -maxdepth 3 -name build.gradle.kts -path '*/app/*' 2>/dev/null | head -1)"; do
        if [ -n "$candidate" ]; then
            echo "$(dirname "$(dirname "$candidate")")"; return
        fi
    done
    echo ""
}
REPO_ROOT="$(find_repo_root)"

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    SUDO="sudo"
fi

# ── Preflight: hardware virtualisation ───────────────────────────────────────
log "Preflight"
EMULATOR_OK=1
KVM_REASON=""
if [ ! -e /dev/kvm ]; then
    EMULATOR_OK=0
    KVM_REASON="no /dev/kvm on this host"
elif ! grep -qE '(vmx|svm)' /proc/cpuinfo; then
    EMULATOR_OK=0
    KVM_REASON="/dev/kvm exists but the CPU exposes no vmx/svm flags, so the host is not passing virtualisation through"
fi

if [ "$EMULATOR_OK" = "1" ]; then
    echo "KVM present and CPU virtualisation flags visible — emulator will be provisioned."
else
    if [ "${REQUIRE_KVM:-0}" = "1" ]; then
        warn "REQUIRE_KVM=1 and the emulator cannot run: $KVM_REASON."
        exit 1
    fi
    warn "Emulator will NOT be provisioned: $KVM_REASON."
    warn "The SDK, JDK, NDK and CMake still get installed, so the project will"
    warn "compile here — but nothing can run the app. Provision on a host with"
    warn "nested virtualisation to get the emulator, or use REQUIRE_KVM=1 to"
    warn "make this a hard failure instead of a warning."
fi

# ── System packages ──────────────────────────────────────────────────────────
# JDK 21, NOT 17: app/build.gradle.kts pins sourceCompatibility,
# targetCompatibility and jvmTarget to 21. A default JDK of 17 fails the build.
log "System packages"
$SUDO apt-get update -qq
$SUDO apt-get install -y --no-install-recommends \
    openjdk-21-jdk \
    unzip \
    curl \
    ca-certificates \
    libglu1-mesa \
    libpulse0

# The ALSA runtime was renamed in Ubuntu 24.04 (time_t transition). Try the new
# name, fall back to the old one, and carry on if neither exists — it is only
# needed for emulator audio, which a headless run does not use anyway.
$SUDO apt-get install -y --no-install-recommends libasound2t64 \
    || $SUDO apt-get install -y --no-install-recommends libasound2 \
    || warn "No libasound2 package available; continuing (headless runs use -no-audio)."

# qemu-kvm is installed for its udev rules and the 'kvm' group, not its QEMU
# binaries — the emulator ships its own. Pointless without KVM.
if [ "$EMULATOR_OK" = "1" ]; then
    $SUDO apt-get install -y --no-install-recommends qemu-kvm cpu-checker || \
        warn "qemu-kvm unavailable; continuing (the emulator bundles its own QEMU)."
fi

# ── Command-line tools ───────────────────────────────────────────────────────
log "Android command-line tools"
mkdir -p "$ANDROID_HOME"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ -x "$SDKMANAGER" ]; then
    echo "Already installed at $SDKMANAGER"
else
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    echo "Downloading $CMDLINE_TOOLS_URL"
    curl -fsSL --retry 3 --retry-delay 2 -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"

    # Verify we got an archive and not an error page or a proxy interstitial.
    # curl -f already rejects HTTP errors; this catches a 200 that isn't a zip.
    if ! unzip -t "$tmp/cmdline-tools.zip" >/dev/null 2>&1; then
        warn "Downloaded file is not a valid zip. First bytes:"
        head -c 200 "$tmp/cmdline-tools.zip" >&2 || true
        warn "If a proxy intercepted this, allow dl.google.com and retry."
        exit 1
    fi

    # The zip carries a top-level 'cmdline-tools/'; sdkmanager requires it at
    # cmdline-tools/latest/ or it cannot resolve its own SDK root.
    unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extracted"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/extracted/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp"
    trap - EXIT
    echo "Installed to $SDKMANAGER"
fi

export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

# ── Persist the environment ──────────────────────────────────────────────────
# /etc/profile.d is read by login shells; the guarded /etc/bash.bashrc line
# covers the non-login interactive shells tooling usually spawns. Writing the
# snippet wholesale (rather than appending) is what keeps repeat runs clean.
log "Persisting environment"
PROFILE_SNIPPET="/etc/profile.d/android-sdk.sh"
$SUDO tee "$PROFILE_SNIPPET" >/dev/null <<EOF
export ANDROID_HOME=$ANDROID_HOME
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=\$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
EOF
$SUDO chmod 0644 "$PROFILE_SNIPPET"

if ! grep -q "android-sdk.sh" /etc/bash.bashrc 2>/dev/null; then
    echo ". $PROFILE_SNIPPET" | $SUDO tee -a /etc/bash.bashrc >/dev/null
fi
echo "Wrote $PROFILE_SNIPPET"

# ── SDK packages ─────────────────────────────────────────────────────────────
log "Licences"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

log "SDK packages"
PACKAGES=(
    "platform-tools"
    "$SDK_PLATFORM"
    "$SDK_BUILD_TOOLS"
    "$SDK_NDK"
    "$SDK_CMAKE"
)
if [ "$EMULATOR_OK" = "1" ]; then
    PACKAGES+=("emulator" "$SYSTEM_IMAGE")
fi
printf '  %s\n' "${PACKAGES[@]}"
"$SDKMANAGER" "${PACKAGES[@]}"

# ── AVD ──────────────────────────────────────────────────────────────────────
if [ "$EMULATOR_OK" = "1" ]; then
    log "Virtual device '$AVD_NAME'"
    AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
    echo "no" | "$AVDMANAGER" create avd \
        --name "$AVD_NAME" \
        --package "$SYSTEM_IMAGE" \
        --device "pixel_6" \
        --force

    # A drawing app on a 1080p-class screen: the default AVD skin is fine, but
    # give it enough heap and RAM that a full-canvas bitmap doesn't OOM.
    AVD_CONFIG="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
    if [ -f "$AVD_CONFIG" ]; then
        sed -i '/^hw\.ramSize=/d;/^vm\.heapSize=/d;/^hw\.gpu\.enabled=/d;/^hw\.gpu\.mode=/d' "$AVD_CONFIG"
        {
            echo "hw.ramSize=4096"
            echo "vm.heapSize=512"
            echo "hw.gpu.enabled=yes"
            echo "hw.gpu.mode=swiftshader_indirect"
        } >> "$AVD_CONFIG"
    fi

    log "KVM permissions"
    # Only meaningful for a non-root user; root already has access.
    if [ "$(id -u)" -ne 0 ] && getent group kvm >/dev/null; then
        $SUDO adduser "$USER" kvm || warn "Could not add $USER to the kvm group."
        echo "Added $USER to the kvm group — log out and back in for it to take effect."
    else
        echo "Running as root, or no kvm group; nothing to do."
    fi
fi

# ── ABI sanity check ─────────────────────────────────────────────────────────
# Graffux ships native code (OpenCV + libgraffitixr via CMake). Both Gradle
# modules restrict abiFilters, and an emulator whose ABI is not in that list
# installs the APK and then dies on System.loadLibrary at startup — a confusing
# failure that reads as an app bug rather than a setup one.
if [ "$EMULATOR_OK" = "1" ]; then
    log "Native ABI check"
    APP_GRADLE="$REPO_ROOT/app/build.gradle.kts"
    if [ -z "$REPO_ROOT" ] || [ ! -f "$APP_GRADLE" ]; then
        warn "Could not locate app/build.gradle.kts (REPO_ROOT='${REPO_ROOT:-unset}')."
        warn "ABI check SKIPPED — re-run with REPO_ROOT=/path/to/Graffux to enable it."
    elif grep -q "abiFilters" "$APP_GRADLE" && ! grep -q "abiFilters.*$ARCH" "$APP_GRADLE"; then
        warn "The emulator is $ARCH, but the build produces no $ARCH native libs."
        warn ""
        warn "  app/build.gradle.kts                 abiFilters += listOf(\"arm64-v8a\", \"armeabi-v7a\")"
        warn "  core/nativebridge/build.gradle.kts   abiFilters += listOf(\"arm64-v8a\", \"armeabi-v7a\")"
        warn ""
        warn "The APK installs and then crashes on System.loadLibrary. Either:"
        warn "  a) add \"$ARCH\" to abiFilters in BOTH files (fine for debug builds), or"
        warn "  b) re-run with ARCH=arm64-v8a — but an arm64 guest gets no KVM"
        warn "     acceleration on an x86_64 host, so it will be slow."
    else
        echo "Emulator ABI $ARCH is covered by the build's abiFilters."
    fi
fi

log "Ready"
cat <<EOF
  ANDROID_HOME  $ANDROID_HOME
  REPO_ROOT     ${REPO_ROOT:-<not found>}
  JDK           $(java -version 2>&1 | grep -i "version" | head -1)
  Emulator      $([ "$EMULATOR_OK" = "1" ] && echo "AVD '$AVD_NAME'" || echo "not provisioned — $KVM_REASON")

Build:  ./gradlew :app:assembleDebug
Run:    scripts/run-android.sh$([ "$EMULATOR_OK" = "1" ] || echo "   (needs a KVM-capable host)")
EOF
