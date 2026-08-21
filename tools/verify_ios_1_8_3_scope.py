#!/usr/bin/env python3
"""Prove that C3 Link 1.8.3 preserves every unrelated 1.8.1 source file."""

from __future__ import annotations

import hashlib
import struct
import sys
import zipfile
from pathlib import Path


ARCHIVE_SHA256 = "8e1f7845d9effb4e52ae4c4c771711badbeb7fccb620534c35e78901e0204395"
PREFIX = "ios/C3Link/"
ALLOWED_CHANGED = {
    Path("C3Link/C3LinkTransport.swift"),
    Path("C3Link/Info.plist"),
    Path("C3Link/MapTileRelay.swift"),
    Path("C3Link/NavigationManager.swift"),
    Path("README.md"),
    Path("project.yml"),
}
ICON_SIZES = {
    "AppIcon-40.png": (40, 40),
    "AppIcon-58.png": (58, 58),
    "AppIcon-60.png": (60, 60),
    "AppIcon-80.png": (80, 80),
    "AppIcon-87.png": (87, 87),
    "AppIcon-120-small.png": (120, 120),
    "AppIcon-120.png": (120, 120),
    "AppIcon-180.png": (180, 180),
    "AppIcon-1024.png": (1024, 1024),
}


def png_dimensions(data: bytes) -> tuple[int, int]:
    if not data.startswith(b"\x89PNG\r\n\x1a\n") or data[12:16] != b"IHDR":
        raise RuntimeError("icon is not a valid PNG")
    return struct.unpack(">II", data[16:24])


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_ios_1_8_3_scope.py SOURCE_ZIP IOS_DIRECTORY")
    archive = Path(sys.argv[1]).resolve()
    ios_root = Path(sys.argv[2]).resolve()

    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != ARCHIVE_SHA256:
        raise RuntimeError(f"unexpected C3 Link 1.8.1 source archive: {digest}")

    with zipfile.ZipFile(archive) as source_zip:
        baseline = {
            Path(name.removeprefix(PREFIX)): source_zip.read(name)
            for name in source_zip.namelist()
            if name.startswith(PREFIX) and not name.endswith("/")
        }

    missing = sorted(path for path in baseline if not (ios_root / path).is_file())
    if missing:
        raise RuntimeError(f"1.8.1 iPhone files are missing: {missing}")

    unexpected_differences = sorted(
        path
        for path, expected in baseline.items()
        if path not in ALLOWED_CHANGED and (ios_root / path).read_bytes() != expected
    )
    if unexpected_differences:
        raise RuntimeError(f"iPhone UI/source changed outside the map scope: {unexpected_differences}")

    unchanged_patch_files = sorted(
        path
        for path in ALLOWED_CHANGED
        if path in baseline and (ios_root / path).read_bytes() == baseline[path]
    )
    if unchanged_patch_files:
        raise RuntimeError(f"expected 1.8.3 patch is missing from: {unchanged_patch_files}")

    transport = (ios_root / "C3Link/C3LinkTransport.swift").read_text(encoding="utf-8")
    navigation = (ios_root / "C3Link/NavigationManager.swift").read_text(encoding="utf-8")
    tiles = (ios_root / "C3Link/MapTileRelay.swift").read_text(encoding="utf-8")
    project = (ios_root / "project.yml").read_text(encoding="utf-8")
    required_markers = {
        "complete route": "sendLegacyRoute(" in transport,
        "paced route parts": "routePartDelayMilliseconds = 8" in transport,
        "route keepalive": "scheduleRouteKeepAlive" in navigation and "15_000_000_000" in navigation,
        "navigation GPS": "kCLLocationAccuracyBestForNavigation" in navigation and "distanceFilter = 2" in navigation,
        "contrast filter": "CIColorControls" in tiles and "CIColorMatrix" in tiles,
        "1.8.3 version": "MARKETING_VERSION: 1.8.3" in project,
        "app icon setting": "ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon" in project,
    }
    failed = [label for label, present in required_markers.items() if not present]
    if failed:
        raise RuntimeError(f"required C3 Link 1.8.3 behavior is missing: {failed}")

    icon_root = ios_root / "C3Link/Assets.xcassets/AppIcon.appiconset"
    for filename, expected_size in ICON_SIZES.items():
        icon = icon_root / filename
        if not icon.is_file() or png_dimensions(icon.read_bytes()) != expected_size:
            raise RuntimeError(f"missing or incorrectly sized app icon: {filename}")

    print("verified: iPhone UI remains 1.8.1; only map, route/GPS, version and icon changed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
