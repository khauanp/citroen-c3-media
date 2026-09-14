#!/usr/bin/env python3
"""Apply K00E Wi-Fi/Bluetooth coexistence hardening over exact 1.8.10."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_10_media import main as apply_1_8_10


AUDIO = Path("smali/io/github/jqssun/airplay/renderer/AudioRenderer.smali")
HOTSPOT_PACKAGE = Path("io/github/jqssun/airplay/connectivity")


def copy_hotspot_helper(root: Path, helper: Path) -> None:
    target = root / "smali" / HOTSPOT_PACKAGE
    for old in target.glob("HotspotController*.smali"):
        old.unlink()
    copied: set[str] = set()
    for smali_root in sorted(helper.glob("smali*")):
        source = smali_root / HOTSPOT_PACKAGE
        if not source.is_dir():
            continue
        for item in source.glob("HotspotController*.smali"):
            shutil.copyfile(item, target / item.name)
            copied.add(item.name)
    for required in ("HotspotController.smali", "HotspotController$Result.smali"):
        if required not in copied:
            raise RuntimeError(f"compiled hotspot helper not found: {required}")


def patch_audio_resilience(root: Path) -> None:
    path = root / AUDIO
    value = path.read_text(encoding="utf-8")
    value = replace_once(
        value,
        "    const/16 v6, 0x3e8",
        "    const/16 v6, 0x5dc",
        "1500 ms RF coexistence cushion",
    )
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_android_1_8_11_coexistence.py APKTOOL_DIRECTORY HELPER_DECODE")
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()

    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_10_media.py", str(root), str(helper)]
        apply_1_8_10()
    finally:
        sys.argv = old_argv

    copy_hotspot_helper(root, helper)
    patch_audio_resilience(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10810", "  versionCode: 10811", "versionCode")
    config = replace_once(config, "  versionName: 1.8.10", "  versionName: 1.8.11", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
