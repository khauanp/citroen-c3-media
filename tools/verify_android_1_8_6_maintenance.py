#!/usr/bin/env python3
"""Verify 1.8.6 preserves 1.8.1 and covers the rotated K00E viewport."""

from __future__ import annotations

import math
import re
import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import (
    AUDIO,
    DASHBOARD,
    EXPECTED_CHANGED,
    SERVER,
    VIDEO,
    files,
    method,
    outside_method,
    require_equal_tree,
    verify_single_replacement,
)


SAFE_MIN = -512.0
SAFE_MAX = 1792.0


def route_guard_accepts(previous: tuple[float, float], current: tuple[float, float]) -> bool:
    return all(
        SAFE_MIN <= value <= SAFE_MAX
        for value in (previous[0], current[0], previous[1], current[1])
    )


def verify_guard_model() -> None:
    cases = {
        ((640.0, 620.0), (700.0, 650.0)): True,
        ((SAFE_MIN, SAFE_MIN), (SAFE_MAX, SAFE_MAX)): True,
        ((SAFE_MIN - 1.0, 620.0), (640.0, 620.0)): False,
        ((640.0, 620.0), (SAFE_MAX + 1.0, 620.0)): False,
        ((640.0, SAFE_MIN - 1.0), (640.0, 620.0)): False,
        ((640.0, 620.0), (640.0, SAFE_MAX + 1.0)): False,
        ((-1_000_000.0, -1_000_000.0), (640.0, 620.0)): False,
    }
    for (previous, current), expected in cases.items():
        if route_guard_accepts(previous, current) != expected:
            raise RuntimeError(f"route guard model failed for {previous=} {current=}")


def verify_rotation_envelope() -> None:
    center_x, center_y = 640.0, 620.0
    corners = ((0.0, 0.0), (1280.0, 0.0), (0.0, 800.0), (1280.0, 800.0))
    for degrees in range(360):
        radians = math.radians(degrees)
        cosine = math.cos(radians)
        sine = math.sin(radians)
        for screen_x, screen_y in corners:
            dx = screen_x - center_x
            dy = screen_y - center_y
            world_x = center_x + dx * cosine - dy * sine
            world_y = center_y + dx * sine + dy * cosine
            if not (SAFE_MIN <= world_x <= SAFE_MAX and SAFE_MIN <= world_y <= SAFE_MAX):
                raise RuntimeError(
                    f"rotation envelope misses {degrees=} {screen_x=} {screen_y=} "
                    f"at {(world_x, world_y)}"
                )


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_6_maintenance.py ORIGINAL_DECODE REBUILT_DECODE")
    original = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()

    old_smali = files(original / "smali", ".smali")
    new_smali = files(rebuilt / "smali", ".smali")
    if old_smali.keys() != new_smali.keys():
        raise RuntimeError("rebuilt DEX has a different class/file set")
    changed = [path for path in old_smali if old_smali[path] != new_smali[path]]
    if changed != EXPECTED_CHANGED:
        raise RuntimeError(f"code outside the approved maintenance scope changed: {changed}")

    old_server, new_server = old_smali[SERVER], new_smali[SERVER]
    server_sig = b"private final checkTimeout()V"
    if outside_method(old_server, server_sig) != outside_method(new_server, server_sig):
        raise RuntimeError("C3LinkServer changed outside checkTimeout")
    timeout = method(new_server, server_sig)
    if b".locals 0" not in timeout or b"disconnect(" in timeout:
        raise RuntimeError("route timeout preservation is missing")

    old_dashboard, new_dashboard = old_smali[DASHBOARD], new_smali[DASHBOARD]
    nav_sig = b"private final drawNavigation(Landroid/graphics/Canvas;)V"
    if outside_method(old_dashboard, nav_sig) != outside_method(new_dashboard, nav_sig):
        raise RuntimeError("DashboardView changed outside drawNavigation")
    old_nav = method(old_dashboard, nav_sig)
    nav = method(new_dashboard, nav_sig)
    if nav.count(b"Canvas;->drawPath") != old_nav.count(b"Canvas;->drawPath"):
        raise RuntimeError("original route Path fallback changed unexpectedly")
    if nav.count(b"Canvas;->drawLine(FFFFLandroid/graphics/Paint;)V") != 2:
        raise RuntimeError("direct route outline/blue segment draws are missing")
    route_markers = {
        b"const/high16 v10, -0x3c000000": 2,
        b"const/high16 v10, 0x44e00000": 2,
        b"const v13, -0x23fef8f4": 1,
        b"move/from16 v32, v6": 2,
        b"move/from16 v33, v4": 2,
    }
    for marker, count in route_markers.items():
        if nav.count(marker) != count:
            raise RuntimeError(f"rotation-safe route renderer marker mismatch: {marker!r}")
    guard = nav.split(b"const/high16 v10, -0x3c000000", 1)[1].split(
        b"iget-object v10, v0, Lio/github/jqssun/airplay/ui/DashboardView;->linePaint",
        1,
    )[0]
    lower_targets = re.findall(rb"if-ltz v13, :([a-zA-Z0-9_]+)", guard)
    upper_targets = re.findall(rb"if-gtz v13, :([a-zA-Z0-9_]+)", guard)
    if len(lower_targets) != 4 or len(upper_targets) != 4:
        raise RuntimeError("route guard does not reject all endpoint bounds")
    if len(set(lower_targets + upper_targets)) != 1:
        raise RuntimeError("route guard bounds do not share the same safe skip target")
    for forbidden in (b"-0x3d800000", b"-0x3c800000", b"0x44840000"):
        if forbidden in guard:
            raise RuntimeError(f"an older incomplete route envelope remains: {forbidden!r}")
    verify_guard_model()
    verify_rotation_envelope()

    old_audio, new_audio = old_smali[AUDIO], new_smali[AUDIO]
    audio_sig = b"public final declared-synchronized attachEngine(J)V"
    if outside_method(old_audio, audio_sig) != outside_method(new_audio, audio_sig):
        raise RuntimeError("AudioRenderer changed outside attachEngine")
    verify_single_replacement(
        method(old_audio, audio_sig),
        method(new_audio, audio_sig),
        b"const/16 v7, 0x5f",
        b"const/16 v7, 0x63",
        "audio jitter percentile",
    )

    old_video, new_video = old_smali[VIDEO], new_smali[VIDEO]
    video_sig = b"private final _feedToCodec([BJ)V"
    if outside_method(old_video, video_sig) != outside_method(new_video, video_sig):
        raise RuntimeError("VideoRenderer changed outside _feedToCodec")
    old_feed = method(old_video, video_sig)
    expected_feed = old_feed.replace(b"const/4 v1, 0x3", b"const/4 v1, 0x4", 1)
    expected_feed = expected_feed.replace(b"const/16 v1, 0xf", b"const/16 v1, 0x14", 1)
    expected_feed = expected_feed.replace(b"const-wide/16 v3, 0x7d0", b"const-wide/16 v3, 0xfa0", 1)
    if expected_feed != method(new_video, video_sig):
        raise RuntimeError("video queue maintenance differs from the approved constants")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(original / name, rebuilt / name, name)
    if (original / "AndroidManifest.xml").read_bytes() != (rebuilt / "AndroidManifest.xml").read_bytes():
        raise RuntimeError("decoded Android manifest structure changed")

    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10806" not in config or "versionName: 1.8.6" not in config:
        raise RuntimeError("rebuilt APK does not report version 1.8.6")
    print("verified: 1.8.1 UI/player preserved; route envelope covers every map bearing")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
