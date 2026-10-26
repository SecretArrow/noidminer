#!/usr/bin/env bash
# Build the Rust PoW core for Android ABIs and place the .so files into
# app/src/main/jniLibs/ before Gradle assembles the APK. Used by CI
# (.github/workflows/*) and by developers with NDK + cargo-ndk installed.
set -euo pipefail

cd "$(dirname "$0")/.."

TARGETS="${1:-aarch64-linux-android}"
JNI_LIBS="app/src/main/jniLibs"

echo "==> Rust targets: $TARGETS"
for t in $TARGETS; do
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

echo "==> Building native-pow (release)"
cd native-pow
ARGS=()
for t in $TARGETS; do
    case "$t" in
        aarch64-linux-android) ARGS+=(-t arm64-v8a) ;;
        armv7a-linux-androideabi) ARGS+=(-t armeabi-v7a) ;;
        x86_64-linux-android) ARGS+=(-t x86_64) ;;
    esac
done
cargo ndk "${ARGS[@]}" -o "../${JNI_LIBS}" build --release
cd ..

echo "==> Built libraries:"
ls -la "${JNI_LIBS}"/*/
