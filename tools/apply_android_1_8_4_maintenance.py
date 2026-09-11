#!/usr/bin/env python3
"""Apply the focused 1.8.4 map/media maintenance to the real 1.8.1 APK."""

from __future__ import annotations

import re
import sys
from pathlib import Path


SERVER_PATH = Path("smali/io/github/jqssun/airplay/connectivity/C3LinkServer.smali")
DASHBOARD_PATH = Path("smali/io/github/jqssun/airplay/ui/DashboardView.smali")
AUDIO_PATH = Path("smali/io/github/jqssun/airplay/renderer/AudioRenderer.smali")
VIDEO_PATH = Path("smali/io/github/jqssun/airplay/renderer/VideoRenderer.smali")


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f"expected one {label}, found {count}")
    return value.replace(old, new, 1)


def replace_method(value: str, signature: str, replacement: str, label: str) -> str:
    pattern = re.compile(
        rf"\.method {re.escape(signature)}\n.*?^\.end method",
        re.MULTILINE | re.DOTALL,
    )
    matches = pattern.findall(value)
    if len(matches) != 1:
        raise RuntimeError(f"expected one {label} method, found {len(matches)}")
    return pattern.sub(replacement, value, count=1)


def patch_timeout(root: Path) -> None:
    path = root / SERVER_PATH
    value = path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"\.method private final checkTimeout\(\)V\n.*?^\.end method",
        re.MULTILINE | re.DOTALL,
    )
    matches = pattern.findall(value)
    if len(matches) != 1:
        raise RuntimeError(f"expected one checkTimeout method, found {len(matches)}")
    original = matches[0]
    for marker in (
        "const-wide/16 v3, 0x4e20",
        "SystemClock;->elapsedRealtime()J",
        "C3LinkServer;->disconnect(Ljava/lang/String;)V",
    ):
        if marker not in original:
            raise RuntimeError(f"unexpected 1.8.1 checkTimeout method: missing {marker}")
    replacement = """.method private final checkTimeout()V
    .locals 0

    # Keep the current C3 Link route through short UDP/background pauses.
    # Explicit stop and goodbye packets continue to clear navigation.
    return-void
.end method"""
    path.write_text(pattern.sub(replacement, value, count=1), encoding="utf-8")


def patch_route_rendering(root: Path) -> None:
    path = root / DASHBOARD_PATH
    value = path.read_text(encoding="utf-8")
    old = """    sub-double v13, v13, v36

    double-to-float v4, v13

    add-float/2addr v4, v9

    if-nez v3, :cond_d
"""
    new = """    sub-double v13, v13, v36

    double-to-float v4, v13

    add-float/2addr v4, v9

    if-nez v3, :cond_d
"""
    value = replace_once(value, old, new, "route coordinate anchor")

    old_branch = """    iget-object v3, v0, Lio/github/jqssun/airplay/ui/DashboardView;->path:Landroid/graphics/Path;

    invoke-virtual {v3, v6, v4}, Landroid/graphics/Path;->moveTo(FF)V

    goto :goto_9

    :cond_d
    iget-object v3, v0, Lio/github/jqssun/airplay/ui/DashboardView;->path:Landroid/graphics/Path;

    invoke-virtual {v3, v6, v4}, Landroid/graphics/Path;->lineTo(FF)V

    :goto_9
    move v3, v5
"""
    new_branch = """    iget-object v3, v0, Lio/github/jqssun/airplay/ui/DashboardView;->path:Landroid/graphics/Path;

    invoke-virtual {v3, v6, v4}, Landroid/graphics/Path;->moveTo(FF)V

    move/from16 v32, v6

    move/from16 v33, v4

    goto :goto_9

    :cond_d
    iget-object v3, v0, Lio/github/jqssun/airplay/ui/DashboardView;->path:Landroid/graphics/Path;

    invoke-virtual {v3, v6, v4}, Landroid/graphics/Path;->lineTo(FF)V

    # Draw each visible segment directly as well as retaining the original
    # Path fallback. This avoids the Android 5 hardware-Path failure observed
    # on the K00E for both short and long routes.
    const/high16 v10, -0x3d800000    # -64.0f

    cmpg-float v13, v32, v10

    if-gez v13, :c3_route_check_left

    goto :c3_route_right

    :c3_route_check_left
    cmpg-float v13, v6, v10

    if-gez v13, :c3_route_segment_done

    :c3_route_right
    const/high16 v10, 0x44a80000    # 1344.0f

    cmpl-float v13, v32, v10

    if-gtz v13, :c3_route_check_right

    goto :c3_route_top

    :c3_route_check_right
    cmpl-float v13, v6, v10

    if-gtz v13, :c3_route_segment_done

    :c3_route_top
    const/high16 v10, -0x3d800000    # -64.0f

    cmpg-float v13, v33, v10

    if-gez v13, :c3_route_check_top

    goto :c3_route_bottom

    :c3_route_check_top
    cmpg-float v13, v4, v10

    if-gez v13, :c3_route_segment_done

    :c3_route_bottom
    const/high16 v10, 0x44580000    # 864.0f

    cmpl-float v13, v33, v10

    if-gtz v13, :c3_route_check_bottom

    goto :c3_route_draw_segment

    :c3_route_check_bottom
    cmpl-float v13, v4, v10

    if-gtz v13, :c3_route_segment_done

    :c3_route_draw_segment
    iget-object v10, v0, Lio/github/jqssun/airplay/ui/DashboardView;->linePaint:Landroid/graphics/Paint;

    const v13, -0x23fef8f4    # 0xdc01070c

    invoke-virtual {v10, v13}, Landroid/graphics/Paint;->setColor(I)V

    const/high16 v13, 0x41c00000    # 24.0f

    invoke-virtual {v10, v13}, Landroid/graphics/Paint;->setStrokeWidth(F)V

    move-object/from16 v19, v1

    move/from16 v20, v32

    move/from16 v21, v33

    move/from16 v22, v6

    move/from16 v23, v4

    move-object/from16 v24, v10

    invoke-virtual/range {v19 .. v24}, Landroid/graphics/Canvas;->drawLine(FFFFLandroid/graphics/Paint;)V

    sget v13, Lio/github/jqssun/airplay/ui/DashboardView;->LINK_BLUE:I

    invoke-virtual {v10, v13}, Landroid/graphics/Paint;->setColor(I)V

    const/high16 v13, 0x41400000    # 12.0f

    invoke-virtual {v10, v13}, Landroid/graphics/Paint;->setStrokeWidth(F)V

    invoke-virtual/range {v19 .. v24}, Landroid/graphics/Canvas;->drawLine(FFFFLandroid/graphics/Paint;)V

    :c3_route_segment_done
    move/from16 v32, v6

    move/from16 v33, v4

    :goto_9
    move v3, v5
"""
    value = replace_once(value, old_branch, new_branch, "direct route segment renderer")
    path.write_text(value, encoding="utf-8")


def patch_audio_jitter(root: Path) -> None:
    path = root / AUDIO_PATH
    value = path.read_text(encoding="utf-8")
    value = replace_once(
        value,
        "    const/16 v7, 0x5f\n",
        "    const/16 v7, 0x63\n",
        "adaptive audio percentile 95 -> 99",
    )
    path.write_text(value, encoding="utf-8")


def patch_video_queue(root: Path) -> None:
    path = root / VIDEO_PATH
    value = path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"\.method private final _feedToCodec\(\[BJ\)V\n.*?^\.end method",
        re.MULTILINE | re.DOTALL,
    )
    matches = pattern.findall(value)
    if len(matches) != 1:
        raise RuntimeError(f"expected one _feedToCodec method, found {len(matches)}")
    method = matches[0]
    method = replace_once(method, "    const/4 v1, 0x3\n", "    const/4 v1, 0x4\n", "video retries 3 -> 4")
    method = replace_once(method, "    const/16 v1, 0xf\n", "    const/16 v1, 0x14\n", "first video retries 15 -> 20")
    method = replace_once(method, "    const-wide/16 v3, 0x7d0\n", "    const-wide/16 v3, 0xfa0\n", "video wait 2 ms -> 4 ms")
    path.write_text(pattern.sub(method, value, count=1), encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_android_1_8_4_maintenance.py APKTOOL_DIRECTORY")

    root = Path(sys.argv[1]).resolve()
    patch_timeout(root)
    patch_route_rendering(root)
    patch_audio_jitter(root)
    patch_video_queue(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10801", "  versionCode: 10804", "versionCode")
    config = replace_once(config, "  versionName: 1.8.1", "  versionName: 1.8.4", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
