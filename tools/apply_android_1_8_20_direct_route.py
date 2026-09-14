#!/usr/bin/env python3
"""Build the direct WebView route flow over the existing local iPhone proxy."""
import sys
from pathlib import Path
from apply_android_1_8_19_webview_ui import main as apply_webview_ui
from apply_android_1_8_6_maintenance import replace_once

def main():
    apply_webview_ui()
    config = Path(sys.argv[1]) / "apktool.yml"
    text = config.read_text()
    text = replace_once(text, "  versionCode: 10819", "  versionCode: 10820", "version code")
    text = replace_once(text, "  versionName: 1.8.19", "  versionName: 1.8.20", "version name")
    config.write_text(text)

if __name__ == "__main__":
    main()
