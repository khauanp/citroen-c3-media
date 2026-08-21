#!/usr/bin/env python3
"""Verify exact long routes, safety fields, and preservation of the 1.8.1 tablet app."""

from __future__ import annotations

import math
import re
import sys
import zlib
from pathlib import Path

from verify_android_1_8_5_maintenance import (
    AUDIO,
    DASHBOARD,
    VIDEO,
    files,
    method,
    outside_method,
    require_equal_tree,
    verify_single_replacement,
)


SERVER = Path("io/github/jqssun/airplay/connectivity/C3LinkServer.smali")
ASSEMBLER = Path("io/github/jqssun/airplay/connectivity/C3LinkRouteAssembler.smali")
POLYLINE = Path("io/github/jqssun/airplay/connectivity/C3LinkPolyline.smali")
INTEGRITY = Path("io/github/jqssun/airplay/connectivity/C3LinkRouteIntegrity.smali")
SAFETY = Path("io/github/jqssun/airplay/connectivity/C3LinkRoadSafety.smali")
EXPECTED_CHANGED = sorted([SERVER, ASSEMBLER, POLYLINE, DASHBOARD, AUDIO, VIDEO])
EXPECTED_ADDED = sorted([INTEGRITY, SAFETY])


def outside_methods(data: bytes, signatures: list[bytes]) -> bytes:
    for signature in signatures:
        data = outside_method(data, signature)
    return data


def encode_value(value: int) -> str:
    shifted = ~(value << 1) if value < 0 else value << 1
    result = []
    while shifted >= 0x20:
        result.append(chr((0x20 | (shifted & 0x1F)) + 63))
        shifted >>= 5
    result.append(chr(shifted + 63))
    return "".join(result)


def encode_polyline(points: list[tuple[float, float]]) -> str:
    result: list[str] = []
    previous_latitude = 0
    previous_longitude = 0
    for latitude, longitude in points:
        lat = round(latitude * 100_000)
        lon = round(longitude * 100_000)
        result.append(encode_value(lat - previous_latitude))
        result.append(encode_value(lon - previous_longitude))
        previous_latitude = lat
        previous_longitude = lon
    return "".join(result)


def route_identifier(polyline: str, point_count: int) -> str:
    return f"r3_{zlib.crc32(polyline.encode('utf-8')):08x}_{point_count}"


def verify_long_route_transport_model() -> None:
    points = [
        (
            -25.45 + index / 24_999 * 1.55 + math.sin(index * 0.07) * 0.00008,
            -49.25 + index / 24_999 * 0.55 + math.cos(index * 0.09) * 0.00008,
        )
        for index in range(25_000)
    ]
    polyline = encode_polyline(points)
    if len(polyline.encode()) <= 28_000:
        raise RuntimeError("long-route fixture does not exceed the failed 1.8.6 budget")
    parts = [polyline[index:index + 700] for index in range(0, len(polyline), 700)]
    if not (64 < len(parts) <= 512):
        raise RuntimeError(f"long-route fixture does not exercise expanded chunking: {len(parts)}")
    if "".join(parts) != polyline:
        raise RuntimeError("route chunks do not reconstruct exactly")
    identity = route_identifier(polyline, len(points))
    prefix, crc, count = identity.split("_")
    if prefix != "r3" or int(count) != len(points) or int(crc, 16) != zlib.crc32(polyline.encode()):
        raise RuntimeError("route identity failed its own validation")
    truncated = "".join(parts[:-1])
    if zlib.crc32(truncated.encode()) == int(crc, 16):
        raise RuntimeError("a truncated route was accepted by the integrity model")
    if len(identity) > 80:
        raise RuntimeError("route identity exceeds the tablet routeId field")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_7_navigation.py ORIGINAL_DECODE REBUILT_DECODE")
    original = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()

    old_smali = files(original / "smali", ".smali")
    new_smali = files(rebuilt / "smali", ".smali")
    added = sorted(set(new_smali) - set(old_smali))
    deleted = sorted(set(old_smali) - set(new_smali))
    changed = sorted(path for path in set(old_smali) & set(new_smali) if old_smali[path] != new_smali[path])
    if added != EXPECTED_ADDED or deleted or changed != EXPECTED_CHANGED:
        raise RuntimeError(f"unexpected tablet code scope: {added=} {deleted=} {changed=}")

    old_server, new_server = old_smali[SERVER], new_smali[SERVER]
    server_methods = [
        b"private final checkTimeout()V",
        b"private final installRoute(Ljava/net/DatagramSocket;Ljava/net/DatagramPacket;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IDD)V",
        b"private final receivePosition(Ljava/net/DatagramSocket;Ljava/net/DatagramPacket;Lorg/json/JSONObject;Ljava/lang/String;)V",
    ]
    if outside_methods(old_server, server_methods) != outside_methods(new_server, server_methods):
        raise RuntimeError("C3LinkServer changed outside timeout/route/position handling")
    timeout = method(new_server, server_methods[0])
    if b".locals 0" not in timeout or b"disconnect(" in timeout:
        raise RuntimeError("navigation may still restart the 1.8.1 tablet process")
    install = method(new_server, server_methods[1])
    for marker in (b"const v2, 0xc350", b"C3LinkRouteIntegrity;->validate", b"route-ok", b"route-invalid"):
        if marker not in install:
            raise RuntimeError(f"route installation marker missing: {marker!r}")
    if install.index(b"C3LinkRouteIntegrity;->validate") > install.index(b"route-ok"):
        raise RuntimeError("tablet acknowledges the route before integrity validation")
    position = method(new_server, server_methods[2])
    if b"C3LinkRoadSafety;->update" not in position:
        raise RuntimeError("speed/radar fields are not installed with each GPS position")
    if position.index(b"route-missing") > position.index(b"C3LinkRoadSafety;->update"):
        raise RuntimeError("a stale/wrong-route packet can overwrite road-safety state")

    old_assembler, new_assembler = old_smali[ASSEMBLER], new_smali[ASSEMBLER]
    assembler_sig = b"public synthetic constructor <init>(IIJILkotlin/jvm/internal/DefaultConstructorMarker;)V"
    if outside_method(old_assembler, assembler_sig) != outside_method(new_assembler, assembler_sig):
        raise RuntimeError("route assembler changed outside its capacity/timeout defaults")
    assembler = method(new_assembler, assembler_sig)
    if b"const/16 p1, 0x200" not in assembler or b"const-wide/32 p3, 0xea60" not in assembler:
        raise RuntimeError("long-route assembler capacity is incomplete")

    old_polyline, new_polyline = old_smali[POLYLINE], new_smali[POLYLINE]
    polyline_sig = b"public final decode(Ljava/lang/String;II)Ljava/util/List;"
    if outside_method(old_polyline, polyline_sig) != outside_method(new_polyline, polyline_sig):
        raise RuntimeError("polyline class changed outside decode")
    decoder = method(new_polyline, polyline_sig)
    if b"if-ne v6, v9" not in decoder or decoder.count(b"CollectionsKt;->emptyList") != 3:
        raise RuntimeError("decoder can still return partial geometry")

    old_dashboard, new_dashboard = old_smali[DASHBOARD], new_smali[DASHBOARD]
    nav_sig = b"private final drawNavigation(Landroid/graphics/Canvas;)V"
    if outside_method(old_dashboard, nav_sig) != outside_method(new_dashboard, nav_sig):
        raise RuntimeError("tablet dashboard changed outside the navigation canvas")
    old_nav = method(old_dashboard, nav_sig)
    nav = method(new_dashboard, nav_sig)
    if nav.count(b"Canvas;->drawPath") != old_nav.count(b"Canvas;->drawPath") - 2:
        raise RuntimeError("unsafe full-route GPU Path draws were not removed exactly")
    markers = {
        b"Canvas;->drawLine(FFFFLandroid/graphics/Paint;)V": 2,
        b"C3LinkRoadSafety;->drawRadar": 1,
        b"C3LinkRoadSafety;->isSpeeding": 1,
        b"C3LinkRoadSafety;->drawSpeedLimit": 1,
        b"const/high16 v10, -0x3c000000": 2,
        b"const/high16 v10, 0x44e00000": 2,
    }
    for marker, expected in markers.items():
        if nav.count(marker) != expected:
            raise RuntimeError(f"navigation canvas marker mismatch: {marker!r}")

    integrity = new_smali[INTEGRITY]
    for marker in (b"java/util/zip/CRC32", b"Ljava/util/List;->size()I", b"Long;->parseLong"):
        if marker not in integrity:
            raise RuntimeError(f"route-integrity helper incomplete: {marker!r}")
    safety = new_smali[SAFETY]
    for marker in (
        b"speedLimitKph",
        b"cameraLatitude",
        b"cameraLongitude",
        b"cameraDistanceMeters",
        b"const-wide v4, 0x400ccccccccccccdL",
        b"const-wide/high16 v4, 0x4000000000000000L",
    ):
        if marker not in safety:
            raise RuntimeError(f"road-safety helper incomplete: {marker!r}")

    old_audio, new_audio = old_smali[AUDIO], new_smali[AUDIO]
    audio_sig = b"public final declared-synchronized attachEngine(J)V"
    if outside_method(old_audio, audio_sig) != outside_method(new_audio, audio_sig):
        raise RuntimeError("AudioRenderer changed outside attachEngine")
    verify_single_replacement(
        method(old_audio, audio_sig), method(new_audio, audio_sig),
        b"const/16 v7, 0x5f", b"const/16 v7, 0x63", "audio jitter percentile",
    )

    old_video, new_video = old_smali[VIDEO], new_smali[VIDEO]
    video_sig = b"private final _feedToCodec([BJ)V"
    if outside_method(old_video, video_sig) != outside_method(new_video, video_sig):
        raise RuntimeError("VideoRenderer changed outside _feedToCodec")
    expected_feed = method(old_video, video_sig)
    expected_feed = expected_feed.replace(b"const/4 v1, 0x3", b"const/4 v1, 0x4", 1)
    expected_feed = expected_feed.replace(b"const/16 v1, 0xf", b"const/16 v1, 0x14", 1)
    expected_feed = expected_feed.replace(b"const-wide/16 v3, 0x7d0", b"const-wide/16 v3, 0xfa0", 1)
    if expected_feed != method(new_video, video_sig):
        raise RuntimeError("video maintenance differs from the approved constants")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(original / name, rebuilt / name, name)
    if (original / "AndroidManifest.xml").read_bytes() != (rebuilt / "AndroidManifest.xml").read_bytes():
        raise RuntimeError("decoded Android manifest structure changed")
    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10807" not in config or "versionName: 1.8.7" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.7")

    verify_long_route_transport_model()
    print("verified: exact long route; false ACK rejected; radar/speed active; 1.8.1 UI/player preserved")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
