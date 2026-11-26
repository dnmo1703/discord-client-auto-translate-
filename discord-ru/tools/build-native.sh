#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}
ndk=${ANDROID_NDK_HOME:-$sdk/ndk/27.2.12479018}
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
target=aarch64-linux-android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android24-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$toolchain/llvm-ar"

if [[ ! -x "$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" ]]; then
    echo "Android NDK 27.2.12479018 arm64 compiler not found in $ndk" >&2
    exit 2
fi

build="$root/app/build"
cd "$root"
cargo build --locked --manifest-path "$root/native/Cargo.toml" \
    --target "$target" --target-dir "$build/rust" --release
install -Dm644 "$build/rust/$target/release/libdiscordru_native.so" \
    "$build/generated/rustJniLibs/arm64-v8a/libdiscordru_native.so"
