#!/usr/bin/env python3
"""Verify 1.8.16 media-session containment and the frozen 1.8.11 UI/map."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import method, require_equal_tree
from verify_android_1_8_8_stability import all_smali


SERVICE = Path("io/github/jqssun/airplay/service/AirPlayService.smali")
FROZEN_PREFIXES = (
    "io/github/jqssun/airplay/MainActivity",
    "io/github/jqssun/airplay/C3MediaApplication",
    "io/github/jqssun/airplay/BootReceiver",
    "io/github/jqssun/airplay/connectivity/C3Link",
    "io/github/jqssun/airplay/connectivity/C3Map",
    "io/github/jqssun/airplay/service/MapTile",
    "io/github/jqssun/airplay/service/C3LinkNavigation",
)


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: verify_android_1_8_16_media_lock.py REFERENCE_1811 REBUILT_1816"
        )
    reference_root = Path(sys.argv[1]).resolve()
    rebuilt_root = Path(sys.argv[2]).resolve()
    reference = all_smali(reference_root)
    rebuilt = all_smali(rebuilt_root)

    for path in sorted(set(reference) | set(rebuilt)):
        if any(str(path).startswith(prefix) for prefix in FROZEN_PREFIXES):
            if reference.get(path) != rebuilt.get(path):
                raise RuntimeError(f"frozen 1.8.11 startup/map class changed: {path}")

    service = rebuilt[SERVICE]
    idle = method(
        service,
        b"private static final delayedPlaybackIdle$lambda$3(Lio/github/jqssun/airplay/service/AirPlayService;)V",
    )
    teardown = method(
        service,
        b"private static final onAudioTeardown$lambda$19(Lio/github/jqssun/airplay/service/AirPlayService;)V",
    )
    for label, body in (("playback idle", idle), ("audio teardown", teardown)):
        if b"return-void" not in body or b"updateState" in body or b"AudioRenderer;->stop" in body:
            raise RuntimeError(f"{label} can still close the music player")

    audio_only = method(service, b"public onAudioOnly(Z)V")
    false_guard = audio_only.find(b"if-nez p1")
    callback = audio_only.find(b"ExternalSyntheticLambda7")
    if false_guard < 0 or callback < 0 or false_guard > callback:
        raise RuntimeError("transient audioOnly=false can still leave the player")

    if b"CONNECTION_CLEANUP_GRACE_MS:J = 0x15f90L" not in service:
        raise RuntimeError("90-second disconnect grace is missing")
    disconnect = method(
        service,
        b"private static final onConnectionDestroy$lambda$21(Lio/github/jqssun/airplay/service/AirPlayService;)V",
    )
    if b"const-wide/32 v2, 0x15f90" not in disconnect:
        raise RuntimeError("disconnect cleanup is not delayed for 90 seconds")

    cover = method(service, b"private final queueCoverArt([B)V")
    for marker in (b"getQueue", b"BlockingQueue;->clear", b".catchall"):
        if marker not in cover:
            raise RuntimeError(f"latest-only artwork queue protection missing: {marker!r}")
    recycler = method(
        service,
        b"private static final queueCoverArt$lambda$31$lambda$30$lambda$29(Lio/github/jqssun/airplay/service/AirPlayService;Landroid/graphics/Bitmap;)V",
    )
    if b"Bitmap;->recycle()V" not in recycler or b"Bitmap;->isRecycled()Z" not in recycler:
        raise RuntimeError("bounded safe artwork retirement is missing")
    if b"const-wide/32 v0, 0xea60" not in service:
        raise RuntimeError("artwork retirement still races the UI")
    if b"const/high16 v1, 0x100000" not in method(service, b"public onCoverArt([B)V"):
        raise RuntimeError("artwork allocation fuse is missing")

    all_rebuilt = b"\n".join(rebuilt.values())
    for marker in (b"C3MbTilesStore", b"c3-map.mbtiles", b"CREATE TABLE tiles"):
        if marker in all_rebuilt:
            raise RuntimeError(f"MBTiles code entered media recovery APK: {marker!r}")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(reference_root / name, rebuilt_root / name, name)
    config = (rebuilt_root / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10816" not in config or "versionName: 1.8.16" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.16")

    print("verified: locked media session; delayed true disconnect; bounded latest-only artwork; frozen 1.8.11 UI/map")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
