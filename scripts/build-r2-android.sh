#!/usr/bin/env bash
# Cross-compiles radare2 for Android arm64 (Poco F6 Pro) and places it at
# app/src/main/jniLibs/arm64-v8a/libr2.so.
# Requires: git, python3, meson, ninja, and an Android NDK (r25+) with
# ANDROID_NDK_HOME set. Host: Linux x86_64 (use WSL on Windows).
# UNTESTED in this environment: the NDK and meson could not be downloaded here.
set -euo pipefail

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK directory}"
API=26
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$HERE/build-r2}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$TOOLCHAIN/aarch64-linux-android${API}-clang"
CLANGXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"
DEST="$HERE/app/src/main/jniLibs/arm64-v8a/libr2.so"

for tool in git python3 meson ninja; do
    command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
[ -x "$CLANG" ] || { echo "NDK clang not found: $CLANG" >&2; exit 1; }

mkdir -p "$WORK"
if [ ! -d "$WORK/radare2" ]; then
    git clone --depth 1 https://github.com/radareorg/radare2 "$WORK/radare2"
fi
SRC="$WORK/radare2"
echo "radare2 commit: $(git -C "$SRC" rev-parse HEAD)"

CROSS="$WORK/android-arm64.ini"
cat > "$CROSS" <<EOF
[binaries]
c = '$CLANG'
cpp = '$CLANGXX'
ar = '$TOOLCHAIN/llvm-ar'
ranlib = '$TOOLCHAIN/llvm-ranlib'
strip = '$TOOLCHAIN/llvm-strip'
pkgconfig = 'false'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

rm -rf "$WORK/build" "$WORK/out"
meson setup "$WORK/build" "$SRC" \
    --cross-file "$CROSS" \
    --prefix="$WORK/out" \
    --default-library=static \
    -Dblob=true
ninja -C "$WORK/build"
ninja -C "$WORK/build" install

mkdir -p "$(dirname "$DEST")"
cp "$WORK/out/bin/radare2" "$DEST"
chmod 755 "$DEST"
echo "packaged: $DEST ($(stat -c %s "$DEST") bytes)"
file "$DEST" || true
