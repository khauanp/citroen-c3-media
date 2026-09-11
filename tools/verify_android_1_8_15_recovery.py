#!/usr/bin/env python3
"""Verify the 1.8.15 boot recovery and reject every MBTiles APK change."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import method, require_equal_tree
from verify_android_1_8_8_stability import all_smali


DASHBOARD = Path("io/github/jqssun/airplay/ui/DashboardView.smali")
ACTIVITY = Path("io/github/jqssun/airplay/MainActivity.smali")
SERVICE = Path("io/github/jqssun/airplay/service/AirPlayService.smali")
FROZEN_STARTUP_AND_MAP_PREFIXES = (
    "io/github/jqssun/airplay/MainActivity",
    "io/github/jqssun/airplay/C3MediaApplication",
    "io/github/jqssun/airplay/BootReceiver",
    "io/github/jqssun/airplay/connectivity/C3Link",
    "io/github/jqssun/airplay/connectivity/C3Map",
    "io/github/jqssun/airplay/service/MapTile",
    "io/github/jqssun/airplay/service/C3LinkNavigation",
)


def joined(files: dict[Path, bytes], prefix: str) -> bytes:
    return b"\n".join(content for path, content in files.items() if str(path).startswith(prefix))


def main() -> int:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: verify_android_1_8_15_recovery.py REFERENCE_1811 REFERENCE_1812 REBUILT_1815"
        )
    reference = all_smali(Path(sys.argv[1]).resolve())
    stable_media = all_smali(Path(sys.argv[2]).resolve())
    recovered = all_smali(Path(sys.argv[3]).resolve())

    for path in sorted(set(reference) | set(recovered)):
        if any(str(path).startswith(prefix) for prefix in FROZEN_STARTUP_AND_MAP_PREFIXES):
            if reference.get(path) != recovered.get(path):
                raise RuntimeError(f"startup/map class differs from bootable 1.8.11: {path}")

    if recovered[ACTIVITY] != reference[ACTIVITY]:
        raise RuntimeError("MainActivity differs from bootable 1.8.11")
    if method(
        recovered[DASHBOARD], b"public onTouchEvent(Landroid/view/MotionEvent;)Z"
    ) != method(reference[DASHBOARD], b"public onTouchEvent(Landroid/view/MotionEvent;)Z"):
        raise RuntimeError("control-free tablet touch behavior was not restored")

    service = recovered[SERVICE]
    if b"AUDIO_PLAYBACK_IDLE_MS:J = 0x2ee0L" not in service or b"const-wide/16 v3, 0x2ee0" not in service:
        raise RuntimeError("the accepted 1.8.12 transition tolerance is missing")
    for prefix in (
        "io/github/jqssun/airplay/audio/DacpController",
        "io/github/jqssun/airplay/audio/RadioMediaSession",
    ):
        if joined(recovered, prefix) != joined(stable_media, prefix):
            raise RuntimeError(f"1.8.12 media receiver changed: {prefix}")

    all_recovered = b"\n".join(recovered.values())
    for marker in (b"C3MbTilesStore", b"c3-map.mbtiles", b"CREATE TABLE tiles"):
        if marker in all_recovered:
            raise RuntimeError(f"MBTiles code entered recovery APK: {marker!r}")

    rebuilt = Path(sys.argv[3]).resolve()
    reference_root = Path(sys.argv[1]).resolve()
    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(reference_root / name, rebuilt / name, name)
    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10815" not in config or "versionName: 1.8.15" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.15")

    print("verified: bootable 1.8.11 startup/map; 1.8.12 media guard; no controls; no MBTiles")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
