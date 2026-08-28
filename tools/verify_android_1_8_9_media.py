#!/usr/bin/env python3
"""Verify 1.8.9 changes media only and preserves the complete 1.8.8 map."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import files, method, require_equal_tree
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


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_9_media.py REFERENCE_188 REBUILT_189")
    reference = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()
    old = all_smali(reference)
    new = all_smali(rebuilt)

    # Absolute freeze: route, tile transport/cache and every DashboardView class
    # must be byte-for-byte identical to the accepted 1.8.8 map.
    map_paths = {
        path for path in set(old) | set(new)
        if any(str(path).startswith(prefix) for prefix in MAP_PREFIXES)
    }
    for path in sorted(map_paths):
        if old.get(path) != new.get(path):
            raise RuntimeError(f"1.8.8 map changed in media-only build: {path}")

    changed = {path for path in old.keys() & new.keys() if old[path] != new[path]}
    added = set(new) - set(old)
    deleted = set(old) - set(new)
    if changed - {SERVICE, AUDIO, HOTSPOT} - {path for path in changed if is_radio(path)}:
        raise RuntimeError(f"unexpected changed classes: {sorted(changed)}")
    if added - {path for path in added if is_radio(path)} or deleted:
        raise RuntimeError(f"unexpected class scope: {sorted(added)=} {sorted(deleted)=}")

    audio = method(new[AUDIO], b"public final declared-synchronized attachEngine(J)V")
    for marker in (b"const/16 v6, 0x258", b"const/16 v7, 0x63", b"const/16 v8, 0x2000"):
        if marker not in audio:
            raise RuntimeError(f"A2DP/AirPlay buffer marker missing: {marker!r}")

    service = new[SERVICE]
    no_recycle = method(
        service,
        b"private static final queueCoverArt$lambda$31$lambda$30$lambda$29(Lio/github/jqssun/airplay/service/AirPlayService;Landroid/graphics/Bitmap;)V",
    )
    if b"Bitmap;->recycle()V" in no_recycle or b"return-void" not in no_recycle:
        raise RuntimeError("old cover-art bitmap can still be recycled while Canvas uses it")

    hotspot = new[HOTSPOT]
    if b'.field public static final SSID:Ljava/lang/String; = "Citroen C3"' not in hotspot:
        raise RuntimeError("hotspot constant is not Citroen C3")
    hotspot_start = method(hotspot, b"public final declared-synchronized ensureStarted()Lio/github/jqssun/airplay/connectivity/HotspotController$Result;")
    expected_ssid_assignment = (
        b'const-string v4, "Citroen C3"\n\n'
        b'    iput-object v4, v2, Landroid/net/wifi/WifiConfiguration;->SSID:Ljava/lang/String;'
    )
    if expected_ssid_assignment not in hotspot_start:
        raise RuntimeError("WifiConfiguration.SSID is not assigned the non-null Citroen C3 value")
    if b'iget-object v4, v2, Landroid/net/wifi/WifiConfiguration;->SSID:Ljava/lang/String;' in hotspot_start:
        raise RuntimeError("null-preserving hotspot SSID self-assignment remains")

    radio = b"\n".join(data for path, data in new.items() if is_radio(path))
    for marker in (
        b"registerMediaButtonEventReceiver",
        b"registerRemoteControlClient",
        b"setMediaButtonReceiver",
        b"android.intent.action.MEDIA_BUTTON",
        b"KEYCODE_MEDIA_NEXT",
        b"KEYCODE_MEDIA_PREVIOUS",
        b"_dacp._tcp.",
        b"Active-Remote",
        b"nextitem",
        b"previtem",
        b"MAX_PENDING_COMMANDS",
    ):
        if marker not in radio:
            raise RuntimeError(f"legacy radio/DACP marker missing: {marker!r}")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(reference / name, rebuilt / name, name)

    manifest = (rebuilt / "AndroidManifest.xml").read_text(encoding="utf-8")
    if "io.github.jqssun.airplay.audio.RadioButtonReceiver" not in manifest:
        raise RuntimeError("legacy media-button receiver missing from manifest")
    if "android.intent.action.MEDIA_BUTTON" not in manifest:
        raise RuntimeError("MEDIA_BUTTON filter missing from manifest")

    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10809" not in config or "versionName: 1.8.9" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.9")

    print("verified: 1.8.8 map frozen; media stable; Android-5 radio active; hotspot SSID is Citroen C3")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
