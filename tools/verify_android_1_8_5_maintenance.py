#!/usr/bin/env python3
"""Verify 1.8.5 keeps 1.8.1 intact and bounds every direct route segment."""

from __future__ import annotations

import re
import sys
from pathlib import Path


SERVER = Path("io/github/jqssun/airplay/connectivity/C3LinkServer.smali")
DASHBOARD = Path("io/github/jqssun/airplay/ui/DashboardView.smali")
AUDIO = Path("io/github/jqssun/airplay/renderer/AudioRenderer.smali")
VIDEO = Path("io/github/jqssun/airplay/renderer/VideoRenderer.smali")
EXPECTED_CHANGED = sorted([SERVER, DASHBOARD, AUDIO, VIDEO])


def files(root: Path, suffix: str | None = None) -> dict[Path, bytes]:
    result: dict[Path, bytes] = {}
    if not root.exists():
        return result
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        if suffix is None or path.suffix == suffix:
            result[path.relative_to(root)] = path.read_bytes()
    return result


def method(data: bytes, signature: bytes) -> bytes:
    pattern = re.compile(
        rb"\.method " + re.escape(signature) + rb"\n.*?^\.end method",
        re.MULTILINE | re.DOTALL,
    )
    matches = pattern.findall(data)
    if len(matches) != 1:
        raise RuntimeError(f"could not isolate {signature!r}: {len(matches)} matches")
    return matches[0]


def outside_method(data: bytes, signature: bytes) -> bytes:
    target = method(data, signature)
    return data.replace(target, b"<verified-method>", 1)


def require_equal_tree(original: Path, rebuilt: Path, label: str) -> None:
    left = files(original)
    right = files(rebuilt)
    if left != right:
        changed = sorted(set(left) ^ set(right) | {key for key in set(left) & set(right) if left[key] != right[key]})
        raise RuntimeError(f"{label} changed unexpectedly: {changed[:10]}")


def verify_single_replacement(old: bytes, new: bytes, before: bytes, after: bytes, label: str) -> None:
    if old.count(before) != 1 or new.count(after) != 1:
        raise RuntimeError(f"unexpected {label} marker count")
    if old.replace(before, after, 1) != new:
        raise RuntimeError(f"{label} changed more than the approved constant")


def route_guard_accepts(previous: tuple[float, float], current: tuple[float, float]) -> bool:
    """Model the exact endpoint guard encoded in DashboardView.smali."""
    left, top, right, bottom = -256.0, -256.0, 1536.0, 1056.0
    return (
        left <= previous[0] <= right
        and left <= current[0] <= right
        and top <= previous[1] <= bottom
        and top <= current[1] <= bottom
    )


def verify_guard_model() -> None:
    cases = {
        ((640.0, 620.0), (700.0, 650.0)): True,
        ((-256.0, -256.0), (1536.0, 1056.0)): True,
        ((-257.0, 620.0), (640.0, 620.0)): False,
        ((640.0, 620.0), (1537.0, 620.0)): False,
        ((640.0, -257.0), (640.0, 620.0)): False,
        ((640.0, 620.0), (640.0, 1057.0)): False,
        ((-1_000_000.0, -1_000_000.0), (640.0, 620.0)): False,
    }
    for (previous, current), expected in cases.items():
        if route_guard_accepts(previous, current) != expected:
            raise RuntimeError(f"route guard model failed for {previous=} {current=}")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_android_1_8_5_maintenance.py ORIGINAL_DECODE REBUILT_DECODE")
    original = Path(sys.argv[1]).resolve()
    rebuilt = Path(sys.argv[2]).resolve()

    old_smali = files(original / "smali", ".smali")
    new_smali = files(rebuilt / "smali", ".smali")
    if old_smali.keys() != new_smali.keys():
        raise RuntimeError("rebuilt DEX has a different class/file set")
    changed = [path for path in old_smali if old_smali[path] != new_smali[path]]
    if changed != EXPECTED_CHANGED:
        raise RuntimeError(f"code outside the approved maintenance scope changed: {changed}")

    old_server, new_server = old_smali[SERVER], new_smali[SERVER]
    server_sig = b"private final checkTimeout()V"
    if outside_method(old_server, server_sig) != outside_method(new_server, server_sig):
        raise RuntimeError("C3LinkServer changed outside checkTimeout")
    timeout = method(new_server, server_sig)
    if b".locals 0" not in timeout or b"disconnect(" in timeout:
        raise RuntimeError("route timeout preservation is missing")

    old_dashboard, new_dashboard = old_smali[DASHBOARD], new_smali[DASHBOARD]
    nav_sig = b"private final drawNavigation(Landroid/graphics/Canvas;)V"
    if outside_method(old_dashboard, nav_sig) != outside_method(new_dashboard, nav_sig):
        raise RuntimeError("DashboardView changed outside drawNavigation")
    old_nav = method(old_dashboard, nav_sig)
    nav = method(new_dashboard, nav_sig)
    if nav.count(b"Canvas;->drawPath") != old_nav.count(b"Canvas;->drawPath"):
        raise RuntimeError("original route Path fallback changed unexpectedly")
    if nav.count(b"Canvas;->drawLine(FFFFLandroid/graphics/Paint;)V") != 2:
        raise RuntimeError("direct route outline/blue segment draws are missing")
    route_markers = {
        b"const/high16 v10, -0x3c800000": 2,
        b"const/high16 v10, 0x44c00000": 1,
        b"const/high16 v10, 0x44840000": 1,
        b"const v13, -0x23fef8f4": 1,
        b"move/from16 v32, v6": 2,
        b"move/from16 v33, v4": 2,
    }
    for marker, count in route_markers.items():
        if nav.count(marker) != count:
            raise RuntimeError(f"bounded route renderer marker mismatch: {marker!r}")
    guard = nav.split(b"const/high16 v10, -0x3c800000", 1)[1].split(
        b"iget-object v10, v0, Lio/github/jqssun/airplay/ui/DashboardView;->linePaint",
        1,
    )[0]
    lower_targets = re.findall(rb"if-ltz v13, :([a-zA-Z0-9_]+)", guard)
    upper_targets = re.findall(rb"if-gtz v13, :([a-zA-Z0-9_]+)", guard)
    if len(lower_targets) != 4 or len(upper_targets) != 4:
        raise RuntimeError("route guard does not reject all four lower and upper endpoint bounds")
    if len(set(lower_targets + upper_targets)) != 1:
        raise RuntimeError("route guard bounds do not share the same safe skip target")
    for forbidden in (b":c3_route_check_left", b"const/high16 v10, -0x3d800000"):
        if forbidden in nav:
            raise RuntimeError(f"unsafe 1.8.4 route guard remains: {forbidden!r}")
    verify_guard_model()

    old_audio, new_audio = old_smali[AUDIO], new_smali[AUDIO]
    audio_sig = b"public final declared-synchronized attachEngine(J)V"
    if outside_method(old_audio, audio_sig) != outside_method(new_audio, audio_sig):
        raise RuntimeError("AudioRenderer changed outside attachEngine")
    verify_single_replacement(
        method(old_audio, audio_sig),
        method(new_audio, audio_sig),
        b"const/16 v7, 0x5f",
        b"const/16 v7, 0x63",
        "audio jitter percentile",
    )

    old_video, new_video = old_smali[VIDEO], new_smali[VIDEO]
    video_sig = b"private final _feedToCodec([BJ)V"
    if outside_method(old_video, video_sig) != outside_method(new_video, video_sig):
        raise RuntimeError("VideoRenderer changed outside _feedToCodec")
    old_feed = method(old_video, video_sig)
    expected_feed = old_feed.replace(b"const/4 v1, 0x3", b"const/4 v1, 0x4", 1)
    expected_feed = expected_feed.replace(b"const/16 v1, 0xf", b"const/16 v1, 0x14", 1)
    expected_feed = expected_feed.replace(b"const-wide/16 v3, 0x7d0", b"const-wide/16 v3, 0xfa0", 1)
    if expected_feed != method(new_video, video_sig):
        raise RuntimeError("video queue maintenance differs from the approved constants")

    for name in ("res", "lib", "assets", "unknown"):
        require_equal_tree(original / name, rebuilt / name, name)
    if (original / "AndroidManifest.xml").read_bytes() != (rebuilt / "AndroidManifest.xml").read_bytes():
        raise RuntimeError("decoded Android manifest structure changed")

    config = (rebuilt / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10805" not in config or "versionName: 1.8.5" not in config:
        raise RuntimeError("rebuilt APK does not report version 1.8.5")
    print("verified: 1.8.1 UI/player preserved; route segments are GPU-bounded; media buffers unchanged from 1.8.4")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
