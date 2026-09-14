#!/usr/bin/env python3
"""Verify map continuity, stable media and radio controls on the preserved 1.8.1 APK."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import files, method, outside_method, require_equal_tree
from verify_android_1_8_7_navigation import verify_long_route_transport_model


SERVICE = Path("io/github/jqssun/airplay/service/AirPlayService.smali")
SERVER = Path("io/github/jqssun/airplay/connectivity/C3LinkServer.smali")
ASSEMBLER = Path("io/github/jqssun/airplay/connectivity/C3LinkRouteAssembler.smali")
POLYLINE = Path("io/github/jqssun/airplay/connectivity/C3LinkPolyline.smali")
STORE = Path("io/github/jqssun/airplay/connectivity/C3MapTileStore.smali")
DASHBOARD = Path("io/github/jqssun/airplay/ui/DashboardView.smali")
AUDIO = Path("io/github/jqssun/airplay/renderer/AudioRenderer.smali")
VIDEO = Path("io/github/jqssun/airplay/renderer/VideoRenderer.smali")
INTEGRITY = Path("io/github/jqssun/airplay/connectivity/C3LinkRouteIntegrity.smali")
SAFETY = Path("io/github/jqssun/airplay/connectivity/C3LinkRoadSafety.smali")
FALLBACK = Path("io/github/jqssun/airplay/connectivity/C3MapTileFallback.smali")


def all_smali(root: Path) -> dict[Path, bytes]:
    result: dict[Path, bytes] = {}
    for directory in sorted(root.glob("smali*")):
        for path, data in files(directory, ".smali").items():
            if path in result:
                raise RuntimeError(f"duplicate smali class: {path}")
            result[path] = data
    return result


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_8_stability.py ORIGINAL_DECODE REBUILT_DECODE")
    original = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()
    old = all_smali(original)
    new = all_smali(rebuilt)

    allowed_changed = {SERVICE, SERVER, ASSEMBLER, POLYLINE, STORE, DASHBOARD, AUDIO, VIDEO}
    old_dacp = {path for path in old if path.name.startswith("DacpController")}
    new_dacp = {path for path in new if path.name.startswith("DacpController")}
    allowed_added = {INTEGRITY, SAFETY, FALLBACK}
    allowed_added |= {path for path in new if path.name.startswith("RadioMediaSession")}
    changed = {path for path in old.keys() & new.keys() if old[path] != new[path]}
    added = set(new) - set(old)
    deleted = set(old) - set(new)
    if changed - (old_dacp | new_dacp) != allowed_changed or added - new_dacp != allowed_added or deleted - old_dacp:
        raise RuntimeError(f"unexpected Android scope: {sorted(changed)=} {sorted(added)=} {sorted(deleted)=}")

    nav = method(new[DASHBOARD], b"private final drawNavigation(Landroid/graphics/Canvas;)V")
    if outside_method(old[DASHBOARD], b"private final drawNavigation(Landroid/graphics/Canvas;)V") != outside_method(new[DASHBOARD], b"private final drawNavigation(Landroid/graphics/Canvas;)V"):
        raise RuntimeError("tablet UI changed outside the map canvas")
    for marker in (
        b"const/16 v2, 0xeb",
        b"const/16 v3, 0xe4",
        b"const/16 v4, 0xee",
        b"C3LinkRoadSafety;->drawRadar",
        b"C3LinkRoadSafety;->drawSpeedLimit",
    ):
        if marker not in nav:
            raise RuntimeError(f"map/navigation marker missing: {marker!r}")

    store = new[STORE]
    for marker in (
        b"c3-map-tiles-v2",
        b"c3-map-tile-expiry-v2",
        b"const-wide/16 v4, 0x7d0",
        b"const-wide/16 v3, 0xbb8",
        b"C3MapTileFallback;->find",
    ):
        if marker not in store:
            raise RuntimeError(f"map continuity marker missing: {marker!r}")
    fallback = new[FALLBACK]
    for marker in (b"fallback_", b"getZoom()I", b"shr-int/lit8", b"Bitmap;->createBitmap", b"Landroid/util/LruCache;->put"):
        if marker not in fallback:
            raise RuntimeError(f"parent tile fallback incomplete: {marker!r}")

    audio = method(new[AUDIO], b"public final declared-synchronized attachEngine(J)V")
    if b"const/16 v6, 0xdc" not in audio or b"const/16 v7, 0x63" not in audio:
        raise RuntimeError("audio jitter buffer is not configured for the K00E")
    if outside_method(old[AUDIO], b"public final declared-synchronized attachEngine(J)V") != outside_method(new[AUDIO], b"public final declared-synchronized attachEngine(J)V"):
        raise RuntimeError("audio renderer changed outside its buffer configuration")
    video = method(new[VIDEO], b"private final _feedToCodec([BJ)V")
    for marker in (b"const/4 v1, 0x6", b"const/16 v1, 0x18", b"const-wide/16 v3, 0x1770"):
        if marker not in video:
            raise RuntimeError(f"video queue marker missing: {marker!r}")
    if outside_method(old[VIDEO], b"private final _feedToCodec([BJ)V") != outside_method(new[VIDEO], b"private final _feedToCodec([BJ)V"):
        raise RuntimeError("video renderer changed outside its decoder queue")

    service = new[SERVICE]
    for marker in (
        b"AUDIO_TEARDOWN_GRACE_MS:J = 0x3a98L",
        b"CONNECTION_CLEANUP_GRACE_MS:J = 0x4e20L",
        b"DacpController;->update",
        b"DacpController;->next()V",
        b"DacpController;->previous()V",
        b"DacpController;->play()V",
        b"DacpController;->pause()V",
        b"RadioMediaSession;->update",
        b"RadioMediaSession;->release",
    ):
        if marker not in service:
            raise RuntimeError(f"radio/media lifecycle marker missing: {marker!r}")
    dacp = b"\n".join(data for path, data in new.items() if path.name.startswith("DacpController"))
    for marker in (b"_dacp._tcp.", b"discoverServices", b"resolveService", b"Active-Remote", b"nextitem", b"previtem"):
        if marker not in dacp:
            raise RuntimeError(f"DACP discovery/control marker missing: {marker!r}")
    radio = b"\n".join(data for path, data in new.items() if path.name.startswith("RadioMediaSession"))
    for marker in (b"Landroid/media/session/MediaSession", b"PlaybackState$Builder;->setActions", b"onSkipToNext", b"onSkipToPrevious"):
        if marker not in radio:
            raise RuntimeError(f"physical radio session marker missing: {marker!r}")

    for marker in (b"C3LinkRouteIntegrity;->validate", b"route-ok", b"route-invalid"):
        if marker not in new[SERVER]:
            raise RuntimeError(f"long route validation marker missing: {marker!r}")
    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(original / name, rebuilt / name, name)
    if (original / "AndroidManifest.xml").read_bytes() != (rebuilt / "AndroidManifest.xml").read_bytes():
        raise RuntimeError("Android manifest changed")
    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10808" not in config or "versionName: 1.8.8" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.8")

    verify_long_route_transport_model()
    print("verified: continuous map fallback; stable media buffers; physical radio controls; 1.8.1 UI preserved")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
