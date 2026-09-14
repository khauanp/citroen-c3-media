#!/usr/bin/env python3
"""Verify 1.8.11 only adds clock theme and conservative thermal thresholds."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import method, require_equal_tree
from verify_android_1_8_8_stability import all_smali


DASHBOARD = Path("io/github/jqssun/airplay/ui/DashboardView.smali")
ACTIVITY = Path("io/github/jqssun/airplay/MainActivity.smali")
ENERGY = Path("io/github/jqssun/airplay/power/EnergyPolicy.smali")
DAY_NIGHT = Path("io/github/jqssun/airplay/ui/DayNightPolicy.smali")
FROZEN_MAP_PREFIXES = (
    "io/github/jqssun/airplay/connectivity/C3Link",
    "io/github/jqssun/airplay/connectivity/C3Map",
    "io/github/jqssun/airplay/service/MapTile",
    "io/github/jqssun/airplay/service/C3LinkNavigation",
)
FROZEN_DASHBOARD_METHODS = (
    b"private final drawNavigation(Landroid/graphics/Canvas;)V",
    b"private final drawNavigationMarker(Landroid/graphics/Canvas;FF)V",
    b"private static final tileInvalidator$lambda$1(Lio/github/jqssun/airplay/ui/DashboardView;)Lkotlin/Unit;",
    b"private static final tileRedrawRunnable$lambda$0(Lio/github/jqssun/airplay/ui/DashboardView;)V",
    b"private final updateMapBearing(Ljava/lang/String;F)F",
    b"public final getMapTileStore()Lio/github/jqssun/airplay/connectivity/C3MapTileStore;",
    b"public final setMapTileStore(Lio/github/jqssun/airplay/connectivity/C3MapTileStore;)V",
)


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_11_daylight.py REFERENCE_1810 REBUILT_1811")
    reference = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()
    old = all_smali(reference)
    new = all_smali(rebuilt)

    frozen = {
        path for path in set(old) | set(new)
        if any(str(path).startswith(prefix) for prefix in FROZEN_MAP_PREFIXES)
    }
    for path in sorted(frozen):
        if old.get(path) != new.get(path):
            raise RuntimeError(f"map/route/tile implementation changed: {path}")

    for signature in FROZEN_DASHBOARD_METHODS:
        if method(old[DASHBOARD], signature) != method(new[DASHBOARD], signature):
            raise RuntimeError(f"dashboard map method changed: {signature!r}")

    changed = {path for path in old.keys() & new.keys() if old[path] != new[path]}
    added = set(new) - set(old)
    deleted = set(old) - set(new)
    if changed != {DASHBOARD, ACTIVITY, ENERGY}:
        raise RuntimeError(f"unexpected changed classes: {sorted(changed)}")
    if added != {DAY_NIGHT} or deleted:
        raise RuntimeError(f"unexpected class scope: {sorted(added)=} {sorted(deleted)=}")

    theme = new[DASHBOARD]
    for marker in (
        b"applyAutomaticTheme()V",
        b"DayNightPolicy;->isDaytimeNow()Z",
        b"0xe8",  # daylight background red component
        b"0xff",  # daylight cards
        b"0x12",  # daylight primary text
    ):
        if marker not in theme:
            raise RuntimeError(f"automatic daylight palette marker missing: {marker!r}")
    if b".field private static final LINK_BLUE:I" not in theme:
        raise RuntimeError("route-blue constant was made mutable")

    policy = new[DAY_NIGHT]
    for marker in (b"DAY_START_HOUR", b"NIGHT_START_HOUR", b"isDaytime(I)Z", b"activeBrightnessNow()F"):
        if marker not in policy:
            raise RuntimeError(f"day/night policy marker missing: {marker!r}")

    activity = new[ACTIVITY]
    if activity.count(b"DayNightPolicy;->activeBrightnessNow()F") != 2:
        raise RuntimeError("initial and active brightness are not both automatic")

    energy = new[ENERGY]
    for marker in (
        b"THERMAL_LIMIT_C:F = 45.0f",
        b"THERMAL_RECOVERY_C:F = 41.0f",
        b"0x42340000    # 45.0f",
        b"0x42240000    # 41.0f",
    ):
        if marker not in energy:
            raise RuntimeError(f"thermal hysteresis marker missing: {marker!r}")

    # All media/network code must be byte-identical to the already isolated 1.8.10.
    for path in sorted(set(old) & set(new)):
        if path not in {DASHBOARD, ACTIVITY, ENERGY} and old[path] != new[path]:
            raise RuntimeError(f"1.8.10 media/network regression: {path}")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(reference / name, rebuilt / name, name)

    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10811" not in config or "versionName: 1.8.11" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.11")

    print("verified: 1.8.10 media frozen; map engine frozen; 07-19 daylight; 45/41 C thermal hysteresis")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
