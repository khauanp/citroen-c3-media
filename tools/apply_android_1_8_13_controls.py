#!/usr/bin/env python3
"""Apply reliable iPhone controls and Android-5 crash isolation over 1.8.12."""

from __future__ import annotations

import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_12_controls import main as apply_1_8_12


SERVICE = Path("smali/io/github/jqssun/airplay/service/AirPlayService.smali")


def patch_service(root: Path) -> None:
    path = root / SERVICE
    value = path.read_text(encoding="utf-8")
    old = r'''.method public final togglePlayPause()V
    .locals 2

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->state:Lio/github/jqssun/airplay/service/MediaState;

    invoke-virtual {v0}, Lio/github/jqssun/airplay/service/MediaState;->getPlaying()Z

    move-result v0

    iget-object v1, p0, Lio/github/jqssun/airplay/service/AirPlayService;->dacp:Lio/github/jqssun/airplay/audio/DacpController;

    if-eqz v0, :cond_0

    invoke-virtual {v1}, Lio/github/jqssun/airplay/audio/DacpController;->pause()V

    return-void

    :cond_0
    invoke-virtual {v1}, Lio/github/jqssun/airplay/audio/DacpController;->play()V

    return-void
.end method'''
    new = r'''.method public final togglePlayPause()V
    .locals 1

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->dacp:Lio/github/jqssun/airplay/audio/DacpController;

    invoke-virtual {v0}, Lio/github/jqssun/airplay/audio/DacpController;->toggle()V

    return-void
.end method'''
    value = replace_once(value, old, new, "state-independent DACP playpause")
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_android_1_8_13_controls.py APKTOOL_DIRECTORY HELPER_DECODE")
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()
    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_12_controls.py", str(root), str(helper)]
        apply_1_8_12()
    finally:
        sys.argv = old_argv
    patch_service(root)
    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10812", "  versionCode: 10813", "versionCode")
    config = replace_once(config, "  versionName: 1.8.12", "  versionName: 1.8.13", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
