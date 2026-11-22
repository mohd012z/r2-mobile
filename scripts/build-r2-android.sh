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

# radare2 runs "sdb" as a code generator during the build, so it needs a
# host-native sdb binary (the Android one cannot run on the x86 build host).
SDB="$WORK/sdb"
SDB_SRCS="$(sed -n '/^libsdb_sources = \[/,/^]/p' "$SDB/meson.build" | grep -o "'src/[^']*'" | tr -d "'" | tr '\n' ' ')"
(cd "$SDB" && cc -O2 -DHAVE_CONFIG=0 -I include -o "$WORK/bin/sdb-host" src/entry.c $SDB_SRCS -lpthread -lm)
"$WORK/bin/sdb-host" -h >/dev/null 2>&1 || true

# Point radare2's code generators at the host sdb (their sdb_exe is the Android build).
python3 - "$SRC/meson.build" "$WORK/bin/sdb-host" <<'PY'
import sys
path, host = sys.argv[1], sys.argv[2]
s = open(path).read()
s = s.replace("sdb_exe = libsdb_sp.get_variable('sdb_exe')\n",
              "sdb_exe = libsdb_sp.get_variable('sdb_exe')\nsdb_host = find_program('%s')\n" % host, 1)
s = s.replace("sdb_gen_cmd_cgen = [ sdb_exe,", "sdb_gen_cmd_cgen = [ sdb_host,", 1)
s = s.replace("sdb_gen_cmd = [ sdb_exe,", "sdb_gen_cmd = [ sdb_host,", 1)
s = s.replace("syscall_sdb_gen_cmd = [ py3_exe, gen_py, sdb_exe,", "syscall_sdb_gen_cmd = [ py3_exe, gen_py, sdb_host,", 1)
assert s.count("sdb_host") >= 4, "meson.build patch did not apply"
open(path, "w").write(s)
PY

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
