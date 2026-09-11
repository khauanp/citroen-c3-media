#!/usr/bin/env python3
"""Apply the map-only 1.8.3 patch to an apktool-decoded C3 Media 1.8.1 tree."""

from __future__ import annotations

import re
import sys
from pathlib import Path


SERVER_PATH = Path(
    "smali/io/github/jqssun/airplay/connectivity/C3LinkServer.smali"
)


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f"expected one {label}, found {count}")
    return value.replace(old, new, 1)


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_android_1_8_3_mapfix.py APKTOOL_DIRECTORY")

    root = Path(sys.argv[1]).resolve()
    server_file = root / SERVER_PATH
    config_file = root / "apktool.yml"
    server = server_file.read_text(encoding="utf-8")

    pattern = re.compile(
        r"\.method private final checkTimeout\(\)V\n.*?^\.end method",
        re.MULTILINE | re.DOTALL,
    )
    matches = pattern.findall(server)
    if len(matches) != 1:
        raise RuntimeError(f"expected one checkTimeout method, found {len(matches)}")
    original_method = matches[0]
    required = (
        "const-wide/16 v3, 0x4e20",
        "SystemClock;->elapsedRealtime()J",
        "C3LinkServer;->disconnect(Ljava/lang/String;)V",
    )
    for marker in required:
        if marker not in original_method:
            raise RuntimeError(f"unexpected 1.8.1 checkTimeout method: missing {marker}")

    replacement = """.method private final checkTimeout()V
    .locals 0

    # Preserve the active route through short background UDP pauses. Explicit
    # stop and goodbye packets still clear navigation through disconnect().
    return-void
.end method"""
    server_file.write_text(pattern.sub(replacement, server, count=1), encoding="utf-8")

    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10801", "  versionCode: 10803", "versionCode")
    config = replace_once(config, "  versionName: 1.8.1", "  versionName: 1.8.3", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
