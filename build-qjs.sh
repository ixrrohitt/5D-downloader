#!/usr/bin/env bash
# Builds the QuickJS runtime for Android and drops it into the app as libqjs.so.
# Run from the repo root:  bash build-qjs.sh
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -z "$SDK" ] && [ -f android/local.properties ] && SDK="$(sed -n 's/^sdk.dir=//p' android/local.properties)"
[ -d "${SDK:-/nonexistent}" ] || { echo "Android SDK not found. Run: export ANDROID_HOME=/path/to/Android/Sdk"; exit 1; }

NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$NDK" ] || { echo "No NDK in $SDK/ndk. Install it first:  sdkmanager \"ndk;27.2.12479018\""; exit 1; }
command -v cmake >/dev/null || { echo "Install cmake first:  sudo apt install cmake git"; exit 1; }
echo "SDK: $SDK"; echo "NDK: $NDK"

SRC=".qjs-src"
[ -d "$SRC" ] || git clone --depth 1 https://github.com/quickjs-ng/quickjs.git "$SRC"

for abi in arm64-v8a armeabi-v7a; do
  echo "=== $abi ==="
  B=".qjs-build/$abi"
  if cmake -S "$SRC" -B "$B" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-29 \
        -DCMAKE_BUILD_TYPE=Release -DQJS_BUILD_EXAMPLES=OFF -DQJS_ENABLE_INSTALL=OFF \
     && cmake --build "$B" --target qjs_exe -j"$(nproc)"; then
    mkdir -p "android/app/src/main/jniLibs/$abi"
    cp "$B/qjs" "android/app/src/main/jniLibs/$abi/libqjs.so"
    echo "OK -> android/app/src/main/jniLibs/$abi/libqjs.so"
  else
    echo "WARNING: $abi build failed (arm64-v8a is the one that matters for modern phones)"
  fi
done
ls -la android/app/src/main/jniLibs/*/libqjs.so
