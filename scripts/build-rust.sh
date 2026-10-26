#!/usr/bin/env bash
# Build the Rust PoW core for Android ABIs and place the .so files into
# app/src/main/jniLibs/ before Gradle assembles the APK. Used by CI
# (.github/workflows/*) and by developers with NDK + cargo-ndk installed.
#
# Default: bangun SEMUA ABI yang didukung (arm64-v8a, armeabi-v7a, x86_64, x86)
# supaya release split-per-ABI otomatis. Override:
#   ./scripts/build-rust.sh aarch64-linux-android            # arm64 saja
#   ./scripts/build-rust.sh "aarch64-linux-android armv7-linux-androideabi"
#   ./scripts/build-rust.sh x86_64-linux-android             # untuk emulator
set -euo pipefail

cd "$(dirname "$0")/.."

# shellcheck disable=SC2206
DEFAULT_TARGETS=(
    aarch64-linux-android
    armv7-linux-androideabi
    x86_64-linux-android
    i686-linux-android
)
TARGETS_STR="${1:-${DEFAULT_TARGETS[*]}}"
read -r -a TARGETS <<< "$TARGETS_STR"

JNI_LIBS="app/src/main/jniLibs"

echo "==> Rust targets: ${TARGETS[*]}"
for t in "${TARGETS[@]}"; do
    rustup target add "$t"
done

echo "==> Ensuring cargo-ndk"
if ! command -v cargo-ndk > /dev/null 2>&1; then
    ARCH=$(uname -m)
    case "$ARCH" in
        x86_64) CNDK_ASSET="x86_64" ;;
        aarch64 | arm64) CNDK_ASSET="aarch64" ;;
        *) echo "unsupported host arch: $ARCH"; exit 1 ;;
    esac
    CNDK_VER="3.5.0"
    mkdir -p ~/.cargo/bin
    if curl -sL "https://github.com/bbqsrc/cargo-ndk/releases/download/${CNDK_VER}/cargo-ndk-linux-${CNDK_ASSET}.tar.gz" \
            -o /tmp/cargo-ndk.tar.gz \
        && tar -xzf /tmp/cargo-ndk.tar.gz -C /tmp 2> /dev/null \
        && [ -f /tmp/cargo-ndk ]; then
        mv /tmp/cargo-ndk ~/.cargo/bin/cargo-ndk
        chmod +x ~/.cargo/bin/cargo-ndk
        export PATH="$HOME/.cargo/bin:$PATH"
    else
        echo "prebuilt download failed — falling back to cargo install (slower)"
        cargo install cargo-ndk --locked
        export PATH="$HOME/.cargo/bin:$PATH"
    fi
fi

echo "==> Locating NDK"
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -z "${ANDROID_NDK_LATEST_HOME:-}" ]; then
    echo "ANDROID_NDK_HOME not set"; exit 1
fi
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME}}"

echo "==> Building native-pow (release) for ${#TARGETS[@]} target(s)"
cd native-pow
ARGS=()
for t in "${TARGETS[@]}"; do
    case "$t" in
        aarch64-linux-android)      ARGS+=(-t arm64-v8a) ;;
        armv7-linux-androideabi)   ARGS+=(-t armeabi-v7a) ;;
        x86_64-linux-android)       ARGS+=(-t x86_64) ;;
        i686-linux-android)         ARGS+=(-t x86) ;;
        *)
            echo "unknown Rust target: $t"; exit 1
            ;;
    esac
done

# 16 KB page-size alignment for Android 15+ (matches CMake linker flag).
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

# Build semua target sekaligus — cargo-ndk mendukung banyak -t flags dan
# kompilasi paralel antar target dengan job cache bersama, lebih cepat daripada
# memanggil `cargo ndk` berulang.
cargo ndk "${ARGS[@]}" -o "../${JNI_LIBS}" build --release
cd ..

echo "==> Built libraries:"
ls -la "${JNI_LIBS}"/*/
