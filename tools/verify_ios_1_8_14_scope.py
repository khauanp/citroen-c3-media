#!/usr/bin/env python3
"""Verify the 1.8.14 iPhone build and route-progress transport."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_ios_1_8_8_scope import main as verify_188


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_ios_1_8_14_scope.py SOURCE_ZIP IOS_DIRECTORY")
    ios_root = Path(sys.argv[2]).resolve()
    project = ios_root / "project.yml"
    info = ios_root / "C3Link/Info.plist"
    original_project = project.read_text(encoding="utf-8")
    original_info = info.read_text(encoding="utf-8")
    try:
        # Reuse every established routing/GPS/radar/interface assertion, changing
        # only the version strings expected by the older verifier.
        project.write_text(original_project.replace("MARKETING_VERSION: 1.8.14", "MARKETING_VERSION: 1.8.8").replace("CURRENT_PROJECT_VERSION: 14", "CURRENT_PROJECT_VERSION: 11"), encoding="utf-8")
        info.write_text(original_info.replace("<string>1.8.14</string>", "<string>1.8.8</string>").replace("<string>14</string>", "<string>11</string>"), encoding="utf-8")
        result = verify_188()
    finally:
        project.write_text(original_project, encoding="utf-8")
        info.write_text(original_info, encoding="utf-8")

    transport = (ios_root / "C3Link/C3LinkTransport.swift").read_text(encoding="utf-8")
    manager = (ios_root / "C3Link/NavigationManager.swift").read_text(encoding="utf-8")
    if '"routeProgressIndex"' not in transport or "routeProgressIndex: nearest.index" not in manager:
        raise RuntimeError("iPhone does not transmit route progress")
    if "MARKETING_VERSION: 1.8.14" not in original_project or "CURRENT_PROJECT_VERSION: 14" not in original_project:
        raise RuntimeError("project does not report iPhone 1.8.14 build 14")
    if "<string>1.8.14</string>" not in original_info or "<string>14</string>" not in original_info:
        raise RuntimeError("Info.plist does not report iPhone 1.8.14 build 14")
    print("verified: C3 Link 1.8.14 route progress; prior GPS/radar/interface checks preserved")
    return result


if __name__ == "__main__":
    raise SystemExit(main())
