#!/usr/bin/env python3
"""Apply the 1.8.10 K00E media isolation over the exact 1.8.9 maintenance."""

from __future__ import annotations

import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_9_media import main as apply_1_8_9


SERVICE = Path("smali/io/github/jqssun/airplay/service/AirPlayService.smali")
AUDIO = Path("smali/io/github/jqssun/airplay/renderer/AudioRenderer.smali")


def replace_method(value: str, signature: str, body: str) -> str:
    start = value.find(signature)
    if start < 0:
        raise RuntimeError(f"method not found: {signature}")
    end = value.find("\n.end method", start)
    if end < 0:
        raise RuntimeError(f"method end not found: {signature}")
    end += len("\n.end method")
    return value[:start] + body + value[end:]


def patch_audio_resilience(root: Path) -> None:
    path = root / AUDIO
    value = path.read_text(encoding="utf-8")
    value = replace_once(
        value,
        "    const/16 v6, 0x258",
        "    const/16 v6, 0x3e8",
        "1000 ms AirPlay RF-loss cushion",
    )
    path.write_text(value, encoding="utf-8")


def patch_navigation_artwork_fuse(root: Path) -> None:
    path = root / SERVICE
    value = path.read_text(encoding="utf-8")
    signature = ".method public onCoverArt([B)V"
    body = signature + """
    .locals 2

    const-string v0, "data"

    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    array-length v0, p1

    if-nez v0, :c3_cover_size

    return-void

    :c3_cover_size
    array-length v0, p1

    const/high16 v1, 0x300000

    if-gt v0, v1, :c3_cover_return

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->state:Lio/github/jqssun/airplay/service/MediaState;

    invoke-virtual {v0}, Lio/github/jqssun/airplay/service/MediaState;->getEnergy()Lio/github/jqssun/airplay/power/EnergySnapshot;

    move-result-object v0

    invoke-virtual {v0}, Lio/github/jqssun/airplay/power/EnergySnapshot;->getThermalLimited()Z

    move-result v0

    if-eqz v0, :c3_cover_video

    return-void

    :c3_cover_video
    invoke-direct {p0}, Lio/github/jqssun/airplay/service/AirPlayService;->getVideoActive()Z

    move-result v0

    if-eqz v0, :c3_cover_navigation

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->deferredCoverArt:Ljava/util/concurrent/atomic/AtomicReference;

    invoke-virtual {v0, p1}, Ljava/util/concurrent/atomic/AtomicReference;->set(Ljava/lang/Object;)V

    return-void

    :c3_cover_navigation
    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->state:Lio/github/jqssun/airplay/service/MediaState;

    invoke-virtual {v0}, Lio/github/jqssun/airplay/service/MediaState;->getNavigation()Lio/github/jqssun/airplay/service/C3LinkNavigation;

    move-result-object v0

    invoke-direct {p0, v0}, Lio/github/jqssun/airplay/service/AirPlayService;->hasActiveNavigation(Lio/github/jqssun/airplay/service/C3LinkNavigation;)Z

    move-result v0

    if-eqz v0, :c3_cover_queue

    return-void

    :c3_cover_queue
    invoke-direct {p0, p1}, Lio/github/jqssun/airplay/service/AirPlayService;->queueCoverArt([B)V

    :c3_cover_return
    return-void
.end method"""
    value = replace_method(value, signature, body)
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_android_1_8_10_media.py APKTOOL_DIRECTORY HELPER_DECODE")
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()

    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_9_media.py", str(root), str(helper)]
        apply_1_8_9()
    finally:
        sys.argv = old_argv

    patch_audio_resilience(root)
    patch_navigation_artwork_fuse(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10809", "  versionCode: 10810", "versionCode")
    config = replace_once(config, "  versionName: 1.8.9", "  versionName: 1.8.10", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
