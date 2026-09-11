#!/usr/bin/env python3
"""Build 1.8.14 from 1.8.12 media behavior, without tablet controls, plus MBTiles."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_11_daylight import main as apply_1_8_11


SERVICE = Path("smali/io/github/jqssun/airplay/service/AirPlayService.smali")
TILE_STORE = Path("smali/io/github/jqssun/airplay/connectivity/C3MapTileStore.smali")
DASHBOARD = Path("smali/io/github/jqssun/airplay/ui/DashboardView.smali")


def copy_mbtiles_helper(root: Path, helper: Path) -> None:
    target = root / "smali/io/github/jqssun/airplay/connectivity"
    copied: set[str] = set()
    for smali_root in sorted(helper.glob("smali*")):
        source = smali_root / "io/github/jqssun/airplay/connectivity"
        if not source.is_dir():
            continue
        for item in source.glob("C3MbTilesStore*.smali"):
            shutil.copyfile(item, target / item.name)
            copied.add(item.name)
    if "C3MbTilesStore.smali" not in copied:
        raise RuntimeError("compiled C3MbTilesStore helper not found")


def patch_pause_guard(root: Path) -> None:
    path = root / SERVICE
    value = path.read_text(encoding="utf-8")
    value = replace_once(
        value,
        ".field private static final AUDIO_PLAYBACK_IDLE_MS:J = 0xdacL",
        ".field private static final AUDIO_PLAYBACK_IDLE_MS:J = 0x2ee0L",
        "1.8.12 12-second playback transition guard",
    )
    value = replace_once(
        value,
        "    const-wide/16 v3, 0xdac",
        "    const-wide/16 v3, 0x2ee0",
        "1.8.12 12-second silence watchdog",
    )
    path.write_text(value, encoding="utf-8")


def patch_mbtiles(root: Path) -> None:
    path = root / TILE_STORE
    value = path.read_text(encoding="utf-8")

    constructor_anchor = """    .line 26
    iput-object p2, p0, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->requester:Lkotlin/jvm/functions/Function1;

    .line 28
"""
    constructor_new = """    .line 26
    iput-object p2, p0, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->requester:Lkotlin/jvm/functions/Function1;

    invoke-static {p1}, Lio/github/jqssun/airplay/connectivity/C3MbTilesStore;->initialize(Landroid/content/Context;)V

    .line 28
"""
    value = replace_once(value, constructor_anchor, constructor_new, "MBTiles initialization")
    value = replace_once(value, 'const-string v1, "c3-map-tiles-v2"', 'const-string v1, "c3-map-tiles-v3"', "clean legacy tile generation")
    value = replace_once(value, 'const-string p2, "c3-map-tile-expiry-v2"', 'const-string p2, "c3-map-tile-expiry-v3"', "clean legacy expiry generation")

    disk_anchor = """    .line 111
    :try_start_0
    invoke-direct {p0, p1}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->tileFile(Lio/github/jqssun/airplay/service/MapTileKey;)Ljava/io/File;
"""
    disk_new = """    .line 111
    :try_start_0
    invoke-virtual {p1}, Lio/github/jqssun/airplay/service/MapTileKey;->getId()Ljava/lang/String;
    move-result-object v1
    invoke-static {v1}, Lio/github/jqssun/airplay/connectivity/C3MbTilesStore;->read(Ljava/lang/String;)Landroid/graphics/Bitmap;
    move-result-object v1
    if-eqz v1, :c3_mbtiles_miss
    iget-object v2, p0, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->memory:Lio/github/jqssun/airplay/connectivity/C3MapTileStore$memory$1;
    invoke-virtual {p1}, Lio/github/jqssun/airplay/service/MapTileKey;->getId()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {v2, v3, v1}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore$memory$1;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
    iget-object v1, p0, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->onTileAvailable:Lkotlin/jvm/functions/Function0;
    if-eqz v1, :c3_mbtiles_loaded
    invoke-interface {v1}, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
    :c3_mbtiles_loaded
    iget-object v1, p0, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->diskLoads:Ljava/util/concurrent/ConcurrentHashMap$KeySetView;
    invoke-virtual {p1}, Lio/github/jqssun/airplay/service/MapTileKey;->getId()Ljava/lang/String;
    move-result-object v2
    invoke-static {v1, v2}, Lio/github/jqssun/airplay/BootReceiver$$ExternalSyntheticApiModelOutline0;->m$1(Ljava/util/concurrent/ConcurrentHashMap$KeySetView;Ljava/lang/Object;)Z
    return-void

    :c3_mbtiles_miss
    invoke-direct {p0, p1}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->tileFile(Lio/github/jqssun/airplay/service/MapTileKey;)Ljava/io/File;
"""
    value = replace_once(value, disk_anchor, disk_new, "MBTiles-first asynchronous tile load")

    store_anchor = """    .line 161
    invoke-virtual {p2}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore$TileAssembly;->getKey()Lio/github/jqssun/airplay/service/MapTileKey;
"""
    store_new = """    invoke-virtual {p2}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore$TileAssembly;->getKey()Lio/github/jqssun/airplay/service/MapTileKey;
    move-result-object v3
    invoke-virtual {v3}, Lio/github/jqssun/airplay/service/MapTileKey;->getId()Ljava/lang/String;
    move-result-object v3
    invoke-virtual {p2}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore$TileAssembly;->getExpiresAtEpochSeconds()J
    move-result-wide v4
    invoke-static {v3, p1, v4, v5}, Lio/github/jqssun/airplay/connectivity/C3MbTilesStore;->write(Ljava/lang/String;[BJ)V

    .line 161
    invoke-virtual {p2}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore$TileAssembly;->getKey()Lio/github/jqssun/airplay/service/MapTileKey;
"""
    value = replace_once(value, store_anchor, store_new, "validated tile persistence in MBTiles")
    path.write_text(value, encoding="utf-8")


def patch_navigation_style(root: Path) -> None:
    path = root / DASHBOARD
    value = path.read_text(encoding="utf-8")

    tint_anchor = """    iget-object v2, v0, Lio/github/jqssun/airplay/ui/DashboardView;->paint:Landroid/graphics/Paint;

    const/4 v3, 0x3

    const/16 v4, 0x12

    const/16 v5, 0x22

    const/16 v14, 0xa

    invoke-static {v5, v3, v14, v4}, Landroid/graphics/Color;->argb(IIII)I
"""
    tint_new = """    iget-object v2, v0, Lio/github/jqssun/airplay/ui/DashboardView;->paint:Landroid/graphics/Paint;

    const/4 v3, 0x3

    const/16 v4, 0x12

    invoke-static {}, Lio/github/jqssun/airplay/ui/DayNightPolicy;->isDaytimeNow()Z
    move-result v5
    if-eqz v5, :c3_map_night_tint
    const/4 v5, 0x0
    goto :c3_map_tint_ready
    :c3_map_night_tint
    const/16 v5, 0x62
    :c3_map_tint_ready

    const/16 v14, 0xa

    invoke-static {v5, v3, v14, v4}, Landroid/graphics/Color;->argb(IIII)I
"""
    value = replace_once(value, tint_anchor, tint_new, "automatic day/night basemap tint")

    progress_anchor = """    invoke-virtual/range {v19 .. v24}, Landroid/graphics/Canvas;->drawLine(FFFFLandroid/graphics/Paint;)V

    sget v13, Lio/github/jqssun/airplay/ui/DashboardView;->LINK_BLUE:I

    invoke-virtual {v10, v13}, Landroid/graphics/Paint;->setColor(I)V
"""
    progress_new = """    invoke-virtual/range {v19 .. v24}, Landroid/graphics/Canvas;->drawLine(FFFFLandroid/graphics/Paint;)V

    invoke-static {}, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->getRouteProgressIndex()I
    move-result v13
    if-gt v3, v13, :c3_route_remaining_color
    const/16 v13, 0x26
    const/16 v14, 0x66
    const/16 v15, 0x72
    invoke-static {v13, v14, v15}, Landroid/graphics/Color;->rgb(III)I
    move-result v13
    const/4 v15, 0x7
    goto :c3_route_color_ready
    :c3_route_remaining_color
    sget v13, Lio/github/jqssun/airplay/ui/DashboardView;->LINK_BLUE:I
    :c3_route_color_ready

    invoke-virtual {v10, v13}, Landroid/graphics/Paint;->setColor(I)V
"""
    value = replace_once(value, progress_anchor, progress_new, "darker already-travelled route segments")
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_android_1_8_14_mbtiles.py APKTOOL_DIRECTORY HELPER_DECODE")
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()
    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_11_daylight.py", str(root), str(helper)]
        apply_1_8_11()
    finally:
        sys.argv = old_argv

    # Deliberately do not call the 1.8.12/1.8.13 control patchers. The exact
    # 1.8.12 receiver classes still come from helper-decoded via the older chain.
    patch_pause_guard(root)
    copy_mbtiles_helper(root, helper)
    patch_mbtiles(root)
    patch_navigation_style(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10811", "  versionCode: 10814", "versionCode")
    config = replace_once(config, "  versionName: 1.8.11", "  versionName: 1.8.14", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
