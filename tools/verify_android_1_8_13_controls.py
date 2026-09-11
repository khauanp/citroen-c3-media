#!/usr/bin/env python3
"""Verify 1.8.13 changes only the iPhone-control transport/crash boundary."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import method, require_equal_tree
from verify_android_1_8_8_stability import all_smali


SERVICE = Path("io/github/jqssun/airplay/service/AirPlayService.smali")
DASHBOARD = Path("io/github/jqssun/airplay/ui/DashboardView.smali")
ACTIVITY = Path("io/github/jqssun/airplay/MainActivity.smali")
RADIO_PREFIX = "io/github/jqssun/airplay/audio/RadioMediaSession"
DACP_PREFIX = "io/github/jqssun/airplay/audio/DacpController"
FROZEN_PREFIXES = (
    "io/github/jqssun/airplay/connectivity/C3Link",
    "io/github/jqssun/airplay/connectivity/C3Map",
    "io/github/jqssun/airplay/service/MapTile",
    "io/github/jqssun/airplay/service/C3LinkNavigation",
    "io/github/jqssun/airplay/ui/DayNight",
    "io/github/jqssun/airplay/power/",
)


def joined(files: dict[Path, bytes], prefix: str) -> bytes:
    return b"\n".join(content for path, content in files.items() if str(path).startswith(prefix))


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_13_controls.py REFERENCE_1812 REBUILT_1813")
    reference = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()
    old = all_smali(reference)
    new = all_smali(rebuilt)

    for path in sorted(set(old) | set(new)):
        if any(str(path).startswith(prefix) for prefix in FROZEN_PREFIXES):
            if old.get(path) != new.get(path):
                raise RuntimeError(f"frozen map/theme/thermal implementation changed: {path}")

    if old[DASHBOARD] != new[DASHBOARD]:
        raise RuntimeError("tablet visual/touch layout changed after 1.8.12")
    if old[ACTIVITY] != new[ACTIVITY]:
        raise RuntimeError("Bluetooth HID Activity bridge changed after 1.8.12")

    changed = {path for path in old.keys() & new.keys() if old[path] != new[path]}
    added = set(new) - set(old)
    deleted = set(old) - set(new)
    allowed = {SERVICE} | {
        path for path in set(old) | set(new)
        if str(path).startswith(RADIO_PREFIX) or str(path).startswith(DACP_PREFIX)
    }
    unexpected = (changed | added | deleted) - allowed
    if unexpected:
        raise RuntimeError(f"unexpected class scope after 1.8.12: {sorted(unexpected)}")

    old_toggle = method(old[SERVICE], b"public final togglePlayPause()V")
    new_toggle = method(new[SERVICE], b"public final togglePlayPause()V")
    if old_toggle == new_toggle:
        raise RuntimeError("service toggle method did not change")
    if b"DacpController;->toggle()V" not in new_toggle:
        raise RuntimeError("service does not use state-independent DACP playpause")
    for unsafe in (b"DacpController;->play()V", b"DacpController;->pause()V"):
        if unsafe in new_toggle:
            raise RuntimeError(f"state-dependent transition command remains: {unsafe!r}")

    radio = joined(new, RADIO_PREFIX)
    for marker in (b"registerMediaButtonEventReceiver", b"unregisterMediaButtonEventReceiver", b"latestPlaying"):
        if marker not in radio:
            raise RuntimeError(f"legacy Android-5 media-key bridge missing: {marker!r}")
    for unsafe in (b"Landroid/media/session/MediaSession;", b"Landroid/media/session/PlaybackState;", b"RemoteControlClient"):
        if unsafe in radio:
            raise RuntimeError(f"unstable local media publication remains: {unsafe!r}")

    dacp = joined(new, DACP_PREFIX)
    for marker in (
        b"iTunes_Ctrl_",
        b"_dacp._tcp.",
        b"NsdManager;->resolveService",
        b"NsdManager;->discoverServices",
        b"/ctrl-int/1/playpause",
        b"/ctrl-int/1/nextitem",
        b"/ctrl-int/1/previtem",
        b"Active-Remote",
    ):
        if marker not in dacp:
            raise RuntimeError(f"DACP reliability marker missing: {marker!r}")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(reference / name, rebuilt / name, name)
    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10813" not in config or "versionName: 1.8.13" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.13")
    print("verified: DACP direct+browse control; no local MediaSession; accepted app frozen")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
