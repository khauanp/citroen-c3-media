#!/usr/bin/env python3
"""Extend the existing scope gate for the isolated Waze resource relay."""
import sys
from pathlib import Path
import verify_ios_1_8_8_scope as old
from verify_ios_1_8_14_scope import main as verify_1814

def main():
    root = Path(sys.argv[2])
    project, info = root / "project.yml", root / "C3Link/Info.plist"
    project_text, info_text = project.read_text(), info.read_text()
    assert "MARKETING_VERSION: 1.8.18" in project_text
    assert "<string>1.8.18</string>" in info_text
    additions = {Path("C3Link/WazeResourceRelay.swift"), Path("C3LinkTests/WazeResourceRelayTests.swift")}
    previous = old.ALLOWED_ADDED.copy()
    try:
        old.ALLOWED_ADDED.update(additions)
        project.write_text(project_text.replace("1.8.18", "1.8.14").replace("CURRENT_PROJECT_VERSION: 18", "CURRENT_PROJECT_VERSION: 14"))
        info.write_text(info_text.replace("1.8.18", "1.8.14").replace("<string>18</string>", "<string>14</string>"))
        return verify_1814()
    finally:
        old.ALLOWED_ADDED = previous
        project.write_text(project_text)
        info.write_text(info_text)

if __name__ == "__main__":
    raise SystemExit(main())
