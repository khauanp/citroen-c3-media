#!/usr/bin/env python3
"""Apply exact long-route integrity and road-safety display to the real 1.8.1 APK."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import (
    DASHBOARD_PATH,
    patch_audio_jitter,
    patch_route_rendering,
    patch_timeout,
    patch_video_queue,
    replace_once,
)


ASSEMBLER_PATH = "smali/io/github/jqssun/airplay/connectivity/C3LinkRouteAssembler.smali"
POLYLINE_PATH = "smali/io/github/jqssun/airplay/connectivity/C3LinkPolyline.smali"
SERVER_PATH = "smali/io/github/jqssun/airplay/connectivity/C3LinkServer.smali"
HELPER_DIRECTORY = Path(__file__).resolve().parent / "android_1_8_7"


def patch_route_assembler(root: Path) -> None:
    path = root / ASSEMBLER_PATH
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, "    const/16 p1, 0x40", "    const/16 p1, 0x200", "512 route parts")
    value = replace_once(
        value,
        "    const-wide/16 p3, 0x4e20",
        "    const-wide/32 p3, 0xea60",
        "60 second exact-route assembly timeout",
    )
    path.write_text(value, encoding="utf-8")


def patch_polyline_decoder(root: Path) -> None:
    path = root / POLYLINE_PATH
    value = path.read_text(encoding="utf-8")
    null_branch = """    if-nez v6, :cond_1

    goto :goto_1
"""
    value = replace_once(
        value,
        null_branch,
        null_branch.replace("goto :goto_1", "goto :c3_decode_invalid"),
        "invalid latitude value rejection",
    )
    null_branch = """    if-nez v9, :cond_2

    goto :goto_1
"""
    value = replace_once(
        value,
        null_branch,
        null_branch.replace("goto :goto_1", "goto :c3_decode_invalid"),
        "invalid longitude value rejection",
    )
    old_return = """    :cond_4
    :goto_1
    check-cast v5, Ljava/util/List;

    return-object v5

    .line 12
    :cond_5
"""
    new_return = """    :cond_4
    invoke-virtual {v1}, Ljava/lang/String;->length()I

    move-result v9

    if-ne v6, v9, :c3_decode_invalid

    :goto_1
    check-cast v5, Ljava/util/List;

    return-object v5

    :c3_decode_invalid
    invoke-static {}, Lkotlin/collections/CollectionsKt;->emptyList()Ljava/util/List;

    move-result-object v1

    return-object v1

    .line 12
    :cond_5
"""
    value = replace_once(value, old_return, new_return, "full polyline consumption check")
    path.write_text(value, encoding="utf-8")


def patch_server(root: Path) -> None:
    path = root / SERVER_PATH
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, "    const/16 v2, 0x2ee0", "    const v2, 0xc350", "50,000 route points")
    success = """    .line 247
    :cond_0
    iget-object v3, v0, Lio/github/jqssun/airplay/connectivity/C3LinkServer;->navigation:Lio/github/jqssun/airplay/service/C3LinkNavigation;
"""
    verified_success = """    move-object/from16 v1, p4

    move-object/from16 v2, p6

    invoke-static {v1, v2, v9}, Lio/github/jqssun/airplay/connectivity/C3LinkRouteIntegrity;->validate(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;)Z

    move-result v1

    if-eqz v1, :cond_1

    .line 247
    :cond_0
    invoke-static {}, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->reset()V

    iget-object v3, v0, Lio/github/jqssun/airplay/connectivity/C3LinkServer;->navigation:Lio/github/jqssun/airplay/service/C3LinkNavigation;
"""
    value = replace_once(value, success, verified_success, "route CRC and point-count validation")
    accepted_position = """    .line 276
    :cond_0
    iget-object v2, v0, Lio/github/jqssun/airplay/connectivity/C3LinkServer;->navigation:Lio/github/jqssun/airplay/service/C3LinkNavigation;
"""
    accepted_position_with_safety = """    .line 276
    :cond_0
    invoke-static {v1}, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->update(Lorg/json/JSONObject;)V

    iget-object v2, v0, Lio/github/jqssun/airplay/connectivity/C3LinkServer;->navigation:Lio/github/jqssun/airplay/service/C3LinkNavigation;
"""
    value = replace_once(value, accepted_position, accepted_position_with_safety, "road-safety position fields")
    path.write_text(value, encoding="utf-8")


def patch_dashboard(root: Path) -> None:
    patch_route_rendering(root)
    path = root / DASHBOARD_PATH
    value = path.read_text(encoding="utf-8")

    unsafe_path = """    iget-object v2, v0, Lio/github/jqssun/airplay/ui/DashboardView;->path:Landroid/graphics/Path;

    iget-object v3, v0, Lio/github/jqssun/airplay/ui/DashboardView;->linePaint:Landroid/graphics/Paint;

    invoke-virtual {v1, v2, v3}, Landroid/graphics/Canvas;->drawPath(Landroid/graphics/Path;Landroid/graphics/Paint;)V
"""
    safe_reset = """    iget-object v2, v0, Lio/github/jqssun/airplay/ui/DashboardView;->path:Landroid/graphics/Path;

    invoke-virtual {v2}, Landroid/graphics/Path;->reset()V
"""
    if value.count(unsafe_path) != 2:
        raise RuntimeError("expected exactly two unsafe long-route Path draws")
    value = value.replace(unsafe_path, safe_reset, 2)

    restore = """    .line 417
    :cond_f
    invoke-virtual {v1}, Landroid/graphics/Canvas;->restore()V
"""
    radar_then_restore = """    .line 417
    :cond_f
    move-object/from16 v28, v1

    move-wide/from16 v29, v34

    move-wide/from16 v31, v36

    move/from16 v33, v7

    invoke-static/range {v28 .. v33}, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->drawRadar(Landroid/graphics/Canvas;DDI)V

    invoke-virtual {v1}, Landroid/graphics/Canvas;->restore()V
"""
    value = replace_once(value, restore, radar_then_restore, "upcoming radar map marker")

    speed_background = """    .line 456
    iget-object v2, v0, Lio/github/jqssun/airplay/ui/DashboardView;->paint:Landroid/graphics/Paint;

    const/16 v3, 0xd7

    const/16 v4, 0xa

    invoke-static {v3, v15, v4, v13}, Landroid/graphics/Color;->argb(IIII)I

    move-result v3

    invoke-virtual {v2, v3}, Landroid/graphics/Paint;->setColor(I)V
"""
    safety_speed_background = """    .line 456
    iget-object v2, v0, Lio/github/jqssun/airplay/ui/DashboardView;->paint:Landroid/graphics/Paint;

    invoke-virtual {v12}, Lio/github/jqssun/airplay/service/C3LinkNavigation;->getSpeedMps()D

    move-result-wide v3

    invoke-static {v3, v4}, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->isSpeeding(D)Z

    move-result v3

    if-eqz v3, :c3_speed_normal

    sget v3, Lio/github/jqssun/airplay/ui/DashboardView;->RED:I

    invoke-virtual {v2, v3}, Landroid/graphics/Paint;->setColor(I)V

    goto :c3_speed_color_done

    :c3_speed_normal
    const/16 v3, 0xd7

    const/16 v4, 0xa

    invoke-static {v3, v15, v4, v13}, Landroid/graphics/Color;->argb(IIII)I

    move-result v3

    invoke-virtual {v2, v3}, Landroid/graphics/Paint;->setColor(I)V

    :c3_speed_color_done
"""
    value = replace_once(value, speed_background, safety_speed_background, "red overspeed speedometer")

    speed_limit_anchor = """    move-object v8, v0

    .line 461
    iget-object v0, v8, Lio/github/jqssun/airplay/ui/DashboardView;->linePaint:Landroid/graphics/Paint;
"""
    speed_limit_display = """    move-object v8, v0

    move-object/from16 v1, p1

    invoke-static {v1}, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->drawSpeedLimit(Landroid/graphics/Canvas;)V

    .line 461
    iget-object v0, v8, Lio/github/jqssun/airplay/ui/DashboardView;->linePaint:Landroid/graphics/Paint;
"""
    value = replace_once(value, speed_limit_anchor, speed_limit_display, "speed limit and camera warning")
    path.write_text(value, encoding="utf-8")


def install_helpers(root: Path) -> None:
    destination = root / "smali/io/github/jqssun/airplay/connectivity"
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("C3LinkRouteIntegrity.smali", "C3LinkRoadSafety.smali"):
        shutil.copyfile(HELPER_DIRECTORY / name, destination / name)


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_android_1_8_7_navigation.py APKTOOL_DIRECTORY")

    root = Path(sys.argv[1]).resolve()
    patch_timeout(root)
    patch_route_assembler(root)
    patch_polyline_decoder(root)
    patch_server(root)
    patch_dashboard(root)
    patch_audio_jitter(root)
    patch_video_queue(root)
    install_helpers(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10801", "  versionCode: 10807", "versionCode")
    config = replace_once(config, "  versionName: 1.8.1", "  versionName: 1.8.7", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
