#!/usr/bin/env python3
"""Build the 1.8.15 startup recovery from the last known-good 1.8.11 map."""

from __future__ import annotations

import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_11_daylight import main as apply_1_8_11


SERVICE = Path("smali/io/github/jqssun/airplay/service/AirPlayService.smali")


def patch_pause_guard(root: Path) -> None:
    """Keep only the accepted 1.8.12 track-transition tolerance."""
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


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: apply_android_1_8_15_recovery.py APKTOOL_DIRECTORY HELPER_DECODE"
        )
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()

    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_11_daylight.py", str(root), str(helper)]
        apply_1_8_11()
    finally:
        sys.argv = old_argv

    # Do not copy or initialize C3MbTilesStore. The K00E must reach the same
    # opening and map renderer that physically booted in 1.8.11.
    patch_pause_guard(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10811", "  versionCode: 10815", "versionCode")
    config = replace_once(config, "  versionName: 1.8.11", "  versionName: 1.8.15", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
