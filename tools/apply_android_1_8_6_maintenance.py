#!/usr/bin/env python3
"""Apply the final 1.8.6 route/map maintenance to the real 1.8.1 APK."""

from __future__ import annotations

import sys
from pathlib import Path

from apply_android_1_8_5_maintenance import (
    AUDIO_PATH,
    DASHBOARD_PATH,
    SERVER_PATH,
    VIDEO_PATH,
    patch_audio_jitter,
    patch_timeout,
    patch_video_queue,
    replace_once,
)


def patch_route_rendering(root: Path) -> None:
    path = root / DASHBOARD_PATH
    value = path.read_text(encoding="utf-8")
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

    # The Canvas is clipped before rotating around (640, 620). Every point
    # that can become visible at any bearing fits safely inside -512..1792 on
    # both axes. Reject only points beyond that rotation-complete envelope so
    # the route reaches every edge without exposing Android 5's GPU to huge
    # off-screen coordinates.
    const/high16 v10, -0x3c000000    # -512.0f

    cmpg-float v13, v32, v10

    if-ltz v13, :c3_route_segment_done

    cmpg-float v13, v6, v10

    if-ltz v13, :c3_route_segment_done

    const/high16 v10, 0x44e00000    # 1792.0f

    cmpl-float v13, v32, v10

    if-gtz v13, :c3_route_segment_done

    cmpl-float v13, v6, v10

    if-gtz v13, :c3_route_segment_done

    const/high16 v10, -0x3c000000    # -512.0f

    cmpg-float v13, v33, v10

    if-ltz v13, :c3_route_segment_done

    cmpg-float v13, v4, v10

    if-ltz v13, :c3_route_segment_done

    const/high16 v10, 0x44e00000    # 1792.0f

    cmpl-float v13, v33, v10

    if-gtz v13, :c3_route_segment_done

    cmpl-float v13, v4, v10

    if-gtz v13, :c3_route_segment_done

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
    value = replace_once(value, old_branch, new_branch, "rotation-complete route segment renderer")
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_android_1_8_6_maintenance.py APKTOOL_DIRECTORY")

    root = Path(sys.argv[1]).resolve()
    patch_timeout(root)
    patch_route_rendering(root)
    patch_audio_jitter(root)
    patch_video_queue(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10801", "  versionCode: 10806", "versionCode")
    config = replace_once(config, "  versionName: 1.8.1", "  versionName: 1.8.6", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
