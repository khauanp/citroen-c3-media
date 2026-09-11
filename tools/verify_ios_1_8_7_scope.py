#!/usr/bin/env python3
"""Verify C3 Link 1.8.7 exact routing and road-safety scope against 1.8.1."""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

from verify_ios_1_8_3_scope import ARCHIVE_SHA256, ICON_SIZES, PREFIX, png_dimensions


ALLOWED_CHANGED = {
    Path("C3Link/C3LinkTransport.swift"),
    Path("C3Link/ContentView.swift"),
    Path("C3Link/Info.plist"),
    Path("C3Link/MapTileRelay.swift"),
    Path("C3Link/NavigationManager.swift"),
    Path("C3Link/NavigationModels.swift"),
    Path("C3Link/OpenNavigationService.swift"),
    Path("C3Link/PolylineEncoder.swift"),
    Path("C3Link/RoutePreviewMap.swift"),
    Path("README.md"),
    Path("project.yml"),
}
ALLOWED_ADDED = {
    Path("C3Link/RoadSafetyService.swift"),
    Path("C3Link/RoadSafetyRules.swift"),
    Path("C3Link/RouteGeometry.swift"),
    Path("C3Link/RouteIntegrity.swift"),
    Path("C3LinkTests/PolylineEncoderTests.swift"),
    Path("C3LinkTests/RouteGeometryTests.swift"),
    Path("C3LinkTests/RoadSafetyRulesTests.swift"),
    Path("C3LinkTests/RouteIntegrityTests.swift"),
    Path("C3Link/Assets.xcassets/Contents.json"),
    Path("C3Link/Assets.xcassets/AppIcon.appiconset/Contents.json"),
    *{Path("C3Link/Assets.xcassets/AppIcon.appiconset") / filename for filename in ICON_SIZES},
}


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_ios_1_8_7_scope.py SOURCE_ZIP IOS_DIRECTORY")
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
    current = {
        path.relative_to(ios_root): path.read_bytes()
        for path in ios_root.rglob("*")
        if path.is_file() and not any(part in {"build", "test-build", "Payload", "C3Link.xcodeproj"} for part in path.parts)
    }
    missing = sorted(set(baseline) - set(current))
    if missing:
        raise RuntimeError(f"1.8.1 iPhone files are missing: {missing}")
    unexpected_added = sorted(set(current) - set(baseline) - ALLOWED_ADDED)
    if unexpected_added:
        raise RuntimeError(f"unexpected iPhone files added: {unexpected_added}")
    unexpected_differences = sorted(
        path for path, expected in baseline.items()
        if path not in ALLOWED_CHANGED and current[path] != expected
    )
    if unexpected_differences:
        raise RuntimeError(f"iPhone source changed outside navigation/safety scope: {unexpected_differences}")

    transport = current[Path("C3Link/C3LinkTransport.swift")].decode()
    manager = current[Path("C3Link/NavigationManager.swift")].decode()
    service = current[Path("C3Link/OpenNavigationService.swift")].decode()
    integrity = current[Path("C3Link/RouteIntegrity.swift")].decode()
    geometry = current[Path("C3Link/RouteGeometry.swift")].decode()
    safety = current[Path("C3Link/RoadSafetyService.swift")].decode()
    map_source = current[Path("C3Link/RoutePreviewMap.swift")].decode()
    project = current[Path("project.yml")].decode()
    info = current[Path("C3Link/Info.plist")].decode()
    tests = current[Path("C3LinkTests/RouteIntegrityTests.swift")].decode()
    requirements = {
        "Apple automobile route": "MKDirections.Request" in service and "transportType = .automobile" in service,
        "exact geometry": "PolylineEncoder.encode(fullCoordinates" in service and "simplify(" not in service,
        "locally dense geometry": "RouteGeometry.densify" in service and "maximumSegmentMeters = 60" in geometry,
        "exact route identity": "crc32" in integrity and 'joined(separator: "_")' in integrity,
        "long chunk transport": "routePartBytes = 700" in transport and "maximumRouteParts = 512" in transport,
        "strict tablet ACK": "candidate == routeId" in manager and "routeId.hasPrefix" in manager,
        "fresh precise GPS": "horizontalAccuracy <= 65" in manager and "timeIntervalSinceNow" in manager,
        "background GPS": "kCLLocationAccuracyBestForNavigation" in manager and "allowsBackgroundLocationUpdates = true" in manager,
        "OSM cameras": 'highway\\\"=\\\"speed_camera' in safety and "cameraRouteToleranceMeters = 120" in safety,
        "road speed limit": 'way(around:' in safety and '[\\\"maxspeed\\\"]' in safety,
        "directional road match": 'tags?["oneway"]' in safety and "headingDifference" in safety,
        "radar annotations": "RouteCameraAnnotation" in map_source and "camera.fill" in map_source,
        "long route test": "25_000" in tests and "28_000" in tests,
        "1.8.7 version": "MARKETING_VERSION: 1.8.7" in project and "CURRENT_PROJECT_VERSION: 10" in project,
        "1.8.7 plist": "<string>1.8.7</string>" in info and "<string>10</string>" in info,
    }
    failed = [label for label, present in requirements.items() if not present]
    if failed:
        raise RuntimeError(f"required C3 Link 1.8.7 behavior is missing: {failed}")
    if "router.project-osrm.org" in service or "preferredTransportPolylineBytes" in service:
        raise RuntimeError("the failed lossy public-OSRM route path is still active")

    icon_root = ios_root / "C3Link/Assets.xcassets/AppIcon.appiconset"
    for filename, expected_size in ICON_SIZES.items():
        icon = icon_root / filename
        if not icon.is_file() or png_dimensions(icon.read_bytes()) != expected_size:
            raise RuntimeError(f"missing or incorrectly sized app icon: {filename}")

    print("verified: MapKit automobile route; exact transport; OSM radar/speed; 1.8.1 files preserved")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
