#!/usr/bin/env python3
"""Add the local iPhone resource proxy without changing the frozen media base."""
import sys
from pathlib import Path
from apply_android_1_8_17_webview import main as apply_webview
from apply_android_1_8_6_maintenance import replace_once

def main():
    apply_webview()
    config = Path(sys.argv[1]) / "apktool.yml"
    text = config.read_text()
    text = replace_once(text, "  versionCode: 10817", "  versionCode: 10818", "version code")
    text = replace_once(text, "  versionName: 1.8.17", "  versionName: 1.8.18", "version name")
    config.write_text(text)

if __name__ == "__main__":
    main()
