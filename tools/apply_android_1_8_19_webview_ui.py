#!/usr/bin/env python3
"""Build the software-rendered map and close button over the existing proxy."""
import sys
from pathlib import Path
from apply_android_1_8_18_proxy import main as apply_proxy
from apply_android_1_8_6_maintenance import replace_once

def main():
    apply_proxy()
    config = Path(sys.argv[1]) / "apktool.yml"
    text = config.read_text()
    text = replace_once(text, "  versionCode: 10818", "  versionCode: 10819", "version code")
    text = replace_once(text, "  versionName: 1.8.18", "  versionName: 1.8.19", "version name")
    config.write_text(text)

if __name__ == "__main__":
    main()
