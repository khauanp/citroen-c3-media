#!/usr/bin/env python3
"""Add the isolated NanoHTTPD/WebView route viewer over the 1.8.16 APK."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_16_media_lock import main as apply_1_8_16


ROOT = Path(__file__).resolve().parent
ACTIVITY = Path("smali/io/github/jqssun/airplay/MainActivity.smali")


def copy_helpers(root: Path, helper: Path) -> None:
    copied = 0
    for smali_root in sorted(helper.glob("smali*")):
        for relative_dir, pattern in (
            (Path("io/github/jqssun/airplay/connectivity"), "Route*.smali"),
            (Path("fi/iki/elonen"), "NanoHTTPD*.smali"),
        ):
            source = smali_root / relative_dir
            if not source.is_dir():
                continue
            target = root / "smali" / relative_dir
            target.mkdir(parents=True, exist_ok=True)
            for item in source.glob(pattern):
                shutil.copyfile(item, target / item.name)
                copied += 1
    if copied < 5:
        raise RuntimeError("compiled WebView/NanoHTTPD helpers not found")


def patch_activity(root: Path) -> None:
    path = root / ACTIVITY
    value = path.read_text(encoding="utf-8")
    create_anchor = """    invoke-direct {p0}, Lio/github/jqssun/airplay/MainActivity;->buildUi()V
"""
    create = create_anchor + """
    invoke-static {p0}, Lio/github/jqssun/airplay/connectivity/RouteWebViewModule;->start(Landroid/app/Activity;)V
"""
    value = replace_once(value, create_anchor, create, "WebView route module startup")

    destroy_anchor = """.method protected onDestroy()V
    .locals 2
"""
    destroy = destroy_anchor + """
    invoke-static {p0}, Lio/github/jqssun/airplay/connectivity/RouteWebViewModule;->stop(Landroid/app/Activity;)V
"""
    value = replace_once(value, destroy_anchor, destroy, "WebView route module shutdown")
    path.write_text(value, encoding="utf-8")


def patch_resources_and_manifest(root: Path) -> None:
    layout_dir = root / "res/layout"
    layout_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(
        ROOT.parent / "app/src/lite/res/layout/waze_webview.xml",
        layout_dir / "waze_webview.xml",
    )

    manifest_path = root / "AndroidManifest.xml"
    manifest = manifest_path.read_text(encoding="utf-8")
    manifest = replace_once(
        manifest,
        'android:largeHeap="false" android:name=',
        'android:largeHeap="false" android:usesCleartextTraffic="true" android:name=',
        "local cleartext route receiver",
    )
    manifest_path.write_text(manifest, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: apply_android_1_8_17_webview.py APKTOOL_DIRECTORY HELPER_DECODE"
        )
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()

    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_16_media_lock.py", str(root), str(helper)]
        apply_1_8_16()
    finally:
        sys.argv = old_argv

    copy_helpers(root, helper)
    patch_activity(root)
    patch_resources_and_manifest(root)

    config_path = root / "apktool.yml"
    config = config_path.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10816", "  versionCode: 10817", "versionCode")
    config = replace_once(config, "  versionName: 1.8.16", "  versionName: 1.8.17", "versionName")
    config_path.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
