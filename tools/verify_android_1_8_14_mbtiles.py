#!/usr/bin/env python3
"""Verify media rollback, removed screen controls and the bounded MBTiles map patch."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import method, require_equal_tree
from verify_android_1_8_8_stability import all_smali


def joined(files: dict[Path, bytes], prefix: str) -> bytes:
    return b"\n".join(content for path, content in files.items() if str(path).startswith(prefix))


def main() -> int:
    if len(sys.argv) != 4:
        raise SystemExit("usage: verify_android_1_8_14_mbtiles.py REFERENCE_1811 REFERENCE_1812 REBUILT_1814")
    ref_1811 = Path(sys.argv[1]).resolve()
    ref_1812 = Path(sys.argv[2]).resolve()
    rebuilt = Path(sys.argv[3]).resolve()
    old = all_smali(ref_1811)
    stable = all_smali(ref_1812)
    new = all_smali(rebuilt)

    dashboard = new[Path("io/github/jqssun/airplay/ui/DashboardView.smali")]
    activity = new[Path("io/github/jqssun/airplay/MainActivity.smali")]
    service = new[Path("io/github/jqssun/airplay/service/AirPlayService.smali")]
    forbidden = (b"drawPlayerControls", b"dispatchMediaKey(I)V")
    for marker in forbidden:
        if marker in dashboard or marker in activity:
            raise RuntimeError(f"tablet control code remains: {marker!r}")
    if b"dispatchMediaKey(I)V" in service:
        raise RuntimeError("tablet/physical command bridge remains in the receiver service")
    old_dashboard = old[Path("io/github/jqssun/airplay/ui/DashboardView.smali")]
    old_activity = old[Path("io/github/jqssun/airplay/MainActivity.smali")]
    if method(dashboard, b"public onTouchEvent(Landroid/view/MotionEvent;)Z") != method(old_dashboard, b"public onTouchEvent(Landroid/view/MotionEvent;)Z"):
        raise RuntimeError("tablet touch behavior differs from control-free 1.8.11")
    if activity != old_activity:
        raise RuntimeError("Activity key/control behavior differs from control-free 1.8.11")
    if b"AUDIO_PLAYBACK_IDLE_MS:J = 0x2ee0L" not in service or b"const-wide/16 v3, 0x2ee0" not in service:
        raise RuntimeError("the 1.8.12 12-second track-transition guard is missing")

    for prefix in (
        "io/github/jqssun/airplay/audio/DacpController",
        "io/github/jqssun/airplay/audio/RadioMediaSession",
    ):
        if joined(new, prefix) != joined(stable, prefix):
            raise RuntimeError(f"1.8.12 stable receiver class changed: {prefix}")

    mbtiles = joined(new, "io/github/jqssun/airplay/connectivity/C3MbTilesStore")
    tile_store = new[Path("io/github/jqssun/airplay/connectivity/C3MapTileStore.smali")]
    road_safety = new[Path("io/github/jqssun/airplay/connectivity/C3LinkRoadSafety.smali")]
    for marker in (b"c3-map.mbtiles", b"CREATE TABLE tiles", b"enableWriteAheadLogging", b"tile_state"):
        if marker not in mbtiles:
            raise RuntimeError(f"MBTiles durability marker missing: {marker!r}")
    for marker in (b"C3MbTilesStore;->initialize", b"C3MbTilesStore;->read", b"C3MbTilesStore;->write"):
        if marker not in tile_store:
            raise RuntimeError(f"tile store is not wired to MBTiles: {marker!r}")
    if b"getRouteProgressIndex()I" not in road_safety or b"routeProgressIndex" not in dashboard:
        raise RuntimeError("already-travelled route styling is not connected")
    if b"DayNightPolicy;->isDaytimeNow()Z" not in dashboard:
        raise RuntimeError("map is not following the automatic day/night theme")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(ref_1811 / name, rebuilt / name, name)
    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10814" not in config or "versionName: 1.8.14" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.14")
    print("verified: 1.8.12 media; no tablet controls; MBTiles; themed route progress")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
