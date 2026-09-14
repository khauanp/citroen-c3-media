#!/usr/bin/env python3
"""Prove that the rebuilt tablet app differs only in the C3 Link timeout method."""

from __future__ import annotations

import re
import sys
from pathlib import Path


TARGET = Path("io/github/jqssun/airplay/connectivity/C3LinkServer.smali")
METHOD = re.compile(
    rb"\.method private final checkTimeout\(\)V\n.*?^\.end method",
    re.MULTILINE | re.DOTALL,
)


def files(root: Path, suffix: str | None = None) -> dict[Path, bytes]:
    result: dict[Path, bytes] = {}
    if not root.exists():
        return result
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        if suffix is None or path.suffix == suffix:
            result[path.relative_to(root)] = path.read_bytes()
    return result


def require_equal_tree(original: Path, rebuilt: Path, label: str) -> None:
    left = files(original)
    right = files(rebuilt)
    if left != right:
        changed = sorted(set(left) ^ set(right) | {key for key in set(left) & set(right) if left[key] != right[key]})
        raise RuntimeError(f"{label} changed unexpectedly: {changed[:10]}")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_3_mapfix.py ORIGINAL_DECODE REBUILT_DECODE")
    original = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()

    old_smali = files(original / "smali", ".smali")
    new_smali = files(rebuilt / "smali", ".smali")
    if old_smali.keys() != new_smali.keys():
        raise RuntimeError("the rebuilt DEX has a different class/file set")
    changed = [path for path in old_smali if old_smali[path] != new_smali[path]]
    if changed != [TARGET]:
        raise RuntimeError(f"code outside the map server changed: {changed}")

    old_server = old_smali[TARGET]
    new_server = new_smali[TARGET]
    old_methods = METHOD.findall(old_server)
    new_methods = METHOD.findall(new_server)
    if len(old_methods) != 1 or len(new_methods) != 1:
        raise RuntimeError("could not isolate checkTimeout for verification")
    if METHOD.sub(b"<checkTimeout>", old_server) != METHOD.sub(b"<checkTimeout>", new_server):
        raise RuntimeError("C3LinkServer changed outside checkTimeout")
    if b".locals 0" not in new_methods[0] or b"disconnect(" in new_methods[0]:
        raise RuntimeError("the rebuilt timeout method is not the expected route-preserving no-op")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(original / name, rebuilt / name, name)
    if (original / "AndroidManifest.xml").read_bytes() != (rebuilt / "AndroidManifest.xml").read_bytes():
        raise RuntimeError("decoded Android manifest structure changed")

    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10803" not in config or "versionName: 1.8.3" not in config:
        raise RuntimeError("rebuilt APK does not report version 1.8.3")
    print("verified: only C3LinkServer.checkTimeout changed; UI and media code are identical")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
