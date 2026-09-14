#!/usr/bin/env python3
"""Prove C3 Link 1.8.6 changes route geometry without redesigning 1.8.1."""

from __future__ import annotations

import hashlib
import re
import sys
import zipfile
from pathlib import Path

from verify_ios_1_8_3_scope import ARCHIVE_SHA256, ICON_SIZES, PREFIX, png_dimensions


ALLOWED_CHANGED = {
    Path("C3Link/C3LinkTransport.swift"),
    Path("C3Link/Info.plist"),
    Path("C3Link/MapTileRelay.swift"),
    Path("C3Link/NavigationManager.swift"),
    Path("C3Link/OpenNavigationService.swift"),
    Path("C3Link/PolylineEncoder.swift"),
    Path("README.md"),
    Path("project.yml"),
}


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_ios_1_8_6_scope.py SOURCE_ZIP IOS_DIRECTORY")
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
        raise RuntimeError(f"iPhone UI/source changed outside route scope: {unexpected_differences}")

    unchanged_patch_files = sorted(
        path
        for path in ALLOWED_CHANGED
        if path in baseline and (ios_root / path).read_bytes() == baseline[path]
    )
    if unchanged_patch_files:
        raise RuntimeError(f"expected C3 Link maintenance is missing from: {unchanged_patch_files}")

    transport = (ios_root / "C3Link/C3LinkTransport.swift").read_text(encoding="utf-8")
    navigation = (ios_root / "C3Link/NavigationManager.swift").read_text(encoding="utf-8")
    tiles = (ios_root / "C3Link/MapTileRelay.swift").read_text(encoding="utf-8")
    service = (ios_root / "C3Link/OpenNavigationService.swift").read_text(encoding="utf-8")
    polyline = (ios_root / "C3Link/PolylineEncoder.swift").read_text(encoding="utf-8")
    project = (ios_root / "project.yml").read_text(encoding="utf-8")
    route_tests = ios_root / "C3LinkTests/PolylineEncoderTests.swift"
    required_markers = {
        "complete route": "sendLegacyRoute(" in transport,
        "paced route parts": "routePartDelayMilliseconds = 8" in transport,
        "route keepalive": "scheduleRouteKeepAlive" in navigation and "15_000_000_000" in navigation,
        "navigation GPS": "kCLLocationAccuracyBestForNavigation" in navigation and "distanceFilter = 2" in navigation,
        "contrast filter": "CIColorControls" in tiles and "CIColorMatrix" in tiles,
        "full preview geometry": "let fullCoordinates" in service and "coordinates: fullCoordinates" in service,
        "large route budget": "maximumTransportRoutePoints = 10_000" in service and "preferredTransportPolylineBytes = 26_000" in service,
        "geometry simplifier": "douglasPeuckerIndices" in polyline and "uniform stride" in polyline,
        "route geometry tests": route_tests.is_file() and "testSimplifierKeepsAStreetCorner" in route_tests.read_text(encoding="utf-8"),
        "1.8.6 version": "MARKETING_VERSION: 1.8.6" in project and "CURRENT_PROJECT_VERSION: 9" in project,
        "app icon setting": "ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon" in project,
    }
    failed = [label for label, present in required_markers.items() if not present]
    if failed:
        raise RuntimeError(f"required C3 Link 1.8.6 behavior is missing: {failed}")
    if re.search(r"(?<!\d)1_200(?!\d)|(?<!\d)8_000(?!\d)", service):
        raise RuntimeError("the lossy 1.8.4 route limits are still present")

    icon_root = ios_root / "C3Link/Assets.xcassets/AppIcon.appiconset"
    for filename, expected_size in ICON_SIZES.items():
        icon = icon_root / filename
        if not icon.is_file() or png_dimensions(icon.read_bytes()) != expected_size:
            raise RuntimeError(f"missing or incorrectly sized app icon: {filename}")

    print("verified: iPhone 1.8.1 UI preserved; full road geometry retained for 1.8.6")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
