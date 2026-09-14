#!/usr/bin/env python3
"""Verify 1.8.12 changes only pause handling and media controls."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import method, require_equal_tree
from verify_android_1_8_8_stability import all_smali


DASHBOARD = Path("io/github/jqssun/airplay/ui/DashboardView.smali")
ACTIVITY = Path("io/github/jqssun/airplay/MainActivity.smali")
SERVICE = Path("io/github/jqssun/airplay/service/AirPlayService.smali")
RADIO_PREFIX = "io/github/jqssun/airplay/audio/RadioMediaSession"
FROZEN_MAP_PREFIXES = (
    "io/github/jqssun/airplay/connectivity/C3Link",
    "io/github/jqssun/airplay/connectivity/C3Map",
    "io/github/jqssun/airplay/service/MapTile",
    "io/github/jqssun/airplay/service/C3LinkNavigation",
)
FROZEN_DASHBOARD_METHODS = (
    b"private final drawNavigation(Landroid/graphics/Canvas;)V",
    b"private final drawNavigationMarker(Landroid/graphics/Canvas;FF)V",
    b"private final applyAutomaticTheme()V",
    b"private final updateMapBearing(Ljava/lang/String;F)F",
)


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_12_controls.py REFERENCE_1811 REBUILT_1812")
    reference = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()
    old = all_smali(reference)
    new = all_smali(rebuilt)

    for path in sorted(set(old) | set(new)):
        if any(str(path).startswith(prefix) for prefix in FROZEN_MAP_PREFIXES):
            if old.get(path) != new.get(path):
                raise RuntimeError(f"map/route/tile implementation changed: {path}")
    for signature in FROZEN_DASHBOARD_METHODS:
        if method(old[DASHBOARD], signature) != method(new[DASHBOARD], signature):
            raise RuntimeError(f"frozen dashboard method changed: {signature!r}")

    changed = {path for path in old.keys() & new.keys() if old[path] != new[path]}
    added = set(new) - set(old)
    deleted = set(old) - set(new)
    allowed = {DASHBOARD, ACTIVITY, SERVICE} | {
        path for path in set(old) | set(new) if str(path).startswith(RADIO_PREFIX)
    }
    unexpected = (changed | added | deleted) - allowed
    if unexpected:
        raise RuntimeError(f"unexpected class scope: {sorted(unexpected)}")

    service = new[SERVICE]
    for marker in (b"AUDIO_PLAYBACK_IDLE_MS:J = 0x2ee0L", b"const-wide/16 v3, 0x2ee0", b"dispatchMediaKey(I)V"):
        if marker not in service:
            raise RuntimeError(f"pause/media-key service marker missing: {marker!r}")

    dashboard = new[DASHBOARD]
    for marker in (b"drawPlayerControls(Landroid/graphics/Canvas;)V", b"Actions;->onPrevious()V", b"Actions;->onPlayPause()V", b"Actions;->onNext()V"):
        if marker not in dashboard:
            raise RuntimeError(f"visible/touch control marker missing: {marker!r}")

    activity = new[ACTIVITY]
    for marker in (b"dispatchKeyEvent(Landroid/view/KeyEvent;)Z", b"AirPlayService;->dispatchMediaKey(I)V"):
        if marker not in activity:
            raise RuntimeError(f"Bluetooth HID key marker missing: {marker!r}")

    radio = b"\n".join(content for path, content in new.items() if str(path).startswith(RADIO_PREFIX))
    for marker in (b"PAUSE_PUBLICATION_GRACE_MS", b"onMediaButtonEvent", b"latestPlaying", b"KeyEvent;->getRepeatCount()I"):
        if marker not in radio:
            raise RuntimeError(f"debounced/raw MediaSession marker missing: {marker!r}")
    for marker in (b"RemoteControlClient", b"MediaMetadata"):
        if marker in radio:
            raise RuntimeError(f"unsafe Android-5 radio integration returned: {marker!r}")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(reference / name, rebuilt / name, name)
    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10812" not in config or "versionName: 1.8.12" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.12")
    print("verified: 12 s transition guard; screen/AVRCP/HID controls; map/network/theme/thermal frozen")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
