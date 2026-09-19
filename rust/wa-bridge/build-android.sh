#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
NDK="$ANDROID_HOME/ndk/29.0.14206865"
JNI="$DIR/../../server/src/main/jniLibs"
KT="$DIR/../../server/src/main/java"

cd "$DIR"

# Gradle daemons don't inherit the shell's cargo PATH.
command -v cargo >/dev/null 2>&1 || source "$HOME/.cargo/env"

# cmake-based C deps (audiopus_sys/opus) need ANDROID_NDK_ROOT; cargo-ndk does not export it.
export ANDROID_NDK_ROOT="$NDK"

cargo ndk -t arm64-v8a -t x86_64 --platform 34 \
  -o "$JNI" --manifest-path "$DIR/Cargo.toml" build --release -p wa-bridge

cargo run --quiet --bin uniffi-bindgen --manifest-path "$DIR/Cargo.toml" -- \
  generate --library "$JNI/arm64-v8a/libwa_bridge.so" \
  --language kotlin --out-dir "$KT" --no-format
