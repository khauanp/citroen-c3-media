#!/usr/bin/env python3
"""Apply map continuity, media stability and radio controls to the real 1.8.1 APK."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from apply_android_1_8_7_navigation import main as _unused_main
from apply_android_1_8_6_maintenance import replace_once


ROOT = Path(__file__).resolve().parent
SERVICE = Path("smali/io/github/jqssun/airplay/service/AirPlayService.smali")
TILE_STORE = Path("smali/io/github/jqssun/airplay/connectivity/C3MapTileStore.smali")
DASHBOARD = Path("smali/io/github/jqssun/airplay/ui/DashboardView.smali")
AUDIO = Path("smali/io/github/jqssun/airplay/renderer/AudioRenderer.smali")
VIDEO = Path("smali/io/github/jqssun/airplay/renderer/VideoRenderer.smali")


def patch_map(root: Path) -> None:
    path = root / TILE_STORE
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, 'const-string v1, "c3-map-tiles"', 'const-string v1, "c3-map-tiles-v2"', "fresh map cache")
    value = replace_once(value, 'const-string p2, "c3-map-tile-expiry"', 'const-string p2, "c3-map-tile-expiry-v2"', "fresh expiry cache")
    value = replace_once(value, "    const-wide/16 v4, 0x1f40", "    const-wide/16 v4, 0x7d0", "2 second tile retry")
    value = replace_once(value, "    const-wide/16 v3, 0x2ee0", "    const-wide/16 v3, 0xbb8", "3 second unavailable backoff")

    missing = """    invoke-direct {p0, p1}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->scheduleDiskLoad(Lio/github/jqssun/airplay/service/MapTileKey;)V

    return-object v0
"""
    fallback = """    invoke-direct {p0, p1}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->scheduleDiskLoad(Lio/github/jqssun/airplay/service/MapTileKey;)V

    iget-object v1, p0, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->memory:Lio/github/jqssun/airplay/connectivity/C3MapTileStore$memory$1;

    check-cast v1, Landroid/util/LruCache;

    invoke-static {p0, v1, p1}, Lio/github/jqssun/airplay/connectivity/C3MapTileFallback;->find(Lio/github/jqssun/airplay/connectivity/C3MapTileStore;Landroid/util/LruCache;Lio/github/jqssun/airplay/service/MapTileKey;)Landroid/graphics/Bitmap;

    move-result-object v0

    return-object v0
"""
    value = replace_once(value, missing, fallback, "parent-tile fallback")
    path.write_text(value, encoding="utf-8")

    path = root / DASHBOARD
    value = path.read_text(encoding="utf-8")
    dark = """    const/16 v2, 0x1f

    const/16 v3, 0x23

    const/16 v4, 0x1a
"""
    light = """    const/16 v2, 0xeb

    const/16 v3, 0xe4

    const/16 v4, 0xee
"""
    value = replace_once(value, dark, light, "light map gap color")
    path.write_text(value, encoding="utf-8")

    destination = root / "smali/io/github/jqssun/airplay/connectivity/C3MapTileFallback.smali"
    shutil.copyfile(ROOT / "android_1_8_8/C3MapTileFallback.smali", destination)


def patch_media_buffers(root: Path) -> None:
    path = root / AUDIO
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, "    const/4 v6, 0x0\n\n    const/16 v7, 0x63", "    const/16 v6, 0xdc\n\n    const/16 v7, 0x63", "220 ms audio cushion")
    path.write_text(value, encoding="utf-8")

    path = root / VIDEO
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, "    const/4 v1, 0x4", "    const/4 v1, 0x6", "video input retries")
    value = replace_once(value, "    const/16 v1, 0x14", "    const/16 v1, 0x18", "video startup retries")
    value = replace_once(value, "    const-wide/16 v3, 0xfa0", "    const-wide/16 v3, 0x1770", "video input wait")
    path.write_text(value, encoding="utf-8")

    path = root / SERVICE
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, ".field private static final AUDIO_TEARDOWN_GRACE_MS:J = 0x1f40L", ".field private static final AUDIO_TEARDOWN_GRACE_MS:J = 0x3a98L", "audio grace constant")
    value = replace_once(value, ".field private static final CONNECTION_CLEANUP_GRACE_MS:J = 0x2ee0L", ".field private static final CONNECTION_CLEANUP_GRACE_MS:J = 0x4e20L", "connection grace constant")
    value = replace_once(value, "    const-wide/16 v1, 0x1f40", "    const-wide/16 v1, 0x3a98", "15 second audio teardown grace")
    value = replace_once(value, "    const-wide/16 v2, 0x2ee0", "    const-wide/16 v2, 0x4e20", "20 second connection cleanup grace")
    path.write_text(value, encoding="utf-8")


def copy_radio_helpers(root: Path, helper: Path) -> None:
    target = root / "smali/io/github/jqssun/airplay/audio"
    for old in target.glob("DacpController*.smali"):
        old.unlink()
    copied: list[str] = []
    for smali_root in sorted(helper.glob("smali*")):
        source = smali_root / "io/github/jqssun/airplay/audio"
        if not source.is_dir():
            continue
        for pattern in ("DacpController*.smali", "RadioMediaSession*.smali"):
            for item in source.glob(pattern):
                shutil.copyfile(item, target / item.name)
                copied.append(item.name)
    if not any(name == "DacpController.smali" for name in copied):
        raise RuntimeError("compiled DacpController helper not found")
    if not any(name == "RadioMediaSession.smali" for name in copied):
        raise RuntimeError("compiled RadioMediaSession helper not found")


def patch_radio_service(root: Path) -> None:
    path = root / SERVICE
    value = path.read_text(encoding="utf-8")
    field_anchor = ".field private final delayedStreamCleanup:Ljava/lang/Runnable;\n"
    fields = field_anchor + "\n.field private dacp:Lio/github/jqssun/airplay/audio/DacpController;\n\n.field private radioMediaSession:Lio/github/jqssun/airplay/audio/RadioMediaSession;\n"
    value = replace_once(value, field_anchor, fields, "radio controller fields")

    init_anchor = """    check-cast v1, Landroid/media/AudioManager;

    iput-object v1, v0, Lio/github/jqssun/airplay/service/AirPlayService;->audioManager:Landroid/media/AudioManager;
"""
    init = init_anchor + """
    new-instance v1, Lio/github/jqssun/airplay/audio/DacpController;

    move-object v2, v0

    check-cast v2, Landroid/content/Context;

    invoke-direct {v1, v2}, Lio/github/jqssun/airplay/audio/DacpController;-><init>(Landroid/content/Context;)V

    iput-object v1, v0, Lio/github/jqssun/airplay/service/AirPlayService;->dacp:Lio/github/jqssun/airplay/audio/DacpController;

    new-instance v1, Lio/github/jqssun/airplay/audio/RadioMediaSession;

    invoke-direct {v1, v0}, Lio/github/jqssun/airplay/audio/RadioMediaSession;-><init>(Lio/github/jqssun/airplay/service/AirPlayService;)V

    iput-object v1, v0, Lio/github/jqssun/airplay/service/AirPlayService;->radioMediaSession:Lio/github/jqssun/airplay/audio/RadioMediaSession;
"""
    value = replace_once(value, init_anchor, init, "radio session initialization")

    empty_dacp = """    const-string p1, "activeRemote"

    invoke-static {p2, p1}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    return-void
.end method
"""
    active_dacp = """    const-string v0, "activeRemote"

    invoke-static {p2, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->dacp:Lio/github/jqssun/airplay/audio/DacpController;

    invoke-virtual {v0, p1, p2}, Lio/github/jqssun/airplay/audio/DacpController;->update(Ljava/lang/String;Ljava/lang/String;)V

    return-void
.end method
"""
    value = replace_once(value, empty_dacp, active_dacp, "DACP identity installation")

    value = replace_once(value, ".method public final nextTrack()V\n    .locals 0\n\n    return-void\n.end method", ".method public final nextTrack()V\n    .locals 1\n\n    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->dacp:Lio/github/jqssun/airplay/audio/DacpController;\n\n    invoke-virtual {v0}, Lio/github/jqssun/airplay/audio/DacpController;->next()V\n\n    return-void\n.end method", "next track command")
    value = replace_once(value, ".method public final previousTrack()V\n    .locals 0\n\n    return-void\n.end method", ".method public final previousTrack()V\n    .locals 1\n\n    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->dacp:Lio/github/jqssun/airplay/audio/DacpController;\n\n    invoke-virtual {v0}, Lio/github/jqssun/airplay/audio/DacpController;->previous()V\n\n    return-void\n.end method", "previous track command")
    value = replace_once(value, ".method public final togglePlayPause()V\n    .locals 0\n\n    return-void\n.end method", """.method public final togglePlayPause()V
    .locals 2

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->state:Lio/github/jqssun/airplay/service/MediaState;

    invoke-virtual {v0}, Lio/github/jqssun/airplay/service/MediaState;->getPlaying()Z

    move-result v0

    iget-object v1, p0, Lio/github/jqssun/airplay/service/AirPlayService;->dacp:Lio/github/jqssun/airplay/audio/DacpController;

    if-eqz v0, :c3_radio_play

    invoke-virtual {v1}, Lio/github/jqssun/airplay/audio/DacpController;->pause()V

    return-void

    :c3_radio_play
    invoke-virtual {v1}, Lio/github/jqssun/airplay/audio/DacpController;->play()V

    return-void
.end method""", "play pause command")

    update_anchor = """    monitor-exit v0

    .line 956
    iget-object p1, p0, Lio/github/jqssun/airplay/service/AirPlayService;->mainHandler:Landroid/os/Handler;
"""
    update = """    monitor-exit v0

    iget-object p1, p0, Lio/github/jqssun/airplay/service/AirPlayService;->radioMediaSession:Lio/github/jqssun/airplay/audio/RadioMediaSession;

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->state:Lio/github/jqssun/airplay/service/MediaState;

    invoke-virtual {p1, v0}, Lio/github/jqssun/airplay/audio/RadioMediaSession;->update(Lio/github/jqssun/airplay/service/MediaState;)V

    .line 956
    iget-object p1, p0, Lio/github/jqssun/airplay/service/AirPlayService;->mainHandler:Landroid/os/Handler;
"""
    value = replace_once(value, update_anchor, update, "radio state publication")

    destroy_anchor = """    :cond_4
    iput-object v1, p0, Lio/github/jqssun/airplay/service/AirPlayService;->wifiLock:Landroid/net/wifi/WifiManager$WifiLock;

    .line 1081
    invoke-super {p0}, Landroid/app/Service;->onDestroy()V
"""
    destroy = """    :cond_4
    iput-object v1, p0, Lio/github/jqssun/airplay/service/AirPlayService;->wifiLock:Landroid/net/wifi/WifiManager$WifiLock;

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->radioMediaSession:Lio/github/jqssun/airplay/audio/RadioMediaSession;

    invoke-virtual {v0}, Lio/github/jqssun/airplay/audio/RadioMediaSession;->release()V

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->dacp:Lio/github/jqssun/airplay/audio/DacpController;

    invoke-virtual {v0}, Lio/github/jqssun/airplay/audio/DacpController;->release()V

    .line 1081
    invoke-super {p0}, Landroid/app/Service;->onDestroy()V
"""
    value = replace_once(value, destroy_anchor, destroy, "radio controller release")
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_android_1_8_8_stability.py APKTOOL_DIRECTORY HELPER_DECODE")
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()

    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_7_navigation.py", str(root)]
        _unused_main()
    finally:
        sys.argv = old_argv

    copy_radio_helpers(root, helper)
    patch_map(root)
    patch_media_buffers(root)
    patch_radio_service(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10807", "  versionCode: 10808", "versionCode")
    config = replace_once(config, "  versionName: 1.8.7", "  versionName: 1.8.8", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
