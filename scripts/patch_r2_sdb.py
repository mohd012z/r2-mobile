"""Rewrite radare2's sdb code generators to use a host-native sdb binary.

Usage: patch_r2_sdb.py <radare2-src-dir> <host-sdb-path>
"""
import pathlib
import sys

src = pathlib.Path(sys.argv[1])
host = sys.argv[2]

top = src / "meson.build"
text = top.read_text()
anchor = "sdb_exe = libsdb_sp.get_variable('sdb_exe')\n"
assert anchor in text, "anchor not found in top-level meson.build"
text = text.replace(anchor, anchor + "sdb_host = find_program('%s')\n" % host, 1)
top.write_text(text)

changed = 0
for f in src.rglob("meson.build"):
    t = f.read_text()
    n = t.count("[sdb_exe,") + t.count("[ sdb_exe,") + t.count("gen_py, sdb_exe,")
    if n:
        t = t.replace("[sdb_exe,", "[sdb_host,").replace("[ sdb_exe,", "[ sdb_host,")
        t = t.replace("gen_py, sdb_exe,", "gen_py, sdb_host,")
        f.write_text(t)
        changed += n

print("sdb generator commands rewritten:", changed)
assert changed >= 10, "too few generator commands rewritten"
