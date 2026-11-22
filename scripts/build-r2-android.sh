#!/usr/bin/env bash
# Cross-compiles radare2 for Android arm64 (Poco F6 Pro) and places it at
# app/src/main/jniLibs/arm64-v8a/libr2.so. Runs on Linux x86_64 (CI or WSL).
set -euo pipefail

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK directory}"
API=26
HERE="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$HERE/build-r2}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$TOOLCHAIN/aarch64-linux-android${API}-clang"
CLANGXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"
DEST="$HERE/app/src/main/jniLibs/arm64-v8a/libr2.so"

for tool in git python3 meson ninja cc; do
    command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done
[ -x "$CLANG" ] || { echo "NDK clang not found: $CLANG" >&2; exit 1; }

mkdir -p "$WORK/bin"
[ -d "$WORK/radare2" ] || git clone --depth 1 https://github.com/radareorg/radare2 "$WORK/radare2"
[ -d "$WORK/sdb" ] || git clone --depth 1 https://github.com/radareorg/sdb "$WORK/sdb"
SRC="$WORK/radare2"
echo "radare2 commit: $(git -C "$SRC" rev-parse HEAD)"

# radare2 runs "sdb" as a build-time code generator. The Android sdb cannot run on
# the x86 build host, so build a host-native sdb from the same sources.
SDB="$WORK/sdb"
SDB_SRCS="$(sed -n '/^libsdb_sources = \[/,/^]/p' "$SDB/meson.build" | grep -o "'src/[^']*'" | tr -d "'" | tr '\n' ' ')"
(cd "$SDB" && cc -O2 -DHAVE_CONFIG=0 -I include -o "$WORK/bin/sdb-host" src/entry.c $SDB_SRCS -lpthread -lm)

# Rewrite every radare2 code generator to use the host sdb.
python3 "$HERE/scripts/patch_r2_sdb.py" "$SRC" "$WORK/bin/sdb-host"

CROSS="$WORK/android-arm64.ini"
cat > "$CROSS" <<CROSSEOF
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

[properties]
needs_exe_wrapper = true
CROSSEOF

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
