#!/usr/bin/env python3
"""Verify 1.8.11 radio coexistence while freezing the accepted 1.8.8 map."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import method, require_equal_tree
from verify_android_1_8_8_stability import all_smali


SERVICE = Path("io/github/jqssun/airplay/service/AirPlayService.smali")
AUDIO = Path("io/github/jqssun/airplay/renderer/AudioRenderer.smali")
HOTSPOT = Path("io/github/jqssun/airplay/connectivity/HotspotController.smali")
MAP_PREFIXES = (
    "io/github/jqssun/airplay/connectivity/C3Link",
    "io/github/jqssun/airplay/connectivity/C3Map",
    "io/github/jqssun/airplay/service/MapTile",
    "io/github/jqssun/airplay/service/C3LinkNavigation",
    "io/github/jqssun/airplay/ui/DashboardView",
)


def is_radio(path: Path) -> bool:
    return path.name.startswith(("DacpController", "RadioMediaSession", "RadioButtonReceiver"))


def is_hotspot(path: Path) -> bool:
    return path.name.startswith("HotspotController")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_11_coexistence.py REFERENCE_188 REBUILT_1811")
    reference = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()
    old = all_smali(reference)
    new = all_smali(rebuilt)

    map_paths = {
        path for path in set(old) | set(new)
        if any(str(path).startswith(prefix) for prefix in MAP_PREFIXES)
    }
    for path in sorted(map_paths):
        if old.get(path) != new.get(path):
            raise RuntimeError(f"accepted 1.8.8 map changed in 1.8.11: {path}")

    changed = {path for path in old.keys() & new.keys() if old[path] != new[path]}
    added = set(new) - set(old)
    deleted = set(old) - set(new)
    allowed = {SERVICE, AUDIO, HOTSPOT}
    allowed_dynamic = {
        path for path in changed | added
        if is_radio(path) or is_hotspot(path)
    }
    if changed - allowed - allowed_dynamic:
        raise RuntimeError(f"unexpected changed classes: {sorted(changed)}")
    if added - allowed_dynamic or deleted:
        raise RuntimeError(f"unexpected class scope: {sorted(added)=} {sorted(deleted)=}")

    audio = method(new[AUDIO], b"public final declared-synchronized attachEngine(J)V")
    for marker in (b"const/16 v6, 0x5dc", b"const/16 v7, 0x63", b"const/16 v8, 0x2000"):
        if marker not in audio:
            raise RuntimeError(f"coexistence audio buffer marker missing: {marker!r}")

    hotspot = b"\n".join(data for path, data in new.items() if is_hotspot(path))
    for marker in (
        b'"Citroen C3"',
        b'"apBand"',
        b'"apChannel"',
        b"chooseLeastCongestedChannel",
        b"frequencyTo2GhzChannel",
        b"getScanResults",
        b"channel scores",
    ):
        if marker not in hotspot:
            raise RuntimeError(f"K00E channel policy marker missing: {marker!r}")
    profile = method(
        new[HOTSPOT],
        b"private final applyK00eRadioProfile(Landroid/net/wifi/WifiConfiguration;I)V",
    )
    if b'"apChannel"' not in profile:
        raise RuntimeError("selected channel is not applied to WifiConfiguration")

    cover = method(new[SERVICE], b"public onCoverArt([B)V")
    for marker in (b"hasActiveNavigation", b"getNavigation", b"queueCoverArt", b"deferredCoverArt"):
        if marker not in cover:
            raise RuntimeError(f"navigation artwork fuse missing: {marker!r}")

    radio = b"\n".join(data for path, data in new.items() if is_radio(path))
    for forbidden in (b"RemoteControlClient", b"MediaMetadata"):
        if forbidden in radio:
            raise RuntimeError(f"duplicate Bluetooth metadata path remains: {forbidden!r}")
    for marker in (
        b"registerMediaButtonEventReceiver",
        b"setMediaButtonReceiver",
        b"android.intent.action.MEDIA_BUTTON",
        b"AirPlayService;->nextTrack()V",
        b"AirPlayService;->previousTrack()V",
        b"_dacp._tcp.",
        b"Active-Remote",
    ):
        if marker not in radio:
            raise RuntimeError(f"safe radio/DACP marker missing: {marker!r}")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(reference / name, rebuilt / name, name)

    manifest = (rebuilt / "AndroidManifest.xml").read_text(encoding="utf-8")
    if "io.github.jqssun.airplay.audio.RadioButtonReceiver" not in manifest:
        raise RuntimeError("legacy media-button receiver missing")

    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10811" not in config or "versionName: 1.8.11" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.11")

    print("verified: map frozen; adaptive 2.4 GHz channel; 1.5 s audio cushion; isolated radio path")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
